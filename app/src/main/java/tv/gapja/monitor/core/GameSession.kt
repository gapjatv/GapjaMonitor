package tv.gapja.monitor.core

/**
 * 한 판의 진행 상태 — 어떤 보스가 어떤 순서로 인식되었는지.
 *
 * PC판의 boss_states / boss_activation_order / auto_recognized_order 에 대응한다.
 * 화면·소리와 무관한 순수 상태라서 그대로 테스트할 수 있다.
 */
class GameSession(var mode: GameMode = GameMode.INVASION) {

    /** 인식된 순서 그대로 (특수보스 포함) */
    val activationOrder: MutableList<String> = ArrayList()

    /** 자동 인식으로 들어온 보스 (수동 클릭과 구분 — 통계는 자동만 집계) */
    val autoRecognized: MutableSet<String> = LinkedHashSet()

    /** 80R 확정이 끝났는지 (판당 1회만 안내) */
    var deduced80R: Boolean = false
        private set

    /** 확정된 80R 보스 */
    var boss80R: String? = null
        private set

    /** 확정 순간에 붙잡아 둔 조건 키 (null = 조건 없음) */
    var cond80R: String? = null
        private set

    /** 과거와 같은 순서라고 이미 알렸는지 (판당 1회) */
    var seqMatchAnnounced: Boolean = false

    /** 보스별 '되돌리기 쿨다운' 만료 시각(ms) — 방금 되돌린 보스를 다시 잡지 않게 */
    private val undoCooldown = HashMap<String, Long>()

    /** 보스별 타일투표 재시도 가능 시각(ms) — 비용 상한 */
    private val tileCooldown = HashMap<String, Long>()

    fun isActive(name: String): Boolean = name in activationOrder

    /** 지금 이 보스를 감시 대상으로 볼지 (이미 나왔거나 쿨다운이면 건너뜀) */
    fun shouldWatch(name: String, nowMs: Long): Boolean {
        if (isActive(name)) return false
        val until = undoCooldown[name] ?: return true
        return nowMs >= until
    }

    fun canTileVote(name: String, nowMs: Long): Boolean =
        nowMs >= (tileCooldown[name] ?: 0L)

    fun markTileVote(name: String, nowMs: Long) {
        tileCooldown[name] = nowMs + (BossCatalog.TILE_RESCUE_COOLDOWN_SEC * 1000).toLong()
    }

    /**
     * 보스를 활성화한다.
     * @return 새로 활성화되었으면 true (이미 있었으면 false)
     */
    fun activate(name: String, auto: Boolean): Boolean {
        if (isActive(name)) return false
        activationOrder.add(name)
        if (auto) autoRecognized.add(name)
        return true
    }

    /**
     * 마지막으로 인식된 보스를 되돌린다.
     * @return 되돌린 보스 이름 (없으면 null)
     */
    fun undoLast(nowMs: Long, cooldownSec: Double = 5.0): String? {
        if (activationOrder.isEmpty()) return null
        val name = activationOrder.removeAt(activationOrder.size - 1)
        autoRecognized.remove(name)
        undoCooldown[name] = nowMs + (cooldownSec * 1000).toLong()
        // 특수보스를 되돌렸으면 80R 확정도 함께 취소한다
        if (BossCatalog.isSpecial(name)) {
            deduced80R = false
            boss80R = null
            cond80R = null
        }
        return name
    }

    /** 지금까지 인식된 침공특수보스 (순서 유지) */
    fun seenSpecial(): List<String> = activationOrder.filter { BossCatalog.isSpecial(it) }

    /** 메인보스 순서 (특수보스 제외) — 10R, 20R, … */
    fun mainOrder(): List<String> = activationOrder.filter { !BossCatalog.isSpecial(it) }

    /** 일반 침공 보스 마릿수 — 특수보스는 세지 않는다 */
    fun mainCount(): Int = mainOrder().size

    /**
     * 침공특수보스 2종이 인식되었으면 남은 1종을 80R로 확정한다.
     *
     * @return 이번 호출에서 새로 확정되었으면 그 보스 이름, 아니면 null.
     *         확정 순간의 조건 키는 [cond80R] 에 보관된다. (3초 뒤 안내에 그대로 쓴다)
     */
    fun tryDeduce80R(): String? {
        if (deduced80R) return null
        if (mode != GameMode.INVASION) return null
        val seen = seenSpecial()
        if (seen.size < 2) return null
        val target = Predictor.deduce80R(seen.toSet()) ?: return null
        deduced80R = true
        boss80R = target
        cond80R = Predictor.condKey(target, activationOrder.toSet())
        return target
    }

    /** 판이 끝났을 때 — 기록으로 남길 한 판 */
    fun toRecord(timestamp: String): GameRecord {
        val main = mainOrder()
        return GameRecord(timestamp, main.size, main, seenSpecial())
    }

    /** 이 판을 기록해야 하는지 (침공 + 자동 인식이 있었던 판만) */
    fun shouldRecord(): Boolean =
        mode == GameMode.INVASION && autoRecognized.isNotEmpty() &&
                (activationOrder.isNotEmpty())

    /** 다음 판을 위해 초기화 */
    fun reset() {
        activationOrder.clear()
        autoRecognized.clear()
        undoCooldown.clear()
        tileCooldown.clear()
        deduced80R = false
        boss80R = null
        cond80R = null
        seqMatchAnnounced = false
    }
}
