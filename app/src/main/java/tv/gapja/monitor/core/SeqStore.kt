package tv.gapja.monitor.core

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** 한 판의 보스 등장 순서 기록 */
data class GameRecord(
    /** 기록 시각 "yyyy-MM-dd HH:mm" */
    val t: String,
    /** 메인보스 몇 마리까지 갔는지 (= 라운드/10) */
    val depth: Int,
    /** 메인보스 순서 — 10R, 20R, …, 80R */
    val main: List<String>,
    /** 침공특수보스 순서 — 30R, 50R, 80R */
    val special: List<String>
)

/** 침공특수보스 순서 집계 */
data class SpecialOrderStats(
    /** 집계에 쓴 판 수 (특수보스가 1마리라도 나온 판) */
    val n: Int,
    /** 3종 모두 나온 판 수 */
    val nFull: Int,
    /** 보스별 [1번째(30R), 2번째(50R), 3번째(80R)] 횟수 */
    val pos: Map<String, IntArray>,
    /** 전체 순서 조합별 횟수 ("A>B>C" → 횟수), 3종 다 나온 판만 */
    val perm: Map<String, Int>
)

/** 접두 일치로 찾아낸 과거 판들 */
data class PrefixGroup(
    /** 앞에서 몇 마리가 일치하는지 */
    val k: Int,
    /** 일치하는 앞부분 */
    val head: List<String>,
    /** 그 순서로 시작했던 과거 판들 */
    val rows: List<GameRecord>
)

/**
 * 판별 시퀀스 기록 — 침공 모드 전용
 *
 * [왜 필요한가]
 *   누적 통계(boss_mode_stats.json)의 slot_hist 는 {보스: {슬롯: 횟수}} 형태라
 *   '보스별 합계'만 남고 **한 판에서 누가 누구 뒤에 나왔는지가 사라진다.**
 *     1판: 그런트→윔→닥터 …
 *     2판: 닥터→샤먼→그런트 …
 *     → 합산되면 "닥터 1번 1회, 3번 1회" 로만 남는다.
 *   그래서 "1~8번 순서가 통째로 반복되는가?"를 검증할 수 없었다.
 *   이 저장소는 판마다 순서를 있는 그대로 남겨 그 검증을 가능하게 한다.
 *
 * [검증이 강력한 이유]
 *   풀 11종에서 8칸을 순서대로 뽑는 경우의 수 = 665만 가지.
 *   매번 새로 섞는다면 겹침은 사실상 생기지 않는다.
 *   → 한 번이라도 겹치면 '정해진 패턴표가 있다'는 결정적 증거,
 *     수십 판을 쌓아도 겹침이 0이면 '완전 셔플'이 거의 확정.
 *   어느 쪽이든 답이 나오므로 기록 자체가 손해 볼 일이 없다.
 *
 * 파일 형식은 PC판(boss_sequences.json)과 완전히 같으므로 서로 복사해 쓸 수 있다.
 */
class SeqStore(val games: MutableList<GameRecord> = ArrayList()) {

    companion object {
        /** 기대 우연 일치가 이 횟수 미만이어야 '우연 아님'으로 인정 */
        const val MAX_CHANCE = 0.02

        /** 아무리 표본이 적어도 이보다 짧은 일치는 보지 않는다 */
        const val MIN_LEN_FLOOR = 4

        /** 일치한 과거 판들의 80R 보스가 몇 % 이상 같아야 알릴지 */
        const val MATCH_MIN_CONF = 60.0

        /** 기록이 적어 보스 풀 크기를 모를 때 쓸 기본값 */
        const val POOL_FALLBACK = 11

        /** 패턴표 존재를 선언할 유의수준 (푸아송 꼬리확률) */
        const val PATTERN_P = 0.01

        /** 메인보스는 최대 8칸(80R)까지 본다 */
        const val MAX_PREFIX = 8
    }

    // ── 기록 ──────────────────────────────────────────────────────

    /**
     * 한 판의 순서를 추가한다.
     * @return 추가되었으면 true (빈 판은 저장하지 않는다)
     */
    fun add(rec: GameRecord): Boolean {
        if (rec.main.isEmpty() && rec.special.isEmpty()) return false
        games.add(rec)
        return true
    }

    // ── 침공특수보스 순서 비율 ─────────────────────────────────────

    /**
     * 침공특수보스 3종이 '몇 번째로 나왔는지' 비율을 집계한다.
     * 1번째 = 30R, 2번째 = 50R, 3번째 = 80R.
     *
     * 30R에서 끝난 판도 "1번째였다"는 집계되지만,
     * 순서 조합(perm)은 3종이 모두 나온 판만 센다.
     */
    fun specialOrderStats(): SpecialOrderStats {
        val pos = HashMap<String, IntArray>()
        for (b in BossCatalog.INVASION_SPECIAL) pos[b] = IntArray(3)
        val perm = HashMap<String, Int>()
        var n = 0
        var nFull = 0
        for (g in games) {
            val sp = g.special
            if (sp.isEmpty()) continue
            n++
            for ((i, b) in sp.withIndex()) {
                if (i >= 3) break
                pos[b]?.let { it[i]++ }
            }
            if (sp.size >= 3) {
                val key = sp.take(3).joinToString(">")
                perm[key] = (perm[key] ?: 0) + 1
                nFull++
            }
        }
        return SpecialOrderStats(n, nFull, pos, perm)
    }

    // ── 순서 패턴 검정 ────────────────────────────────────────────

    /** 기록에 실제로 등장한 메인보스 종류 수 (경우의 수 계산의 밑값) */
    fun poolSize(): Int {
        val s = HashSet<String>()
        for (g in games) s.addAll(g.main)
        return if (s.size >= 6) s.size else POOL_FALLBACK
    }

    /**
     * '완전 무작위라면 앞 k칸이 겹치는 일이 몇 번쯤 생길까'.
     *
     *     기대 횟수 = C(판수, 2) ÷ P(보스풀, k)
     *
     * 이 값이 작을수록(0.02 미만) 실제로 발견된 일치는 우연이 아니다.
     *
     * ⚠️ 기록이 늘수록 이 값이 급격히 커진다. 그래서 '몇 칸 일치면 의미 있는가'를
     *    고정값으로 둘 수 없다. (무작위 40판 시뮬레이션에서 4칸 일치가 실제로 나왔다)
     *
     *      판수 │  4칸    5칸    6칸    7칸
     *      ─────┼────────────────────────────
     *        50 │  0.15   0.02   0.00   0.00
     *       300 │  5.66   0.81   0.13   0.03
     *      1000 │ 63.07   9.01   1.50   0.30
     */
    fun expectedCollisions(k: Int, nGames: Int = games.size, pool: Int = poolSize()): Double {
        if (nGames < 2 || k < 1 || k > pool) return 0.0
        var p = 1.0
        for (i in 0 until k) p *= (pool - i).toDouble()
        return (nGames * (nGames - 1) / 2.0) / p
    }

    /**
     * 지금 기록량에서 '몇 칸 일치부터 의미 있는가'를 자동으로 정한다.
     * 기록이 늘수록 기준 길이도 함께 올라간다. (50판 → 6칸, 300판 → 8칸)
     *
     * 8칸이 최대이므로, 기록이 아주 많아지면 8칸에서 멈춘다.
     */
    fun minMatchLen(nGames: Int = games.size): Int {
        val pool = poolSize()
        for (k in MIN_LEN_FLOOR..MAX_PREFIX) {
            if (expectedCollisions(k, nGames, pool) < MAX_CHANCE) return k
        }
        return MAX_PREFIX
    }

    /**
     * '우연히 이만큼 겹칠 확률'을 푸아송 꼬리확률로 구한다. P(X >= observed)
     *
     * 발견 1건 / 기대 0.098건 → p ≈ 0.093  (흔히 일어남, 패턴 아님)
     * 발견 5건 / 기대 0.030건 → p ≈ 1e-9   (사실상 불가능, 패턴 맞음)
     *
     * → 건수만 보지 않고 '기대보다 얼마나 많은가'로 판단하게 해준다.
     */
    fun patternPValue(observed: Int, expected: Double): Double {
        if (observed <= 0) return 1.0
        if (expected <= 0.0) return 0.0
        var acc = 0.0
        var term = exp(-expected)
        for (i in 0 until observed) {
            if (i > 0) term *= expected / i
            acc += term
        }
        return max(0.0, min(1.0, 1.0 - acc))
    }

    /**
     * 지금 판의 순서가 과거 어느 판과 같은지 찾는다. 가장 긴 접두 일치를 고른다.
     *
     * @return (일치길이, 과거 판들, 그 판들의 80R 보스 분포)
     *         앞 7~8칸이 일치하는 판이 나오면 그 판의 80R 보스가 이번 판의 정답일
     *         가능성이 매우 높다.
     */
    fun findMatches(prefix: List<String>, minLen: Int = minMatchLen()):
            Triple<Int, List<GameRecord>, Map<String, Int>> {
        if (prefix.isEmpty() || games.isEmpty()) {
            return Triple(0, emptyList(), emptyMap())
        }
        var bestK = 0
        var bestRows: List<GameRecord> = emptyList()
        for (k in min(prefix.size, MAX_PREFIX) downTo minLen) {
            val head = prefix.take(k)
            val rows = games.filter { it.main.size >= k && it.main.take(k) == head }
            if (rows.isNotEmpty()) {
                bestK = k
                bestRows = rows
                break
            }
        }
        val dist = HashMap<String, Int>()
        for (g in bestRows) {
            if (g.main.size >= 8) {
                val b = g.main[7]
                dist[b] = (dist[b] ?: 0) + 1
            }
        }
        return Triple(bestK, bestRows, dist)
    }

    /**
     * '앞 k마리가 똑같은 판'을 찾는다. 긴 일치부터 돌려준다.
     *
     * ⚠️ 긴 일치로 이미 보고한 '같은 판 묶음'은 다시 보고하지 않는다.
     *    (3판이 8칸 일치하면 7·6·5·4칸에서도 당연히 일치하므로,
     *     그대로 두면 같은 3판이 다섯 번 세어진다)
     *    단, 짧은 길이에서 판이 더 늘어난 묶음은 새 정보이므로 보고한다.
     */
    fun prefixDuplicates(minLen: Int = minMatchLen()): List<PrefixGroup> {
        val found = ArrayList<PrefixGroup>()
        val reported = ArrayList<Set<Int>>()   // 이미 보고한 판 묶음 (games 인덱스)
        for (k in MAX_PREFIX downTo minLen) {
            val buckets = LinkedHashMap<List<String>, MutableList<Int>>()
            for ((idx, g) in games.withIndex()) {
                if (g.main.size < k) continue
                buckets.getOrPut(g.main.take(k)) { ArrayList() }.add(idx)
            }
            for ((head, idxs) in buckets) {
                if (idxs.size < 2) continue
                val set = idxs.toSet()
                // 이미 보고한 묶음이 이 묶음을 전부 품고 있으면 새 정보가 없다
                if (reported.any { it.containsAll(set) }) continue
                reported.add(set)
                found.add(PrefixGroup(k, head, idxs.map { games[it] }))
            }
        }
        return found
    }

    /**
     * 겹침이 '우연 기대보다 유의하게 많은가'로 패턴표 존재를 판정한다.
     *
     * ⚠️ 판정에 쓰는 겹침 건수는 **정확히 minLen 길이에서 한 번만** 센다.
     *    expectedCollisions(minLen) 이 그 길이의 기대값이므로 길이를 섞으면
     *    같은 판이 여러 번 세어져 건수가 부풀려진다.
     * ⚠️ 기대값은 '겹치는 쌍(pair)'의 기대 개수이므로, 관측값도 쌍으로 센다.
     *    한 묶음에 m판이 모였으면 m(m−1)/2 쌍이다.
     */
    fun patternVerdict(minLen: Int = minMatchLen()): PatternVerdict {
        val buckets = HashMap<List<String>, Int>()
        for (g in games) {
            if (g.main.size < minLen) continue
            val head = g.main.take(minLen)
            buckets[head] = (buckets[head] ?: 0) + 1
        }
        var observed = 0
        for (m in buckets.values) if (m >= 2) observed += m * (m - 1) / 2
        val expected = expectedCollisions(minLen)
        val p = patternPValue(observed, expected)
        // 화면 표시용 목록은 긴 일치부터 (판정과 무관)
        return PatternVerdict(p < PATTERN_P, observed, expected, p, prefixDuplicates(minLen))
    }

    // ── JSON (PC판 boss_sequences.json 과 동일 형식) ──────────────

    fun toJson(): String {
        val sb = StringBuilder()
        sb.append("{\"version\":1,\"games\":[")
        for ((i, g) in games.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append("{\"t\":").append(quote(g.t))
                .append(",\"depth\":").append(g.depth)
                .append(",\"main\":").append(arr(g.main))
                .append(",\"special\":").append(arr(g.special))
                .append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun arr(xs: List<String>): String =
        xs.joinToString(",", "[", "]") { quote(it) }

    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        sb.append('"')
        return sb.toString()
    }
}
