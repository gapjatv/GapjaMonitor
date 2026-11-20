package tv.gapja.monitor

import android.content.Context
import tv.gapja.monitor.core.BossCatalog
import tv.gapja.monitor.core.Predictor

/**
 * 설정값 저장소 (SharedPreferences).
 *
 * PC판 설정(2)에 있던 값들을 폰에서도 조절할 수 있게 한 것이다.
 * 기본값은 모두 PC판과 같다.
 */
object Prefs {

    private const val F = "gapja"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(F, Context.MODE_PRIVATE)

    // ── 감시 ──────────────────────────────────────────────────────

    /** 화면 축소 비율 — 작을수록 빠르고, 너무 작으면 작은 보스를 놓친다 */
    fun downscale(ctx: Context): Double =
        sp(ctx).getInt("downscale", 50).coerceIn(25, 100) / 100.0

    fun setDownscale(ctx: Context, pct: Int) =
        sp(ctx).edit().putInt("downscale", pct.coerceIn(25, 100)).apply()

    /** 한 바퀴 돌고 쉬는 시간(ms) */
    fun intervalMs(ctx: Context): Long =
        sp(ctx).getInt("interval", 150).coerceIn(50, 2000).toLong()

    fun setIntervalMs(ctx: Context, ms: Int) =
        sp(ctx).edit().putInt("interval", ms.coerceIn(50, 2000)).apply()

    /** 마지막으로 쓴 모드 */
    fun mode(ctx: Context): String = sp(ctx).getString("mode", "invasion") ?: "invasion"

    fun setMode(ctx: Context, key: String) = sp(ctx).edit().putString("mode", key).apply()

    // ── 타일 구제 ─────────────────────────────────────────────────

    /** 같은 원점에 동의해야 하는 최소 조각 수 (0이면 타일구제 끔) */
    fun tileMinAgree(ctx: Context): Int =
        sp(ctx).getInt("tile_agree", BossCatalog.TILE_MIN_AGREE)
            .coerceIn(0, BossCatalog.TILE_ROWS * BossCatalog.TILE_COLS)

    fun setTileMinAgree(ctx: Context, v: Int) =
        sp(ctx).edit().putInt("tile_agree",
            v.coerceIn(0, BossCatalog.TILE_ROWS * BossCatalog.TILE_COLS)).apply()

    /** 동의한 조각들의 평균 점수 최소치 */
    fun tileMinScore(ctx: Context): Double =
        sp(ctx).getInt("tile_score", (BossCatalog.TILE_MIN_SCORE * 100).toInt())
            .coerceIn(50, 99) / 100.0

    fun setTileMinScore(ctx: Context, pct: Int) =
        sp(ctx).edit().putInt("tile_score", pct.coerceIn(50, 99)).apply()

    // ── 80R 안내 ──────────────────────────────────────────────────

    /** 80R 확정 후 몇 ms 뒤에 읽어줄지 */
    fun announceDelayMs(ctx: Context): Long =
        sp(ctx).getInt("announce_delay", 3000).coerceIn(0, 20000).toLong()

    fun setAnnounceDelayMs(ctx: Context, ms: Int) =
        sp(ctx).edit().putInt("announce_delay", ms.coerceIn(0, 20000)).apply()

    /** 사용자가 바꾼 기존 확정 멘트 */
    fun baseTexts(ctx: Context): Map<String, String> {
        val s = sp(ctx)
        val out = HashMap<String, String>()
        for (b in BossCatalog.INVASION_SPECIAL) {
            s.getString("t80_$b", null)?.takeIf { it.isNotBlank() }?.let { out[b] = it }
        }
        return out
    }

    fun setBaseText(ctx: Context, boss: String, text: String) =
        sp(ctx).edit().putString("t80_$boss", text.trim()).apply()

    /** 사용자가 바꾼 조건별 멘트 */
    fun condTexts(ctx: Context): Map<String, String> {
        val s = sp(ctx)
        val out = HashMap<String, String>()
        for (k in Predictor.TTS_80R_COND.keys) {
            s.getString("t80c_$k", null)?.takeIf { it.isNotBlank() }?.let { out[k] = it }
        }
        return out
    }

    fun setCondText(ctx: Context, key: String, text: String) =
        sp(ctx).edit().putString("t80c_$key", text.trim()).apply()

    fun resetTexts(ctx: Context) {
        val e = sp(ctx).edit()
        for (b in BossCatalog.INVASION_SPECIAL) e.remove("t80_$b")
        for (k in Predictor.TTS_80R_COND.keys) e.remove("t80c_$k")
        e.apply()
    }

    // ── 인식 기준 ─────────────────────────────────────────────────

    /** 보스별 기준값 보정 (% 단위) */
    fun thresholds(ctx: Context): Map<String, Int> {
        val s = sp(ctx)
        val out = HashMap<String, Int>()
        for (b in BossCatalog.watchList(tv.gapja.monitor.core.GameMode.INVASION) +
                BossCatalog.BOSS_NAMES) {
            val v = s.getInt("th_$b", -1)
            if (v in 1..99) out[b] = v
        }
        return out
    }

    fun setThreshold(ctx: Context, boss: String, pct: Int) =
        sp(ctx).edit().putInt("th_$boss", pct.coerceIn(1, 99)).apply()

    fun clearThreshold(ctx: Context, boss: String) =
        sp(ctx).edit().remove("th_$boss").apply()

    /** 깐깐 검사를 적용할 보스 (오인식이 생길 때만 추가) */
    fun strictBosses(ctx: Context): Set<String> =
        sp(ctx).getStringSet("strict", emptySet()) ?: emptySet()

    fun setStrictBosses(ctx: Context, set: Set<String>) =
        sp(ctx).edit().putStringSet("strict", set).apply()

    // ── 음성 ──────────────────────────────────────────────────────

    fun voiceEnabled(ctx: Context): Boolean = sp(ctx).getBoolean("voice", true)

    fun setVoiceEnabled(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean("voice", on).apply()

    /** 읽기 속도 (0.5 ~ 2.0) */
    fun speechRate(ctx: Context): Float =
        sp(ctx).getInt("rate", 100).coerceIn(50, 200) / 100f

    fun setSpeechRate(ctx: Context, pct: Int) =
        sp(ctx).edit().putInt("rate", pct.coerceIn(50, 200)).apply()

    /** 보스 이름만 읽기 vs 전체 멘트 */
    fun speakBossName(ctx: Context): Boolean = sp(ctx).getBoolean("say_boss", true)

    fun setSpeakBossName(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean("say_boss", on).apply()

    // ── 오버레이 ──────────────────────────────────────────────────

    fun overlayEnabled(ctx: Context): Boolean = sp(ctx).getBoolean("overlay", true)

    fun setOverlayEnabled(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean("overlay", on).apply()

    fun overlayX(ctx: Context): Int = sp(ctx).getInt("ov_x", 16)
    fun overlayY(ctx: Context): Int = sp(ctx).getInt("ov_y", 120)

    fun setOverlayPos(ctx: Context, x: Int, y: Int) =
        sp(ctx).edit().putInt("ov_x", x).putInt("ov_y", y).apply()

    /** 침공 특수보스(거대 전기로봇·악마 모노폴리·쌍둥이 슬라임) 사진 표시 — 꺼도 인식·80R 안내는 그대로 */
    fun showSpecial(ctx: Context): Boolean = sp(ctx).getBoolean("ov_special", true)

    fun setShowSpecial(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean("ov_special", on).apply()

    /** 보스가 늘면 사진을 줄여서 정해 둔 길이 안에 전부 보이게 한다 */
    fun autoShrink(ctx: Context): Boolean = sp(ctx).getBoolean("ov_shrink", true)

    fun setAutoShrink(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean("ov_shrink", on).apply()

    /** 사진 배치 — true 면 세로로 쌓고, false 면 가로로 늘어놓는다 */
    fun overlayVertical(ctx: Context): Boolean = sp(ctx).getBoolean("ov_vertical", true)

    fun setOverlayVertical(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean("ov_vertical", on).apply()

    /** 보스 사진 최대 지름(dp) — 보스가 적을 때의 크기 */
    fun overlayMaxDp(ctx: Context): Int = sp(ctx).getInt("ov_size", 56).coerceIn(24, 120)

    fun setOverlayMaxDp(ctx: Context, dp: Int) =
        sp(ctx).edit().putInt("ov_size", dp.coerceIn(24, 120)).apply()

    /** 사진 줄의 최대 길이(dp) — 이 길이를 넘으면 자동 축소. 0 이면 화면 끝까지 */
    fun overlayLengthDp(ctx: Context): Int = sp(ctx).getInt("ov_len", 0).coerceIn(0, 4000)

    fun setOverlayLengthDp(ctx: Context, dp: Int) =
        sp(ctx).edit().putInt("ov_len", dp.coerceIn(0, 4000)).apply()

    /** 보스 사진 전체 표시 — 끄면 사진은 안 뜨고 음성만 나온다 */
    fun overlayShowAll(ctx: Context): Boolean = sp(ctx).getBoolean("ov_all", true)

    fun setOverlayShowAll(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean("ov_all", on).apply()

    /** [캡처]·[조절] 버튼 묶음의 위치 (처음에는 화면 오른쪽 위) */
    fun chipX(ctx: Context, def: Int): Int = sp(ctx).getInt("chip_x", def)
    fun chipY(ctx: Context, def: Int): Int = sp(ctx).getInt("chip_y", def)

    fun setChipPos(ctx: Context, x: Int, y: Int) =
        sp(ctx).edit().putInt("chip_x", x).putInt("chip_y", y).apply()

    /** 기본 자료(보스 사진·인식 사진·음성)를 앱에 풀어 둔 버전 */
    fun assetsVersion(ctx: Context): Int = sp(ctx).getInt("assets_ver", 0)

    fun setAssetsVersion(ctx: Context, v: Int) =
        sp(ctx).edit().putInt("assets_ver", v).apply()
}
