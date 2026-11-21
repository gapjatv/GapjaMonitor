package tv.gapja.monitor.core

/**
 * 갑자TV 관제탑 — 보스 목록과 인식 기준값
 *
 * PC판(gapjatv_control_tower57.py)의 상수들을 그대로 옮긴 것이다.
 * 값을 바꿀 때는 PC판과 함께 맞춰야 통계가 호환된다.
 */
object BossCatalog {

    /** 일반(메인) 보스 — 침공 기준 10R, 20R, … 순서로 등장한다 */
    val BOSS_NAMES: List<String> = listOf(
        "그런트", "윔", "콜디스트", "보호막로봇", "고기슬라임",
        "닥터", "샤먼", "매지션", "사신", "쿨타임", "양술사",
        "반파이어", "나하투", "블랑카", "히든보스"
    )

    /** 태초 모드에만 나오는 보스 — 침공에서는 후보에서 제외한다 */
    val NORMAL_ONLY_BOSSES: Set<String> = setOf("반파이어", "나하투", "블랑카", "히든보스")

    /**
     * 침공특수보스 — 침공 모드의 30·50·80라운드에 '추가로' 등장하는 3종.
     *
     * 일반 침공 보스와는 별개이며 보스 마릿수 집계에 포함하지 않는다.
     * 중복 없이 1종씩 배정되므로, 2종이 인식되면 남은 1종이 80R로 확정된다.
     */
    val INVASION_SPECIAL: List<String> = listOf("거대 전기로봇", "악마 모노폴리", "쌍둥이 슬라임")

    /**
     * 히든보스 — 태초에서는 태초 전용 보스(태초 마릿수에 포함, 기존 그대로).
     *
     * 침공에서는 80라운드 침공특수보스를 일찍 잡으면 내 필드로 돌아가 잡을 수 있는 '침공 히든보스'.
     * 침공에서는 침공특수보스처럼 보스 마릿수·순서 기록·80R 예측에 넣지 않는다. (v2.3)
     * 인식용 사진은 침공 전용 `침공_히든보스.png` 만 쓴다 — 태초 히든보스 사진으로 대신하지 않는다.
     */
    const val HIDDEN = "히든보스"

    /** 침공 모드에서 80R 메인보스 후보가 되는 보스들 */
    val INVASION_MAIN_POOL: List<String> =
        BOSS_NAMES.filter { it !in NORMAL_ONLY_BOSSES }

    // ── 인식 기준값 ────────────────────────────────────────────────

    /** 기본 매칭 기준 (%) */
    const val THRESHOLD_DEFAULT = 72

    /** 보스별·모드별 기준값 보정. 침공에서 양술사는 색상이 비슷한 곳에 잘 걸려서 낮춘다 */
    val THRESHOLD_INVASION: Map<String, Int> = mapOf("양술사" to 52)

    /** 멀티스케일 검사 순서 — 앞쪽이 가장 흔한 크기라 조기 종료가 잘 걸린다 */
    val SCALES: DoubleArray =
        doubleArrayOf(1.0, 0.85, 1.15, 0.7, 1.3, 0.6, 1.5, 0.5)

    /** 근접 구간에서만 추가로 돌리는 구제 스케일 */
    val RESCUE_SCALES: DoubleArray = doubleArrayOf(0.95, 1.05, 0.78, 1.22)

    /** 기준보다 이만큼 아래까지를 '아깝게 놓친 근접 구간'으로 본다 */
    const val RESCUE_BAND = 0.12

    /** 기준 + 이 값을 넘으면 남은 스케일을 건너뛴다 (조기 종료) */
    const val EARLY_EXIT_MARGIN = 0.05

    /** 첫 스케일에서 기준보다 이만큼이나 낮으면 바로 포기한다 (조기 중단) */
    const val EARLY_ABORT_GAP = 0.35

    /**
     * 깐깐 검사 대상 — PSR·윤곽선·연속프레임 3관문을 모두 통과해야 인식한다.
     *
     * ⚠️ PC판 통계 분석 결과, 거대 전기로봇이 다른 침공특수보스보다
     *    약 39% 적게 인식되고 있었다. (카이제곱 47.5, p≈5e-11)
     *    3관문이 가려짐에 취약한 탓으로 보여, 폰판에서는 기본적으로 비워 둔다.
     *    오인식이 생기면 여기에 보스 이름을 넣으면 된다.
     */
    val STRICT_BOSSES: Set<String> = emptySet()

    /** 피크 선명도(PSR) 최소치 — 깐깐 검사 대상에만 적용 */
    const val MIN_PSR = 4.0

    /** 윤곽선(모양) 검증 최소치 */
    const val MIN_EDGE_SCORE = 0.40

    /** 깐깐 검사 대상이 연속으로 몇 프레임 보여야 인정할지 */
    const val CONFIRM_FRAMES = 2

    // ── 네임텍 가려짐 구제 (타일 분할 투표) ────────────────────────
    //
    //   팀원 네임텍이 보스 그림과 겹치면 매칭 점수만 떨어진다.
    //   (실측: 가림 35%에서 점수 19.5% 하락, PSR·윤곽선은 여유 충분)
    //   그래서 템플릿을 조각내어 가려진 조각을 버리고 판정한다.
    //
    //   격자 실측 (가려진 보스 120건 / 가짜 60건):
    //     3×3 동의6 → 심한가림 77.1% 구제, 오인식 1건
    //     4×4 동의8 → 심한가림 100%  구제, 오인식 0건  ← 채택

    const val TILE_ROWS = 4
    const val TILE_COLS = 4
    const val TILE_KEEP = 10          // 16조각 중 성적 좋은 몇 개만 평균낼지
    const val TILE_TOL = 6            // 원점 추정이 이 픽셀 안이면 '같은 자리'
    const val TILE_MIN_AGREE = 8      // 같은 원점에 동의해야 하는 최소 조각 수 (0 = 끔)
    const val TILE_MIN_SCORE = 0.85   // 동의한 조각들의 평균 점수 최소치

    /** 타일구제로 찾아낸 보스는 깐깐 검사 대상이 아니어도 윤곽선 검증을 통과해야 한다 */
    const val TILE_RESCUE_MIN_EDGE = 0.40

    /** 같은 보스에 타일투표를 다시 돌리기까지의 최소 간격(초) — 비용 상한 */
    const val TILE_RESCUE_COOLDOWN_SEC = 3.0

    /** 보조 스샷 파일 접미사 — {보스}_2.png 처럼 찍어두면 구제 단계에서 함께 시도한다 */
    val EXTRA_SHOT_SUFFIXES: List<String> = listOf("_2", "_3", "_4")

    // ── 조회 함수 ──────────────────────────────────────────────────

    /** 해당 보스가 침공특수보스인지 */
    fun isSpecial(name: String): Boolean = name in INVASION_SPECIAL

    /** 침공 모드의 히든보스인지 (v2.3) */
    fun isInvasionHidden(name: String, mode: GameMode): Boolean =
        mode == GameMode.INVASION && name == HIDDEN

    /** 보스 마릿수·순서 기록에서 빼는 보스인지 — 침공특수보스 + 침공 히든보스 */
    fun isUncounted(name: String, mode: GameMode): Boolean =
        isSpecial(name) || isInvasionHidden(name, mode)

    /** 침공에만 나오는 보스인지 — 태초 화면 목록에서는 뺀다 (침공특수보스 3종) */
    fun isInvasionOnly(name: String): Boolean = isSpecial(name)

    /** 보스·모드별 매칭 기준값을 0~1 범위로 돌려준다 */
    fun threshold(name: String, mode: GameMode, overrides: Map<String, Int> = emptyMap()): Double {
        overrides[name]?.let { return it / 100.0 }
        if (mode == GameMode.INVASION) {
            THRESHOLD_INVASION[name]?.let { return it / 100.0 }
        }
        return THRESHOLD_DEFAULT / 100.0
    }

    /** 깐깐 검사 대상인지 */
    fun isStrict(name: String, extra: Set<String> = emptySet()): Boolean =
        name in STRICT_BOSSES || name in extra

    /** 그 모드에서 감시해야 하는 보스 전체 */
    fun watchList(mode: GameMode): List<String> = when (mode) {
        GameMode.INVASION -> INVASION_MAIN_POOL + INVASION_SPECIAL + HIDDEN
        GameMode.NORMAL -> BOSS_NAMES
    }
}

/** 게임 모드 */
enum class GameMode(val key: String, val label: String) {
    NORMAL("normal", "태초"),
    INVASION("invasion", "침공");

    companion object {
        fun from(key: String?): GameMode =
            if (key == "invasion") INVASION else NORMAL
    }
}
