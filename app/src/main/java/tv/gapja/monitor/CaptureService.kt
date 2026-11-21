package tv.gapja.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import org.opencv.core.Mat
import tv.gapja.monitor.core.BossCatalog
import tv.gapja.monitor.core.CondKey
import tv.gapja.monitor.core.GameMode
import tv.gapja.monitor.core.GameSession
import tv.gapja.monitor.core.MatchOutcome
import tv.gapja.monitor.core.Matcher
import tv.gapja.monitor.core.Predictor
import tv.gapja.monitor.core.SeqStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * 화면을 계속 들여다보며 보스를 찾아내는 서비스.
 *
 * ⚠️ [안드로이드 제약 — 알고 쓰셔야 합니다]
 *   • 화면 캡처는 세션마다 사용자 동의가 필요하다. (앱을 다시 켜면 다시 떠요)
 *   • 상태바에 '화면 공유 중' 칩이 계속 표시된다. 숨길 수 없다.
 *   • **화면이 잠기면 캡처가 자동으로 끊긴다.** (Android 15 QPR1 이상)
 *     게임을 하는 중에는 화면이 켜져 있으니 실사용에는 문제가 없다.
 *   • 포그라운드 서비스 + mediaProjection 타입이 필수다. (매니페스트 참고)
 */
class CaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private lateinit var templates: TemplateStore
    private lateinit var seqRepo: SeqRepo
    private lateinit var speaker: Speaker
    private val matcher = Matcher(CvOps)

    @Volatile private var running = false
    private var session = GameSession(GameMode.INVASION)
    private lateinit var seq: SeqStore

    /** 깐깐 검사 대상의 연속 인식 횟수 */
    private val streak = HashMap<String, Int>()

    /** 화면을 이 비율로 줄여서 매칭한다 (1.0 = 원본). 작을수록 빠르다 */
    private var downscale = 0.5

    /** 한 바퀴 돌고 쉬는 시간(ms) */
    private var intervalMs = 150L

    /** [캡처] 버튼 — 다음 프레임을 사진으로 저장한 뒤 부를 함수 */
    @Volatile private var pendingSnap: ((Bitmap?) -> Unit)? = null

    // ── 로비 인식 (PC monitor 와 같다) ──
    /** 다음 로비 검사 시각 */
    private var lobbyNextCheck = 0L
    /** 로비로 초기화한 직후 잠깐 보스 검사를 쉰다 (PC: 2초) */
    private var lobbyQuietUntil = 0L
    /**
     * 지금 로비 화면에 있는지.
     * 로비 화면에도 보스 그림이 나오는 곳이 있어서, 로비에 있는 동안은 보스 검사를 아예 쉰다.
     * 로비가 [LOBBY_EXIT_MS] 동안 안 보여야 다시 보스를 찾는다.
     */
    private var inLobby = false
    private var lobbyLastSeen = 0L

    /** [보스설정 → 일시정지] — 화면 공유는 유지한 채 인식만 멈춘다 (다시 켤 때 동의 창이 안 뜬다) */
    @Volatile private var paused = false

    // ── 배속 (배속 고정을 끈 경우만 화면에서 인식) ──
    private var speedNextCheck = 0L
    /** 지금 게임 배속 — 고정이면 2 */
    private var currentSpeed = 2

    // ── 위험 경보 타이머 (PC schedule_warning) ──
    private class Warn(val boss: String, val mode: GameMode, val base: Double, val gen: Int) {
        var progress = 0.0
        var lastT = SystemClock.elapsedRealtime()
    }
    private val warns = ArrayList<Warn>()
    /** 판이 바뀔 때마다 올라간다 — 이전 판 경보가 새 판에 울리지 않게 */
    private var gameGen = 0
    private var warnTickerPosted = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        AssetInstaller.installIfNeeded(this)
        templates = TemplateStore(this)
        seqRepo = SeqRepo(this)
        seq = seqRepo.load()
        speaker = Speaker(this)
        // startForeground 는 onStartCommand 에서 방식(화면 공유 / 접근성)에 맞는 종류로 부른다
        // 메인 화면을 거치지 않고(게임 위 [시작]) 켜질 수도 있으니 여기서도 OpenCV 를 불러 둔다
        try { org.opencv.android.OpenCVLoader.initLocal() } catch (_: Throwable) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundFor(projection = true)
                val code = intent.getIntExtra(EXTRA_CODE, 0)
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                val mode = GameMode.from(intent.getStringExtra(EXTRA_MODE))
                downscale = intent.getDoubleExtra(EXTRA_DOWNSCALE, 0.5)
                intervalMs = intent.getLongExtra(EXTRA_INTERVAL, 150L)
                if (data != null) beginCapture(code, data, mode)
                else failStart("화면 공유 정보가 비어 있습니다.")
            }
            // 접근성 방식 — 확인창 없이 시작
            ACTION_START_A11Y -> {
                startForegroundFor(projection = false)
                val mode = GameMode.from(intent.getStringExtra(EXTRA_MODE))
                downscale = intent.getDoubleExtra(EXTRA_DOWNSCALE, 0.5)
                intervalMs = intent.getLongExtra(EXTRA_INTERVAL, 150L)
                handlerOn().post { beginA11y(mode) }
            }
            // 감시 루프는 capture 스레드에서 판 상태를 읽고 쓰므로,
            // 판 상태를 바꾸는 동작도 같은 스레드에서 차례로 처리한다 (동시 접근 충돌 방지)
            ACTION_STOP -> if (running) handlerOn().post { stopEverything() } else stopEverything()
            ACTION_UNDO -> handlerOn().post { undoLast() }
            ACTION_PAUSE -> handlerOn().post {
                paused = true; Bus.paused = true
                warns.clear()
                Bus.log("⏸ [일시정지] 화면 공유는 유지한 채 인식을 멈췄습니다. [재개] 로 바로 다시 시작합니다.")
                updateNotification("일시정지 · ${session.mode.label}")
                Bus.onState(session, seq)
            }
            ACTION_RESUME -> handlerOn().post {
                paused = false; Bus.paused = false
                lobbyNextCheck = 0L; inLobby = false
                Bus.log("▶️ [재개] 보스 인식을 다시 시작합니다.")
                updateNotification("감시 중 · ${session.mode.label} (${PcDefaults.speedLabel(session.mode, Prefs.speedLock(this))})")
                Bus.onState(session, seq)
            }
            ACTION_RESET -> handlerOn().post {
                if (session.activationOrder.isNotEmpty()) Bus.log("🔄 [리셋] 보스 사진을 초기화합니다.")
                endGame()
            }
            // [보스설정 → 끄기] 감시·오버레이를 멈추고 앱을 닫는다
            ACTION_EXIT -> {
                val doExit = {
                    if (running || projection != null) stopEverything()
                    else { OverlayService.stop(this); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                    Bus.exitApp()
                }
                if (running) handlerOn().post { doExit() } else doExit()
            }
            ACTION_MODE -> {
                val m = GameMode.from(intent.getStringExtra(EXTRA_MODE))
                handlerOn().post {
                    session.mode = m
                    streak.clear()
                    Bus.onState(session, seq)
                    Prefs.setMode(this, m.key)
                    updateNotification("감시 중 · ${m.label} (${PcDefaults.speedLabel(m, Prefs.speedLock(this))})")
                }
            }
        }
        return START_NOT_STICKY
    }

    // ── 캡처 시작 / 종료 ──────────────────────────────────────────

    private fun beginCapture(code: Int, data: Intent, mode: GameMode) {
        if (running) return
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = try {
            mgr.getMediaProjection(code, data)
        } catch (e: Exception) {
            Log.e(TAG, "동의 토큰이 유효하지 않습니다", e); null
        } ?: run { failStart("화면 캡처 권한을 받지 못했습니다."); return }

        projection = proj
        session = GameSession(mode)
        streak.clear()

        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Bus.log("⏹ 화면 공유가 중단되었습니다. (화면 잠김 또는 사용자 중지) " +
                        "게임 위 [보스설정 → 시작] 으로 바로 다시 켤 수 있습니다.")
                if (running) handlerOn().post { stopEverything(keepOverlay = true) }
                else stopEverything(keepOverlay = true)
            }
        }, handlerOn())

        // 감시 영역 = 폰(태블릿) 화면 전체 (상태바·내비게이션 바까지 포함한 실제 크기)
        val dm: DisplayMetrics = resources.displayMetrics
        val (w, h) = screenSize()
        try {
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            virtualDisplay = proj.createVirtualDisplay(
                "BossCapture", w, h, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader!!.surface, null, handlerOn()
            )
        } catch (e: Exception) {
            Log.e(TAG, "가상 화면 생성 실패", e)
            try { reader?.close() } catch (_: Exception) {}
            try { proj.stop() } catch (_: Exception) {}
            reader = null; projection = null
            failStart("화면을 읽을 준비를 하지 못했습니다. (${e.javaClass.simpleName})")
            return
        }

        viaA11y = false
        markStarted(mode)
        Bus.log("▶ 감시를 시작했습니다. (화면 공유 · ${w}×${h}, 축소 ${(downscale * 100).toInt()}%, " +
                "${intervalMs}ms 간격, 템플릿 ${templates.count()}장)")
        handlerOn().post(loop)
    }

    /** 감시가 실제로 시작됐을 때 공통 처리 */
    private fun markStarted(mode: GameMode) {
        running = true
        Bus.running = true
        Bus.mode = mode
        Bus.onState(session, seq)
        OverlayService.start(this)
        currentSpeed = if (Prefs.speedLock(this)) 2 else 1
        lobbyNextCheck = 0L; lobbyQuietUntil = 0L; inLobby = false
        paused = false; Bus.paused = false
        updateNotification("감시 중 · ${session.mode.label} (${PcDefaults.speedLabel(session.mode, Prefs.speedLock(this))})")
    }

    /**
     * 포그라운드 서비스로 올린다 — 방식에 맞는 종류로.
     * Android 14부터 '화면 공유' 종류는 화면 공유 동의가 있어야만 쓸 수 있어서,
     * 접근성 방식에서는 '특수 용도(specialUse)' 종류를 쓴다.
     */
    private fun startForegroundFor(projection: Boolean) {
        val n = buildNotification("준비 중")
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n,
                    if (projection) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    else android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            Log.e(TAG, "포그라운드 시작 실패", e)
        }
    }

    // ── 접근성 방식 ──────────────────────────────────────────────

    /** 접근성 방식으로 감시 중인지 */
    private var viaA11y = false
    /** 지금 화면을 찍는 중인지 (한 번에 한 장만) */
    private var a11yBusy = false
    /** 접근성 루프 횟수 (게임 화면을 아직 못 찾았으면 가끔 다시 찾는다) */
    private var a11yRounds = 0
    /** 접근성 화면 찍기 간격 — 안드로이드가 거절하면 늘린다 */
    private var a11yGapMs = 400L
    /** [캡처] 를 누른 시각 — 그 뒤에 찍기 시작한 화면만 캡처로 쓴다 (사진 줄이 찍히지 않게) */
    @Volatile private var pendingSnapAt = 0L

    private fun beginA11y(mode: GameMode) {
        if (running) return
        if (!BossA11yService.supported()) {
            failStart("접근성 화면 읽기는 안드로이드 11 이상에서만 됩니다."); return
        }
        if (BossA11yService.instance == null) {
            failStart("접근성 권한이 꺼져 있습니다. 메인 화면 '인식 방식' 에서 켜 주세요."); return
        }
        session.mode = mode
        viaA11y = true
        a11yBusy = false
        a11yRounds = 0
        // 게임이 어느 화면(뮤뮤 탭)에 있는지 먼저 찾는다
        BossA11yService.instance?.findGame()
        a11yGapMs = max(A11Y_MIN_GAP_MS, intervalMs)
        markStarted(mode)
        Bus.log("▶ 감시를 시작했습니다. (접근성 · 확인창 없음, 약 ${a11yGapMs}ms 간격, 템플릿 ${templates.count()}장)")
        handlerOn().post(a11yLoop)
    }

    /** 접근성 감시 루프 — 한 장 찍고, 분석하고, 잠깐 쉬고 다시 */
    private val a11yLoop = object : Runnable {
        override fun run() {
            if (!running || !viaA11y) return
            if (a11yBusy) return
            val svc = BossA11yService.instance
            if (svc == null) {
                Bus.log("⏹ 접근성 권한이 꺼져서 감시를 멈췄습니다.")
                stopEverything(keepOverlay = true)
                return
            }
            a11yBusy = true
            if (++a11yRounds % 8 == 0 && BossA11yService.gamePackage == null) svc.findGame()
            val startedAt = System.currentTimeMillis()
            svc.grab { bmp, err ->
                handlerOn().post {
                    a11yBusy = false
                    if (!running || !viaA11y) { bmp?.recycle(); return@post }
                    if (bmp == null) {
                        if (err == BossA11yService.ERROR_TOO_SOON) {
                            a11yGapMs = (a11yGapMs + 150).coerceAtMost(1200)
                        }
                    } else {
                        try { processBitmap(bmp, startedAt) } catch (e: Exception) { Log.w(TAG, "분석 오류", e) }
                    }
                    if (running && viaA11y) handlerOn().postDelayed(this, nextDelay(a11yGapMs))
                }
            }
        }
    }

    /** 접근성으로 찍은 화면 한 장을 처리한다 (캡처 요청 → 분석) */
    private fun processBitmap(bmp: Bitmap, startedAt: Long) {
        var handedOff = false
        pendingSnap?.let { cb ->
            if (startedAt >= pendingSnapAt) {
                pendingSnap = null
                cb(bmp)               // 자르기 화면이 이 비트맵을 쓴다 (여기서 지우지 않는다)
                handedOff = true
            }
        }
        if (paused) { if (!handedOff) bmp.recycle(); return }
        val w = bmp.width; val h = bmp.height
        val buf = java.nio.ByteBuffer.allocateDirect(w * h * 4)
        bmp.copyPixelsToBuffer(buf)
        buf.rewind()
        if (!handedOff) bmp.recycle()
        val full = CvOps.fromRgba(buf, w, h, w * 4)
        val eff = downscale * refScale(w, h)
        val scr = if (kotlin.math.abs(eff - 1.0) < 0.001) full else {
            val s2 = CvOps.resize(full, max(1, (w * eff).toInt()), max(1, (h * eff).toInt()))
            full.release(); s2
        }
        analyze(scr)
    }

    /**
     * 시작에 실패했을 때 — '감시 중' 표시를 되돌리고 서비스를 내린다.
     * 그래야 메인 화면 [시작] 을 다시 누를 수 있다.
     */
    private fun failStart(why: String) {
        Bus.log("❌ $why [시작] 을 다시 눌러 주세요.")
        running = false
        Bus.running = false
        Bus.onState(session, seq)
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        stopSelf()
    }

    /** 지금 화면의 실제 크기 (회전하면 가로·세로가 바뀐다) */
    private fun screenSize(): Pair<Int, Int> {
        val dm: DisplayMetrics = resources.displayMetrics
        var w = dm.widthPixels
        var h = dm.heightPixels
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val b = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
                if (b.width() > 0 && b.height() > 0) { w = b.width(); h = b.height() }
            } catch (_: Exception) {}
        }
        return w to h
    }

    /**
     * 화면이 회전하면(태블릿 가로/세로) 캡처 크기를 다시 맞춘다.
     * 그대로 두면 회전한 화면이 찌그러지거나 검은 띠가 생겨 인식이 안 된다.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (running) handlerOn().postDelayed({ resizeCapture() }, 300)
    }

    private fun resizeCapture() {
        val vd = virtualDisplay ?: return
        val (w, h) = screenSize()
        val old = reader
        if (old != null && old.width == w && old.height == h) return
        try {
            val nr = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            vd.resize(w, h, resources.displayMetrics.densityDpi)
            vd.surface = nr.surface
            reader = nr
            try { old?.close() } catch (_: Exception) {}
            Bus.log("🔄 화면이 회전해서 감시 크기를 다시 맞췄습니다. (${w}×${h})")
        } catch (e: Exception) {
            Log.w(TAG, "회전 대응 실패", e)
        }
    }

    /**
     * 감시를 멈춘다.
     * @param keepOverlay true 면 게임 위 [보스설정] 버튼은 남겨 둔다 —
     *        화면 공유가 OS 쪽에서 끊겼을 때 게임 화면에서 바로 [시작] 을 다시 누를 수 있게.
     */
    private fun stopEverything(keepOverlay: Boolean = false) {
        if (!running && projection == null) return
        running = false
        viaA11y = false
        endGame()
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        virtualDisplay = null; reader = null; projection = null
        if (!keepOverlay) OverlayService.stop(this)
        paused = false; Bus.paused = false; Bus.lobby = false
        Bus.running = false
        Bus.log("⏹ 감시를 멈췄습니다.")
        updateNotification("멈춤")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun handlerOn(): Handler {
        if (handler == null) {
            // ⚠️ apply { start() } 로 쓰면 이 클래스의 companion start() 와 이름이 겹친다.
            //    also { it.start() } 로 받는 쪽을 분명히 한다.
            thread = HandlerThread("capture").also { it.start() }
            handler = Handler(thread!!.looper)
        }
        return handler!!
    }

    // ── 감시 루프 ─────────────────────────────────────────────────

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                scanOnce()
            } catch (e: Exception) {
                Log.w(TAG, "스캔 중 오류", e)
            }
            if (running) handlerOn().postDelayed(this, nextDelay(intervalMs))
        }
    }

    /** 보스를 마지막으로 찾은 시각 (발열 줄이기: 찾은 직후에는 천천히) */
    private var lastFoundAt = 0L

    /**
     * 다음 검사까지 쉴 시간 — 발열 줄이기가 켜져 있으면 상황에 따라 늘린다.
     *   • 로비에 있거나 자르기 화면이 떠 있음 → 1초
     *   • 보스를 찾은 지 8초 안 (다음 보스는 한참 뒤에 나온다) → 3배
     *   • 평소 → 최소 0.3초
     */
    private fun nextDelay(base: Long): Long {
        if (!Prefs.powerSave(this)) return base
        val now = System.currentTimeMillis()
        return when {
            inLobby || Bus.cropVisible || paused -> max(base, 1000L)
            now - lastFoundAt < 8000 -> max(base, 300L) * 3
            else -> max(base, 300L)
        }
    }

    private fun scanOnce() {
        val img = reader?.acquireLatestImage() ?: return
        var screen: Mat? = null
        try {
            val plane = img.planes[0]
            pendingSnap?.let { cb ->
                pendingSnap = null
                val f = grabSnapshot(plane, img.width, img.height)
                plane.buffer.rewind()
                cb(f)
            }
            // 일시정지 중에는 캡처(사진 찍기)만 되고 인식은 쉰다
            if (paused) return
            val full = CvOps.fromRgba(plane.buffer, img.width, img.height, plane.rowStride)
            // 화면을 '짧은 변 1080' 기준으로 맞춘 뒤 축소한다.
            // 그래서 폰·태블릿·뮤뮤플레이어처럼 해상도가 달라도 같은 보스 사진으로 인식된다.
            // (자르기 화면도 사진을 같은 기준으로 맞춰서 저장한다)
            val eff = downscale * refScale(img.width, img.height)
            screen = if (kotlin.math.abs(eff - 1.0) < 0.001) full else {
                val s = CvOps.resize(full, max(1, (img.width * eff).toInt()),
                    max(1, (img.height * eff).toInt()))
                full.release(); s
            }
        } finally {
            img.close()
        }
        val scr = screen ?: return
        analyze(scr)
    }

    /**
     * 한 화면을 분석한다 — 로비 → 배속 → 보스 순서.
     * 화면 공유 방식과 접근성 방식이 같은 분석을 쓴다. 끝나면 scr 을 풀어 준다.
     */
    private fun analyze(scr: Mat) {
        try {
            val now = System.currentTimeMillis()

            // 자르기 화면이 떠 있는 동안은 쉰다 (그 화면 속 게임 사진을 진짜 게임으로 착각하지 않게)
            if (Bus.cropVisible) return

            // ① 로비 화면 — 인식되면 모드를 맞추고 보스 사진을 초기화한다
            if (now >= lobbyNextCheck) {
                lobbyNextCheck = now + LOBBY_CHECK_MS
                if (checkLobby(scr, now)) {
                    if (!inLobby) Bus.lobby = true
                    inLobby = true; lobbyLastSeen = now
                    return
                }
                if (inLobby && now - lobbyLastSeen >= LOBBY_EXIT_MS) {
                    inLobby = false; Bus.lobby = false
                    Bus.log("🎮 로비를 벗어났습니다. 보스 인식을 다시 시작합니다.")
                }
            }
            // 로비에 있는 동안(로비 그림 속 보스 오인식 방지)과 초기화 직후에는 보스를 찾지 않는다
            if (inLobby || now < lobbyQuietUntil) return

            // ② 배속 — 배속 고정을 끈 경우만 화면에서 1배속/2배속을 읽는다
            if (!Prefs.speedLock(this) && now >= speedNextCheck) {
                speedNextCheck = now + SPEED_CHECK_MS
                checkSpeed(scr)
            }

            // ③ 보스
            for (name in BossCatalog.watchList(session.mode)) {
                if (!running) break
                if (!session.shouldWatch(name, now)) continue
                val tpl = templates.forBoss(name, session.mode) ?: continue
                if (detect(name, scr, tpl, now)) {
                    // 한 바퀴에 여러 마리가 동시에 켜지면 안내가 겹치므로 하나만 처리한다
                    break
                }
            }
        } finally {
            scr.release()
        }
    }

    /**
     * 로비 화면 3종을 찾는다. (PC monitor 의 로비 인식과 같다)
     *
     *   태초로비 → 태초 모드로 바꾸고 초기화
     *   침공로비 → 침공 모드로 바꾸고 초기화
     *   공통로비 → 모드는 그대로, 초기화만 (로비까지 안 가고 '다시하기'를 누르는 화면)
     *
     * @return 로비가 보였으면 true (이번 프레임은 보스 검사를 건너뛴다)
     */
    private var graceLogged = false

    /** 로비 후보 — 연달아 보여야 진짜 로비로 본다 (한 번 반짝 맞은 것으로 보스를 지우지 않게) */
    private var lobbyCandidate: String? = null
    private var lobbyCandHits = 0

    /** 로비 오인식 확인용 화면 저장 간격 */
    private var lastLobbyShotAt = 0L

    /** 로비 사진 한 종류의 최고 점수 (기준을 넘은 사진만, 없으면 null) */
    private data class LobbyHit(val lobby: String, val mode: GameMode?, val score: Double, val file: String)

    private fun scoreLobby(scr: Mat, lobby: String, lobbyMode: GameMode?): LobbyHit? {
        var hit: LobbyHit? = null
        for (fn in templates.lobbyShots(lobby)) {
            // 내 사진은 '새로 등록/기본 사진' 설정과 상관없이 항상 쓴다
            val t = (if (templates.isUser(fn)) templates.get(fn) else templates.getUsable(fn)) ?: continue
            // 이 기기에서 찍은 로비 사진은 높은 기준, PC 사진은 기준값 + 윤곽선 검사
            val phone = templates.isPhoneShot(fn)
            val th = if (phone) PcDefaults.LOBBY_THRESHOLD_PHONE else PcDefaults.LOBBY_THRESHOLD
            val r = matcher.multiScale(scr, t, th, downscale = downscale)
            if (r.score < th) continue
            if (!phone && matcher.verifyEdge(scr, t, r.x, r.y, r.w, r.h) < PcDefaults.LOBBY_MIN_EDGE) continue
            // 첫 번째로 넘은 사진에서 멈추지 않고, 그 로비 사진들 중 가장 높은 점수를 쓴다
            if (hit == null || r.score > hit.score) hit = LobbyHit(lobby, lobbyMode, r.score, fn)
        }
        return hit
    }

    /** 마지막 로비 판정 로그 시각 (같은 내용이 계속 쌓이지 않게) */
    private var lastLobbyAmbigLog = 0L

    private fun checkLobby(scr: Mat, now: Long): Boolean {
        // ── v2.0: 로비 3종을 모두 재고, 가장 높은 것을 고른다 ──
        // 예전에는 태초로비 → 침공로비 순서로 '먼저 기준을 넘은 것' 을 골랐다.
        // 핸드폰 태초로비와 침공로비 화면은 배치가 똑같고 색만 달라서(인식은 회색조)
        // 태초로비 사진이 침공로비 화면에도 75~78% 로 맞는다 (기준 72%).
        // 그래서 침공로비에서 화면이 조금씩 움직일 때마다 태초/침공이 번갈아 골라져
        // "태초모드 > 침공모드 전환" 이 계속 반복됐다.
        val hits = listOfNotNull(
            scoreLobby(scr, PcDefaults.LOBBY_NORMAL, GameMode.NORMAL),
            scoreLobby(scr, PcDefaults.LOBBY_INVASION, GameMode.INVASION),
            scoreLobby(scr, PcDefaults.LOBBY_COMMON, null)
        )
        val top = hits.maxByOrNull { it.score }
        if (top != null) {
            val lobby = top.lobby
            var lobbyMode = top.mode
            val best = top.score
            val bestFile = top.file

            // 태초로비·침공로비가 둘 다 맞았는데 점수 차이가 작으면 어느 쪽인지 믿지 않는다
            // → 로비로는 보되(보스 초기화는 함), 모드는 바꾸지 않는다
            if (lobbyMode != null) {
                val other = hits.firstOrNull { it.mode != null && it.mode != lobbyMode }
                if (other != null && best - other.score < PcDefaults.LOBBY_MODE_MARGIN) {
                    if (now - lastLobbyAmbigLog > 10_000) {
                        lastLobbyAmbigLog = now
                        Bus.log("🤔 [로비 판정 보류] 태초로비 ${pct(hits.first { it.mode == GameMode.NORMAL }.score)} · " +
                                "침공로비 ${pct(hits.first { it.mode == GameMode.INVASION }.score)} — " +
                                "차이가 ${pct(PcDefaults.LOBBY_MODE_MARGIN)} 보다 작아 모드는 그대로 둡니다. " +
                                "(그 기기에서 로비 사진을 직접 등록하면 확실해집니다)")
                    }
                    lobbyMode = null
                }
            }

            // 연달아 보여야 로비 — 한 번만 맞은 것은 아직 믿지 않는다
            // 후보는 '어떤 모드의 로비인지' 로 센다 (보류된 판정은 모드 없음으로 센다)
            val candKey = lobbyMode?.key ?: "keep"
            if (lobbyCandidate == candKey) lobbyCandHits++ else { lobbyCandidate = candKey; lobbyCandHits = 1 }
            if (!inLobby && lobbyCandHits < PcDefaults.LOBBY_CONFIRM_HITS) return false
            // 이미 로비에 있는 동안 모드를 바꾸려면 더 여러 번 연달아 같은 쪽이 나와야 한다
            if (lobbyMode != null && lobbyMode != session.mode && lobbyCandHits < PcDefaults.LOBBY_MODE_SWITCH_HITS) {
                lobbyMode = null
            }

            val entering = !inLobby
            val label = PcDefaults.lobbyLabel(lobby)
            val oldMode = session.mode
            val newMode = lobbyMode ?: session.mode
            val modeChanged = newMode != session.mode
            if (modeChanged) {
                session.mode = newMode
                streak.clear()
                Prefs.setMode(this, newMode.key)
                Bus.mode = newMode
                updateNotification("감시 중 · ${newMode.label} (${PcDefaults.speedLabel(newMode, Prefs.speedLock(this))})")
            }
            // 게임 화면 가운데 조금 아래에 잠깐 알린다 (예: 태초모드 > 침공모드 전환)
            val willReset = session.activationOrder.isNotEmpty() && now - Bus.cropClosedAt >= CROP_GRACE_MS
            if (modeChanged || entering || willReset) {
                val head = when {
                    modeChanged -> (if (newMode == GameMode.INVASION) "🔥 " else "🟢 ") +
                            "${oldMode.label}모드 > ${newMode.label}모드 전환"
                    top.mode == null -> "🏁 $label 인식"
                    lobbyMode == null -> "🏠 로비 인식"
                    else -> "🏠 $label 인식"
                }
                // 로비·모드 전환 글자는 메인 화면 스위치로 켜고 끈다 (기본 끄기, v2.0)
                if (Prefs.lobbyNotice(this)) Bus.notice(if (willReset) "$head\n보스 사진 초기화" else head)
            }
            // 자르기 화면에서 막 돌아온 직후(15초)에는 로비가 잠깐 보여도 보스를 지우지 않는다
            // (게임이 다시 접속하면서 로비를 잠깐 보여 주는 경우가 있어서)
            if (session.activationOrder.isNotEmpty() && now - Bus.cropClosedAt < CROP_GRACE_MS) {
                if (!graceLogged) {
                    graceLogged = true
                    Bus.log("🛡 캡처 화면에서 돌아오는 중이라 로비가 보여도 보스 사진을 그대로 둡니다.")
                }
                return true
            }
            graceLogged = false
            if (session.activationOrder.isNotEmpty()) {
                Bus.log((if (top.mode == null)
                    "🏁 [$label(공통로비) 인식 → ${newMode.label} 모드 유지] 보스 사진을 초기화합니다."
                else if (lobbyMode == null)
                    "🏠 [로비 인식 → ${newMode.label} 모드 유지(판정 보류)] 보스 사진을 초기화합니다."
                else
                    "🏠 [$lobby 귀환 → ${newMode.label} 모드] 보스 사진을 초기화합니다.") +
                        " (사진 '${bestFile.removeSuffix(".png")}', 정확도 ${pct(best)}, " +
                        "보스 ${session.activationOrder.size}마리였음)")
                saveLobbyShot(scr, bestFile, now)
                endGame()
                lobbyQuietUntil = now + LOBBY_QUIET_MS
            } else if (modeChanged) {
                Bus.log("🏠 [$lobby 인식] ${newMode.label} 모드로 바꿨습니다.")
                Bus.onState(session, seq)
            }
            return true
        }
        lobbyCandidate = null; lobbyCandHits = 0
        return false
    }

    /**
     * 게임 중 로비로 인식해서 보스를 지웠을 때, 그 순간 화면을 사진첩에 남긴다.
     * (사진첩 › 운빨보스알림 › 원본화면 › 로비인식_…) — 오인식이면 어떤 사진이 문제인지 바로 알 수 있다.
     */
    private fun saveLobbyShot(scr: Mat, file: String, now: Long) {
        if (now - lastLobbyShotAt < 20_000) return
        lastLobbyShotAt = now
        try {
            val bmp = Bitmap.createBitmap(scr.cols(), scr.rows(), Bitmap.Config.ARGB_8888)
            org.opencv.android.Utils.matToBitmap(scr, bmp)
            Thread {
                Backup.saveFullScreen(this, bmp, "로비인식_${file.removeSuffix(".png")}")
                bmp.recycle()
            }.start()
        } catch (e: Exception) {
            Log.w(TAG, "로비 화면 저장 실패", e)
        }
    }

    /** 1배속/2배속 표시를 읽는다 (PC monitor 의 배속 인식과 같은 판정) */
    private fun checkSpeed(scr: Mat) {
        val inv = session.mode == GameMode.INVASION
        fun pick(n: String): String =
            if (inv && templates.get("침공_$n.png") != null) "침공_$n.png" else "$n.png"
        val t2 = templates.get(pick("2배속상태"))
        val t1 = templates.get(pick("1배속상태"))
        val th = PcDefaults.SPEED_THRESHOLD
        val s2 = t2?.let { matcher.multiScale(scr, it, th, downscale = downscale).score } ?: 0.0
        val s1 = t1?.let { matcher.multiScale(scr, it, th, downscale = downscale).score } ?: 0.0
        val newSpeed = when {
            s2 >= th && s1 >= th -> when {
                s2 - s1 >= PcDefaults.SPEED_MARGIN -> 2
                s1 - s2 >= PcDefaults.SPEED_MARGIN -> 1
                else -> currentSpeed
            }
            s2 >= th -> 2
            s1 >= th -> 1
            else -> currentSpeed
        }
        if (newSpeed != currentSpeed) {
            currentSpeed = newSpeed
            Bus.log(if (newSpeed == 2) "⏩ [게임 속도 감지] 2배속 (2x ${pct(s2)} / 1x ${pct(s1)})"
                    else "▶️ [게임 속도 감지] 1배속 (1x ${pct(s1)} / 2x ${pct(s2)})")
        }
    }

    /**
     * 보스 한 마리를 찾는다.
     *
     * ① 기본 멀티스케일 매칭
     * ② (근접 구간이면) 보충 크기 검사
     * ③ (근접 구간이면) 보조 스샷
     * ④ (근접 구간이면) 타일 분할 투표 — 네임텍 가려짐 구제
     * ⑤ 깐깐 검사 대상이면 PSR · 윤곽선 · 연속프레임 확인
     *
     * @return 활성화되었으면 true
     */
    private fun detect(name: String, scr: Mat, tpl: Mat, now: Long): Boolean {
        // PC 사진을 쓸 때는 PC판 기준값(대부분 60%)을,
        // 폰에서 새로 등록한 사진만 쓸 때는 폰판 기준값(72%)을 쓴다.
        // 폰 사진은 크기가 정확히 맞아서 높은 기준으로도 잘 잡히고, 엉뚱한 곳(로비 그림 등)에 덜 걸린다.
        val th = if (Prefs.usePcTemplates(this))
            BossCatalog.threshold(name, session.mode, PcDefaults.thresholds(session.mode, Prefs.thresholds(this)))
        else BossCatalog.threshold(name, session.mode, Prefs.thresholds(this))
        val strict = BossCatalog.isStrict(name, Prefs.strictBosses(this))

        // 발열 줄이기: 이 기기에서 직접 등록한 사진은 크기가 거의 같으므로 크기 비교를 3단계만 한다
        val scales = if (Prefs.powerSave(this) && templates.bossFileIsUser(name, session.mode))
            NARROW_SCALES else BossCatalog.SCALES
        var out = matcher.multiScale(scr, tpl, th, scales = scales, downscale = downscale)
        var ok = out.score >= th
        var tileEdge = -1.0

        val inBand = !ok && out.score >= th - BossCatalog.RESCUE_BAND
        if (inBand) {
            // ② 보충 크기
            val r = matcher.rescueScales(scr, tpl, th, downscale)
            if (r.score > out.score) out = r
            ok = out.score >= th
            if (ok) Bus.log("🔎 [구제 인식] $name — 보충 크기 검사로 찾았습니다. " +
                    "(정확도 ${pct(out.score)})")

        }

        // ③ 보조 사진 — 이 기기에서 찍은 보조 사진은 기본 사진 점수와 상관없이 항상 본다
        //    (PC 기본 사진이 폰 화면에서 낮게 나와도 내가 등록한 사진으로 잡히게)
        //    PC 보조 사진은 예전처럼 구제 구간에서만 본다
        if (!ok) {
            for (fn in templates.extraShots(name, session.mode)) {
                val phone = templates.isPhoneShot(fn)
                if (!phone && !inBand) continue
                val ex = templates.get(fn) ?: continue
                val sc = if (phone && Prefs.powerSave(this)) NARROW_SCALES else BossCatalog.SCALES
                val e = matcher.multiScale(scr, ex, th, scales = sc, downscale = downscale)
                if (e.score > out.score) out = e
                if (out.score >= th) {
                    ok = true
                    Bus.log("🖼 [보조 사진 인식] $name — '${fn.removeSuffix(".png")}' 로 찾았습니다. " +
                            "(정확도 ${pct(out.score)})")
                    break
                }
            }
        }

        if (inBand) {
            // ④ 타일 분할 투표 (마지막 수단, 쿨다운으로 비용 상한)
            if (!ok && Prefs.tileMinAgree(this) > 0 && session.canTileVote(name, now)) {
                session.markTileVote(name, now)
                val tv = matcher.tileVote(scr, tpl, scale = out.scale, downscale = downscale)
                if (tv.agree >= Prefs.tileMinAgree(this) && tv.score >= Prefs.tileMinScore(this)) {
                    // 타일구제는 깐깐 검사 대상이 아니어도 윤곽선 검증을 통과해야 한다
                    val need = max(BossCatalog.MIN_EDGE_SCORE, BossCatalog.TILE_RESCUE_MIN_EDGE)
                    val ev = matcher.verifyEdge(scr, tpl, tv.x, tv.y, tv.w, tv.h)
                    if (ev >= need) {
                        Bus.log("🧩 [가려짐 구제] $name — 네임텍 등으로 일부가 가려진 것 같습니다. " +
                                "조각 검사로 찾았습니다. (기본 ${pct(out.score)} → " +
                                "조각평균 ${pct(tv.score)}, 같은 자리 동의 ${tv.agree}/" +
                                "${BossCatalog.TILE_ROWS * BossCatalog.TILE_COLS}조각, " +
                                "윤곽선 ${pct(ev)})")
                        out = MatchOutcome(max(out.score, th), BossCatalog.MIN_PSR,
                            tv.x, tv.y, tv.w, tv.h, out.scale)
                        tileEdge = ev
                        ok = true
                    }
                }
            }
        }

        if (!ok) {
            streak.remove(name)
            return false
        }

        // ⑤ 깐깐 검사
        if (strict) {
            if (out.psr < BossCatalog.MIN_PSR) { streak.remove(name); return false }
            if (tileEdge < 0) {
                val ev = matcher.verifyEdge(scr, tpl, out.x, out.y, out.w, out.h)
                if (ev < BossCatalog.MIN_EDGE_SCORE) { streak.remove(name); return false }
            }
            val n = (streak[name] ?: 0) + 1
            streak[name] = n
            if (n < BossCatalog.CONFIRM_FRAMES) return false
        }
        streak.remove(name)
        return activate(name, out.score, auto = true)
    }

    // ── 보스 활성화 / 안내 ────────────────────────────────────────

    fun activate(name: String, score: Double, auto: Boolean): Boolean {
        if (!session.activate(name, auto)) return false

        val special = BossCatalog.isSpecial(name)
        val tag = if (special) "침공특수보스" else "${session.mainCount() * 10}라운드"
        Bus.log("🎯 [$tag] $name 인식 (정확도 ${pct(score)})")
        Bus.onState(session, seq)
        speaker.sayBoss(name)
        scheduleWarning(name, session.mode)
        lastFoundAt = System.currentTimeMillis()

        // 침공특수보스 2종이 모이면 80R 확정 — 확률이 아니라 소거법이라 100%다
        val target = session.tryDeduce80R()
        if (target != null) {
            val cond = session.cond80R
            Bus.log("🎯 [80R 확정] 침공특수보스 2종이 인식되어 남은 1종으로 확정했습니다 → '$target'")
            val (msg, used) = Predictor.message(
                target, session.activationOrder.toSet(), cond,
                PcDefaults.condTexts(Prefs.condTexts(this)),
                PcDefaults.baseTexts(Prefs.baseTexts(this))
            )
            if (msg != null) {
                val delay = Prefs.announceDelayMs(this)
                handlerOn().postDelayed({
                    if (used != null) {
                        Bus.log("🔊 [80R 안내] (${Predictor.COND_LABEL[used] ?: used} → 조건 멘트) \"$msg\"")
                    } else {
                        Bus.log("🔊 [80R 안내] \"$msg\"")
                    }
                    speaker.say(msg)
                }, delay)
            }
        }

        // 과거와 같은 순서인지 — 같으면 80R 메인보스를 미리 알려준다
        if (!special) checkSequenceMatch()
        return true
    }

    /**
     * 지금까지의 순서가 과거 판과 길게 일치하면 알려준다. (판당 1회)
     *
     * 기준 길이는 기록량에 따라 자동으로 올라간다. 기록이 쌓일수록 우연한 일치가
     * 늘어나기 때문이다. (50판이면 6칸, 300판이면 8칸)
     */
    private fun checkSequenceMatch() {
        if (session.mode != GameMode.INVASION) return
        if (session.seqMatchAnnounced) return
        val cur = session.mainOrder()
        val need = seq.minMatchLen()
        if (cur.size < need) return
        val (k, rows, dist) = seq.findMatches(cur, need)
        if (k < need || dist.isEmpty()) return
        val top = dist.maxByOrNull { it.value } ?: return
        val total = dist.values.sum()
        val conf = top.value * 100.0 / total
        if (conf < SeqStore.MATCH_MIN_CONF) return

        session.seqMatchAnnounced = true
        val chance = seq.expectedCollisions(k)
        Bus.log("🔮 [순서 일치 발견] 이번 판의 앞 ${k}마리 순서가 과거 ${rows.size}판과 같습니다. " +
                "그 판들의 80라운드 보스는 '${top.key}' (${top.value}/$total, ${conf.toInt()}%) 였습니다. " +
                "→ 패턴이 반복된다면 이번 80R도 '${top.key}' 일 가능성이 높습니다. " +
                "(무작위라면 이런 일치가 생길 기대 ${"%.3f".format(chance)}회)")
        Bus.onPredict(top.key, conf)
        if (conf >= 100.0 && k >= 5) {
            speaker.say("과거와 같은 순서입니다. 80라운드는 ${top.key} 예상")
        }
    }

    private fun undoLast() {
        val name = session.undoLast(System.currentTimeMillis()) ?: run {
            Bus.log("↩️ 되돌릴 보스가 없습니다."); return
        }
        streak.remove(name)
        Bus.log("↩️ [되돌리기] '$name' 을 목록에서 제거했습니다. " +
                "(5초 동안 다시 인식하지 않습니다)")
        Bus.onState(session, seq)
    }

    // ── 위험 경보 ────────────────────────────────────────────────

    /**
     * 보스가 인식되면 경보 타이머를 건다. (PC schedule_warning 과 같은 계산)
     *
     * 설정한 시간은 '게임 안 시간'이다. 실제로는 배속만큼 빨리 흐른다.
     *   • 배속 고정(기본): 태초는 항상 2배속, 침공은 앞 30% 만 2배속이고 나머지는 1배속
     *   • 배속 자동 인식: 화면에서 읽은 배속 (태초가 1배속으로 잡히면 2배속으로 보정)
     * 보스가 되돌리기·초기화로 사라지면 타이머도 취소된다.
     */
    private fun scheduleWarning(name: String, mode: GameMode) {
        if (!Prefs.warningEnabled(this) || !Prefs.voiceEnabled(this)) return
        val base = Prefs.warnDelay(this, mode, name).toDouble()
        val locked = Prefs.speedLock(this)
        val expect = if (locked) {
            if (mode == GameMode.INVASION) {
                val er = PcDefaults.INVASION_EARLY_2X_PCT / 100.0
                base * er / 2.0 + base * (1 - er)
            } else base / 2.0
        } else base / max(1, if (mode == GameMode.NORMAL) max(2, currentSpeed) else currentSpeed)
        Bus.log("⏳ [$name] (${mode.label}) 위험 경보 타이머 — 목표 ${base.toInt()}초 / " +
                "${PcDefaults.speedLabel(mode, locked)} → 약 ${expect.toInt()}초 뒤 알림")
        warns.removeAll { it.boss == name }
        warns.add(Warn(name, mode, base, gameGen))
        if (!warnTickerPosted) {
            warnTickerPosted = true
            handlerOn().postDelayed(warnTicker, WARN_TICK_MS)
        }
    }

    private val warnTicker = object : Runnable {
        override fun run() {
            warnTickerPosted = false
            val now = SystemClock.elapsedRealtime()
            val locked = Prefs.speedLock(this@CaptureService)
            val iter = warns.iterator()
            while (iter.hasNext()) {
                val w = iter.next()
                if (w.gen != gameGen || !session.isActive(w.boss)) {
                    if (w.gen == gameGen) Bus.log("🛑 [${w.boss}] 목록에서 빠져 경보 타이머를 취소했습니다.")
                    iter.remove(); continue
                }
                val speed = if (locked) {
                    if (w.mode == GameMode.INVASION) {
                        val early = w.base * PcDefaults.INVASION_EARLY_2X_PCT / 100.0
                        if (w.progress < early) 2 else 1
                    } else 2
                } else {
                    if (w.mode == GameMode.NORMAL && currentSpeed == 1) 2 else currentSpeed
                }
                val elapsed = (now - w.lastT) / 1000.0
                w.lastT = now
                w.progress += elapsed * speed
                if (w.progress >= w.base) {
                    iter.remove()
                    val what = speaker.playWarning(w.boss)
                    Bus.log("🚨 [${w.boss}] (${w.mode.label}) 위험 경보! " +
                            (what ?: "(경보음·경보 멘트가 없어 소리 없이 넘어갑니다)"))
                }
            }
            if (warns.isNotEmpty() && running) {
                warnTickerPosted = true
                handlerOn().postDelayed(this, WARN_TICK_MS)
            }
        }
    }

    /** 판이 끝났을 때 — 순서를 기록하고 초기화한다 */
    private fun endGame() {
        if (session.shouldRecord()) {
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date())
            if (seq.add(session.toRecord(ts))) {
                seqRepo.save(seq)
                val r = seq.games.last()
                Bus.log("🗂 [시퀀스 기록] 이번 판 순서를 저장했습니다. " +
                        "(메인 ${r.main.size}마리 / 특수 ${r.special.size}마리, " +
                        "누적 ${seq.games.size}판)")
            }
        }
        session.reset()
        streak.clear()
        // 진행 중이던 경보 타이머·대기 중인 안내도 함께 비운다 (PC reset_game 과 같다)
        gameGen++
        warns.clear()
        speaker.clearQueue()
        currentSpeed = if (Prefs.speedLock(this)) 2 else 1
        Bus.onState(session, seq)
    }

    private fun pct(v: Double) = "%.1f%%".format(v * 100)

    /** 짧은 변을 1080 으로 맞추는 배율 */
    private fun refScale(w: Int, h: Int): Double = REF_SHORT_SIDE / min(w, h).toDouble()

    /**
     * 지금 화면 한 장을 컬러 사진으로 떠 온다 (보스 사진을 직접 잘라 등록할 때 쓴다).
     * 파일로 저장했다가 다시 읽으면 1초 가까이 걸려서, 메모리에 든 채로 자르기 화면에 넘긴다.
     */
    private fun grabSnapshot(plane: android.media.Image.Plane, width: Int, height: Int): Bitmap? {
        return try {
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * width
            val buf = plane.buffer
            buf.rewind()
            val full = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(buf)
            if (rowPadding == 0) full
            else Bitmap.createBitmap(full, 0, 0, width, height).also { full.recycle() }
        } catch (e: Throwable) {
            Log.w(TAG, "캡처 실패", e); null
        }
    }

    // ── 알림 ──────────────────────────────────────────────────────

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CH_ID, "보스 감시",
                    NotificationManager.IMPORTANCE_LOW).apply {
                    description = "화면을 감시하며 보스를 찾습니다"
                }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val undo = PendingIntent.getService(
            this, 2, Intent(this, CaptureService::class.java).setAction(ACTION_UNDO),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CH_ID)
            .setContentTitle("운빨보스알림")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "되돌리기", undo).build())
            .addAction(Notification.Action.Builder(null, "멈춤", stop).build())
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (instance === this) instance = null
        pendingSnap?.let { cb -> pendingSnap = null; cb(null) }
        running = false
        try { speaker.release() } catch (_: Exception) {}
        try { thread?.quitSafely() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CaptureService"
        private const val CH_ID = "capture"
        private const val NOTIF_ID = 1001

        const val ACTION_START = "tv.gapja.monitor.START"
        const val ACTION_STOP = "tv.gapja.monitor.STOP"
        const val ACTION_UNDO = "tv.gapja.monitor.UNDO"
        const val ACTION_RESET = "tv.gapja.monitor.RESET"
        const val ACTION_MODE = "tv.gapja.monitor.MODE"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_MODE = "mode"
        const val EXTRA_DOWNSCALE = "downscale"
        const val EXTRA_INTERVAL = "interval"

        fun start(ctx: Context, code: Int, data: Intent, mode: GameMode,
                  downscale: Double, intervalMs: Long) {
            ctx.startForegroundService(
                Intent(ctx, CaptureService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_CODE, code)
                    .putExtra(EXTRA_DATA, data)
                    .putExtra(EXTRA_MODE, mode.key)
                    .putExtra(EXTRA_DOWNSCALE, downscale)
                    .putExtra(EXTRA_INTERVAL, intervalMs)
            )
        }

        /** 접근성 방식으로 시작 (확인창 없음) */
        fun startA11y(ctx: Context, mode: GameMode, downscale: Double, intervalMs: Long) {
            ctx.startForegroundService(
                Intent(ctx, CaptureService::class.java)
                    .setAction(ACTION_START_A11Y)
                    .putExtra(EXTRA_MODE, mode.key)
                    .putExtra(EXTRA_DOWNSCALE, downscale)
                    .putExtra(EXTRA_INTERVAL, intervalMs)
            )
        }

        const val ACTION_START_A11Y = "tv.gapja.monitor.START_A11Y"
        /** 접근성 방식 최소 간격 — 안드로이드 제한(약 333ms)보다 약간 넉넉히 */
        private const val A11Y_MIN_GAP_MS = 400L

        const val ACTION_EXIT = "tv.gapja.monitor.EXIT"
        const val ACTION_PAUSE = "tv.gapja.monitor.PAUSE"
        const val ACTION_RESUME = "tv.gapja.monitor.RESUME"

        /** 자르기 화면을 닫은 뒤 로비 초기화를 미루는 시간 */
        private const val CROP_GRACE_MS = 15_000L

        /** 직접 등록한 사진용 좁은 크기 범위 (같은 기기·1080 기준이라 거의 1배) */
        private val NARROW_SCALES = doubleArrayOf(1.0, 0.93, 1.07)

        /** 로비가 이 시간 동안 안 보여야 로비를 벗어난 것으로 본다 */
        private const val LOBBY_EXIT_MS = 2500L

        /** 로비 검사 간격 (PC 는 0.5초마다) */
        private const val LOBBY_CHECK_MS = 600L
        /** 로비로 초기화한 뒤 쉬는 시간 (PC: 2초) */
        private const val LOBBY_QUIET_MS = 2000L
        /** 배속 검사 간격 */
        private const val SPEED_CHECK_MS = 600L
        /** 경보 타이머 간격 (PC: 0.5초) */
        private const val WARN_TICK_MS = 500L

        /**
         * 화면 공유 동의 창을 띄우는 Intent.
         *
         * Android 14부터는 동의 창에서 '앱 하나 공유 / 전체 화면 공유' 를 고르게 되어 있는데,
         * 이 앱은 게임 화면 전체를 봐야 하므로 '전체 화면' 으로 정해서 요청한다.
         * (고르는 단계 없이 [시작] 만 누르면 된다. 동의 창 자체는 OS 규칙이라 없앨 수 없다)
         */
        fun captureIntent(ctx: Context): Intent {
            val mgr = ctx.getSystemService(android.media.projection.MediaProjectionManager::class.java)
            return if (Build.VERSION.SDK_INT >= 34) {
                mgr.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                mgr.createScreenCaptureIntent()
            }
        }

        /** 지금 돌고 있는 서비스 (캡처 요청용) */
        @Volatile private var instance: CaptureService? = null

        /**
         * 다음 프레임을 사진으로 찍어 달라고 요청한다.
         * 감시 중이 아니면 바로 null 을 돌려준다.
         */
        /** 인식 기준 화면 크기 — 짧은 변 1080 (1080×1920 기준) */
        const val REF_SHORT_SIDE = 1080.0

        /** 서비스가 살아 있는지 */
        fun isAlive(): Boolean = instance != null

        fun requestSnapshot(cb: (Bitmap?) -> Unit) {
            val s = instance
            if (s == null || !s.running) { cb(null); return }
            s.handlerOn().post { s.pendingSnapAt = System.currentTimeMillis(); s.pendingSnap = cb }
        }

        /**
         * 돌고 있는 서비스에 명령을 보낸다.
         *
         * ⚠️ 서비스가 안 돌고 있을 때 보내면 안 된다. 그러면 서비스가 새로 켜지면서
         *    onCreate 에서 startForeground(mediaProjection) 를 부르는데, 화면 공유 동의가
         *    없으면 Android 14부터 앱이 죽는다. (모드·되돌리기 버튼을 시작 전에 누를 때)
         */
        fun send(ctx: Context, action: String, mode: GameMode? = null) {
            if (instance == null) return
            val i = Intent(ctx, CaptureService::class.java).setAction(action)
            if (mode != null) i.putExtra(EXTRA_MODE, mode.key)
            try { ctx.startService(i) } catch (_: Exception) {}
        }
    }
}
