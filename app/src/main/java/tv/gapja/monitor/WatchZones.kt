package tv.gapja.monitor

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import org.opencv.core.Rect
import tv.gapja.monitor.core.BossCatalog
import tv.gapja.monitor.core.GameMode

/**
 * 인식 위치 (v3.7) — 보스는 지정한 네모 안에서만 찾는다. 전체 화면에서는 보스를 찾지 않는다.
 *
 *   전체보스 — 태초·침공 모드의 일반 보스(히든보스 포함)가 나오는 곳 (v3.7: 태초보스·침공보스를 한 곳으로 통합)
 *              지금이 태초인지 침공인지는 로비 인식으로 정해지고, 그 모드의 보스 목록만 이 네모 안에서 찾는다.
 *   시공보스 — 침공특수보스 3종(악마 모노폴리 · 거대 전기로봇 · 쌍둥이 슬라임)이 나오는 곳 (오른쪽)
 *
 * 로비 화면(태초·침공·공통로비 1~3)과 배속 표시는 지금처럼 전체 화면에서 찾는다.
 *
 * 위치는 '화면에 대한 0~1 비율' + 가로/세로 방향으로 저장한다 → 앱을 다시 켜거나 새 버전을 받아도,
 * 해상도가 달라도 그대로 쓰인다.
 *
 * v3.7: 처음 설치했을 때(저장한 값이 없을 때)는 기기 종류(핸드폰 / 뮤뮤)별 기본 위치를 그대로 쓴다.
 *       (세로 화면 기준 — 가로 화면은 저장한 값이 있을 때만 쓴다)
 *       사용자가 바꿔서 저장하면 그 값이 우선이고, [위치 지우기] 를 하면 기본 위치로 돌아온다.
 */
object WatchZones {
    const val BOSS = "전체보스"
    const val SIGONG = "시공보스"

    val ALL: List<String> = listOf(BOSS, SIGONG)

    private val KEYS = mapOf(BOSS to "boss", SIGONG to "sigong")

    /** v3.6 까지 쓰던 이름(태초보스·침공보스) 저장 키 — 전체보스로 이어받는다 */
    private val LEGACY_KEYS = listOf("chimgong", "taecho", "normal")

    /** 박스 색 — 위치마다 다르게 */
    private val COLORS = mapOf(
        BOSS to Color.parseColor("#2ECC71"),       // 초록
        SIGONG to Color.parseColor("#2EC4F0")      // 하늘
    )

    /** 뮤뮤플레이어 같은 PC 앱플레이어(x86)인지 — 인식 사진 폴더를 고르는 기준과 같다 */
    private fun isMumu(): Boolean =
        android.os.Build.SUPPORTED_ABIS.firstOrNull()?.startsWith("x86") == true

    /**
     * 처음 설치했을 때의 기본 자리 (왼, 위, 오른, 아래 비율, 세로 화면).
     * 실제 게임 화면 스크린샷(갤럭시 S26 울트라 / 뮤뮤플레이어 안드로이드15)에서 보스 그림·체력바·웨이브가
     * 나오는 곳을 잰 값이다.
     */
    private val DEFAULTS_PHONE = mapOf(
        BOSS to floatArrayOf(0.300f, 0.142f, 0.725f, 0.297f),
        SIGONG to floatArrayOf(0.755f, 0.164f, 0.991f, 0.295f)
    )
    private val DEFAULTS_MUMU = mapOf(
        BOSS to floatArrayOf(0.319f, 0.097f, 0.688f, 0.239f),
        SIGONG to floatArrayOf(0.763f, 0.121f, 0.995f, 0.253f)
    )

    fun key(zone: String): String = KEYS[zone] ?: zone
    fun color(zone: String): Int = COLORS[zone] ?: Color.parseColor("#F39C12")

    /** 이 기기의 기본 자리 (세로 화면 기준) */
    fun defaultRect(zone: String): FloatArray {
        val table = if (isMumu()) DEFAULTS_MUMU else DEFAULTS_PHONE
        return (table[zone] ?: floatArrayOf(0.3f, 0.15f, 0.7f, 0.3f)).copyOf()
    }

    /** 자르기 화면 목록에 보이는 이름 */
    fun label(zone: String): String = "📐 $zone 인식위치"

    /** 목록의 이름 → 위치 이름 (인식위치 항목이 아니면 null) */
    fun zoneOf(label: String?): String? = ALL.firstOrNull { label(it) == label }

    /** 그 보스를 찾을 위치 — 침공특수보스 3종만 시공보스, 나머지(태초·침공 일반·히든)는 전체보스 */
    fun zoneForBoss(name: String, mode: GameMode): String = when {
        mode == GameMode.NORMAL -> BOSS
        BossCatalog.isSpecial(name) -> SIGONG
        else -> BOSS
    }

    /** 그 모드에서 보스를 찾는 데 쓰는 위치 */
    fun requiredFor(mode: GameMode): List<String> =
        if (mode == GameMode.NORMAL) listOf(BOSS) else listOf(BOSS, SIGONG)

    /** 저장돼 있는 원래 값 — 전체보스는 v3.6 이전 태초·침공 위치가 있으면 그것을 이어받는다 */
    private fun raw(ctx: Context, zone: String): FloatArray? {
        Prefs.zone(ctx, key(zone))?.let { return it }
        if (zone != BOSS) return null
        // 침공보스·태초보스 두 곳을 다 등록해 뒀으면(같은 방향일 때) 둘을 함께 감싸는 큰 네모로 합친다
        val olds = LEGACY_KEYS.mapNotNull { Prefs.zone(ctx, it) }
        if (olds.isEmpty()) return null
        val first = olds[0]
        val same = olds.filter { (it[4] >= 0.5f) == (first[4] >= 0.5f) }
        return floatArrayOf(
            same.minOf { it[0] }, same.minOf { it[1] }, same.maxOf { it[2] }, same.maxOf { it[3] }, first[4]
        )
    }

    /** 등록된 위치 (지금 화면 방향과 같을 때만, 아니면 null) */
    fun saved(ctx: Context, zone: String, land: Boolean): FloatArray? {
        val v = raw(ctx, zone) ?: return null
        return if ((v[4] >= 0.5f) == land) v else null
    }

    /**
     * 실제로 쓰는 위치 — 이 화면 방향으로 저장한 값이 있으면 그것, 없으면 세로 화면에서는 기본 자리, 가로 화면은 null.
     */
    fun effective(ctx: Context, zone: String, land: Boolean): FloatArray? {
        saved(ctx, zone, land)?.let { return it.copyOf(4) }
        return if (land) null else defaultRect(zone)
    }

    /** 편집 화면에서 처음 보여 줄 위치 — 등록한 값이 있으면 그것(마지막 설정), 없으면 기본 자리 */
    fun current(ctx: Context, zone: String, land: Boolean): FloatArray =
        saved(ctx, zone, land)?.copyOf(4) ?: defaultRect(zone)

    /** 사용자가 직접 저장한 값이 있는지 (없으면 기본 위치를 쓰는 중) */
    fun isRegistered(ctx: Context, zone: String): Boolean = raw(ctx, zone) != null

    /** 화면 크기(w×h)에 맞춘 네모 — 위치가 없으면 null (= 그 위치의 보스는 찾지 않는다) */
    fun rect(ctx: Context, zone: String, w: Int, h: Int): Rect? {
        val v = effective(ctx, zone, w > h) ?: return null
        val l = (v[0] * w).toInt().coerceIn(0, w - 1)
        val t = (v[1] * h).toInt().coerceIn(0, h - 1)
        val r = (v[2] * w).toInt().coerceIn(l + 1, w)
        val b = (v[3] * h).toInt().coerceIn(t + 1, h)
        if (r - l < 20 || b - t < 20) return null
        return Rect(l, t, r - l, b - t)
    }

    fun save(ctx: Context, zone: String, l: Float, t: Float, r: Float, b: Float, land: Boolean) {
        Prefs.setZone(ctx, key(zone), floatArrayOf(l, t, r, b, if (land) 1f else 0f))
        // 전체보스로 저장하면 예전 태초·침공 값은 더 이어받지 않는다
        if (zone == BOSS) for (k in LEGACY_KEYS) Prefs.clearZone(ctx, k)
    }

    /** 저장한 값을 지운다 → 기본 위치로 돌아간다 */
    fun clear(ctx: Context, zone: String) {
        Prefs.clearZone(ctx, key(zone))
        if (zone == BOSS) for (k in LEGACY_KEYS) Prefs.clearZone(ctx, k)
    }

    /** 시작할 때 로그에 남기는 한 줄 */
    fun summary(ctx: Context): String =
        ALL.joinToString(" / ") { z -> z + (if (isRegistered(ctx, z)) " ✔저장" else " 기본") }

    /** 그 모드에서 쓰는 위치 중 지금 화면 방향에서 위치가 없는 것 (세로 화면이면 기본 자리가 있어 비지 않는다) */
    fun missingFor(ctx: Context, mode: GameMode): List<String> {
        val land = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return requiredFor(mode).filter { effective(ctx, it, land) == null }
    }
}
