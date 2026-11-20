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

    // ── 로비 인식 (PC monitor 와 같다) ──
    /** 다음 로비 검사 시각 */
    private var lobbyNextCheck = 0L
    /** 로비로 초기화한 직후 잠깐 보스 검사를 쉰다 (PC: 2초) */
    private var lobbyQuietUntil = 0L

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

        // 감시 영역 = 폰(태블릿) 화면 전체 (상태바·내비게이션 바까지 포함한 실제 크기)
        val dm: DisplayMetrics = resources.displayMetrics
        val (w, h) = screenSize()
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
        currentSpeed = if (Prefs.speedLock(this)) 2 else 1
        lobbyNextCheck = 0L; lobbyQuietUntil = 0L
        updateNotification("감시 중 · ${session.mode.label} (${PcDefaults.speedLabel(session.mode, Prefs.speedLock(this))})")
        Bus.log("▶ 감시를 시작했습니다. (${w}×${h}, 축소 ${(downscale * 100).toInt()}%, " +
                "${intervalMs}ms 간격, 템플릿 ${templates.count()}장)")
        handlerOn().post(loop)
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

            // ① 로비 화면 — 인식되면 모드를 맞추고 보스 사진을 초기화한다
            if (now >= lobbyNextCheck) {
                lobbyNextCheck = now + LOBBY_CHECK_MS
                if (checkLobby(scr, now)) return
            }
            if (now < lobbyQuietUntil) return

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
    private fun checkLobby(scr: Mat, now: Long): Boolean {
        for ((lobby, lobbyMode) in listOf(
            PcDefaults.LOBBY_NORMAL to GameMode.NORMAL,
            PcDefaults.LOBBY_INVASION to GameMode.INVASION,
            PcDefaults.LOBBY_COMMON to null
        )) {
            val files = listOf("$lobby.png") + templates.extraShots(lobby)
            var best = 0.0
            for (fn in files) {
                val t = templates.get(fn) ?: continue
                val r = matcher.multiScale(scr, t, PcDefaults.LOBBY_THRESHOLD, downscale = downscale)
                if (r.score > best) best = r.score
                if (best >= PcDefaults.LOBBY_THRESHOLD) break
            }
            if (best < PcDefaults.LOBBY_THRESHOLD) continue

            val newMode = lobbyMode ?: session.mode
            val modeChanged = newMode != session.mode
            if (modeChanged) {
                session.mode = newMode
                streak.clear()
                Prefs.setMode(this, newMode.key)
                updateNotification("감시 중 · ${newMode.label} (${PcDefaults.speedLabel(newMode, Prefs.speedLock(this))})")
            }
            if (session.activationOrder.isNotEmpty()) {
                Bus.log(if (lobbyMode == null)
                    "🏠 [$lobby 인식 → ${newMode.label} 모드 유지] 보스 사진을 초기화합니다. (정확도 ${pct(best)})"
                else
                    "🏠 [$lobby 귀환 → ${newMode.label} 모드] 보스 사진을 초기화합니다. (정확도 ${pct(best)})")
                endGame()
                lobbyQuietUntil = now + LOBBY_QUIET_MS
            } else if (modeChanged) {
                Bus.log("🏠 [$lobby 인식] ${newMode.label} 모드로 바꿨습니다.")
                Bus.onState(session, seq)
            }
            return true
        }
        return false
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
        scheduleWarning(name, session.mode)

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

        const val ACTION_EXIT = "tv.gapja.monitor.EXIT"

        /** 로비 검사 간격 (PC 는 0.5초마다) */
        private const val LOBBY_CHECK_MS = 600L
        /** 로비로 초기화한 뒤 쉬는 시간 (PC: 2초) */
        private const val LOBBY_QUIET_MS = 2000L
        /** 배속 검사 간격 */
        private const val SPEED_CHECK_MS = 600L
        /** 경보 타이머 간격 (PC: 0.5초) */
        private const val WARN_TICK_MS = 500L

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
