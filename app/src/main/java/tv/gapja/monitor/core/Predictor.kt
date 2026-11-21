package tv.gapja.monitor.core

/**
 * 80라운드 침공특수보스 확정 + 조건별 안내 멘트
 *
 * ⚠️ 여기는 '예측'이 아니라 '확정(소거법)'이다.
 *    침공특수보스 3종은 30·50·80라운드에 중복 없이 1종씩 배정되므로,
 *    2종이 인식되면 남은 1종은 논리적으로 80R이 된다. 통계가 0판이어도 정확도 100%.
 */
object Predictor {

    /** 80R 확정 멘트 (조건 보스가 인식되지 않았을 때) */
    val TTS_80R: Map<String, String> = mapOf(
        "거대 전기로봇" to "80라운드 거대 전기로봇 입니다. 물리딜러 준비",
        "악마 모노폴리" to "80라운드 악마 모노폴리 입니다. 마법딜러 준비",
        "쌍둥이 슬라임" to "80라운드 쌍둥이 슬라임 입니다. 마법,또는 물리 결정하세요"
    )

    /**
     * 조건 판정 대상 — 80R 보스별로 '어떤 일반 침공보스의 인식 여부를 보는지'.
     * 적힌 순서가 조건 키를 만들 때의 연결 순서가 된다.
     */
    val COND_WATCH: Map<String, List<String>> = mapOf(
        "거대 전기로봇" to listOf("그런트"),
        "악마 모노폴리" to listOf("양술사"),
        "쌍둥이 슬라임" to listOf("양술사", "그런트")
    )

    /**
     * 조건별 멘트. 키 형식은 "{80R보스}|{인식된 보스들을 + 로 연결}".
     * 이미 잡은 보스는 80R 후보에서 '빠짐'을 알려주기 위한 것이다.
     */
    val TTS_80R_COND: Map<String, String> = mapOf(
        "거대 전기로봇|그런트" to "80라운드 전기로봇 입니다. 물리준비 그런트 빠짐",
        "악마 모노폴리|양술사" to "80라운드 악마 모노폴리 입니다. 마법딜러 준비, 양술사 빠짐",
        "쌍둥이 슬라임|양술사" to "80라운드 쌍둥이 슬라임. 양술사 빠짐",
        "쌍둥이 슬라임|그런트" to "80라운드 쌍둥이 슬라임. 그런트 빠짐",
        "쌍둥이 슬라임|양술사+그런트" to "80라운드 쌍둥이 슬라임 양술, 그런트 둘다 빠짐"
    )

    /** 설정 화면에 표시할 조건 설명 */
    val COND_LABEL: Map<String, String> = mapOf(
        "거대 전기로봇|그런트" to "그런트 인식됨",
        "악마 모노폴리|양술사" to "양술사 인식됨",
        "쌍둥이 슬라임|양술사" to "양술사만 인식됨",
        "쌍둥이 슬라임|그런트" to "그런트만 인식됨",
        "쌍둥이 슬라임|양술사+그런트" to "양술사 + 그런트 둘 다"
    )

    /** 침공특수보스 3종이 나올 수 있는 순서 6가지 */
    fun allSpecialPerms(): List<String> {
        val a = BossCatalog.INVASION_SPECIAL
        val out = ArrayList<String>(6)
        for (i in a.indices) for (j in a.indices) for (k in a.indices) {
            if (i != j && j != k && i != k) out.add("${a[i]}>${a[j]}>${a[k]}")
        }
        return out
    }

    /**
     * 아직 인식되지 않은 침공특수보스가 1종만 남았으면 그것을 돌려준다.
     * 2종 이상 남았으면 null — 아직 확정할 수 없다.
     */
    fun deduce80R(seen: Set<String>): String? {
        val remaining = BossCatalog.INVASION_SPECIAL.filter { it !in seen }
        return if (remaining.size == 1) remaining[0] else null
    }

    /**
     * 확정 시점의 '조건 키'를 만든다.
     *
     *   거대 전기로봇 + 그런트 인식          → "거대 전기로봇|그런트"
     *   쌍둥이 슬라임 + 양술사·그런트 둘 다   → "쌍둥이 슬라임|양술사+그런트"
     *   조건 보스가 하나도 인식되지 않음      → null (기존 멘트 사용)
     */
    fun condKey(boss: String, recognized: Set<String>): String? {
        val watch = COND_WATCH[boss] ?: return null
        val hit = watch.filter { it in recognized }
        if (hit.isEmpty()) return null
        return "$boss|${hit.joinToString("+")}"
    }

    /**
     * 80R 확정 안내로 읽어줄 문구를 결정한다.
     *
     * [우선순위]
     *   ① 사용자가 바꾼 조건별 멘트
     *   ② 기본 조건별 멘트
     *   ③ 사용자가 바꾼 기존 멘트
     *   ④ 기본 확정 멘트
     *
     * @param condKey 확정된 그 순간에 붙잡아 둔 조건 키.
     *                [CondKey.AUTO] 를 넘기면 recognized 로 즉석 판정한다.
     *                null 을 넘기면 '조건 없음(기존 멘트)'으로 확정 처리한다.
     *                → 확정 순간의 상태를 3초 뒤까지 그대로 쓰기 위한 구분이다.
     * @return (문구, 실제로 쓰인 조건 키 또는 null)
     */
    fun message(
        boss: String,
        recognized: Set<String>,
        condKey: String? = CondKey.AUTO,
        userCond: Map<String, String> = emptyMap(),
        userBase: Map<String, String> = emptyMap()
    ): Pair<String?, String?> {
        val key = if (condKey == CondKey.AUTO) condKey(boss, recognized) else condKey
        if (key != null) {
            val m = userCond[key]?.takeIf { it.isNotBlank() }
                ?: TTS_80R_COND[key]?.takeIf { it.isNotBlank() }
            if (m != null) return m.trim() to key
        }
        val base = userBase[boss]?.takeIf { it.isNotBlank() }
            ?: TTS_80R[boss]?.takeIf { it.isNotBlank() }
        return base?.trim() to null
    }

    /**
     * 이번 판에서 아직 나오지 않은 침공 메인보스 후보.
     * 태초 전용 보스와 이미 나온 보스는 제외한다.
     */
    fun remainingMainCandidates(activated: Collection<String>): List<String> =
        BossCatalog.INVASION_MAIN_POOL.filter { it !in activated }

    /**
     * 일반 침공 보스 마릿수 — 침공특수보스는 세지 않는다.
     * PC판의 `len([b for b in order if b not in INVASION_HIDDEN_BOSSES])` 와 같다.
     */
    fun mainCount(activated: Collection<String>, mode: GameMode = GameMode.INVASION): Int =
        activated.count { !BossCatalog.isUncounted(it, mode) }
}

/** [Predictor.message] 의 condKey 기본값 표식 */
object CondKey {
    /**
     * '아직 판정하지 않았음'을 뜻한다.
     *
     * null 은 '조건 보스가 하나도 인식되지 않았음(= 기존 멘트)'이라는 확정된 결과이므로
     * 반드시 구분해야 한다. 섞으면, 확정 순간엔 그런트가 없었는데 3초 사이에
     * 인식되어 멘트가 바뀌는 문제가 생긴다.
     */
    const val AUTO = "__auto__"
}
