package tv.gapja.monitor

import tv.gapja.monitor.core.GameMode

/**
 * PC판 설정(app_config.json)에서 가져온 기본값.
 *
 * 폰판의 첫 실행 기본값을 PC에서 쓰던 값과 맞춘다. 사용자가 앱에서 바꾼 값이 있으면
 * 그쪽이 우선한다. (애저 키 등 PC 전용 값은 가져오지 않는다)
 */
object PcDefaults {

    /** PC boss_match_thresholds — 모든 모드 공통 매칭 기준(%) */
    private val THRESHOLDS: Map<String, Int> = mapOf(
        "고기슬라임" to 65, "그런트" to 60, "윔" to 60, "콜디스트" to 60,
        "보호막로봇" to 60, "양술사" to 60, "닥터" to 60, "샤먼" to 60,
        "매지션" to 60, "사신" to 60, "반파이어" to 60, "나하투" to 60,
        "블랑카" to 60, "히든보스" to 60, "쿨타임" to 60,
        "거대 전기로봇" to 55, "쌍둥이 슬라임" to 60, "악마 모노폴리" to 60
    )

    /** PC boss_match_thresholds_invasion — 침공 모드에서만 덮어쓰는 값(%) */
    private val THRESHOLDS_INVASION: Map<String, Int> = mapOf(
        "양술사" to 52, "거대 전기로봇" to 58
    )

    /** 그 모드에서 쓸 기본 기준값 (사용자 보정값이 있으면 그걸로 덮어쓴다) */
    fun thresholds(mode: GameMode, user: Map<String, Int>): Map<String, Int> {
        val out = HashMap(THRESHOLDS)
        if (mode == GameMode.INVASION) out.putAll(THRESHOLDS_INVASION)
        out.putAll(user)
        return out
    }

    /**
     * PC custom_texts — 보스가 인식되면 읽어주는 말.
     * voices/ 폴더에 같은 이름의 mp3가 있으면 그 파일로 재생한다. (예: 고기.mp3)
     */
    val VOICE_TEXT: Map<String, String> = mapOf(
        "고기슬라임" to "고기", "그런트" to "그런트", "윔" to "윔 출현", "콜디스트" to "콜디",
        "보호막로봇" to "보호막", "닥터" to "닥터", "샤먼" to "샤먼", "매지션" to "매지션",
        "사신" to "사신", "반파이어" to "반파", "나하투" to "나하투", "블랑카" to "블랑카",
        "히든보스" to "히든", "쿨타임" to "쿨타임", "거대 전기로봇" to "거대전기로봇",
        "쌍둥이 슬라임" to "쌍둥이슬라임", "악마 모노폴리" to "악마모노폴리", "양술사" to "양술사"
    )

    /** PC invasion_80r_texts — 80R 확정 기본 멘트 */
    val TEXT_80R: Map<String, String> = mapOf(
        "거대 전기로봇" to "80라운드 거대 전기로봇 입니다. 물리딜러 준비",
        "쌍둥이 슬라임" to "80라운드 쌍둥이 슬라임 입니다. 광역딜러 준비",
        "악마 모노폴리" to "80라운드 악마 모노폴리 입니다. 마법딜러 준비"
    )

    /** PC invasion_80r_cond_texts — 80R 조건별 멘트 */
    val TEXT_80R_COND: Map<String, String> = mapOf(
        "거대 전기로봇|그런트" to "80라운드 전기로봇 입니다. 물리준비 그런트 빠짐",
        "쌍둥이 슬라임|양술사" to "80라운드 쌍둥이 슬라임. 양술 빠짐",
        "쌍둥이 슬라임|그런트" to "80라운드 쌍둥이 슬라임. 그런트 빠짐",
        "쌍둥이 슬라임|양술사+그런트" to "80라운드 쌍둥이 슬라임 양술, 그런트 둘다 빠짐",
        "악마 모노폴리|양술사" to "80라운드 악마 모노폴리 입니다. 마법딜러 준비, 양술도 빠짐"
    )

    /** 사용자 멘트가 우선, 없으면 PC 멘트 */
    fun baseTexts(user: Map<String, String>): Map<String, String> = TEXT_80R + user

    fun condTexts(user: Map<String, String>): Map<String, String> = TEXT_80R_COND + user

    /** 배속 표시 — PC판과 같은 규칙 */
    fun speedLabel(mode: GameMode, locked: Boolean = true): String = when {
        !locked -> "배속 자동 인식"
        mode == GameMode.NORMAL -> "2배속 고정"
        else -> "초반 2배속 → 1배속"
    }

    // ── 로비 화면 (인식되면 보스 사진 초기화) ───────────────────

    /** 태초 로비 — 인식되면 태초 모드로 바꾸고 초기화 (PC '로비화면.png') */
    const val LOBBY_NORMAL = "태초로비"

    /** 침공 로비 — 인식되면 침공 모드로 바꾸고 초기화 (PC '침공로비화면.png') */
    const val LOBBY_INVASION = "침공로비"

    /** 공통 로비 — 로비까지 안 가고 '다시하기'를 누르는 화면. 모드는 그대로 두고 초기화 (PC '공용화면.png') */
    const val LOBBY_COMMON = "공통로비"

    val LOBBIES: List<String> = listOf(LOBBY_NORMAL, LOBBY_INVASION, LOBBY_COMMON)

    fun isLobby(name: String): Boolean = name in LOBBIES

    /** 자르기 화면에서 그 모드에 보여줄 로비 항목 */
    fun lobbiesFor(mode: GameMode): List<String> =
        if (mode == GameMode.NORMAL) listOf(LOBBY_NORMAL, LOBBY_COMMON) else listOf(LOBBY_INVASION, LOBBY_COMMON)

    /** PC MATCH_THRESHOLD — 로비 화면 인식 기준 */
    const val LOBBY_THRESHOLD = 0.60

    /**
     * 이 기기에서 찍은 로비 사진의 기준값 — 크기가 정확히 맞아 진짜 로비에서는 90% 가까이 나온다.
     * 그래서 높게 잡아 게임 화면을 로비로 착각하지 않게 한다.
     */
    const val LOBBY_THRESHOLD_PHONE = 0.72

    /** PC 로비 사진은 윤곽선까지 이만큼 맞아야 로비로 본다 (폰 화면에서 엉뚱한 곳에 걸리지 않게) */
    const val LOBBY_MIN_EDGE = 0.35

    /** 로비가 이만큼 연달아(약 0.6초 간격) 보여야 진짜 로비로 본다 */
    const val LOBBY_CONFIRM_HITS = 2

    /** 화면 알림·기록에 쓰는 로비 이름 (공통로비 = 판이 끝난 '종료!' 화면) */
    fun lobbyLabel(lobby: String): String = if (lobby == LOBBY_COMMON) "종료 화면" else lobby

    // ── 배속 인식 (배속 고정을 끈 경우만) ───────────────────────

    /** PC SPEED_MATCH_THRESHOLD / SPEED_MATCH_MARGIN */
    const val SPEED_THRESHOLD = 0.60
    const val SPEED_MARGIN = 0.03

    // ── 보스 위험 경보 (PC app_config.json 값) ─────────────────

    /** 일반 보스 경보 목표 시간(초) */
    const val WARN_BASE_NORMAL = 117
    const val WARN_BASE_INVASION = 113

    /** PC special_bosses — 개별 시간을 따로 쓰는 보스 */
    val WARN_SPECIAL_BOSSES: List<String> =
        listOf("사신", "매지션", "반파이어", "히든보스", "악마 모노폴리", "쌍둥이 슬라임", "거대 전기로봇")

    /** 태초 모드에서 개별 시간을 쓰는 보스 */
    val WARN_SPECIAL_NORMAL_LIST: List<String> = listOf("사신", "매지션", "반파이어", "히든보스")

    /** 침공 모드에서 개별 시간을 쓰는 보스 */
    val WARN_SPECIAL_INVASION_LIST: List<String> =
        listOf("사신", "매지션", "반파이어", "히든보스", "악마 모노폴리", "쌍둥이 슬라임", "거대 전기로봇")

    val WARN_SPECIAL_NORMAL: Map<String, Int> = mapOf(
        "사신" to 115, "매지션" to 115, "반파이어" to 115, "히든보스" to 3
    )

    val WARN_SPECIAL_INVASION: Map<String, Int> = mapOf(
        "사신" to 112, "매지션" to 112, "반파이어" to 114, "히든보스" to 4,
        "악마 모노폴리" to 60, "쌍둥이 슬라임" to 60, "거대 전기로봇" to 60
    )

    /** PC invasion_early_2x_ratio — 침공은 경보 시간의 앞 30% 만 2배속으로 계산 */
    const val INVASION_EARLY_2X_PCT = 30

    /**
     * PC warning_texts — 경보음(alarms/{보스}.mp3)이 없을 때 읽어주는 말.
     * voices/{말}.mp3 가 있으면 그 목소리로 나온다. (예: 미티어대비.mp3)
     */
    val WARN_TEXT: Map<String, String> = mapOf(
        "콜디스트" to "준비", "닥터" to "준비", "샤먼" to "변신주의",
        "매지션" to "미티어대비", "사신" to "삭제대비", "반파이어" to "반파주의"
    )
}
