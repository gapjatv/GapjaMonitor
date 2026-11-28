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
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import tv.gapja.monitor.core.BossCatalog
import tv.gapja.monitor.core.GameMode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 게임 화면 위에 보스 사진을 띄운다. (PC판 OBS 크로마키 창을 대신한다)
 *
 *   • 인식된 순서대로 동그란 보스 사진만 보이고, 주변은 투명해서 게임 화면이 그대로 보인다
 *   • 가로 / 세로 배치를 고를 수 있다
 *   • 보스가 늘어나면 사진을 자동으로 줄여서 정해 둔 길이 안에 전부 보이게 한다
 *   • 침공 특수보스 사진만 숨기거나, 보스 사진 전체를 숨길 수 있다 (음성은 그대로)
 *   • [위치조절] 을 누르면 사진 줄을 끌어서 옮기고, 테두리의 점 8개를 끌어서 크기를 바꾼다
 *     (줄바꿈 없이 전체 크기만 부드럽게 바뀐다 · 모서리 점은 비율을 지키며 키운다)
 *   • [보스캡처] 를 누르면 지금 게임 화면을 찍어서 보스 사진을 잘라 등록할 수 있다
 *   • 침공에서 70R 보스가 나와 남은 후보가 4마리가 되면 그 4마리를 사진으로 잠깐 보여 준다
 *     (크기·위치는 [위치조절] 에서 자유롭게 · 가로/세로/2×2 배치가 부드럽게 바뀐다)
 *   • [음성만] 을 누르면 보스 사진·예상보스 4마리 사진을 숨기고 음성으로만 알린다
 *     (누른 뒤에는 버튼이 [사진표시] 로 바뀌고, 다시 누르면 사진이 보인다)
 *   • [위치조절] 설정 창의 '보스흐림설정' 막대로 보스 사진이 보이는 정도(10~100%)를 바꾼다
 *   • [홈앱이동] 은 메인 화면(앱)으로 돌아간다
 *
 * ⚠️ 사진 줄 터치 — 두 가지 중 고를 수 있다 (조절 메뉴 · 메인 화면)
 *    • 기본: 언제든 사진 줄을 손가락으로 끌어서 옮길 수 있다.
 *      대신 사진이 놓인 자리는 게임 터치가 막힌다. (사진 사이 빈칸 포함, 줄 크기만큼)
 *    • 터치 통과: 사진 줄이 터치를 받지 않아(FLAG_NOT_TOUCHABLE) 사진 위를 눌러도 게임이 눌린다.
 *      Android 12부터는 덮인 창의 불투명도가 0.8 이하여야 터치가 아래로 전달되므로
 *      이때는 창 투명도를 0.8 로 둔다. 위치는 [위치조절] 에서 옮긴다.
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

    private var panel: View? = null
    private var panelLp: WindowManager.LayoutParams? = null

    /** 남은 보스(80R 예상) 사진 창 */
    private var remain: RemainView? = null
    private var remainLp: WindowManager.LayoutParams? = null

    private var editing = false

    /** 인식위치 조절 화면 (v3.6) — 전체 화면 크기의 투명 창 */
    private var zoneView: ZoneEditView? = null
    private var zoneLp: WindowManager.LayoutParams? = null
    /** 완료를 눌러 천천히 사라지는 중인지 */
    private var zoneFading = false
    private var menuZone: TextView? = null

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
        Bus.zoneEditing = false
        if (!attachTo(Bus.gameDisplay)) {
            Bus.log("⚠️ 화면 위에 띄울 방법이 없습니다. 메인 화면에서 '접근성' 을 켜거나 " +
                    "'다른 앱 위에 표시' 를 허용해 주세요.")
            stopSelf(); return
        }
        Bus.addChangeListener(listener)
        Bus.onCropOpened = { hideLoading() }
        Bus.onNotice = { t -> showNotice(t) }
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
        if (zoneView != null) finishZoneEdit(fast = true, save = false)
        if (editing) { editing = false; hidePanel() }
        hideLoading()
        hideNotice()
        removeRemain()
        main.removeCallbacks(resizeTick); rsActive = false; rsTickPosted = false
        liveD = -1; liveLen = -1
        fadingPreview = false
        strip?.animate()?.cancel()
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
        wrapShift = 0
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

        // 끌어서 이동 (언제든) / 조절 모드에서는 테두리의 주황 점 8개로 크기 조절
        var handle = 0                     // 0 이면 이동
        var sx = 0f; var sy = 0f
        var ox = 0; var oy = 0
        v.setOnTouchListener { _, ev ->
            val lp = stripLp ?: return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = ev.rawX; sy = ev.rawY; ox = lp.x; oy = lp.y
                    handle = v.hitHandle(ev.x, ev.y)
                    if (handle != 0) beginResize(v, lp, handle)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - sx; val dy = ev.rawY - sy
                    if (handle == 0) {
                        lp.x = ox + dx.toInt(); lp.y = oy + dy.toInt()
                        try { wm?.updateViewLayout(v, lp) } catch (_: Exception) {}
                    } else {
                        dragResize(dx, dy)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (handle != 0) endResize() else saveStripPos(lp)
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
     * 조절 중에는 테두리·점 자리(여백)가 평소보다 12dp 넓다 (14dp ↔ 2dp).
     * 그래서 조절 모드를 켜고 끌 때 창을 이만큼 옮겨서, 사진이 눈에 보이는 자리는 그대로 두고
     * 저장할 때는 평소(여백 2dp) 기준 자리로 바꿔서 저장한다. (v3.0 — 저장하면 왼쪽으로 밀리던 버그 수정)
     */
    private fun editPadDelta(): Int = dp(14) - dp(2)

    /** 줄바꿈 때문에 옮겨 둔 만큼과 조절 모드 여백 차이를 빼고, '첫 줄' 자리를 저장한다 */
    private fun saveStripPos(lp: WindowManager.LayoutParams) {
        val e = if (editing) editPadDelta() else 0
        if (Prefs.overlayVertical(this)) Prefs.setOverlayPos(this, lp.x + wrapShift + e, lp.y + e)
        else Prefs.setOverlayPos(this, lp.x + e, lp.y + wrapShift + e)
    }

    // ── 사진 줄 크기 조절 (점 8개) ─────────────────────────────────
    //
    // 어느 점을 끌든 '줄바꿈 없이' 전체 크기만 바뀐다. (한 줄에 놓인 사진 수는 그대로)
    //   • 변 가운데 점  → 그 변이 손가락을 따라가도록 크기 변경
    //   • 모서리 점     → 가로·세로 비율을 지키며 대각선 방향으로 키우고 줄임
    // 반대편 모서리는 제자리에 고정된다. 손가락보다 살짝 느리게(RESIZE_DAMPING) 따라가고,
    // 화면에는 매 프레임 조금씩 다가가며(RESIZE_EASE) 그려서 뚝뚝 끊기지 않게 한다.

    private var rsActive = false          // 조절 중이거나, 손을 뗀 뒤 목표 크기까지 따라가는 중
    private var rsDragging = false        // 손가락이 닿아 있는 중
    private var rsTickPosted = false
    private var rsHandle = 0
    private var rsOx = 0; private var rsOy = 0
    private var rsW0 = 0; private var rsH0 = 0
    private var rsD0 = 0
    private var rsPer = 1
    private var rsCur = 0f                // 지금 그리는 사진 지름(px)
    private var rsTgt = 0f                // 목표 지름(px)

    /** 조절 중 화면에 그리는 사진 지름·줄 길이(px). -1 이면 저장된 설정값을 쓴다 */
    private var liveD = -1
    private var liveLen = -1

    /** 렌더링 때 '줄바꿈으로 옮긴 만큼'을 창 위치에 다시 반영하지 않는다 (조절 중에는 위치를 직접 계산) */
    private var skipShiftAdjust = false

    private fun beginResize(v: BossStripView, lp: WindowManager.LayoutParams, h: Int) {
        if (rsActive) commitResize()      // 아직 따라가는 중이었으면 먼저 확정한다
        rsActive = true; rsDragging = true; rsHandle = h
        rsOx = lp.x; rsOy = lp.y
        rsW0 = v.width; rsH0 = v.height
        rsD0 = v.d
        rsPer = v.linesPerRow()
        rsCur = v.d.toFloat(); rsTgt = rsCur
    }

    private fun dragResize(dx: Float, dy: Float) {
        val v = strip ?: return
        val k = RESIZE_DAMPING
        val hd = rsHandle
        val pad2 = v.padPx() * 2
        val iw = max(1, rsW0 - pad2).toFloat()
        val ih = max(1, rsH0 - pad2).toFloat()
        var num = 0f; var den = 0f
        if ((hd and BossStripView.H_RIGHT) != 0 || (hd and BossStripView.H_LEFT) != 0) {
            val dW = (if ((hd and BossStripView.H_RIGHT) != 0) dx else -dx) * k
            num += dW * iw; den += iw * iw
        }
        if ((hd and BossStripView.H_BOTTOM) != 0 || (hd and BossStripView.H_TOP) != 0) {
            val dH = (if ((hd and BossStripView.H_BOTTOM) != 0) dy else -dy) * k
            num += dH * ih; den += ih * ih
        }
        // 한 변이면 그 변의 늘어난 비율, 모서리면 대각선 방향으로 사영한 비율
        val scale = if (den > 0f) 1f + num / den else 1f
        rsTgt = (rsD0 * scale).coerceIn(dp(20).toFloat(), max(dp(20), maxStripD(v)).toFloat())
        ensureResizeTick()
    }

    /** 화면 안에 다 들어가는 가장 큰 사진 지름(px) — 줄이 화면 밖으로 나가지 않게 */
    private fun maxStripD(v: BossStripView): Int {
        val (sw, sh) = screenSize()
        val vertical = Prefs.overlayVertical(this)
        val pad2 = v.padPx() * 2; val gap = v.gapPx()
        // 사진 줄은 늘 한 줄 — 사진 수만큼 길게 놓아도 화면 안에 들어가는 크기까지만
        val per = max(1, v.itemCount())
        val along = (if (vertical) sh else sw) - pad2 - (per - 1) * gap
        val across = (if (vertical) sw else sh) - pad2
        return min(dp(160), min(along / per, across))
    }

    private fun endResize() {
        rsDragging = false
        val dens = resources.displayMetrics.density
        // 설정에는 dp 정수로 저장되므로, 손을 떼면 가장 가까운 dp 로 맞춰서 끝낸다
        rsTgt = dp(Math.round(rsTgt / dens)).toFloat().coerceIn(dp(20).toFloat(), dp(160).toFloat())
        ensureResizeTick()
    }

    private fun ensureResizeTick() {
        if (rsTickPosted) return
        rsTickPosted = true
        main.post(resizeTick)
    }

    private val resizeTick = object : Runnable {
        override fun run() {
            rsTickPosted = false
            if (!rsActive) return
            var cur = rsCur + (rsTgt - rsCur) * RESIZE_EASE
            if (kotlin.math.abs(rsTgt - cur) < 0.6f) cur = rsTgt
            rsCur = cur
            applyStripSize(cur.roundToInt())
            if (cur != rsTgt) { rsTickPosted = true; main.postDelayed(this, 16) }
            else if (!rsDragging) commitResize()
        }
    }

    /** 사진 지름을 dd(px) 로 맞추고, 반대편 모서리가 제자리에 있도록 창 위치를 옮긴다 */
    private fun applyStripSize(dd: Int) {
        val v = strip ?: return
        val lp = stripLp ?: return
        val per = rsPer
        liveD = dd
        liveLen = per * dd + (per - 1) * v.gapPx()
        val (w, h) = v.sizeFor(dd, per)
        lp.x = if ((rsHandle and BossStripView.H_LEFT) != 0) rsOx + (rsW0 - w) else rsOx
        lp.y = if ((rsHandle and BossStripView.H_TOP) != 0) rsOy + (rsH0 - h) else rsOy
        skipShiftAdjust = true
        try { render() } finally { skipShiftAdjust = false }
    }

    /** 조절이 끝나면 크기·길이·위치를 저장한다 */
    private fun commitResize() {
        main.removeCallbacks(resizeTick); rsTickPosted = false
        val dens = resources.displayMetrics.density
        val dd = liveD; val len = liveLen
        rsActive = false; rsDragging = false
        if (dd > 0) {
            Prefs.setOverlayMaxDp(this, Math.round(dd / dens))
            // 길이는 올림 + 1dp 여유 — 반올림 오차로 한 장이 다음 줄로 밀려나지 않게
            Prefs.setOverlayLengthDp(this, kotlin.math.ceil(len / dens).toInt() + 1)
        }
        liveD = -1; liveLen = -1
        stripLp?.let { saveStripPos(it) }
        render()
    }

    // ── [보스설정] 버튼 (누르면 보스캡처 · 위치조절 · 보스리셋 · 음성만 · 홈앱이동 · 종료) ─────────────

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
        val capBtn = menuBtn("보스캡처", "#E62980B9") { takeSnapshot() }
        val resetBtn = menuBtn("보스리셋", "#E6D35400") { resetBosses() }
        val voiceBtn = menuBtn("음성만", "#E6B7950B") { toggleVoiceOnly() }
        // [태초] ⇄ [침공] — 로비 인식이 안 됐을 때 모드를 손으로 바꾼다 (글자는 지금 모드)
        val modeBtn = menuBtn(Bus.mode.label, "#E62E86C1") { toggleModeByHand() }
        menuStart = startBtn; menuPause = pauseBtn; menuCapture = capBtn; menuReset = resetBtn
        menuVoice = voiceBtn; menuMode = modeBtn
        // [인식위치] — 보스를 찾는 네모(전체보스·시공보스)를 게임 화면에서 보고 옮기고 크기를 바꾼다 (v3.6)
        val zoneBtn = menuBtn("인식위치", "#E6119DA4") { toggleZoneEdit() }
        menuZone = zoneBtn
        val items = listOf(
            startBtn, pauseBtn, capBtn,
            menuBtn("위치조절", "#E616A085") { setEditing(!editing) },
            zoneBtn,
            resetBtn,
            modeBtn,
            voiceBtn,
            menuBtn("홈앱이동", "#E68E44AD") { openHome() },
            menuBtn("종료", "#E6C0392B") { exitApp() }
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
    private var menuVoice: TextView? = null
    private var menuMode: TextView? = null

    /**
     * 감시 상태에 맞게 메뉴를 바꾼다.
     *   감시 중  → 일시정지(재개) · 보스캡처 · 위치조절 · 보스리셋 · 음성만(사진표시) · 홈앱이동 · 종료
     *   멈춤     → 시작 · 위치조절 · 음성만(사진표시) · 홈앱이동 · 종료
     */
    private fun refreshMenu() {
        val run = Bus.running
        menuStart?.visibility = if (run) View.GONE else View.VISIBLE
        menuPause?.visibility = if (run) View.VISIBLE else View.GONE
        menuCapture?.visibility = if (run) View.VISIBLE else View.GONE
        menuReset?.visibility = if (run) View.VISIBLE else View.GONE
        menuMode?.visibility = if (run) View.VISIBLE else View.GONE
        // [태초] / [침공] — 지금 모드 이름이 보이고, 누르면 반대 모드로 바뀐다
        menuMode?.let { b ->
            val inv = Bus.mode == tv.gapja.monitor.core.GameMode.INVASION
            val t = Bus.mode.label
            if (b.text != t) {
                b.text = t
                (b.background as? GradientDrawable)?.setColor(
                    Color.parseColor(if (inv) "#E6E67E22" else "#E62E86C1"))
            }
        }
        menuZone?.text = if (zoneView != null && !zoneFading) "인식위치 완료" else "인식위치"
        menuPause?.text = if (Bus.paused) "재개" else "일시정지"
        // [음성만] 을 누른 뒤에는 [사진표시] 로 바뀐다 (다시 누르면 사진이 보인다)
        val vo = Prefs.voiceOnly(this)
        menuVoice?.let { b ->
            val t = if (vo) "사진표시" else "음성만"
            if (b.text != t) {
                b.text = t
                (b.background as? GradientDrawable)?.setColor(
                    Color.parseColor(if (vo) "#E627AE60" else "#E6B7950B"))
            }
        }
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

    /** 버튼 크기를 설정값에 맞춘다 (보스설정 → 위치조절 → 버튼 크기) */
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

    /**
     * [음성만] / [사진표시] — 보스 사진과 예상보스 4마리 사진을 숨기거나 다시 보인다.
     * 인식·음성 안내는 그대로 계속된다. (위치조절 중에는 크기를 맞출 수 있게 예시 사진이 보인다)
     */
    private fun toggleVoiceOnly() {
        val on = !Prefs.voiceOnly(this)
        Prefs.setVoiceOnly(this, on)
        Toast.makeText(this,
            if (on) "음성만 — 보스 사진을 숨기고 음성으로만 알려 줍니다" else "사진표시 — 보스 사진을 다시 보여 줍니다",
            Toast.LENGTH_SHORT).show()
        render()
    }

    /** [보스리셋] 지금 판의 보스 사진을 모두 지운다 (침공 판은 순서가 기록된다) */
    /** [태초/침공] 로비를 못 알아봤을 때 모드를 손으로 바꾼다 — 자동 인식과 똑같이 처리된다 */
    private fun toggleModeByHand() {
        if (!Bus.running) {
            Toast.makeText(this, "감시 중이 아닙니다", Toast.LENGTH_SHORT).show(); return
        }
        CaptureService.send(this, CaptureService.ACTION_TOGGLE_MODE)
    }

    private fun resetBosses() {
        if (!Bus.running) {
            Toast.makeText(this, "감시 중이 아닙니다", Toast.LENGTH_SHORT).show(); return
        }
        CaptureService.send(this, CaptureService.ACTION_RESET)
        Toast.makeText(this, "보스 사진을 초기화했습니다", Toast.LENGTH_SHORT).show()
    }

    /**
     * [홈앱이동] 메인 화면(앱)으로 간다. 감시·게임 위 표시는 그대로 계속된다.
     * 게임이 다른 화면(뮤뮤 탭)에 있으면 메인 화면도 그 화면에 연다 (자르기 화면과 같은 방식).
     */
    private fun openHome() {
        if (editing) setEditing(false)
        try {
            val it = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            val opened = if (shownDisplay > 0) try {
                startActivity(it, android.app.ActivityOptions.makeBasic()
                    .setLaunchDisplayId(shownDisplay).toBundle()); true
            } catch (_: Exception) { false } else false
            if (!opened) startActivity(it)
        } catch (e: Exception) {
            Bus.log("⚠️ 메인 화면을 열지 못했습니다: ${e.message}")
            Toast.makeText(this, "메인 화면을 열지 못했습니다", Toast.LENGTH_SHORT).show()
        }
    }

    /** [종료] 감시와 게임 위 표시를 멈추고 앱을 닫는다 */
    private fun exitApp() {
        if (editing) setEditing(false)
        Toast.makeText(this, "보스알림을 종료합니다", Toast.LENGTH_SHORT).show()
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

    /** 위치조절을 끝낼 때 예시 사진이 천천히 흐려지며 사라지는 중인지 (v3.0) */
    private var fadingPreview = false

    private fun setEditing(on: Boolean) {
        if (editing == on) return
        // 여백이 바뀌어도 사진이 눈에 보이는 자리는 그대로 두도록 창을 옮긴다 (저장 위치 어긋남 방지)
        stripLp?.let { lp ->
            val d = editPadDelta()
            if (on) { lp.x -= d; lp.y -= d } else { lp.x += d; lp.y += d }
        }
        // 인식된 보스가 없을 때만(= 예시 사진만 보일 때) 완료하면 2초 동안 흐려지며 사라진다.
        // 이미 인식된 보스가 있으면 예시는 바로 걷고 인식된 보스를 곧바로 보여 준다.
        val previewOnly = Bus.activated.isEmpty()
        editing = on
        cancelPreviewFade()
        applyStripTouchMode()
        if (on) showPanel() else hidePanel()
        if (!on && previewOnly && strip != null) startPreviewFade()
        render()
    }

    private fun startPreviewFade() {
        val v = strip ?: return
        fadingPreview = true
        v.animate().cancel()
        v.alpha = Prefs.bossOpacity(this) / 100f
        v.animate().alpha(0f).setDuration(PREVIEW_FADE_MS).withEndAction {
            if (fadingPreview) { fadingPreview = false; render() }
        }.start()
    }

    private fun cancelPreviewFade() {
        if (!fadingPreview) return
        fadingPreview = false
        strip?.animate()?.cancel()
    }

    private fun showPanel() {
        if (panel != null) return
        val box = LinearLayout(uiCtx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(7))
        }
        // 화면이 작으면 위아래로 밀어서 볼 수 있게 (칸이 많아져도 '완료' 버튼이 화면 밖으로 나가지 않는다)
        val scroll = ScrollView(uiCtx).apply {
            isVerticalScrollBarEnabled = false
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#F0101A24"))
                setStroke(dp(1), Color.parseColor("#661ABC9C"))
            }
            addView(box)
        }
        fun label(t: String) = TextView(uiCtx).apply {
            text = t; setTextColor(Color.WHITE); textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
        }
        // 보스흐림설정 — 보스 사진(남은 보스 4마리 포함)이 보이는 정도. 100% = 또렷하게
        val dimLabel = label("")
        fun syncDim() {
            val p = Prefs.bossOpacity(this)
            dimLabel.text = "보스흐림설정: $p%" + if (p >= 100) " (또렷하게)" else ""
        }
        syncDim()
        box.addView(dimLabel)
        box.addView(SeekBar(uiCtx).apply {
            setPadding(dp(10), dp(3), dp(10), dp(3))
            setMax(100 - 10)
            setProgress(Prefs.bossOpacity(this@OverlayService) - 10)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    if (!u) return
                    Prefs.setBossOpacity(this@OverlayService, p + 10); syncDim(); applyBossOpacity()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
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
            b.text = t; b.setTextColor(Color.WHITE); b.isAllCaps = false; b.textSize = 12f
            b.minHeight = 0; b.minimumHeight = 0; b.setPadding(0, 0, 0, 0)
            b.setOnClickListener {
                Prefs.setOverlayVertical(this, vert)
                Prefs.setOverlayLengthDp(this, 0)   // 방향이 바뀌면 길이는 화면 끝까지로 되돌린다
                syncLayoutBtns(); render()
            }
            row.addView(b, LinearLayout.LayoutParams(0, dp(32), 1f).also { it.setMargins(dp(3), 0, dp(3), 0) })
        }
        syncLayoutBtns()
        box.addView(row)

        // 최대 크기 — 점으로 조절할 때와 똑같이, 줄바꿈 없이 크기만 바꾼다
        val sizeLabel = label("")
        fun syncSize() { sizeLabel.text = "사진 최대 크기: ${Prefs.overlayMaxDp(this)}dp" }
        syncSize()
        box.addView(sizeLabel)
        box.addView(SeekBar(uiCtx).apply {
            setPadding(dp(10), dp(3), dp(10), dp(3))
            setMax(160 - 20)
            setProgress(Prefs.overlayMaxDp(this@OverlayService) - 20)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    if (!u) return
                    // 한 줄이 화면 밖으로 나가지 않는 크기까지만 키운다
                    val fit = strip?.let { sv -> max(dp(20), maxStripD(sv)) } ?: dp(160)
                    val dens = resources.displayMetrics.density
                    val wantDp = p + 20
                    val useDp = if (dp(wantDp) > fit) max(20, (fit / dens).toInt()) else wantDp
                    if (useDp != wantDp) s?.progress = useDp - 20
                    val dd = dp(useDp)
                    // 한 줄에 놓인 사진 수가 그대로이도록 줄 길이도 같이 맞춘다
                    strip?.let { sv ->
                        val per = sv.linesPerRow()
                        val len = per * dd + (per - 1) * sv.gapPx()
                        Prefs.setOverlayLengthDp(this@OverlayService,
                            kotlin.math.ceil(len / dens).toInt() + 1)
                    }
                    Prefs.setOverlayMaxDp(this@OverlayService, useDp); syncSize(); render()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        fun check(t: String, get: () -> Boolean, set: (Boolean) -> Unit) = CheckBox(uiCtx).apply {
            text = t; setTextColor(Color.WHITE); textSize = 12f
            minHeight = 0; minimumHeight = 0; setPadding(dp(2), 0, 0, 0)
            isChecked = get()
            setOnCheckedChangeListener { _, v -> set(v); render() }
        }
        box.addView(check("보스 사진 전체 표시", { Prefs.overlayShowAll(this) }) { Prefs.setOverlayShowAll(this, it) })
        box.addView(check("침공 특수보스 사진 표시", { Prefs.showSpecial(this) }) { Prefs.setShowSpecial(this, it) })
        box.addView(check("자동 축소 (보스가 늘면 줄여서 다 보이게)", { Prefs.autoShrink(this) }) { Prefs.setAutoShrink(this, it) })
        box.addView(check("사진 터치 통과 (사진 위도 게임이 눌림 · 대신 이동은 여기서만)", { Prefs.stripPassThrough(this) }) {
            Prefs.setStripPassThrough(this, it)
        })
        box.addView(check("남은 보스 4마리 예측 (70R 보스 후 화면·음성)", { Prefs.remainEnabled(this) }) {
            Prefs.setRemainEnabled(this, it)
        })

        // [보스설정] 버튼 크기
        val btnLabel = label("")
        fun syncBtn() { btnLabel.text = "보스설정 버튼 크기: ${Prefs.buttonScale(this)}%" }
        syncBtn()
        box.addView(btnLabel)
        box.addView(SeekBar(uiCtx).apply {
            setPadding(dp(10), dp(3), dp(10), dp(3))
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
            minHeight = 0; minimumHeight = 0; setPadding(0, 0, 0, 0)
            setBackgroundColor(Color.parseColor("#16A085"))
            setOnClickListener { setEditing(false) }
        }
        box.addView(done, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36)).also { it.topMargin = dp(4) })

        val (sw, sh) = screenSize()
        // 폰에서는 세로 사진 줄이 오른쪽에 붙어 있으니, 설정 창은 왼쪽에 두고 오른쪽을 40dp 비운다
        panelLp = WindowManager.LayoutParams(
            min(sw - dp(8) - dp(40), dp(310)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 보스 사진 줄이 화면 아래쪽에 있으면 설정 창은 위에, 위쪽에 있으면 아래에 띄운다
            val stripMid = (stripLp?.y ?: 0) + (strip?.height ?: 0) / 2
            gravity = (if (stripMid > sh / 2) Gravity.TOP else Gravity.BOTTOM) or Gravity.START
            x = dp(8)
            y = dp(16)
        }
        panel = scroll
        try { wm?.addView(scroll, panelLp) } catch (_: Exception) { panel = null }
        restack()
    }

    /**
     * 창을 쌓는 순서를 바로잡는다 (아래부터: 남은 보스 창 → 설정 창 → 사진 줄 → 버튼).
     * 오버레이 창은 나중에 붙인 것이 위에 그려지므로, 떼었다가 순서대로 다시 붙인다.
     * 사진 줄·버튼이 설정 창에 가려지면 끌 수 없어서 항상 맨 위에 둔다.
     */
    private fun restack() {
        val w = wm ?: return
        fun again(v: View?, lp: WindowManager.LayoutParams?) {
            if (v == null || lp == null) return
            try { w.removeView(v); w.addView(v, lp) } catch (_: Exception) {}
        }
        again(zoneView, zoneLp)
        again(remain, remainLp)
        again(panel, panelLp)
        again(strip, stripLp)
        again(chip, chipLp)
    }

    // ── 인식위치 조절 (v3.6) ─────────────────────────────────────

    /** 화면 전체 크기 (상태 표시줄·내비게이션 바까지 — 인식 화면과 같은 기준) */
    private fun realSize(): Pair<Int, Int> {
        val w = wm ?: return screenSize()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = w.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") w.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    /** [인식위치] 누르면 조절 화면이 열리고, 다시 누르면(또는 [완료]) 저장하고 천천히 사라진 뒤 인식을 다시 시작한다 */
    private fun toggleZoneEdit() {
        when {
            zoneView == null -> startZoneEdit()
            zoneFading -> resumeZoneEdit()
            else -> finishZoneEdit(fast = false, save = true)
        }
    }

    private fun startZoneEdit() {
        val w = wm ?: return
        if (editing) setEditing(false)
        val (sw, sh) = realSize()
        val v = ZoneEditView(uiCtx)
        v.load(this, sw > sh)
        v.onChanged = { v.saveAll(this) }
        v.onDone = { finishZoneEdit(fast = false, save = true) }
        val lp = WindowManager.LayoutParams(
            sw, sh, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try { w.addView(v, lp) } catch (e: Exception) {
            Bus.log("⚠️ 인식위치 화면을 띄우지 못했습니다: ${e.javaClass.simpleName}: ${e.message}")
            Toast.makeText(this, "인식위치 화면을 열지 못했습니다", Toast.LENGTH_SHORT).show()
            return
        }
        zoneView = v; zoneLp = lp; zoneFading = false
        Bus.zoneEditing = true            // 조절하는 동안 인식은 잠시 쉰다
        restack()                         // 버튼·사진 줄이 박스 위에 오도록
        refreshMenu()
        Bus.log("📐 [인식위치] 조절을 시작합니다. 인식은 잠시 쉽니다. (끝나면 [보스설정 → 인식위치 완료])")
    }

    /** 사라지는 중에 다시 눌렀을 때 — 조절을 이어서 한다 */
    private fun resumeZoneEdit() {
        val v = zoneView ?: return
        zoneFading = false
        v.animate().cancel()
        v.alpha = 1f
        zoneLp?.let { lp ->
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            try { wm?.updateViewLayout(v, lp) } catch (_: Exception) {}
        }
        refreshMenu()
    }

    /**
     * 인식위치 조절을 끝낸다.
     *   fast=false: 터치를 막고 박스가 천천히 흐려지며 사라진 뒤에 인식을 다시 시작한다
     *   fast=true : 바로 닫고 인식을 다시 시작한다
     */
    private fun finishZoneEdit(fast: Boolean, save: Boolean) {
        val v = zoneView ?: return
        if (save) v.saveAll(this)
        val finish = {
            try { wm?.removeView(v) } catch (_: Exception) {}
            if (zoneView === v) { zoneView = null; zoneLp = null }
            zoneFading = false
            Bus.zoneEditing = false       // 여기서부터 인식을 다시 진행한다
            refreshMenu()
            Bus.log("📐 [인식위치] 저장했습니다. 인식을 다시 시작합니다. (${WatchZones.summary(this)})")
        }
        v.animate().cancel()
        if (fast) { finish(); return }
        zoneFading = true
        zoneLp?.let { lp ->
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            try { wm?.updateViewLayout(v, lp) } catch (_: Exception) {}
        }
        refreshMenu()
        Toast.makeText(this, "인식위치를 저장했습니다", Toast.LENGTH_SHORT).show()
        v.animate().alpha(0f).setDuration(ZONE_FADE_MS).withEndAction {
            if (zoneFading && zoneView === v) finish()
        }.start()
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
        if (zoneView != null) finishZoneEdit(fast = true, save = true)   // 인식위치 네모가 캡처에 찍히지 않게
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
                    } else if (!Prefs.cropRightAway(this)) {
                        // 기본: 저장만 하고 게임에 그대로 머문다 (게임이 튕기지 않게)
                        val mode = Bus.mode
                        val hint = Bus.activated.lastOrNull()
                        showLoading("📸 캡처 저장 중…")
                        Thread {
                            val f = Snaps.save(this, bmp, mode, hint)
                            Backup.saveFullScreen(this, bmp, "캡처")
                            val n = Snaps.count(this)
                            bmp.recycle()
                            main.post {
                                hideLoading()
                                if (f != null) {
                                    showLoading("📸 캡처함에 저장 ($n 장)", spinner = false)
                                    main.postDelayed({ hideLoading() }, 1100)
                                    Bus.log("📸 캡처를 저장했습니다 (캡처함 ${n}장). 판이 끝나면 메인 화면 [📸 캡처함] 에서 잘라 등록하세요.")
                                } else {
                                    Toast.makeText(this, "캡처를 저장하지 못했습니다", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }.start()
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

    // ── 로비·모드 전환 알림 (화면 가운데 조금 아래, 2.5초) ─────────

    // v3.2 — 로비·모드 전환·자동 멈춤 안내는 [보스리셋] 을 눌렀을 때와 같은 토스트(검은 반투명 + 앱 아이콘 + 글자)로 통일
    private var noticeToast: Toast? = null

    private fun showNotice(text: String) {
        hideNotice()
        val t = Toast.makeText(this, text.replace("\n", " "), Toast.LENGTH_SHORT)
        noticeToast = t
        t.show()
    }

    private fun hideNotice() {
        noticeToast?.cancel()
        noticeToast = null
    }

    // ── '캡처 준비 중…' 안내 (화면 가운데) ───────────────────────

    private var loading: View? = null
    private val hideLoadingRunnable = Runnable { hideLoading() }

    private fun showLoading(text: String, spinner: Boolean = true) {
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
        if (spinner) box.addView(android.widget.ProgressBar(uiCtx).apply {
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

    /**
     * 사진이 한 줄에 다 안 들어가 둘째 줄이 생길 때, 화면 가장자리 쪽이면
     * 창을 안쪽으로 옮겨서 줄이 화면 안쪽으로 늘어나게 한다. 그 옮긴 거리(px).
     */
    private var wrapShift = 0

    private fun render() {
        applyChipScale()
        applyStripTouchMode()
        refreshMenu()
        syncRemain()
        val v = strip ?: return
        val lp = stripLp ?: return
        // [음성만] 이면 보스 사진을 숨긴다 (위치조절 중에는 예시를 보여 준다)
        val showAll = Prefs.overlayShowAll(this) && !Prefs.voiceOnly(this)
        val showSpecial = Prefs.showSpecial(this)

        // 흐려지는 중에 보스가 인식되면 예시는 바로 걷는다
        if (fadingPreview && Bus.activated.isNotEmpty()) cancelPreviewFade()
        // 위치조절 중에는 인식된 보스 수와 상관없이 전체 예시 11장을 보여 준다 (보스 수는 완료하면 그대로 돌아온다)
        val showPreview = editing || fadingPreview
        val src = if (showPreview) PREVIEW else Bus.activated

        val items = ArrayList<BossStripView.Item>()
        for (b in src) {
            val sp = BossCatalog.isSpecial(b)
            if (sp && !showSpecial) continue
            items.add(BossStripView.Item(b, sp))
        }

        val vertical = Prefs.overlayVertical(this)
        val (sw, sh) = screenSize()
        val lenDp = Prefs.overlayLengthDp(this)
        // 크기를 조절하는 동안(liveD/liveLen)에는 저장값 대신 지금 그리는 값을 쓴다
        val avail = if (liveLen > 0) liveLen else if (lenDp > 0) dp(lenDp) else {
            if (vertical) sh - max(0, lp.y) - dp(24) else sw - max(0, lp.x) - dp(8)
        }
        // 첫 줄의 자리(줄바꿈으로 옮기기 전) 가 화면 오른쪽(아래쪽) 절반에 있으면 줄을 안쪽(왼쪽·위쪽)으로 늘린다
        val maxD = if (liveD > 0) liveD else dp(Prefs.overlayMaxDp(this))
        val anchor = if (vertical) lp.x + wrapShift else lp.y + wrapShift
        val reverse = if (vertical) anchor + maxD / 2 > sw / 2 else anchor + maxD / 2 > sh / 2
        v.update(
            items = if (showAll || showPreview) items else emptyList(),
            vertical = vertical,
            maxD = maxD,
            lengthPx = max(dp(60), avail),
            shrink = Prefs.autoShrink(this),
            editing = editing,
            highlightLast = !showPreview && Bus.activated.isNotEmpty(),
            reverseLines = reverse
        )
        val newShift = if (reverse) v.extraAcross() else 0
        if (newShift != wrapShift) {
            if (!skipShiftAdjust) {
                if (vertical) lp.x += wrapShift - newShift else lp.y += wrapShift - newShift
            }
            wrapShift = newShift
        }
        v.visibility = if ((items.isEmpty() || !showAll) && !showPreview) View.GONE else View.VISIBLE
        applyBossOpacity()
        try { wm?.updateViewLayout(v, lp) } catch (_: Exception) {}
    }

    /** 보스흐림설정 값을 보스 사진 줄과 남은 보스 4마리 창에 반영한다 */
    private fun applyBossOpacity() {
        val a = Prefs.bossOpacity(this) / 100f
        // 흐려지는 중에는 애니메이션이 투명도를 맡는다
        if (!fadingPreview) strip?.let { if (it.alpha != a) it.alpha = a }
        remain?.let { if (it.alpha != a) it.alpha = a }
    }

    // ── 남은 보스 4마리 (80R 예상) 창 ───────────────────────────────
    //
    // 침공에서 70R 보스가 나와 남은 후보가 4마리가 되면 CaptureService 가 Bus.remaining 에 담는다.
    // 평소에는 터치를 통과시키고(게임을 가리지 않음), [위치조절] 중에는 예시 4마리를 보여 주며
    // 끌어서 옮기고 파란 점 8개로 가로·세로 크기를 자유롭게 바꿀 수 있다.
    // 크기에 따라 사진이 가로 한 줄 → 2×2 → 세로 한 줄로 부드럽게 다시 배치된다.

    private var remainShownItems: List<String> = emptyList()
    private var remainShownEditing = false

    /** 조절 화면에서 보여 줄 예시 (실제 후보가 없을 때) */
    private fun remainSample(): List<String> = listOf("그런트", "샤먼", "매지션", "사신")

    private fun syncRemain() {
        val real = Bus.remaining
        // [음성만] 이면 사진은 띄우지 않는다 (음성은 CaptureService 가 그대로 읽어 준다)
        val want = Prefs.remainEnabled(this) &&
                ((real.isNotEmpty() && !Prefs.voiceOnly(this)) || editing)
        if (!want) { removeRemain(); return }
        val items = if (real.isNotEmpty()) real else remainSample()
        var created = false
        if (remain == null) { buildRemain(); created = true }
        val v = remain ?: return
        if (created || items != remainShownItems || editing != remainShownEditing) {
            remainShownItems = items; remainShownEditing = editing
            v.update(items, editing)
            applyRemainTouchMode()
        }
        if (created) applyBossOpacity()
        if (created && editing) restack()
    }

    private fun remainFlags(): Int = if (editing) editFlags else normalFlags

    /** 평소엔 사진 위도 게임이 눌리게(터치 통과), 조절 중에만 터치를 받는다 */
    private fun remainAlpha(): Float = if (editing || useA11yWin) 1f else 0.8f

    private fun applyRemainTouchMode() {
        val lp = remainLp ?: return
        val f = remainFlags(); val a = remainAlpha()
        if (lp.flags == f && lp.alpha == a) return
        lp.flags = f; lp.alpha = a
        try { wm?.updateViewLayout(remain, lp) } catch (_: Exception) {}
    }

    // 창 위치·크기의 '지금 값'과 '목표 값' (px) — 목표를 향해 부드럽게 따라간다
    private var rmX = 0f; private var rmY = 0f; private var rmW = 0f; private var rmH = 0f
    private var rmTx = 0f; private var rmTy = 0f; private var rmTw = 0f; private var rmTh = 0f
    private var rmTickPosted = false

    @SuppressLint("ClickableViewAccessibility")
    private fun buildRemain() {
        val v = RemainView(uiCtx)
        remain = v
        val (sw, sh) = screenSize()
        val saved = Prefs.remainRect(this)
        val w: Int; val h: Int; val x: Int; val y: Int
        if (saved != null) {
            x = saved[0]; y = saved[1]; w = dp(saved[2]); h = dp(saved[3])
        } else {
            // 처음에는 화면 위쪽 가운데에 가로 한 줄 (사진 4장)
            w = dp(4 * 56 + 3 * 3 + 20); h = dp(56 + 20)
            x = max(0, (sw - w) / 2); y = (sh * 0.18f).toInt()
        }
        val minW = dp(56); val minH = dp(56)
        val cw = w.coerceIn(minW, sw); val ch = h.coerceIn(minH, sh)
        remainLp = WindowManager.LayoutParams(
            cw, ch, overlayType(), remainFlags(), PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x.coerceIn(-cw / 2, max(0, sw - cw / 2)); this.y = y.coerceIn(0, max(0, sh - ch / 2))
            alpha = remainAlpha()
        }
        remainLp?.let {
            rmX = it.x.toFloat(); rmY = it.y.toFloat(); rmW = it.width.toFloat(); rmH = it.height.toFloat()
            rmTx = rmX; rmTy = rmY; rmTw = rmW; rmTh = rmH
        }

        var handle = 0
        var sx = 0f; var sy = 0f
        var ox = 0f; var oy = 0f; var w0 = 0f; var h0 = 0f
        v.setOnTouchListener { _, ev ->
            val lp = remainLp ?: return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    handle = v.hitHandle(ev.x, ev.y)
                    sx = ev.rawX; sy = ev.rawY
                    // 아직 목표로 가는 중이면 그 목표를 기준으로 이어서 조절한다
                    ox = rmTx; oy = rmTy; w0 = rmTw; h0 = rmTh
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - sx; val dy = ev.rawY - sy
                    val (scw, sch) = screenSize()
                    if (handle == 0) {
                        // 이동은 손가락에 딱 붙어서 따라온다
                        rmTx = ox + dx; rmTy = oy + dy
                        rmX = rmTx; rmY = rmTy
                        lp.x = rmX.roundToInt(); lp.y = rmY.roundToInt()
                        try { wm?.updateViewLayout(v, lp) } catch (_: Exception) {}
                    } else {
                        val k = RESIZE_DAMPING
                        var nw = w0; var nh = h0
                        if ((handle and BossStripView.H_RIGHT) != 0) nw = w0 + dx * k
                        if ((handle and BossStripView.H_LEFT) != 0) nw = w0 - dx * k
                        if ((handle and BossStripView.H_BOTTOM) != 0) nh = h0 + dy * k
                        if ((handle and BossStripView.H_TOP) != 0) nh = h0 - dy * k
                        nw = nw.coerceIn(minW.toFloat(), scw.toFloat())
                        nh = nh.coerceIn(minH.toFloat(), sch.toFloat())
                        // 반대편 모서리는 제자리에 둔다
                        rmTw = nw; rmTh = nh
                        rmTx = if ((handle and BossStripView.H_LEFT) != 0) ox + (w0 - nw) else ox
                        rmTy = if ((handle and BossStripView.H_TOP) != 0) oy + (h0 - nh) else oy
                        ensureRemainTick()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (handle != 0) ensureRemainTick()
                    // 손을 떼면 지금(목표) 위치·크기를 저장한다
                    val dens = resources.displayMetrics.density
                    Prefs.setRemainRect(this, rmTx.roundToInt(), rmTy.roundToInt(),
                        (rmTw / dens).roundToInt(), (rmTh / dens).roundToInt())
                    handle = 0
                    true
                }
                else -> false
            }
        }
        try {
            wm?.addView(v, remainLp)
        } catch (e: Exception) {
            remain = null; remainLp = null
            Bus.log("⚠️ 남은 보스 창을 띄우지 못했습니다: ${e.javaClass.simpleName}")
        }
    }

    private fun ensureRemainTick() {
        if (rmTickPosted) return
        rmTickPosted = true
        main.post(remainTick)
    }

    /** 창 크기를 목표 크기로 조금씩 다가가게 한다 (뚝 바뀌지 않고 부드럽게) */
    private val remainTick = object : Runnable {
        override fun run() {
            rmTickPosted = false
            val v = remain ?: return
            val lp = remainLp ?: return
            fun step(c: Float, t: Float): Float {
                val n = c + (t - c) * RESIZE_EASE
                return if (kotlin.math.abs(t - n) < 0.6f) t else n
            }
            rmX = step(rmX, rmTx); rmY = step(rmY, rmTy)
            rmW = step(rmW, rmTw); rmH = step(rmH, rmTh)
            lp.x = rmX.roundToInt(); lp.y = rmY.roundToInt()
            lp.width = rmW.roundToInt(); lp.height = rmH.roundToInt()
            try { wm?.updateViewLayout(v, lp) } catch (_: Exception) {}
            if (rmX != rmTx || rmY != rmTy || rmW != rmTw || rmH != rmTh) {
                rmTickPosted = true
                main.postDelayed(this, 16)
            }
        }
    }

    private fun removeRemain() {
        main.removeCallbacks(remainTick); rmTickPosted = false
        remain?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        remain = null; remainLp = null
        remainShownItems = emptyList(); remainShownEditing = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 태블릿을 돌리면 화면 밖으로 나간 사진 줄·버튼을 안으로 들여온다 */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (zoneView != null) finishZoneEdit(fast = true, save = false)   // 방향이 바뀌면 인식위치 조절을 닫는다 (방향마다 따로 저장)
        val (sw, sh) = screenSize()
        stripLp?.let { lp ->
            lp.x = lp.x.coerceIn(0, max(0, sw - dp(60))); lp.y = lp.y.coerceIn(0, max(0, sh - dp(60)))
        }
        chipLp?.let { lp ->
            lp.x = lp.x.coerceIn(0, max(0, sw - dp(80))); lp.y = lp.y.coerceIn(0, max(0, sh - dp(40)))
            chip?.let { v -> try { wm?.updateViewLayout(v, lp) } catch (_: Exception) {} }
        }
        remainLp?.let { lp ->
            lp.width = min(lp.width, sw); lp.height = min(lp.height, sh)
            lp.x = lp.x.coerceIn(0, max(0, sw - lp.width)); lp.y = lp.y.coerceIn(0, max(0, sh - lp.height))
            rmX = lp.x.toFloat(); rmY = lp.y.toFloat(); rmW = lp.width.toFloat(); rmH = lp.height.toFloat()
            rmTx = rmX; rmTy = rmY; rmTw = rmW; rmTh = rmH
            remain?.let { v -> try { wm?.updateViewLayout(v, lp) } catch (_: Exception) {} }
        }
        render()
    }

    override fun onDestroy() {
        main.removeCallbacks(fadeRunnable)
        main.removeCallbacks(resizeTick)
        main.removeCallbacks(remainTick)
        hideLoading()
        hideNotice()
        Bus.onCropOpened = null
        Bus.onNotice = null
        Bus.onGameDisplay = null
        Bus.removeChangeListener(listener)
        if (zoneView != null) finishZoneEdit(fast = true, save = true)
        Bus.zoneEditing = false
        hidePanel()
        try { if (remain != null) wm?.removeView(remain) } catch (_: Exception) {}
        try { if (strip != null) wm?.removeView(strip) } catch (_: Exception) {}
        try { if (chip != null) wm?.removeView(chip) } catch (_: Exception) {}
        strip = null; chip = null
        super.onDestroy()
    }

    companion object {
        /** 이 시간 동안 안 쓰면 [보스설정] 버튼을 흐리게 한다 */
        private const val IDLE_FADE_MS = 60_000L
        private const val IDLE_ALPHA = 0.3f

        /** 크기를 조절할 때 손가락 움직임 중 반영하는 비율 (1.0 보다 작을수록 천천히) */
        private const val RESIZE_DAMPING = 0.8f

        /** 화면에 그리는 크기가 목표 크기로 다가가는 비율(프레임당) — 작을수록 부드럽지만 느리다 */
        private const val RESIZE_EASE = 0.30f

        /** 위치조절 완료 후 예시 사진이 사라지는 데 걸리는 시간 */
        private const val PREVIEW_FADE_MS = 2000L

        /** 인식위치 완료 후 박스가 천천히 사라지는 데 걸리는 시간 — 다 사라지면 인식을 다시 시작한다 (v3.6) */
        private const val ZONE_FADE_MS = 2200L

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
 *   • 어느 점을 끌든 줄바꿈 없이 전체 크기만 바뀐다 (한 줄 사진 수는 그대로)
 *   • 변 가운데 점 → 그 변을 따라 크기 변경, 모서리 점 → 비율을 지키며 키움·줄임
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
    private var reverseLines = false

    /** 지금 그리는 사진 지름(px) */
    var d = maxD
        private set
    /** 사진 사이 간격 — 최대한 붙인다 */
    private val gap get() = dp(1f).toInt()
    /** 조절 중에는 주황 테두리·점 자리를 넉넉히, 평소에는 게임을 덜 가리게 좁게 */
    private val pad get() = dp(if (editing) 14f else 2f).toInt()

    private val circles = BossCircles(ctx)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
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
               shrink: Boolean, editing: Boolean, highlightLast: Boolean, reverseLines: Boolean = false) {
        this.items = items; this.vertical = vertical; this.maxD = maxD
        this.lengthPx = lengthPx; this.shrink = shrink; this.editing = editing
        this.highlightLast = highlightLast; this.reverseLines = reverseLines
        d = computeD()
        requestLayout(); invalidate()
    }

    /**
     * 한 줄에 놓을 사진 수 — 이제는 늘 전부(한 줄). 줄바꿈 없이, 고른 배치(가로/세로) 방향으로만 늘어선다.
     * 자동 축소가 켜져 있으면 줄 길이에 맞춰 사진을 줄이고, 꺼져 있으면 설정한 크기 그대로 둔다.
     */
    private fun perLine(): Int = max(1, items.size)   // v3.0 — 늘 한 줄(가로면 가로 한 줄, 세로면 세로 한 줄)

    private fun lineCount(): Int {
        val n = max(1, items.size); val per = perLine()
        return (n + per - 1) / per
    }

    /** 둘째 줄부터 늘어난 폭(px) — 화면 가장자리 쪽이면 창을 이만큼 안쪽으로 옮긴다 */
    fun extraAcross(): Int = if (items.isEmpty()) 0 else (lineCount() - 1) * (d + gap)

    fun itemCount(): Int = items.size

    /** 지금 한 줄에 놓인 사진 수 (크기를 조절할 때 이 수를 그대로 지켜 줄바꿈을 막는다) */
    fun linesPerRow(): Int = perLine()

    fun padPx(): Int = pad
    fun gapPx(): Int = gap

    /** 사진 지름 dd, 한 줄에 per 장일 때 이 뷰의 크기 (너비 to 높이) */
    fun sizeFor(dd: Int, per: Int): Pair<Int, Int> {
        val n = max(1, items.size)
        val p = per.coerceIn(1, n)
        val lines = (n + p - 1) / p
        val len = p * dd + (p - 1) * gap
        val across = lines * dd + (lines - 1) * gap
        return if (vertical) (across + pad * 2) to (len + pad * 2)
        else (len + pad * 2) to (across + pad * 2)
    }

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
        val len = lengthFor(d, perLine())
        val lines = lineCount()
        val across = lines * d + (lines - 1) * gap
        if (vertical) setMeasuredDimension(across + pad * 2, len + pad * 2)
        else setMeasuredDimension(len + pad * 2, across + pad * 2)
    }

    override fun onDraw(canvas: Canvas) {
        if (items.isEmpty()) {
            if (editing) {
                canvas.drawRoundRect(RectF(2f, 2f, width - 2f, height - 2f), dp(10f), dp(10f), editPaint)
                canvas.drawText("보스 대기 중", dp(14f), height / 2f + dp(5f), emptyPaint)
            }
            return
        }
        val per = perLine()
        val lines = lineCount()
        for ((i, it) in items.withIndex()) {
            // 줄 번호 — 화면 가장자리 쪽이면 첫 줄이 바깥(오른쪽·아래)에, 다음 줄이 안쪽으로 쌓인다
            val line = if (reverseLines) lines - 1 - i / per else i / per
            val off = (i % per) * (d + gap) + d / 2f
            val acr = line * (d + gap) + d / 2f
            val cx = if (vertical) pad + acr else pad + off
            val cy = if (vertical) pad + off else pad + acr
            circles.get(it.boss, d)?.let { bmp ->
                canvas.drawBitmap(bmp, null, RectF(cx - d / 2f, cy - d / 2f, cx + d / 2f, cy + d / 2f), bmpPaint)
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
}

/**
 * 남은 보스(80R 예상) 사진을 보여 주는 창의 뷰. 배경은 완전히 투명하다.
 *
 * 사진은 창 크기에 맞춰 알아서 배치된다 — 창을 가로로 길게 하면 한 줄(4×1),
 * 네모에 가깝게 하면 2×2, 세로로 길게 하면 세로 한 줄(1×4). 사진 크기도 창에 꽉 차게 맞춘다.
 * 배치가 바뀔 때 사진이 제자리에서 새 자리로 미끄러지듯 움직인다.
 *
 * 퍼센트·글자 없이 사진만 보여 준다. [위치조절] 중에는 파란 테두리와 점 8개가 생긴다.
 */
class RemainView(ctx: Context) : View(ctx) {

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val circles = BossCircles(ctx)
    private var items: List<String> = emptyList()
    private var editing = false

    /** 지금 쓰는 칸 수 (경계에서 배치가 깜빡이지 않게 기억해 둔다) */
    private var cols = 0

    private val pad get() = dp(10f)
    private val gap get() = dp(3f)

    /** 사진 한 장의 지금 그리는 자리 (목표 자리로 조금씩 다가간다) */
    private class Cell(var x: Float, var y: Float, var d: Float)
    private var cells: List<Cell> = emptyList()
    private var snapNext = true

    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE
    }
    private val editColor = Color.parseColor("#3498DB")
    private val editPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = editColor; strokeWidth = dp(2.5f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = editColor }
    private val handleRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = dp(1.5f)
    }

    fun update(items: List<String>, editing: Boolean) {
        if (items.size != this.items.size) snapNext = true   // 장수가 달라지면 새로 배치
        this.items = items; this.editing = editing
        invalidate()
    }

    /** 창 크기에 가장 크게 들어가는 칸 수를 골라, 사진마다 (x, y, 지름) 목표를 돌려준다 */
    private fun computeTargets(): List<FloatArray> {
        val n = items.size
        if (n == 0 || width <= 0 || height <= 0) return emptyList()
        val iw = width - pad * 2; val ih = height - pad * 2
        fun dFor(c: Int): Float {
            val rows = (n + c - 1) / c
            return min((iw - (c - 1) * gap) / c, (ih - (rows - 1) * gap) / rows)
        }
        var best = 1; var bestD = dFor(1)
        for (c in 2..n) {
            val dd = dFor(c)
            if (dd > bestD + 0.5f) { best = c; bestD = dd }
        }
        // 경계에서 칸 수가 왔다갔다 하지 않게 — 지금 칸 수가 거의 비슷하게 크면 그대로 둔다
        if (cols in 1..n) {
            val cd = dFor(cols)
            if (cd >= bestD * 0.96f) { best = cols; bestD = cd }
        }
        cols = best
        val d = max(dp(12f), bestD)
        val rows = (n + cols - 1) / cols
        val gridH = rows * d + (rows - 1) * gap
        val out = ArrayList<FloatArray>(n)
        for (i in 0 until n) {
            val r = i / cols; val c = i % cols
            val inRow = if (r == rows - 1) n - r * cols else cols   // 마지막 줄은 가운데 정렬
            val rowW = inRow * d + (inRow - 1) * gap
            out.add(floatArrayOf(
                pad + (iw - rowW) / 2f + c * (d + gap),
                pad + (ih - gridH) / 2f + r * (d + gap),
                d
            ))
        }
        return out
    }

    // ── 조절 점 (BossStripView 와 같은 8개) ──
    private fun handlePoints(): List<Pair<Int, Pair<Float, Float>>> {
        val l = pad / 2f; val t = pad / 2f
        val r = width - pad / 2f; val b = height - pad / 2f
        val cx = width / 2f; val cy = height / 2f
        val L = BossStripView.H_LEFT; val R = BossStripView.H_RIGHT
        val T = BossStripView.H_TOP; val B = BossStripView.H_BOTTOM
        return listOf(
            (L or T) to (l to t), T to (cx to t), (R or T) to (r to t),
            L to (l to cy), R to (r to cy),
            (L or B) to (l to b), B to (cx to b), (R or B) to (r to b)
        )
    }

    /** 누른 곳이 어느 조절 점인지 (없으면 0) */
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

    override fun onDraw(canvas: Canvas) {
        val t = computeTargets()
        var moving = false
        if (t.isNotEmpty()) {
            if (snapNext || cells.size != t.size) {
                cells = t.map { Cell(it[0], it[1], it[2]) }
                snapNext = false
            }
            for ((i, tg) in t.withIndex()) {
                val c = cells[i]
                c.x = ease(c.x, tg[0]); c.y = ease(c.y, tg[1]); c.d = ease(c.d, tg[2])
                if (c.x != tg[0] || c.y != tg[1] || c.d != tg[2]) moving = true
            }
            for ((i, boss) in items.withIndex()) {
                val c = cells[i]
                circles.get(boss, c.d.roundToInt())?.let { bmp ->
                    canvas.drawBitmap(bmp, null, RectF(c.x, c.y, c.x + c.d, c.y + c.d), bmpPaint)
                }
                ringPaint.strokeWidth = max(dp(1.2f), c.d * 0.03f)
                canvas.drawCircle(c.x + c.d / 2f, c.y + c.d / 2f, c.d / 2f - ringPaint.strokeWidth / 2f, ringPaint)
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
        if (moving) postInvalidateOnAnimation()
    }

    /** 현재 값 c 를 목표 값 t 쪽으로 조금 옮긴다 (거의 도착하면 딱 맞춘다) */
    private fun ease(c: Float, t: Float): Float {
        val n = c + (t - c) * 0.30f
        return if (kotlin.math.abs(t - n) < 0.4f) t else n
    }
}
