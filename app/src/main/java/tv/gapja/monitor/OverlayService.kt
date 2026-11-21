package tv.gapja.monitor

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import tv.gapja.monitor.core.BossCatalog
import tv.gapja.monitor.core.GameMode
import kotlin.math.max
import kotlin.math.min

/**
 * 게임 화면 위에 보스 사진을 띄운다. (PC판 OBS 크로마키 창을 대신한다)
 *
 *   • 인식된 순서대로 동그란 보스 사진만 보이고, 주변은 투명해서 게임 화면이 그대로 보인다
 *   • 가로 / 세로 배치를 고를 수 있다
 *   • 보스가 늘어나면 사진을 자동으로 줄여서 정해 둔 길이 안에 전부 보이게 한다
 *   • 침공 특수보스 사진만 숨기거나, 보스 사진 전체를 숨길 수 있다 (음성은 그대로)
 *   • [조절] 을 누르면 사진 줄을 끌어서 옮기고, 끝의 점을 끌어서 길이를 바꾼다
 *   • [캡처] 를 누르면 지금 게임 화면을 찍어서 보스 사진을 잘라 등록할 수 있다
 *
 * ⚠️ 사진 줄 터치 — 두 가지 중 고를 수 있다 (조절 메뉴 · 메인 화면)
 *    • 기본: 언제든 사진 줄을 손가락으로 끌어서 옮길 수 있다.
 *      대신 사진이 놓인 자리는 게임 터치가 막힌다. (사진 사이 빈칸 포함, 줄 크기만큼)
 *    • 터치 통과: 사진 줄이 터치를 받지 않아(FLAG_NOT_TOUCHABLE) 사진 위를 눌러도 게임이 눌린다.
 *      Android 12부터는 덮인 창의 불투명도가 0.8 이하여야 터치가 아래로 전달되므로
 *      이때는 창 투명도를 0.8 로 둔다. 위치는 [조절] 에서 옮긴다.
 *
 * ⚠️ '다른 앱 위에 표시' 권한이 필요하다. 없으면 조용히 아무것도 하지 않는다.
 */
class OverlayService : Service() {

    private var wm: WindowManager? = null
    private val main = Handler(Looper.getMainLooper())

    private var strip: BossStripView? = null
    private var stripLp: WindowManager.LayoutParams? = null

    private var chip: LinearLayout? = null
    private var chipLp: WindowManager.LayoutParams? = null

    private var panel: LinearLayout? = null
    private var panelLp: WindowManager.LayoutParams? = null

    private var editing = false

    private val listener: () -> Unit = { render() }

    /** 뷰를 만들 때 쓰는 화면 — 게임이 다른 화면(뮤뮤 탭)에 있으면 그 화면용 */
    private var uiCtx: Context = this
    /** 접근성 창으로 띄우는 중인지 (다른 화면에도 띄울 수 있고, '다른 앱 위에 표시' 권한이 필요 없다) */
    private var useA11yWin = false
    /** 지금 오버레이가 붙어 있는 화면 번호 */
    private var shownDisplay = -1

    /** 창 붙이기에 실패했는지 (실패하면 다른 방법으로 다시 시도) */
    private var addFailed = false
    private var lastAddError = ""

    override fun onCreate() {
        super.onCreate()
        if (!Prefs.overlayEnabled(this)) { stopSelf(); return }
        if (!attachTo(Bus.gameDisplay)) {
            Bus.log("⚠️ 화면 위에 띄울 방법이 없습니다. 메인 화면에서 '접근성' 을 켜거나 " +
                    "'다른 앱 위에 표시' 를 허용해 주세요.")
            stopSelf(); return
        }
        Bus.addChangeListener(listener)
        Bus.onCropOpened = { hideLoading() }
        // 게임이 다른 화면(뮤뮤 탭)으로 옮겨 가면 오버레이도 따라간다
        Bus.onGameDisplay = { d -> moveToDisplay(d) }
        render()
    }

    /**
     * 어느 화면에 어떤 종류의 창으로 띄울지 정한다.
     *   • 접근성이 켜져 있으면: 접근성 창(TYPE_ACCESSIBILITY_OVERLAY) 으로 게임이 있는 화면에 띄운다.
     *     뮤뮤처럼 탭마다 화면이 따로인 환경에서도 게임 탭 위에 보인다.
     *   • 아니면: 일반 오버레이(다른 앱 위에 표시) 로 기본 화면에 띄운다.
     * @return 띄울 수 있으면 true
     */
    private fun pickWindowManager(display: Int, allowA11y: Boolean = true, allowAppOnDisplay: Boolean = true): Boolean {
        val svc = BossA11yService.instance
        if (allowA11y && svc != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val dm = svc.getSystemService(android.hardware.display.DisplayManager::class.java)
                val disp = dm.getDisplay(display) ?: dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                val wctx = svc.createDisplayContext(disp)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
                uiCtx = wctx
                wm = wctx.getSystemService(WindowManager::class.java)
                useA11yWin = true
                shownDisplay = disp.displayId
                return true
            } catch (e: Exception) {
                Bus.log("⚠️ 화면 #$display 에 띄우지 못해 기본 화면에 띄웁니다. (${e.javaClass.simpleName})")
            }
        }
        if (!canDraw(this)) return false
        // 일반 오버레이를 게임 화면에 (접근성 창이 막혔을 때의 예비 방법)
        if (allowAppOnDisplay && display != android.view.Display.DEFAULT_DISPLAY &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
                val disp = dm.getDisplay(display)
                if (disp != null) {
                    val wctx = createDisplayContext(disp)
                        .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                    uiCtx = wctx
                    wm = wctx.getSystemService(WindowManager::class.java)
                    useA11yWin = false
                    shownDisplay = display
                    return true
                }
            } catch (_: Exception) {}
        }
        uiCtx = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        useA11yWin = false
        shownDisplay = android.view.Display.DEFAULT_DISPLAY
        return true
    }

    /**
     * 화면에 사진 줄·버튼을 붙인다. 안 되면 다음 방법으로 차례로 시도한다.
     *   ① 접근성 창 → 게임 화면   ② 일반 오버레이 → 게임 화면   ③ 일반 오버레이 → 기본 화면
     */
    private fun attachTo(display: Int): Boolean {
        val tries = listOf(
            Triple("접근성 창", true, true),
            Triple("일반 오버레이(게임 화면)", false, true),
            Triple("일반 오버레이(기본 화면)", false, false)
        )
        for ((name, a11y, appOnDisplay) in tries) {
            removeAllWindows()
            if (!pickWindowManager(display, allowA11y = a11y, allowAppOnDisplay = appOnDisplay)) continue
            addFailed = false; lastAddError = ""
            chipScaleApplied = -1
            buildStrip()
            buildChip()
            if (!addFailed) {
                Bus.overlayDisplay = shownDisplay
                if (display != android.view.Display.DEFAULT_DISPLAY || shownDisplay != display)
                    Bus.log("🪟 보스 사진·[보스설정] → 화면 #$shownDisplay ($name)" +
                            if (shownDisplay != display) " — 게임 화면 #$display 에는 붙지 않았습니다" else "")
                render()
                return true
            }
            Bus.log("⚠️ [$name] 화면 #$shownDisplay 에 붙이기 실패: $lastAddError")
        }
        return false
    }

    /** 붙어 있는 창을 모두 뗀다 */
    private fun removeAllWindows() {
        if (editing) { editing = false; hidePanel() }
        hideLoading()
        try { strip?.let { wm?.removeView(it) } } catch (_: Exception) {}
        try { chip?.let { wm?.removeView(it) } } catch (_: Exception) {}
        strip = null; chip = null
    }

    /** 게임이 있는 화면으로 오버레이를 옮긴다 (창을 떼었다가 그 화면에 다시 붙인다) */
    private fun moveToDisplay(display: Int) {
        if (display == shownDisplay) return
        if (!attachTo(display)) stopSelf()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun overlayType(): Int = when {
        useA11yWin -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else -> @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
    }

    private fun screenSize(): Pair<Int, Int> {
        val w = wm ?: return resources.displayMetrics.widthPixels to resources.displayMetrics.heightPixels
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = w.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            resources.displayMetrics.widthPixels to resources.displayMetrics.heightPixels
        }
    }

    // ── 보스 사진 줄 ─────────────────────────────────────────────

    private val normalFlags =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private val editFlags =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    @SuppressLint("ClickableViewAccessibility")
    private fun buildStrip() {
        val v = BossStripView(uiCtx)
        strip = v
        stripLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(), stripFlags(), PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = Prefs.overlayX(this@OverlayService)
            y = Prefs.overlayY(this@OverlayService)
            alpha = stripAlpha()
        }

        // 끌어서 이동 (언제든) / 조절 모드에서는 테두리의 주황 점 8개로 길이·크기 조절
        var handle = 0                     // 0 이면 이동
        var sx = 0f; var sy = 0f
        var ox = 0; var oy = 0
        var startLen = 0
        var startD = 0
        var lastAt = 0L
        v.setOnTouchListener { _, ev ->
            val lp = stripLp ?: return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = ev.rawX; sy = ev.rawY; ox = lp.x; oy = lp.y
                    startLen = v.currentLengthPx()
                    startD = v.d
                    handle = v.hitHandle(ev.x, ev.y)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - sx).toInt(); val dy = (ev.rawY - sy).toInt()
                    if (handle == 0) {
                        lp.x = ox + dx; lp.y = oy + dy
                        try { wm?.updateViewLayout(v, lp) } catch (_: Exception) {}
                    } else {
                        // 너무 자주 다시 그리지 않게 (마우스로 끌 때 버벅임 방지)
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastAt < 16) return@setOnTouchListener true
                        lastAt = now
                        resizeStrip(v, lp, handle, dx, dy, ox, oy, startLen, startD)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    Prefs.setOverlayPos(this, lp.x, lp.y)
                    handle = 0
                    true
                }
                else -> false
            }
        }

        try {
            wm?.addView(v, stripLp)
        } catch (e: Exception) {
            // 여기서 서비스를 끝내지 않는다 — 다른 방법(다른 창 종류·기본 화면)으로 다시 시도한다
            addFailed = true
            lastAddError = "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /**
     * 조절 점을 끌 때 — 줄 방향 끝은 길이, 직각 방향 옆은 사진 크기를 바꾼다.
     * 왼쪽·위쪽 점을 끌면 반대편이 제자리에 있도록 창 위치도 같이 옮긴다.
     */
    private fun resizeStrip(v: BossStripView, lp: WindowManager.LayoutParams, h: Int,
                            dx: Int, dy: Int, ox: Int, oy: Int, startLen: Int, startD: Int) {
        val vertical = Prefs.overlayVertical(this)
        val dens = resources.displayMetrics.density
        // 줄 방향(길이)과 직각 방향(크기)으로 나눈다
        val lenEnd = if (vertical) BossStripView.H_BOTTOM else BossStripView.H_RIGHT
        val lenStart = if (vertical) BossStripView.H_TOP else BossStripView.H_LEFT
        val sizeEnd = if (vertical) BossStripView.H_RIGHT else BossStripView.H_BOTTOM
        val sizeStart = if (vertical) BossStripView.H_LEFT else BossStripView.H_TOP
        val along = if (vertical) dy else dx
        val across = if (vertical) dx else dy

        var newX = ox; var newY = oy
        var len = startLen
        if ((h and lenEnd) != 0) len = startLen + along
        if ((h and lenStart) != 0) {
            len = startLen - along
            if (vertical) newY = oy + along else newX = ox + along
        }
        var dd = startD
        if ((h and sizeEnd) != 0) dd = startD + across
        if ((h and sizeStart) != 0) {
            dd = startD - across
            if (vertical) newX = ox + across else newY = oy + across
        }
        dd = dd.coerceIn(dp(20), dp(160))
        len = max(dp(60), len)
        if ((h and (sizeEnd or sizeStart)) != 0) {
            Prefs.setOverlayMaxDp(this, (dd / dens).toInt())
            // 사진을 키웠는데 줄 길이 때문에 자동 축소되면 안 커지므로, 길이도 같이 늘린다
            if ((h and (lenEnd or lenStart)) == 0) len = max(len, v.lengthFor(dd))
        }
        Prefs.setOverlayLengthDp(this, (len / dens).toInt())
        lp.x = newX; lp.y = newY
        render()
    }

    // ── [보스설정] 버튼 (누르면 캡처 · 조절 · 리셋 · 끄기) ─────────────

    private var chipMain: TextView? = null
    private var chipMenu: LinearLayout? = null
    private val chipMenuBtns = ArrayList<TextView>()
    private var chipScaleApplied = -1

    /** 1분 동안 안 쓰면 버튼을 흐리게 해서 게임 중에 덜 거슬리게 한다 */
    private val fadeRunnable = Runnable { chip?.animate()?.alpha(IDLE_ALPHA)?.setDuration(600)?.start() }

    private fun wakeChip() {
        chip?.animate()?.cancel()
        chip?.alpha = 1f
        main.removeCallbacks(fadeRunnable)
        main.postDelayed(fadeRunnable, IDLE_FADE_MS)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildChip() {
        val box = LinearLayout(uiCtx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val mainBtn = TextView(uiCtx).apply {
            text = "보스설정"
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#B3000000"))
                setStroke(dp(1), Color.parseColor("#801ABC9C"))
            }
        }
        chipMain = mainBtn
        box.addView(mainBtn)

        val menu = LinearLayout(uiCtx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(4), 0, 0)
        }
        fun menuBtn(text: String, color: String, action: () -> Unit) = TextView(uiCtx).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor(color))
            }
            setOnClickListener {
                wakeChip()
                menu.visibility = View.GONE
                action()
            }
        }
        val startBtn = menuBtn("시작", "#E616A085") { startFromOverlay() }
        val pauseBtn = menuBtn("일시정지", "#E67F8C8D") { togglePause() }
        val capBtn = menuBtn("캡처", "#E62980B9") { takeSnapshot() }
        val resetBtn = menuBtn("리셋", "#E6D35400") { resetBosses() }
        menuStart = startBtn; menuPause = pauseBtn; menuCapture = capBtn; menuReset = resetBtn
        val items = listOf(
            startBtn, pauseBtn, capBtn,
            menuBtn("조절", "#E616A085") { setEditing(!editing) },
            resetBtn,
            menuBtn("끄기", "#E6C0392B") { exitApp() }
        )
        chipMenuBtns.clear()
        for (b in items) {
            chipMenuBtns.add(b)
            menu.addView(b, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(3) })
        }
        chipMenu = menu
        box.addView(menu, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        chip = box
        applyChipScale()

        val (sw, _) = screenSize()
        chipLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = Prefs.chipX(this@OverlayService, sw - dp(110))
            y = Prefs.chipY(this@OverlayService, dp(64))
        }

        // [보스설정] 버튼: 짧게 누르면 메뉴 열기/닫기, 끌면 위치 이동
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        mainBtn.setOnTouchListener { _, ev ->
            val lp = chipLp ?: return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    wakeChip()
                    sx = ev.rawX; sy = ev.rawY; ox = lp.x; oy = lp.y; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(ev.rawX - sx) + kotlin.math.abs(ev.rawY - sy) > dp(10)) moved = true
                    if (moved) {
                        lp.x = ox + (ev.rawX - sx).toInt(); lp.y = oy + (ev.rawY - sy).toInt()
                        try { wm?.updateViewLayout(box, lp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) Prefs.setChipPos(this, lp.x, lp.y)
                    else {
                        refreshMenu()
                        menu.visibility = if (menu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    }
                    wakeChip()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        try { wm?.addView(box, chipLp) } catch (e: Exception) {
            addFailed = true
            lastAddError = "${e.javaClass.simpleName}: ${e.message}"
        }
        wakeChip()
    }

    private var menuStart: TextView? = null
    private var menuPause: TextView? = null
    private var menuCapture: TextView? = null
    private var menuReset: TextView? = null

    /**
     * 감시 상태에 맞게 메뉴를 바꾼다.
     *   감시 중  → 일시정지(재개) · 캡처 · 조절 · 리셋 · 끄기
     *   멈춤     → 시작 · 조절 · 끄기
     */
    private fun refreshMenu() {
        val run = Bus.running
        menuStart?.visibility = if (run) View.GONE else View.VISIBLE
        menuPause?.visibility = if (run) View.VISIBLE else View.GONE
        menuCapture?.visibility = if (run) View.VISIBLE else View.GONE
        menuReset?.visibility = if (run) View.VISIBLE else View.GONE
        menuPause?.text = if (Bus.paused) "재개" else "일시정지"
        chipMain?.text = when {
            !run -> "보스설정 · 멈춤"
            Bus.paused -> "보스설정 · ⏸"
            else -> "보스설정"
        }
    }

    /** [시작] 게임 화면에서 바로 감시를 시작한다 (화면 공유 동의 창만 한 번 뜬다) */
    private fun startFromOverlay() {
        if (Prefs.useA11y(this) && BossA11yService.instance != null) {
            // 접근성 방식 — 확인창 없이 바로 시작
            CaptureService.startA11y(this, tv.gapja.monitor.core.GameMode.from(Prefs.mode(this)),
                Prefs.downscale(this), Prefs.intervalMs(this))
            Toast.makeText(this, "감시를 시작했습니다", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(this, ConsentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        } catch (e: Exception) {
            Bus.log("⚠️ 시작 화면을 열지 못했습니다: ${e.message}")
        }
    }

    /** [일시정지]/[재개] 화면 공유는 그대로 두고 인식만 멈췄다 켠다 */
    private fun togglePause() {
        val pausing = !Bus.paused
        CaptureService.send(this, if (pausing) CaptureService.ACTION_PAUSE else CaptureService.ACTION_RESUME)
        Bus.paused = pausing
        refreshMenu()
        Toast.makeText(this, if (pausing) "인식을 잠시 멈췄습니다" else "인식을 다시 시작합니다", Toast.LENGTH_SHORT).show()
    }

    /** 버튼 크기를 설정값에 맞춘다 (보스설정 → 조절 → 버튼 크기) */
    private fun applyChipScale() {
        val pct = Prefs.buttonScale(this)
        if (pct == chipScaleApplied) return
        chipScaleApplied = pct
        val k = pct / 100f
        chipMain?.apply {
            textSize = 13f * k
            setPadding((dp(12) * k).toInt(), (dp(7) * k).toInt(), (dp(12) * k).toInt(), (dp(7) * k).toInt())
        }
        for (b in chipMenuBtns) {
            b.textSize = 13f * k
            b.setPadding((dp(10) * k).toInt(), (dp(8) * k).toInt(), (dp(10) * k).toInt(), (dp(8) * k).toInt())
        }
        chip?.let { v -> chipLp?.let { lp -> try { wm?.updateViewLayout(v, lp) } catch (_: Exception) {} } }
    }

    /** [리셋] 지금 판의 보스 사진을 모두 지운다 (침공 판은 순서가 기록된다) */
    private fun resetBosses() {
        if (!Bus.running) {
            Toast.makeText(this, "감시 중이 아닙니다", Toast.LENGTH_SHORT).show(); return
        }
        CaptureService.send(this, CaptureService.ACTION_RESET)
        Toast.makeText(this, "보스 사진을 초기화했습니다", Toast.LENGTH_SHORT).show()
    }

    /** [끄기] 감시와 게임 위 표시를 멈추고 앱을 닫는다 */
    private fun exitApp() {
        if (editing) setEditing(false)
        Toast.makeText(this, "운빨보스알림을 종료합니다", Toast.LENGTH_SHORT).show()
        if (Bus.running) {
            CaptureService.send(this, CaptureService.ACTION_EXIT)
        } else {
            Bus.exitApp()
            stopSelf()
        }
    }

    // ── 조절 모드 ────────────────────────────────────────────────

    /** 사진 줄 창의 터치 설정 — 조절 중이거나 '터치 통과' 를 껐으면 터치를 받는다 */
    private fun stripFlags(): Int =
        if (editing || !Prefs.stripPassThrough(this)) editFlags else normalFlags

    private fun stripAlpha(): Float =
        if (editing || !Prefs.stripPassThrough(this)) 1f else 0.8f

    /** 설정이 바뀌었으면 사진 줄 창에 반영한다 */
    private fun applyStripTouchMode() {
        val lp = stripLp ?: return
        val f = stripFlags(); val a = stripAlpha()
        if (lp.flags == f && lp.alpha == a) return
        lp.flags = f; lp.alpha = a
        try { wm?.updateViewLayout(strip, lp) } catch (_: Exception) {}
    }

    private fun setEditing(on: Boolean) {
        editing = on
        applyStripTouchMode()
        if (on) showPanel() else hidePanel()
        render()
    }

    private fun showPanel() {
        if (panel != null) return
        val box = LinearLayout(uiCtx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#F0101A24"))
                setStroke(dp(1), Color.parseColor("#661ABC9C"))
            }
        }
        fun label(t: String) = TextView(uiCtx).apply {
            text = t; setTextColor(Color.WHITE); textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        }
        box.addView(TextView(uiCtx).apply {
            text = "사진 줄을 끌면 이동 · 테두리 주황 점(상하좌우·모서리)을 끌면 길이·크기 조절"
            setTextColor(Color.parseColor("#F39C12")); textSize = 12f
        })

        // 배치
        val row = LinearLayout(uiCtx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(label("배치  "))
        val hBtn = Button(uiCtx); val vBtn = Button(uiCtx)
        fun syncLayoutBtns() {
            val vert = Prefs.overlayVertical(this)
            vBtn.setBackgroundColor(Color.parseColor(if (vert) "#1ABC9C" else "#3C4B5A"))
            hBtn.setBackgroundColor(Color.parseColor(if (!vert) "#1ABC9C" else "#3C4B5A"))
        }
        for ((b, t, vert) in listOf(Triple(hBtn, "가로", false), Triple(vBtn, "세로", true))) {
            b.text = t; b.setTextColor(Color.WHITE); b.isAllCaps = false; b.textSize = 13f
            b.setOnClickListener {
                Prefs.setOverlayVertical(this, vert)
                Prefs.setOverlayLengthDp(this, 0)   // 방향이 바뀌면 길이는 화면 끝까지로 되돌린다
                syncLayoutBtns(); render()
            }
            row.addView(b, LinearLayout.LayoutParams(0, dp(40), 1f).also { it.setMargins(dp(3), 0, dp(3), 0) })
        }
        syncLayoutBtns()
        box.addView(row)

        // 최대 크기
        val sizeLabel = label("")
        fun syncSize() { sizeLabel.text = "사진 최대 크기: ${Prefs.overlayMaxDp(this)}dp" }
        syncSize()
        box.addView(sizeLabel)
        box.addView(SeekBar(uiCtx).apply {
            setMax(160 - 20)
            setProgress(Prefs.overlayMaxDp(this@OverlayService) - 20)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    if (!u) return
                    Prefs.setOverlayMaxDp(this@OverlayService, p + 20); syncSize(); render()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        fun check(t: String, get: () -> Boolean, set: (Boolean) -> Unit) = CheckBox(uiCtx).apply {
            text = t; setTextColor(Color.WHITE); textSize = 13f
            isChecked = get()
            setOnCheckedChangeListener { _, v -> set(v); render() }
        }
        box.addView(check("보스 사진 전체 표시", { Prefs.overlayShowAll(this) }) { Prefs.setOverlayShowAll(this, it) })
        box.addView(check("침공 특수보스 사진 표시", { Prefs.showSpecial(this) }) { Prefs.setShowSpecial(this, it) })
        box.addView(check("자동 축소 (보스가 늘면 줄여서 다 보이게)", { Prefs.autoShrink(this) }) { Prefs.setAutoShrink(this, it) })
        box.addView(check("사진 터치 통과 (사진 위도 게임이 눌림 · 대신 이동은 여기서만)", { Prefs.stripPassThrough(this) }) {
            Prefs.setStripPassThrough(this, it)
        })

        // [보스설정] 버튼 크기
        val btnLabel = label("")
        fun syncBtn() { btnLabel.text = "보스설정 버튼 크기: ${Prefs.buttonScale(this)}%" }
        syncBtn()
        box.addView(btnLabel)
        box.addView(SeekBar(uiCtx).apply {
            setMax(200 - 60)
            setProgress(Prefs.buttonScale(this@OverlayService) - 60)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    if (!u) return
                    Prefs.setButtonScale(this@OverlayService, p + 60); syncBtn(); applyChipScale()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        val done = Button(uiCtx).apply {
            text = "완료 (저장)"; setTextColor(Color.WHITE); isAllCaps = false
            setBackgroundColor(Color.parseColor("#16A085"))
            setOnClickListener { setEditing(false) }
        }
        box.addView(done, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).also { it.topMargin = dp(6) })

        val (sw, _) = screenSize()
        panelLp = WindowManager.LayoutParams(
            min(sw - dp(24), dp(360)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 보스 사진 줄이 화면 아래쪽에 있으면 설정 창은 위에, 위쪽에 있으면 아래에 띄운다
            val (_, sh) = screenSize()
            val stripMid = (stripLp?.y ?: 0) + (strip?.height ?: 0) / 2
            gravity = (if (stripMid > sh / 2) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
            y = dp(24)
        }
        panel = box
        try { wm?.addView(box, panelLp) } catch (_: Exception) { panel = null }
        bringStripToFront()
    }

    /**
     * 사진 줄을 설정 창보다 위로 올린다.
     * 오버레이 창은 나중에 붙인 것이 위에 그려지므로, 사진 줄을 떼었다가 다시 붙인다.
     */
    private fun bringStripToFront() {
        val v = strip ?: return
        val lp = stripLp ?: return
        try { wm?.removeView(v); wm?.addView(v, lp) } catch (_: Exception) {}
        chip?.let { c -> chipLp?.let { clp -> try { wm?.removeView(c); wm?.addView(c, clp) } catch (_: Exception) {} } }
    }

    private fun hidePanel() {
        val p = panel ?: return
        try { wm?.removeView(p) } catch (_: Exception) {}
        panel = null
    }

    // ── 캡처 ─────────────────────────────────────────────────────

    /**
     * 지금 게임 화면을 찍어서 자르기 화면을 연다.
     * 사진 줄과 버튼이 찍히지 않도록 잠깐 숨겼다가 찍는다.
     */
    private fun takeSnapshot() {
        if (!Bus.running) {
            Toast.makeText(this, "먼저 메인 화면에서 '시작'을 눌러 주세요", Toast.LENGTH_SHORT).show()
            return
        }
        if (editing) setEditing(false)
        strip?.visibility = View.INVISIBLE
        chip?.visibility = View.INVISIBLE
        main.postDelayed({
            CaptureService.requestSnapshot { bmp ->
                main.post {
                    strip?.visibility = View.VISIBLE
                    chip?.visibility = View.VISIBLE
                    render()
                    if (bmp == null) {
                        Toast.makeText(this, "화면을 찍지 못했습니다. 다시 눌러 주세요", Toast.LENGTH_SHORT).show()
                    } else {
                        // 화면은 이미 찍혔으니 이제 안내를 띄워도 사진에 안 들어간다
                        showLoading("캡처 준비 중…")
                        try {
                            CropActivity.pendingBitmap = bmp
                            val it = Intent(this, CropActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                .putExtra(CropActivity.EXTRA_FROM_MEMORY, true)
                                .putExtra(CropActivity.EXTRA_MODE, Bus.mode.key)
                            // 게임이 다른 화면(뮤뮤 탭)에 있으면 자르기 화면도 그 화면에 연다
                            val opened = if (shownDisplay > 0) try {
                                startActivity(it, android.app.ActivityOptions.makeBasic()
                                    .setLaunchDisplayId(shownDisplay).toBundle()); true
                            } catch (_: Exception) { false } else false
                            if (!opened) startActivity(it)
                        } catch (e: Exception) {
                            hideLoading()
                            Bus.log("⚠️ 자르기 화면을 열지 못했습니다: ${e.message}")
                        }
                    }
                }
            }
        }, 250)
    }

    // ── '캡처 준비 중…' 안내 (화면 가운데) ───────────────────────

    private var loading: View? = null
    private val hideLoadingRunnable = Runnable { hideLoading() }

    private fun showLoading(text: String) {
        hideLoading()
        val box = LinearLayout(uiCtx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(22), dp(28), dp(22))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#E6101A24"))
                setStroke(dp(1), Color.parseColor("#991ABC9C"))
            }
        }
        box.addView(android.widget.ProgressBar(uiCtx).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1ABC9C"))
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        box.addView(TextView(uiCtx).apply {
            this.text = text
            setTextColor(Color.WHITE); textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        })
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        try { wm?.addView(box, lp); loading = box } catch (_: Exception) {}
        box.alpha = 0f
        box.animate().alpha(1f).setDuration(150).start()
        // 혹시 자르기 화면이 안 열려도 5초 뒤에는 닫는다
        main.removeCallbacks(hideLoadingRunnable)
        main.postDelayed(hideLoadingRunnable, 5000)
    }

    private fun hideLoading() {
        main.removeCallbacks(hideLoadingRunnable)
        loading?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        loading = null
    }

    // ── 그리기 ───────────────────────────────────────────────────

    private fun render() {
        applyChipScale()
        applyStripTouchMode()
        refreshMenu()
        val v = strip ?: return
        val lp = stripLp ?: return
        val showAll = Prefs.overlayShowAll(this)
        val showSpecial = Prefs.showSpecial(this)

        // 조절 중인데 아직 보스가 없으면 크기를 맞춰볼 수 있게 예시 11장을 보여준다
        val src = if (editing && Bus.activated.isEmpty()) PREVIEW else Bus.activated

        val items = ArrayList<BossStripView.Item>()
        for (b in src) {
            val sp = BossCatalog.isSpecial(b)
            if (sp && !showSpecial) continue
            items.add(BossStripView.Item(b, sp))
        }

        val vertical = Prefs.overlayVertical(this)
        val (sw, sh) = screenSize()
        val lenDp = Prefs.overlayLengthDp(this)
        val avail = if (lenDp > 0) dp(lenDp) else {
            if (vertical) sh - max(0, lp.y) - dp(24) else sw - max(0, lp.x) - dp(8)
        }
        v.update(
            items = if (showAll || editing) items else emptyList(),
            vertical = vertical,
            maxD = dp(Prefs.overlayMaxDp(this)),
            lengthPx = max(dp(60), avail),
            shrink = Prefs.autoShrink(this),
            editing = editing,
            highlightLast = !editing && Bus.activated.isNotEmpty()
        )
        v.visibility = if (items.isEmpty() && !editing) View.GONE else View.VISIBLE
        try { wm?.updateViewLayout(v, lp) } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 태블릿을 돌리면 화면 밖으로 나간 사진 줄·버튼을 안으로 들여온다 */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val (sw, sh) = screenSize()
        stripLp?.let { lp ->
            lp.x = lp.x.coerceIn(0, max(0, sw - dp(60))); lp.y = lp.y.coerceIn(0, max(0, sh - dp(60)))
        }
        chipLp?.let { lp ->
            lp.x = lp.x.coerceIn(0, max(0, sw - dp(80))); lp.y = lp.y.coerceIn(0, max(0, sh - dp(40)))
            chip?.let { v -> try { wm?.updateViewLayout(v, lp) } catch (_: Exception) {} }
        }
        render()
    }

    override fun onDestroy() {
        main.removeCallbacks(fadeRunnable)
        hideLoading()
        Bus.onCropOpened = null
        Bus.onGameDisplay = null
        Bus.removeChangeListener(listener)
        hidePanel()
        try { if (strip != null) wm?.removeView(strip) } catch (_: Exception) {}
        try { if (chip != null) wm?.removeView(chip) } catch (_: Exception) {}
        strip = null; chip = null
        super.onDestroy()
    }

    companion object {
        /** 이 시간 동안 안 쓰면 [보스설정] 버튼을 흐리게 한다 */
        private const val IDLE_FADE_MS = 60_000L
        private const val IDLE_ALPHA = 0.3f

        /** 조절 화면에서 크기를 맞춰볼 때 쓰는 예시 (판 끝까지 간 경우 11장) */
        private val PREVIEW = listOf(
            "그런트", "윔", "거대 전기로봇", "닥터", "샤먼", "악마 모노폴리",
            "매지션", "콜디스트", "사신", "쿨타임", "쌍둥이 슬라임"
        )

        fun canDraw(ctx: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)

        fun start(ctx: Context) {
            if (!Prefs.overlayEnabled(ctx) || !canDraw(ctx)) return
            try { ctx.startService(Intent(ctx, OverlayService::class.java)) } catch (_: Exception) {}
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, OverlayService::class.java)) } catch (_: Exception) {}
        }
    }
}

/**
 * 동그란 보스 사진을 한 줄로 그리는 뷰. 배경은 완전히 투명하다.
 * 라운드 글자 없이 보스 사진만, 사진끼리 거의 붙여서 그린다.
 *
 * 조절 모드에서는 테두리에 주황 점 8개(네 모서리 + 네 변 가운데)가 생긴다.
 *   • 줄 방향의 양 끝 점 → 줄 길이 (사진이 많을 때 자동 축소되는 길이)
 *   • 줄과 직각인 양 옆 점 → 사진 크기
 *   • 모서리 점 → 둘 다
 */
class BossStripView(ctx: Context) : View(ctx) {

    data class Item(val boss: String, val special: Boolean)

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private var items: List<Item> = emptyList()
    private var vertical = true
    private var maxD = dp(56f).toInt()
    private var lengthPx = dp(600f).toInt()
    private var shrink = true
    private var editing = false
    private var highlightLast = false

    /** 지금 그리는 사진 지름(px) */
    var d = maxD
        private set
    /** 사진 사이 간격 — 최대한 붙인다 */
    private val gap get() = dp(1f).toInt()
    /** 조절 중에는 주황 테두리·점 자리를 넉넉히, 평소에는 게임을 덜 가리게 좁게 */
    private val pad get() = dp(if (editing) 14f else 2f).toInt()

    private val circleCache = HashMap<String, Bitmap?>()
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val editPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.parseColor("#F39C12"); strokeWidth = dp(2.5f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F39C12") }
    private val handleRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = dp(1.5f)
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = dp(13f); typeface = Typeface.DEFAULT_BOLD
    }

    fun update(items: List<Item>, vertical: Boolean, maxD: Int, lengthPx: Int,
               shrink: Boolean, editing: Boolean, highlightLast: Boolean) {
        this.items = items; this.vertical = vertical; this.maxD = maxD
        this.lengthPx = lengthPx; this.shrink = shrink; this.editing = editing
        this.highlightLast = highlightLast
        d = computeD()
        requestLayout(); invalidate()
    }

    fun itemCount(): Int = items.size

    /** 보스 수와 길이에 맞춰 사진 지름을 정한다 */
    private fun computeD(): Int {
        val n = max(1, items.size)
        if (!shrink) return maxD
        val per = (lengthPx - (n - 1) * gap) / n
        return per.coerceIn(dp(16f).toInt(), maxD)
    }

    /** n 장을 지름 dd 로 늘어놓을 때 필요한 줄 길이(px) */
    fun lengthFor(dd: Int, n: Int = items.size): Int = max(1, n) * dd + (max(1, n) - 1) * gap

    /** 사진 줄의 실제 길이(px) — 길이 조절의 시작점 */
    fun currentLengthPx(): Int = if (vertical) height - pad * 2 else width - pad * 2

    // ── 조절 점 ──
    companion object {
        const val H_LEFT = 1
        const val H_RIGHT = 2
        const val H_TOP = 4
        const val H_BOTTOM = 8
    }

    private fun handlePoints(): List<Pair<Int, Pair<Float, Float>>> {
        val l = pad / 2f; val t = pad / 2f
        val r = width - pad / 2f; val b = height - pad / 2f
        val cx = width / 2f; val cy = height / 2f
        return listOf(
            (H_LEFT or H_TOP) to (l to t), H_TOP to (cx to t), (H_RIGHT or H_TOP) to (r to t),
            H_LEFT to (l to cy), H_RIGHT to (r to cy),
            (H_LEFT or H_BOTTOM) to (l to b), H_BOTTOM to (cx to b), (H_RIGHT or H_BOTTOM) to (r to b)
        )
    }

    /** 누른 곳이 어느 조절 점인지 (없으면 0) — 손가락·마우스로 잡기 쉽게 넉넉히 본다 */
    fun hitHandle(x: Float, y: Float): Int {
        if (!editing) return 0
        val r = dp(24f)
        var best = 0; var bestDist = Float.MAX_VALUE
        for ((code, p) in handlePoints()) {
            val dx = x - p.first; val dy = y - p.second
            val dist = dx * dx + dy * dy
            if (kotlin.math.abs(dx) < r && kotlin.math.abs(dy) < r && dist < bestDist) {
                best = code; bestDist = dist
            }
        }
        return best
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val n = items.size
        if (n == 0) {
            if (editing) setMeasuredDimension(dp(160f).toInt(), dp(48f).toInt())
            else setMeasuredDimension(1, 1)
            return
        }
        val len = lengthFor(d, n)
        if (vertical) setMeasuredDimension(d + pad * 2, len + pad * 2)
        else setMeasuredDimension(len + pad * 2, d + pad * 2)
    }

    override fun onDraw(canvas: Canvas) {
        if (items.isEmpty()) {
            if (editing) {
                canvas.drawRoundRect(RectF(2f, 2f, width - 2f, height - 2f), dp(10f), dp(10f), editPaint)
                canvas.drawText("보스 대기 중", dp(14f), height / 2f + dp(5f), emptyPaint)
            }
            return
        }
        for ((i, it) in items.withIndex()) {
            val off = i * (d + gap) + d / 2f
            val cx = if (vertical) pad + d / 2f else pad + off
            val cy = if (vertical) pad + off else pad + d / 2f
            circle(it.boss)?.let { bmp ->
                canvas.drawBitmap(bmp, null, RectF(cx - d / 2f, cy - d / 2f, cx + d / 2f, cy + d / 2f), null)
            }
            ringPaint.color = if (it.special) Color.parseColor("#E74C3C") else Color.WHITE
            ringPaint.strokeWidth = if (it.special) max(dp(2f), d * 0.06f) else max(dp(1.2f), d * 0.03f)
            canvas.drawCircle(cx, cy, d / 2f - ringPaint.strokeWidth / 2f, ringPaint)

            if (highlightLast && i == items.size - 1) {
                // 방금 인식된 보스 — 사진 테두리 위에 겹쳐 그린다 (바깥에 그리면 좁은 여백에서 잘린다)
                ringPaint.color = Color.parseColor("#F39C12")
                ringPaint.strokeWidth = max(dp(2.5f), d * 0.07f)
                canvas.drawCircle(cx, cy, d / 2f - ringPaint.strokeWidth / 2f, ringPaint)
            }
        }

        if (editing) {
            canvas.drawRoundRect(RectF(pad / 2f, pad / 2f, width - pad / 2f, height - pad / 2f),
                dp(10f), dp(10f), editPaint)
            for ((_, p) in handlePoints()) {
                canvas.drawCircle(p.first, p.second, dp(8f), handlePaint)
                canvas.drawCircle(p.first, p.second, dp(8f), handleRim)
            }
        }
    }

    /** 보스 사진을 동그랗게 잘라 둔다 (지름별로 캐시) */
    private fun circle(boss: String): Bitmap? {
        val key = "$boss@$d"
        if (circleCache.containsKey(key)) return circleCache[key]
        if (circleCache.size > 120) circleCache.clear()
        val f = AssetInstaller.displayFile(context, boss)
        val src = try { if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null } catch (_: Exception) { null }
        val size = max(8, d)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // 동그라미 안쪽 바탕 — 밝은 게임 화면 위에서도 보스가 잘 보이게
        p.shader = LinearGradient(0f, 0f, 0f, size.toFloat(),
            Color.parseColor("#FF463C50"), Color.parseColor("#FF282332"), Shader.TileMode.CLAMP)
        c.drawCircle(size / 2f, size / 2f, size / 2f, p)
        p.shader = null
        if (src != null) {
            val clip = android.graphics.Path().apply {
                addCircle(size / 2f, size / 2f, size / 2f, android.graphics.Path.Direction.CW)
            }
            c.save(); c.clipPath(clip)
            val s = size * 0.92f
            val scale = min(s / src.width, s / src.height)
            val w = src.width * scale; val h = src.height * scale
            val l = (size - w) / 2f; val t = (size - h) / 2f + size * 0.03f
            c.drawBitmap(src, Rect(0, 0, src.width, src.height), RectF(l, t, l + w, t + h),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            c.restore()
            src.recycle()
        } else {
            p.color = Color.WHITE; p.textAlign = Paint.Align.CENTER
            p.textSize = size * 0.32f; p.typeface = Typeface.DEFAULT_BOLD
            c.drawText(boss.take(2), size / 2f, size / 2f + p.textSize / 3f, p)
        }
        circleCache[key] = out
        return out
    }
}
