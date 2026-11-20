package tv.gapja.monitor.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 한 번의 템플릿 매칭 결과.
 *
 * @param score 최고 일치 점수 (0~1)
 * @param x     최고점이 나온 위치의 왼쪽 좌표
 * @param y     최고점이 나온 위치의 위쪽 좌표
 * @param mean  매칭 결과면 전체의 평균 (PSR 계산용)
 * @param std   매칭 결과면 전체의 표준편차 (PSR 계산용)
 */
data class MatchPeak(
    val score: Double,
    val x: Int,
    val y: Int,
    val mean: Double,
    val std: Double
) {
    /**
     * PSR(Peak to Sidelobe Ratio) — 피크가 주변보다 얼마나 '도드라지는가'.
     *
     * 색상만 비슷한 엉뚱한 곳은 점수가 높아도 주변과 고르게 높아서 PSR이 낮다.
     * 진짜 보스는 한 점에서만 뾰족하게 솟아 PSR이 크다.
     */
    val psr: Double get() = if (std > 1e-6) (score - mean) / std else 0.0
}

/** 이미지 한 장을 다루는 최소 연산 묶음. 안드로이드에서는 OpenCV Mat으로 구현한다. */
interface ImageOps<T> {
    /** [너비, 높이] */
    fun size(img: T): IntArray

    /** 지정 크기로 축소/확대한 새 이미지 */
    fun resize(img: T, w: Int, h: Int): T

    /** 잘라낸 새 이미지 (범위를 벗어나면 가능한 만큼만) */
    fun crop(img: T, x: Int, y: Int, w: Int, h: Int): T

    /** 정규화 상관 매칭. 템플릿이 화면보다 크면 null */
    fun match(screen: T, tpl: T): MatchPeak?

    /** 윤곽선(외곽선) 기준 일치도 — 반투명 가림막에 강하다 */
    fun edgeScore(screen: T, tpl: T): Double
}

/** 멀티스케일 매칭 결과 */
data class MatchOutcome(
    val score: Double,
    val psr: Double,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val scale: Double
) {
    companion object {
        val NONE = MatchOutcome(0.0, 0.0, 0, 0, 0, 0, 1.0)
    }
}

/** 타일 분할 투표 결과 */
data class TileOutcome(
    val score: Double,
    /** 같은 원점에 동의한 조각 수 */
    val agree: Int,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int
) {
    companion object {
        val NONE = TileOutcome(0.0, 0, 0, 0, 0, 0)
    }
}

/**
 * 보스 인식 알고리즘 — PC판(gapjatv_control_tower57.py)의 매칭 로직을 그대로 옮겼다.
 *
 * 이미지 연산은 [ImageOps] 로 분리해 두었기 때문에,
 * 안드로이드에서는 OpenCV로, 테스트에서는 간단한 배열 구현으로 바꿔 끼울 수 있다.
 */
class Matcher<T>(private val ops: ImageOps<T>) {

    /**
     * 여러 크기로 바꿔가며 템플릿을 찾는다.
     *
     * [조기 종료] 기준 + EARLY_EXIT_MARGIN 을 넘으면 남은 크기는 보지 않는다.
     * [조기 중단] 첫 크기에서 기준보다 EARLY_ABORT_GAP 이나 낮으면 바로 포기한다.
     *             (보스가 없는 평소 화면에서 8개 크기를 모두 돌리지 않게 해준다)
     */
    fun multiScale(
        screen: T,
        template: T,
        threshold: Double,
        scales: DoubleArray = BossCatalog.SCALES,
        downscale: Double = 1.0
    ): MatchOutcome {
        val (sw, sh) = ops.size(screen).let { it[0] to it[1] }
        val (tw, th) = ops.size(template).let { it[0] to it[1] }
        if (tw <= 0 || th <= 0) return MatchOutcome.NONE

        var best = MatchOutcome.NONE
        for ((i, sc) in scales.withIndex()) {
            val eff = sc * downscale
            val nw = (tw * eff).toInt()
            val nh = (th * eff).toInt()
            if (nw < 12 || nh < 12 || nw > sw || nh > sh) continue

            val scaled = if (eff == 1.0) template else ops.resize(template, nw, nh)
            val peak = ops.match(screen, scaled) ?: continue

            if (peak.score > best.score) {
                best = MatchOutcome(peak.score, peak.psr, peak.x, peak.y, nw, nh, sc)
            }
            // 충분히 확실하면 더 볼 필요 없다
            if (best.score >= threshold + BossCatalog.EARLY_EXIT_MARGIN) break
            // 첫 크기에서 너무 낮으면 나머지도 볼 가치가 없다
            if (i == 0 && best.score < threshold - BossCatalog.EARLY_ABORT_GAP) break
        }
        return best
    }

    /**
     * 근접 구간에서만 돌리는 보충 크기 검사.
     * 기본 크기 목록으로 아깝게 놓친 경우를 건져낸다.
     */
    fun rescueScales(
        screen: T,
        template: T,
        threshold: Double,
        downscale: Double = 1.0
    ): MatchOutcome = multiScale(
        screen, template, threshold,
        scales = BossCatalog.RESCUE_SCALES, downscale = downscale
    )

    /**
     * [네임텍 가려짐 구제] 템플릿을 조각내어 매칭하고, 같은 자리에 모이는 조각만 인정한다.
     *
     * ① 템플릿을 TILE_ROWS × TILE_COLS 조각으로 쪼갠다.
     * ② 조각마다 화면 전체에서 가장 잘 맞는 자리를 찾는다.
     * ③ 그 자리에서 '보스 전체의 왼쪽 위 원점'이 어디였을지 역산한다.
     *      원점 = 찾은자리 − 조각이 템플릿 안에서 있던 위치
     * ④ 같은 원점에 모이는 조각이 가장 많은 묶음을 고른다.
     *      → 네임텍에 덮인 조각은 엉뚱한 곳을 가리키므로 자동으로 빠진다.
     * ⑤ 그 묶음에서 성적 좋은 TILE_KEEP개의 평균을 점수로 쓴다.
     *
     * ⚠️ 조각 매칭은 원래 아무 곳에나 잘 맞는 성질이 있다. 그래서 '기하 일치'(④)가
     *    핵심이다. 실측에서 이것만으로 엉뚱한 보스 점수가 0.780 → 0.611 로 떨어졌다.
     * ⚠️ 일반 매칭의 약 10배로 비싸다. 반드시 근접 구간에서만 호출해야 한다.
     */
    fun tileVote(
        screen: T,
        template: T,
        scale: Double = 1.0,
        downscale: Double = 1.0,
        rows: Int = BossCatalog.TILE_ROWS,
        cols: Int = BossCatalog.TILE_COLS,
        keep: Int = BossCatalog.TILE_KEEP,
        tol: Int = BossCatalog.TILE_TOL
    ): TileOutcome {
        val (sw, sh) = ops.size(screen).let { it[0] to it[1] }
        val (tw0, th0) = ops.size(template).let { it[0] to it[1] }
        val eff = scale * downscale
        val tw = (tw0 * eff).toInt()
        val th = (th0 * eff).toInt()
        if (tw < 24 || th < 24 || tw > sw || th > sh) return TileOutcome.NONE

        val tpl = if (eff == 1.0) template else ops.resize(template, tw, th)

        // (조각점수, 추정원점x, 추정원점y)
        val cand = ArrayList<Triple<Double, Int, Int>>(rows * cols)
        for (iy in 0 until rows) {
            for (ix in 0 until cols) {
                val y0 = th * iy / rows
                val y1 = th * (iy + 1) / rows
                val x0 = tw * ix / cols
                val x1 = tw * (ix + 1) / cols
                val pw = x1 - x0
                val ph = y1 - y0
                // 너무 작은 조각은 아무데나 맞아버리므로 쓰지 않는다
                if (pw < 8 || ph < 8 || pw > sw || ph > sh) continue
                val piece = ops.crop(tpl, x0, y0, pw, ph)
                val peak = ops.match(screen, piece) ?: continue
                cand.add(Triple(peak.score, peak.x - x0, peak.y - y0))
            }
        }
        if (cand.isEmpty()) return TileOutcome.NONE

        // 원점 투표 — 가장 많은 조각이 동의하는 자리를 찾는다
        var bestN = 0
        var bestOx = 0
        var bestOy = 0
        var bestGrp: List<Triple<Double, Int, Int>> = emptyList()
        for ((_, ox, oy) in cand) {
            val grp = cand.filter { abs(it.second - ox) <= tol && abs(it.third - oy) <= tol }
            if (grp.size > bestN) {
                bestN = grp.size
                bestOx = ox
                bestOy = oy
                bestGrp = grp
            }
        }
        if (bestGrp.isEmpty()) return TileOutcome.NONE

        val use = bestGrp.sortedByDescending { it.first }.take(max(1, min(keep, bestGrp.size)))
        val score = use.sumOf { it.first } / use.size
        return TileOutcome(score, bestN, bestOx, bestOy, tw, th)
    }

    /**
     * 찾아낸 자리에서 윤곽선(모양)을 검증한다.
     * 색상만 비슷한 곳을 걸러내는 마지막 관문이다.
     */
    fun verifyEdge(screen: T, template: T, x: Int, y: Int, w: Int, h: Int, pad: Int = 6): Double {
        val (sw, sh) = ops.size(screen).let { it[0] to it[1] }
        val x0 = max(0, x - pad)
        val y0 = max(0, y - pad)
        val x1 = min(sw, x + w + pad)
        val y1 = min(sh, y + h + pad)
        val rw = x1 - x0
        val rh = y1 - y0
        if (rw < w / 2 || rh < h / 2) return 0.0
        val roi = ops.crop(screen, x0, y0, rw, rh)
        val tpl = if (ops.size(template).let { it[0] == w && it[1] == h }) template
        else ops.resize(template, w, h)
        return ops.edgeScore(roi, tpl)
    }
}
