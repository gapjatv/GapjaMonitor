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

    /** 남은 보스 예측 화면에 띄울 보스들 (비어 있으면 창을 숨긴다) */
    @Volatile var remaining: List<String> = emptyList()
    private var remainingHide: Runnable? = null

    /**
     * 남은 보스 예측 화면을 띄운다.
     * @param durationMs 이 시간 뒤 자동으로 닫는다. 0 이하면 80R 보스가 인식되거나 판이 끝날 때까지 계속 띄운다.
     */
    fun showRemaining(list: List<String>, durationMs: Long) {
        main.post {
            remainingHide?.let { main.removeCallbacks(it) }
            remainingHide = null
            remaining = list
            fireChange()
            if (durationMs > 0) {
                val r = Runnable { remainingHide = null; remaining = emptyList(); fireChange() }
                remainingHide = r
                main.postDelayed(r, durationMs)
            }
        }
    }

    /** 일시정지 중인지 (보스설정 메뉴 글자를 바꾼다) */
    @Volatile var paused: Boolean = false

    /** 지금 로비 화면에 있는지 */
    @Volatile var lobby: Boolean = false

        /** 지금 모드 (오버레이 제목에 쓴다) */
    @Volatile var mode: tv.gapja.monitor.core.GameMode = tv.gapja.monitor.core.GameMode.INVASION

    var onLog: ((String) -> Unit)? = null

    /** 게임 창이 있는 화면 번호와 앱 이름 (뮤뮤 탭 대응) */
    @Volatile var gameDisplay: Int = 0
    @Volatile var gameLabel: String? = null

    /**
     * 자르기 화면이 열려 있는지.
     * 자르기 화면에 게임 사진(로비·보스)이 크게 보이므로, 그동안은 인식을 쉰다.
     * (안 쉬면 로비 사진을 자르는 중에 '로비 인식 → 보스 초기화' 가 일어났다)
     */
    @Volatile var cropVisible: Boolean = false

    /** 게임 위 [인식위치] 로 위치를 조절하는 동안 true — 인식을 잠시 쉰다 (v3.6) */
    @Volatile var zoneEditing: Boolean = false

    /** 자르기 화면을 닫은 시각 — 게임으로 돌아오는 동안 로비가 잠깐 보여도 보스를 지우지 않으려고 */
    @Volatile var cropClosedAt: Long = 0L

    /** 오버레이(보스 사진·버튼)가 지금 붙어 있는 화면 번호 */
    @Volatile var overlayDisplay: Int = -1

    /** 게임 화면이 바뀌면 오버레이를 그 화면으로 옮긴다 */
    var onGameDisplay: ((Int) -> Unit)? = null

    fun setGameDisplay(display: Int, label: String) {
        gameDisplay = display; gameLabel = label
        main.post {
            try { onGameDisplay?.invoke(display) } catch (_: Exception) {}
            fireChange()
        }
    }

    /** 자르기 화면이 열리면 게임 위 '캡처 준비 중…' 안내를 닫는다 */
    var onCropOpened: (() -> Unit)? = null

    fun cropOpened() { main.post { try { onCropOpened?.invoke() } catch (_: Exception) {} } }

    /** 게임 화면 가운데 조금 아래에 잠깐 띄울 알림 (로비·모드 전환) */
    var onNotice: ((String) -> Unit)? = null

    fun notice(text: String) { main.post { try { onNotice?.invoke(text) } catch (_: Exception) {} } }

    /** [보스설정 → 종료] 를 누르면 메인 화면을 닫는다 */
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
            // 판이 끝났거나, 남은 후보가 달라졌으면(80R 보스가 나옴 · 되돌리기) 예측 화면을 닫는다
            if (remaining.isNotEmpty() &&
                remaining.toSet() != tv.gapja.monitor.core.Predictor.remainingMainCandidates(order).toSet()) {
                remainingHide?.let { main.removeCallbacks(it) }
                remainingHide = null
                remaining = emptyList()
            }
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
