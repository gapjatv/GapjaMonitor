package tv.gapja.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
    @Volatile private var pendingSnap: ((File?) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        AssetInstaller.installIfNeeded(this)
        templates = TemplateStore(this)
        seqRepo = SeqRepo(this)
        seq = seqRepo.load()
        speaker = Speaker(this)
        startForeground(NOTIF_ID, buildNotification("준비 중"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_CODE, 0)
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                val mode = GameMode.from(intent.getStringExtra(EXTRA_MODE))
                downscale = intent.getDoubleExtra(EXTRA_DOWNSCALE, 0.5)
                intervalMs = intent.getLongExtra(EXTRA_INTERVAL, 150L)
                if (data != null) beginCapture(code, data, mode)
            }
            // 감시 루프는 capture 스레드에서 판 상태를 읽고 쓰므로,
            // 판 상태를 바꾸는 동작도 같은 스레드에서 차례로 처리한다 (동시 접근 충돌 방지)
            ACTION_STOP -> if (running) handlerOn().post { stopEverything() } else stopEverything()
            ACTION_UNDO -> handlerOn().post { undoLast() }
            ACTION_RESET -> handlerOn().post { endGame() }
            ACTION_MODE -> {
                val m = GameMode.from(intent.getStringExtra(EXTRA_MODE))
                handlerOn().post {
                    session.mode = m
                    streak.clear()
                    Bus.onState(session, seq)
                    updateNotification("감시 중 · ${m.label} (${PcDefaults.speedLabel(m)})")
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
        } ?: run { Bus.log("❌ 화면 캡처 권한을 받지 못했습니다. 다시 시작해 주세요."); return }

        projection = proj
        session = GameSession(mode)
        streak.clear()

        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Bus.log("⏹ 화면 공유가 중단되었습니다. (화면 잠김 또는 사용자 중지)")
                stopEverything()
            }
        }, handlerOn())

        // 감시 영역 = 폰 화면 전체 (상태바·내비게이션 바까지 포함한 실제 크기)
        val dm: DisplayMetrics = resources.displayMetrics
        var w = dm.widthPixels
        var h = dm.heightPixels
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val b = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
                w = b.width(); h = b.height()
            } catch (_: Exception) {}
        }
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        virtualDisplay = proj.createVirtualDisplay(
            "GapjaCapture", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, handlerOn()
        )

        running = true
        Bus.mode = mode
        Bus.onState(session, seq)
        OverlayService.start(this)
        updateNotification("감시 중 · ${session.mode.label} (${PcDefaults.speedLabel(session.mode)})")
        Bus.log("▶ 감시를 시작했습니다. (${w}×${h}, 축소 ${(downscale * 100).toInt()}%, " +
                "${intervalMs}ms 간격, 템플릿 ${templates.count()}장)")
        handlerOn().post(loop)
    }

    private fun stopEverything() {
        if (!running && projection == null) return
        running = false
        endGame()
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        virtualDisplay = null; reader = null; projection = null
        OverlayService.stop(this)
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
            if (running) handlerOn().postDelayed(this, intervalMs)
        }
    }

    private fun scanOnce() {
        val img = reader?.acquireLatestImage() ?: return
        var screen: Mat? = null
        try {
            val plane = img.planes[0]
            pendingSnap?.let { cb ->
                pendingSnap = null
                val f = saveSnapshot(plane, img.width, img.height)
                plane.buffer.rewind()
                cb(f)
            }
            val full = CvOps.fromRgba(plane.buffer, img.width, img.height, plane.rowStride)
            screen = if (downscale >= 0.999) full else {
                val s = CvOps.resize(full, (img.width * downscale).toInt(),
                    (img.height * downscale).toInt())
                full.release(); s
            }
        } finally {
            img.close()
        }
        val scr = screen ?: return
        try {
            val now = System.currentTimeMillis()
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
        // PC판에서 쓰던 기준값을 기본으로, 앱에서 바꾼 값이 있으면 그걸로
        val th = BossCatalog.threshold(name, session.mode,
            PcDefaults.thresholds(session.mode, Prefs.thresholds(this)))
        val strict = BossCatalog.isStrict(name, Prefs.strictBosses(this))

        var out = matcher.multiScale(scr, tpl, th, downscale = downscale)
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

            // ③ 보조 스샷 — 네임텍이 겹친 화면을 찍어뒀다면 여기서 걸린다
            if (!ok) {
                for (fn in templates.extraShots(name, session.mode)) {
                    val ex = templates.get(fn) ?: continue
                    val e = matcher.multiScale(scr, ex, th, downscale = downscale)
                    if (e.score > out.score) out = e
                    if (out.score >= th) {
                        ok = true
                        Bus.log("🖼 [보조 스샷 인식] $name — '$fn' 로 찾았습니다. " +
                                "(정확도 ${pct(out.score)})")
                        break
                    }
                }
            }

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
        Bus.onState(session, seq)
    }

    private fun pct(v: Double) = "%.1f%%".format(v * 100)

    /** 지금 화면 한 장을 컬러 PNG 로 저장한다 (보스 사진을 직접 잘라 등록할 때 쓴다) */
    private fun saveSnapshot(plane: android.media.Image.Plane, width: Int, height: Int): File? {
        return try {
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * width
            val buf = plane.buffer
            buf.rewind()
            val full = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(buf)
            val bmp = if (rowPadding == 0) full else Bitmap.createBitmap(full, 0, 0, width, height)
            val out = File(cacheDir, "snap.png")
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (bmp !== full) full.recycle()
            bmp.recycle()
            out
        } catch (e: Exception) {
            Log.w(TAG, "캡처 저장 실패", e); null
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
            .setContentTitle("갑자TV 관제탑")
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

        /** 지금 돌고 있는 서비스 (캡처 요청용) */
        @Volatile private var instance: CaptureService? = null

        /**
         * 다음 프레임을 사진으로 찍어 달라고 요청한다.
         * 감시 중이 아니면 바로 null 을 돌려준다.
         */
        fun requestSnapshot(cb: (File?) -> Unit) {
            val s = instance
            if (s == null || !s.running) { cb(null); return }
            s.handlerOn().post { s.pendingSnap = cb }
        }

        fun send(ctx: Context, action: String, mode: GameMode? = null) {
            val i = Intent(ctx, CaptureService::class.java).setAction(action)
            if (mode != null) i.putExtra(EXTRA_MODE, mode.key)
            try { ctx.startService(i) } catch (_: Exception) {}
        }
    }
}
