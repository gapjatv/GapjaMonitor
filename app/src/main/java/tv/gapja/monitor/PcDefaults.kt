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

    /** 모드별 고정 배속 표시 (폰판은 배속 토글 없이 고정) */
    fun speedLabel(mode: GameMode): String =
        if (mode == GameMode.NORMAL) "2배 고정" else "1배 고정"
}
