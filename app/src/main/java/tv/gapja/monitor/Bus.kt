package tv.gapja.monitor

import android.os.Handler
import android.os.Looper
import tv.gapja.monitor.core.GameSession
import tv.gapja.monitor.core.SeqStore

/**
 * 서비스 ↔ 화면 사이의 아주 단순한 전달 통로.
 *
 * 로그 한 줄, 현재 상태, 예측 결과를 화면과 오버레이에 알린다.
 * 외부 라이브러리(LiveData/Flow)를 쓰지 않아 빌드가 가벼운 대신,
 * 반드시 메인 스레드로 넘겨서 호출해 준다.
 */
object Bus {

    private val main = Handler(Looper.getMainLooper())

    /** 화면에 뿌릴 로그 (최근 것이 뒤에 온다) */
    val logs = ArrayList<String>()

    /** 감시 중인지 */
    @Volatile var running: Boolean = false

    /** 지금 활성화된 보스 순서 (오버레이가 그린다) */
    @Volatile var activated: List<String> = emptyList()

    /** 확정된 80R 침공특수보스 */
    @Volatile var boss80R: String? = null

    /** 순서 일치로 예측한 80R 메인보스 */
    @Volatile var predicted: String? = null
    @Volatile var predictedConf: Double = 0.0

    /** 누적 기록 판 수 */
    @Volatile var recordedGames: Int = 0

    /** 일시정지 중인지 (보스설정 메뉴 글자를 바꾼다) */
    @Volatile var paused: Boolean = false

    /** 지금 로비 화면에 있는지 */
    @Volatile var lobby: Boolean = false

        /** 지금 모드 (오버레이 제목에 쓴다) */
    @Volatile var mode: tv.gapja.monitor.core.GameMode = tv.gapja.monitor.core.GameMode.INVASION

    var onLog: ((String) -> Unit)? = null

    /** 자르기 화면이 열리면 게임 위 '캡처 준비 중…' 안내를 닫는다 */
    var onCropOpened: (() -> Unit)? = null

    fun cropOpened() { main.post { try { onCropOpened?.invoke() } catch (_: Exception) {} } }

    /** [보스설정 → 끄기] 를 누르면 메인 화면을 닫는다 */
    var onExit: (() -> Unit)? = null

    fun exitApp() { main.post { try { onExit?.invoke() } catch (_: Exception) {} } }

    /**
     * 상태가 바뀌면 부를 화면들.
     *
     * 예전에는 한 칸(onChange)뿐이라 메인 화면과 오버레이가 서로를 덮어썼다.
     * (메인 화면에 다녀오면 게임 위 사진이 더 이상 갱신되지 않던 문제) → 여러 개를 받는다.
     */
    private val changeListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addChangeListener(l: () -> Unit) { if (!changeListeners.contains(l)) changeListeners.add(l) }

    fun removeChangeListener(l: () -> Unit) { changeListeners.remove(l) }

    private fun fireChange() { for (l in changeListeners) try { l() } catch (_: Exception) {} }

    /** 오버레이 설정(크기·배치·표시 여부)이 바뀌었을 때 다시 그리게 한다 */
    fun settingsChanged() { main.post { fireChange() } }

    private const val MAX_LOGS = 400

    fun log(msg: String) {
        val line = "[${timeNow()}] $msg"
        main.post {
            logs.add(line)
            while (logs.size > MAX_LOGS) logs.removeAt(0)
            onLog?.invoke(line)
            fireChange()
        }
    }

    fun onState(session: GameSession, seq: SeqStore) {
        val order = session.activationOrder.toList()
        val b80 = session.boss80R
        val n = seq.games.size
        val md = session.mode
        main.post {
            mode = md
            activated = order
            boss80R = b80
            recordedGames = n
            if (order.isEmpty()) { predicted = null; predictedConf = 0.0 }
            fireChange()
        }
    }

    fun onPredict(boss: String, conf: Double) {
        main.post {
            predicted = boss
            predictedConf = conf
            fireChange()
        }
    }

    fun clearLogs() {
        main.post { logs.clear(); fireChange() }
    }

    private fun timeNow(): String {
        val c = java.util.Calendar.getInstance()
        return "%02d:%02d:%02d".format(
            c.get(java.util.Calendar.HOUR_OF_DAY),
            c.get(java.util.Calendar.MINUTE),
            c.get(java.util.Calendar.SECOND)
        )
    }
}
