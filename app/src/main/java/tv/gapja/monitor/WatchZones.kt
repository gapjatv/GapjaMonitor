package tv.gapja.monitor

import android.content.Context
import android.graphics.Color
import org.opencv.core.Rect
import tv.gapja.monitor.core.BossCatalog
import tv.gapja.monitor.core.GameMode

/**
 * 인식 위치 (v3.6) — 보스는 지정한 네모 안에서만 찾는다. 전체 화면에서는 보스를 찾지 않는다.
 *
 *   태초보스 — 태초 모드의 보스 전부가 나오는 곳
 *   침공보스 — 침공 모드의 일반 보스(히든보스 포함)가 나오는 곳
 *   시공보스 — 침공특수보스 3종(악마 모노폴리 · 거대 전기로봇 · 쌍둥이 슬라임)이 나오는 곳
 *
 * 로비 화면(태초·침공·공통로비 1~3)과 배속 표시는 지금처럼 전체 화면에서 찾는다.
 *
 * 위치는 '화면에 대한 0~1 비율' + 가로/세로 방향으로 저장한다 → 앱을 다시 켜거나 새 버전을 받아도,
 * 해상도가 달라도 그대로 쓰인다. (방향이 다르면 그 위치는 등록 안 한 것으로 본다)
 */
object WatchZones {
    const val TAECHO = "태초보스"
    const val CHIMGONG = "침공보스"
    const val SIGONG = "시공보스"

    val ALL: List<String> = listOf(TAECHO, CHIMGONG, SIGONG)

    private val KEYS = mapOf(TAECHO to "taecho", CHIMGONG to "chimgong", SIGONG to "sigong")

    /** 박스 색 — 위치마다 다르게 */
    private val COLORS = mapOf(
        TAECHO to Color.parseColor("#2ECC71"),     // 초록
        CHIMGONG to Color.parseColor("#FF5A4F"),   // 빨강
        SIGONG to Color.parseColor("#2EC4F0")      // 하늘
    )

    /** 처음 보여 줄 자리(등록 전) — 서로 겹치지 않게 (왼, 위, 오른, 아래 비율) */
    private val DEFAULTS = mapOf(
        TAECHO to floatArrayOf(0.08f, 0.22f, 0.44f, 0.42f),
        CHIMGONG to floatArrayOf(0.56f, 0.22f, 0.92f, 0.42f),
        SIGONG to floatArrayOf(0.30f, 0.56f, 0.70f, 0.76f)
    )

    fun key(zone: String): String = KEYS[zone] ?: zone
    fun color(zone: String): Int = COLORS[zone] ?: Color.parseColor("#F39C12")
    fun defaultRect(zone: String): FloatArray = (DEFAULTS[zone] ?: floatArrayOf(0.3f, 0.3f, 0.7f, 0.5f)).copyOf()

    /** 자르기 화면 목록에 보이는 이름 */
    fun label(zone: String): String = "📐 $zone 인식위치"

    /** 목록의 이름 → 위치 이름 (인식위치 항목이 아니면 null) */
    fun zoneOf(label: String?): String? = ALL.firstOrNull { label(it) == label }

    /** 그 보스를 찾을 위치 — 태초는 전부 태초보스, 침공은 특수보스 3종만 시공보스, 나머지는 침공보스 */
    fun zoneForBoss(name: String, mode: GameMode): String = when {
        mode == GameMode.NORMAL -> TAECHO
        BossCatalog.isSpecial(name) -> SIGONG
        else -> CHIMGONG
    }

    /** 그 모드에서 꼭 등록돼 있어야 하는 위치 */
    fun requiredFor(mode: GameMode): List<String> =
        if (mode == GameMode.NORMAL) listOf(TAECHO) else listOf(CHIMGONG, SIGONG)

    private fun raw(ctx: Context, zone: String): FloatArray? {
        // v3.5 에서 '태초 보스 구역' 으로 등록한 값은 그대로 이어받는다
        return Prefs.zone(ctx, key(zone)) ?: if (zone == TAECHO) Prefs.zone(ctx, "normal") else null
    }

    /** 등록된 위치 (지금 화면 방향과 같을 때만, 아니면 null) */
    fun saved(ctx: Context, zone: String, land: Boolean): FloatArray? {
        val v = raw(ctx, zone) ?: return null
        return if ((v[4] >= 0.5f) == land) v else null
    }

    /** 편집 화면에서 처음 보여 줄 위치 — 등록한 값이 있으면 그것(마지막 설정), 없으면 기본 자리 */
    fun current(ctx: Context, zone: String, land: Boolean): FloatArray =
        saved(ctx, zone, land)?.copyOf(4) ?: defaultRect(zone)

    fun isRegistered(ctx: Context, zone: String): Boolean = raw(ctx, zone) != null

    /** 화면 크기(w×h)에 맞춘 네모 — 등록 안 했거나 방향이 다르면 null (= 그 위치의 보스는 찾지 않는다) */
    fun rect(ctx: Context, zone: String, w: Int, h: Int): Rect? {
        val v = saved(ctx, zone, w > h) ?: return null
        val l = (v[0] * w).toInt().coerceIn(0, w - 1)
        val t = (v[1] * h).toInt().coerceIn(0, h - 1)
        val r = (v[2] * w).toInt().coerceIn(l + 1, w)
        val b = (v[3] * h).toInt().coerceIn(t + 1, h)
        if (r - l < 20 || b - t < 20) return null
        return Rect(l, t, r - l, b - t)
    }

    fun save(ctx: Context, zone: String, l: Float, t: Float, r: Float, b: Float, land: Boolean) =
        Prefs.setZone(ctx, key(zone), floatArrayOf(l, t, r, b, if (land) 1f else 0f))

    fun clear(ctx: Context, zone: String) {
        Prefs.clearZone(ctx, key(zone))
        if (zone == TAECHO) Prefs.clearZone(ctx, "normal")
    }

    /** 시작할 때 로그에 남기는 한 줄 */
    fun summary(ctx: Context): String =
        ALL.joinToString(" / ") { z -> z + (if (isRegistered(ctx, z)) " ✔" else " ✘") }

    /** 그 모드에 꼭 필요한데 등록 안 된 위치 */
    fun missingFor(ctx: Context, mode: GameMode): List<String> =
        requiredFor(mode).filter { !isRegistered(ctx, it) }
}
