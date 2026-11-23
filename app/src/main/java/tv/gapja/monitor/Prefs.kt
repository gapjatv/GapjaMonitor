package tv.gapja.monitor

import android.content.Context
import tv.gapja.monitor.core.GameMode
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
    // 기본은 끔 — 내가 정한 크기를 그대로 지키고, 넘치면 줄을 바꿔 다 보이게 한다
    // (예전 키 ov_shrink 는 기본이 켬이라 너무 작아지는 문제가 있어 새 키로 바꿨다)
    fun autoShrink(ctx: Context): Boolean = sp(ctx).getBoolean("ov_shrink2", false)

    fun setAutoShrink(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean("ov_shrink2", on).apply()

    /** 사진 배치 — true 면 세로로 쌓고, false 면 가로로 늘어놓는다 */
    fun overlayVertical(ctx: Context): Boolean = sp(ctx).getBoolean("ov_vertical", true)

    fun setOverlayVertical(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean("ov_vertical", on).apply()

    /** 보스 사진 최대 지름(dp) — 보스가 적을 때의 크기 */
    fun overlayMaxDp(ctx: Context): Int = sp(ctx).getInt("ov_size", 56).coerceIn(20, 160)

    fun setOverlayMaxDp(ctx: Context, dp: Int) =
        sp(ctx).edit().putInt("ov_size", dp.coerceIn(20, 160)).apply()

    /** 사진 줄의 최대 길이(dp) — 이 길이를 넘으면 자동 축소. 0 이면 화면 끝까지 */
    fun overlayLengthDp(ctx: Context): Int = sp(ctx).getInt("ov_len", 0).coerceIn(0, 4000)

    fun setOverlayLengthDp(ctx: Context, dp: Int) =
        sp(ctx).edit().putInt("ov_len", dp.coerceIn(0, 4000)).apply()

    /** 보스 사진 전체 표시 — 끄면 사진은 안 뜨고 음성만 나온다 */
    fun overlayShowAll(ctx: Context): Boolean = sp(ctx).getBoolean("ov_all", true)

    fun setOverlayShowAll(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean("ov_all", on).apply()

    /** [음성만] 켜면 보스 사진·예상보스 4마리 사진을 숨기고 음성으로만 알린다 (v2.6) */
    fun voiceOnly(ctx: Context): Boolean = sp(ctx).getBoolean("ov_voice_only", false)

    fun setVoiceOnly(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean("ov_voice_only", on).apply()

    /** 보스흐림설정 — 보스 사진이 보이는 정도 (%). 100 = 또렷하게, 낮출수록 흐리게 (v2.6) */
    fun bossOpacity(ctx: Context): Int = sp(ctx).getInt("ov_opacity", 100).coerceIn(10, 100)

    fun setBossOpacity(ctx: Context, pct: Int) =
        sp(ctx).edit().putInt("ov_opacity", pct.coerceIn(10, 100)).apply()

    /** [캡처]·[조절] 버튼 묶음의 위치 (처음에는 화면 오른쪽 위) */
    fun chipX(ctx: Context, def: Int): Int = sp(ctx).getInt("chip_x", def)
    fun chipY(ctx: Context, def: Int): Int = sp(ctx).getInt("chip_y", def)

    fun setChipPos(ctx: Context, x: Int, y: Int) =
        sp(ctx).edit().putInt("chip_x", x).putInt("chip_y", y).apply()

    // ── 남은 보스 예측 화면 (침공 70R 보스가 나온 뒤 후보 4마리) ──

    /** 남은 보스 4마리를 화면·음성으로 알려 줄지 */
    fun remainEnabled(ctx: Context): Boolean = sp(ctx).getBoolean("remain_on", true)

    fun setRemainEnabled(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean("remain_on", on).apply()

    /** 70R 보스가 나온 뒤 몇 초 있다가 알려 줄지 (PC 미출현 팝업: 6초) */
    fun remainDelaySec(ctx: Context): Int = sp(ctx).getInt("remain_delay", 6).coerceIn(0, 60)

    fun setRemainDelaySec(ctx: Context, sec: Int) =
        sp(ctx).edit().putInt("remain_delay", sec.coerceIn(0, 60)).apply()

    /** 예측 화면을 몇 초 띄울지 (PC 미출현 팝업: 5초). 0 이면 80R 보스가 인식되거나 리셋될 때까지 계속 */
    fun remainShowSec(ctx: Context): Int = sp(ctx).getInt("remain_show", 5).coerceIn(0, 120)

    fun setRemainShowSec(ctx: Context, sec: Int) =
        sp(ctx).edit().putInt("remain_show", sec.coerceIn(0, 120)).apply()

    /** 예측 화면의 위치(px)와 크기(dp). 처음(저장 전)에는 null → 화면 위쪽 가운데에 가로 한 줄 */
    fun remainRect(ctx: Context): IntArray? {
        val s = sp(ctx)
        if (!s.contains("remain_w")) return null
        return intArrayOf(s.getInt("remain_x", 0), s.getInt("remain_y", 0),
            s.getInt("remain_w", 0), s.getInt("remain_h", 0))
    }

    fun setRemainRect(ctx: Context, xPx: Int, yPx: Int, wDp: Int, hDp: Int) =
        sp(ctx).edit().putInt("remain_x", xPx).putInt("remain_y", yPx)
            .putInt("remain_w", wDp).putInt("remain_h", hDp).apply()

    // ── 보스설정 버튼 ──

    /** [보스설정] 버튼 크기(%) — 130 이 기본 */
    fun buttonScale(ctx: Context): Int = sp(ctx).getInt("btn_scale", 130).coerceIn(60, 200)

    fun setButtonScale(ctx: Context, pct: Int) =
        sp(ctx).edit().putInt("btn_scale", pct.coerceIn(60, 200)).apply()

    // ── 배속 · 위험 경보 ──

    /** 배속 고정 — 켜면 태초는 항상 2배속, 침공은 앞 30% 만 2배속으로 계산 (PC 기본값과 같다) */
    fun speedLock(ctx: Context): Boolean = sp(ctx).getBoolean("speed_lock", true)

    fun setSpeedLock(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean("speed_lock", on).apply()

    /** 로비·모드 전환 글자를 게임 화면에 띄울지 (기본 끔 — 반복 표시 문제 테스트용, v2.0) */
    fun lobbyNotice(ctx: Context): Boolean = sp(ctx).getBoolean("lobby_notice", false)

    fun setLobbyNotice(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean("lobby_notice", on).apply()

    /** 위험 경보 사용 */
    fun warningEnabled(ctx: Context): Boolean = sp(ctx).getBoolean("warn_on", true)

    fun setWarningEnabled(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean("warn_on", on).apply()

    /** 일반 보스 경보 목표 시간(초) */
    fun warnBase(ctx: Context, mode: GameMode): Int =
        if (mode == GameMode.NORMAL) sp(ctx).getInt("warn_base_n", PcDefaults.WARN_BASE_NORMAL)
        else sp(ctx).getInt("warn_base_i", PcDefaults.WARN_BASE_INVASION)

    fun setWarnBase(ctx: Context, mode: GameMode, sec: Int) =
        sp(ctx).edit().putInt(if (mode == GameMode.NORMAL) "warn_base_n" else "warn_base_i",
            sec.coerceIn(0, 3600)).apply()

    /** 보스별 개별 경보 시간(초) — 설정 안 했으면 PC 기본값 */
    fun warnSpecial(ctx: Context, mode: GameMode, boss: String): Int? {
        val key = (if (mode == GameMode.NORMAL) "warn_sp_n_" else "warn_sp_i_") + boss
        val def = (if (mode == GameMode.NORMAL) PcDefaults.WARN_SPECIAL_NORMAL else PcDefaults.WARN_SPECIAL_INVASION)[boss]
        val v = sp(ctx).getInt(key, Int.MIN_VALUE)
        return if (v == Int.MIN_VALUE) def else v
    }

    fun setWarnSpecial(ctx: Context, mode: GameMode, boss: String, sec: Int) =
        sp(ctx).edit().putInt((if (mode == GameMode.NORMAL) "warn_sp_n_" else "warn_sp_i_") + boss,
            sec.coerceIn(0, 3600)).apply()

    /** 그 보스의 경보 목표 시간(초) — PC schedule_warning 과 같은 규칙 */
    fun warnDelay(ctx: Context, mode: GameMode, boss: String): Int {
        val base = warnBase(ctx, mode)
        if (boss !in PcDefaults.WARN_SPECIAL_BOSSES) return base
        return warnSpecial(ctx, mode, boss) ?: base
    }

    /** 경보 시간을 PC 기본값으로 되돌린다 */
    fun resetWarnings(ctx: Context) {
        val e = sp(ctx).edit()
        e.remove("warn_base_n"); e.remove("warn_base_i")
        for (b in PcDefaults.WARN_SPECIAL_BOSSES) { e.remove("warn_sp_n_$b"); e.remove("warn_sp_i_$b") }
        e.apply()
    }

    // ── 인식용 사진: PC 사진 / 폰에서 새로 등록 ──

    /** true 면 앱에 넣은 PC 사진도 인식에 쓴다. false('새로 등록')면 내가 등록한 사진만 쓴다 */
    fun usePcTemplates(ctx: Context): Boolean = sp(ctx).getBoolean("use_pc_tpl", true)

    fun setUsePcTemplates(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean("use_pc_tpl", on).putBoolean("tpl_asked", true).apply()

    /** 처음 실행할 때 'PC 사진 / 새로 등록' 을 물어봤는지 */
    fun templateSourceAsked(ctx: Context): Boolean = sp(ctx).getBoolean("tpl_asked", false)

    /** 내가 직접 캡처했거나 가져온 인식용 사진 파일 이름들 */
    fun userTemplates(ctx: Context): Set<String> =
        sp(ctx).getStringSet("user_tpls", emptySet())?.toSet() ?: emptySet()

    fun addUserTemplate(ctx: Context, name: String) {
        val set = userTemplates(ctx).toMutableSet(); set.add(name)
        sp(ctx).edit().putStringSet("user_tpls", set).apply()
    }

    fun clearUserTemplates(ctx: Context) = sp(ctx).edit().remove("user_tpls").apply()

    fun removeUserTemplate(ctx: Context, name: String) {
        val set = userTemplates(ctx).toMutableSet(); set.remove(name)
        sp(ctx).edit().putStringSet("user_tpls", set).apply()
    }

    /** 보스 사진 줄 터치 통과 — 켜면 사진 위를 눌러도 게임이 눌리고(사진 고정), 끄면 사진을 끌어서 옮길 수 있다 */
    fun stripPassThrough(ctx: Context): Boolean = sp(ctx).getBoolean("strip_pass", false)

    fun setStripPassThrough(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean("strip_pass", on).apply()

    /**
     * 인식 방식 — true 면 접근성(확인창 없음), false 면 화면 공유(빠름, 시작할 때 확인창).
     * 처음 값: 뮤뮤플레이어 같은 PC 앱플레이어(x86_64)는 접근성, 폰은 화면 공유.
     */
    fun useA11y(ctx: Context): Boolean = sp(ctx).getBoolean("use_a11y",
        android.os.Build.SUPPORTED_ABIS.firstOrNull()?.startsWith("x86") == true &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)

    fun setUseA11y(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("use_a11y", on).apply()

    /** 찾아 둔 게임 앱 (운빨존많겜) — 다른 화면(뮤뮤 탭)에서도 게임 창을 알아보는 데 쓴다 */
    fun gamePackage(ctx: Context): String? = sp(ctx).getString("game_pkg", null)

    fun setGamePackage(ctx: Context, pkg: String?) = sp(ctx).edit().putString("game_pkg", pkg).apply()

    /**
     * [캡처] 후 바로 자르기 화면을 열지.
     * 기본은 끔 — 캡처만 '캡처함' 에 저장하고 게임 화면에 그대로 머문다.
     * (게임을 벗어나면 게임이 튕기거나 다시 접속되면서 인식한 보스가 초기화되는 일이 있어서)
     */
    fun cropRightAway(ctx: Context): Boolean = sp(ctx).getBoolean("crop_now", false)

    fun setCropRightAway(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("crop_now", on).apply()

    /**
     * 발열 줄이기(절전) — 기본 켬.
     *   • 검사 간격을 최소 0.3초로   • 내가 등록한 사진은 크기 비교를 3단계만
     *   • 보스를 찾은 뒤 8초, 로비에 있는 동안은 천천히 검사
     */
    fun powerSave(ctx: Context): Boolean = sp(ctx).getBoolean("power_save", true)

    fun setPowerSave(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("power_save", on).apply()

    /**
     * 자르기 네모의 위치·크기와 확대 배율을 기억한다 (보스 / 로비, 세로 / 가로 따로).
     * 값: "왼,위,오른,아래,배율" (사진 크기에 대한 0~1 비율)
     */
    fun cropBox(ctx: Context, key: String): FloatArray? {
        val v = sp(ctx).getString("crop_box_$key", null) ?: return null
        val a = v.split(',').mapNotNull { it.toFloatOrNull() }
        return if (a.size == 5) a.toFloatArray() else null
    }

    fun setCropBox(ctx: Context, key: String, v: FloatArray) =
        sp(ctx).edit().putString("crop_box_$key", v.joinToString(",")).apply()

    /** [사진 추가] 를 누르면 사진첩에서 고를지(기본) 파일에서 고를지 */
    fun pickFromGallery(ctx: Context): Boolean = sp(ctx).getBoolean("pick_gallery", true)

    fun setPickFromGallery(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean("pick_gallery", on).apply()

    /** 기본 자료(보스 사진·인식 사진·음성)를 앱에 풀어 둔 버전 */
    fun assetsVersion(ctx: Context): Int = sp(ctx).getInt("assets_ver", 0)

    fun setAssetsVersion(ctx: Context, v: Int) =
        sp(ctx).edit().putInt("assets_ver", v).apply()

    /** 마지막으로 풀어 놓은 기본 자료의 지문 (빌드할 때 깃허브 사진이 바뀌면 달라진다, v2.8) */
    fun assetsStamp(ctx: Context): String = sp(ctx).getString("assets_stamp", "") ?: ""

    fun setAssetsStamp(ctx: Context, v: String) =
        sp(ctx).edit().putString("assets_stamp", v).apply()

    // ── 깃허브 자동 저장 (v2.8) ──────────────────────────────────

    /** 깃허브 열쇠(토큰) — 이 기기 안에만 저장한다. 코드에는 넣지 않는다 */
    fun ghToken(ctx: Context): String = (sp(ctx).getString("gh_token", "") ?: "").trim()

    fun setGhToken(ctx: Context, v: String) =
        sp(ctx).edit().putString("gh_token", v.trim()).apply()

    /** 등록·삭제할 때 자동으로 깃허브에 맞출지 (열쇠가 있을 때만 동작) */
    fun ghSyncOn(ctx: Context): Boolean = sp(ctx).getBoolean("gh_sync_on", true)

    fun setGhSyncOn(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean("gh_sync_on", on).apply()

    /** 깃허브에 맞추기를 기다리는 사진 이름들 (인터넷이 끊겨도 잊지 않게 저장해 둔다) */
    fun ghPending(ctx: Context): Set<String> = synchronized(ghLock) {
        sp(ctx).getStringSet("gh_pending", emptySet())?.toSet() ?: emptySet()
    }

    fun addGhPending(ctx: Context, names: Collection<String>) = synchronized(ghLock) {
        val set = ghPending(ctx).toMutableSet(); set.addAll(names)
        sp(ctx).edit().putStringSet("gh_pending", set).apply()
    }

    fun removeGhPending(ctx: Context, name: String) = synchronized(ghLock) {
        val set = ghPending(ctx).toMutableSet(); set.remove(name)
        sp(ctx).edit().putStringSet("gh_pending", set).apply()
    }

    /** 깃허브 자동 저장의 최근 결과 (메인 화면 표시용) */
    fun ghStatus(ctx: Context): String = sp(ctx).getString("gh_status", "") ?: ""

    fun setGhStatus(ctx: Context, v: String) =
        sp(ctx).edit().putString("gh_status", v).apply()

    private val ghLock = Any()
}
