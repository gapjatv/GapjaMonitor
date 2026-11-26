package tv.gapja.monitor

import android.content.Context
import org.opencv.core.Rect
import tv.gapja.monitor.core.GameMode

/**
 * 감시 구역 (v3.5) — 화면 전체가 아니라 지정한 네모 안에서만 찾는다.
 *
 *   태초 보스 구역 / 침공 보스 구역1 / 침공 보스 구역2 / 로비·배속 구역  (총 4칸)
 *
 * • 위치는 '화면에 대한 0~1 비율' + 가로/세로 방향으로 저장한다 → 앱을 다시 켜거나 새 버전을 받아도,
 *   해상도가 달라도 그대로 쓰인다. (방향이 다르면 그 구역은 쓰지 않고 전체 화면을 본다)
 * • 등록하지 않은 칸은 전체 화면을 본다 (예전과 같다).
 */
object WatchZones {
    const val NORMAL = "📐 태초 보스 구역"
    const val INV1 = "📐 침공 보스 구역1"
    const val INV2 = "📐 침공 보스 구역2"
    const val LOBBY = "📐 로비·배속 구역"

    val ALL: List<String> = listOf(NORMAL, INV1, INV2, LOBBY)

    private val KEYS = mapOf(NORMAL to "normal", INV1 to "inv1", INV2 to "inv2", LOBBY to "lobby")

    fun isZone(name: String?): Boolean = name != null && name in KEYS

    /** 저장 이름표 */
    fun key(zone: String): String = KEYS[zone] ?: zone

    /** 그 모드의 보스를 찾을 구역 — 태초 1곳, 침공 2곳 */
    fun bossZones(mode: GameMode): List<String> =
        if (mode == GameMode.NORMAL) listOf(NORMAL) else listOf(INV1, INV2)

    /** 자르기 화면에서 그 모드에 보여줄 구역 항목 */
    fun forMode(mode: GameMode): List<String> = bossZones(mode) + LOBBY

    /** 등록돼 있고, 지금 화면 방향과 같으면 그 구역을 화면 크기(w×h)에 맞춘 네모로 돌려준다 */
    fun rect(ctx: Context, zone: String, w: Int, h: Int): Rect? {
        val v = Prefs.zone(ctx, key(zone)) ?: return null
        val land = v[4] >= 0.5f
        if ((w > h) != land) return null
        val l = (v[0] * w).toInt().coerceIn(0, w - 1)
        val t = (v[1] * h).toInt().coerceIn(0, h - 1)
        val r = (v[2] * w).toInt().coerceIn(l + 1, w)
        val b = (v[3] * h).toInt().coerceIn(t + 1, h)
        if (r - l < 20 || b - t < 20) return null
        return Rect(l, t, r - l, b - t)
    }

    /** 시작할 때 로그에 남기는 한 줄 */
    fun summary(ctx: Context): String =
        ALL.joinToString(" / ") { z ->
            z.removePrefix("📐 ") + (if (Prefs.zone(ctx, key(z)) != null) " ✔" else " ✘")
        }
}
