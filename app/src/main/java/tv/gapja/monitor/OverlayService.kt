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
 * ⚠️ 평소에는 사진 줄이 터치를 받지 않는다(FLAG_NOT_TOUCHABLE).
 *    그래야 사진 위를 눌러도 게임이 그대로 조작된다.
 *    Android 12부터는 위에 덮인 창의 불투명도가 0.8 이하여야 터치가 아래로 전달되므로
 *    평소에는 창 투명도를 0.8 로 둔다.
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

    override fun onCreate() {
        super.onCreate()
        if (!canDraw(this)) {
            Bus.log("⚠️ 오버레이 권한이 없어 화면 위 표시를 건너뜁니다. " +
                    "(메인 화면의 '오버레이 권한' 버튼으로 허용할 수 있습니다)")
            stopSelf(); return
        }
        if (!Prefs.overlayEnabled(this)) { stopSelf(); return }
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        buildStrip()
        buildChip()
        Bus.addChangeListener(listener)
        render()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

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
        val v = BossStripView(this)
        strip = v
        stripLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(), normalFlags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = Prefs.overlayX(this@OverlayService)
            y = Prefs.overlayY(this@OverlayService)
            alpha = 0.8f
        }

        // 조절 모드에서만 터치가 들어온다: 끌어서 이동 / 끝의 점을 끌어서 길이 조절
        var mode = 0           // 0 없음, 1 이동, 2 길이
        var sx = 0f; var sy = 0f
        var ox = 0; var oy = 0
        var startLen = 0
        v.setOnTouchListener { _, ev ->
            val lp = stripLp ?: return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = ev.rawX; sy = ev.rawY; ox = lp.x; oy = lp.y
                    startLen = v.currentLengthPx()
                    mode = if (v.isOnHandle(ev.x, ev.y)) 2 else 1
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (mode == 1) {
                        lp.x = ox + (ev.rawX - sx).toInt()
                        lp.y = oy + (ev.rawY - sy).toInt()
                        try { wm?.updateViewLayout(v, lp) } catch (_: Exception) {}
                    } else if (mode == 2) {
                        val delta = if (Prefs.overlayVertical(this)) ev.rawY - sy else ev.rawX - sx
                        val newLen = max(dp(60), startLen + delta.toInt())
                        val d = resources.displayMetrics.density
                        Prefs.setOverlayLengthDp(this, (newLen / d).toInt())
                        render()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (mode == 1) Prefs.setOverlayPos(this, lp.x, lp.y)
                    mode = 0
                    true
                }
                else -> false
            }
        }

        try {
            wm?.addView(v, stripLp)
        } catch (e: Exception) {
            Bus.log("⚠️ 오버레이를 띄우지 못했습니다: ${e.message}")
            stopSelf()
        }
    }

    // ── 작은 버튼 (캡처 / 조절) ──────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun buildChip() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(3), dp(4), dp(3))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#99000000"))
            }
        }
        fun chipBtn(text: String, action: () -> Unit) = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(9), dp(4), dp(9), dp(4))
            setOnClickListener { action() }
        }
        box.addView(chipBtn("캡처") { takeSnapshot() })
        box.addView(chipBtn("조절") { setEditing(!editing) })
        chip = box

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
            x = Prefs.chipX(this@OverlayService, sw - dp(130))
            y = Prefs.chipY(this@OverlayService, dp(64))
        }

        // 버튼 묶음도 끌어서 옮길 수 있다 (손가락이 조금이라도 움직이면 이동으로 본다)
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        box.setOnTouchListener { _, ev ->
            val lp = chipLp ?: return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = ev.rawX; sy = ev.rawY; ox = lp.x; oy = lp.y; moved = false; false }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(ev.rawX - sx) + kotlin.math.abs(ev.rawY - sy) > dp(8)) moved = true
                    if (moved) {
                        lp.x = ox + (ev.rawX - sx).toInt(); lp.y = oy + (ev.rawY - sy).toInt()
                        try { wm?.updateViewLayout(box, lp) } catch (_: Exception) {}
                    }
                    moved
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) { Prefs.setChipPos(this, lp.x, lp.y); true } else false
                }
                else -> false
            }
        }
        try { wm?.addView(box, chipLp) } catch (_: Exception) {}
    }

    // ── 조절 모드 ────────────────────────────────────────────────

    private fun setEditing(on: Boolean) {
        editing = on
        val lp = stripLp ?: return
        lp.flags = if (on) editFlags else normalFlags
        lp.alpha = if (on) 1f else 0.8f
        try { wm?.updateViewLayout(strip, lp) } catch (_: Exception) {}
        if (on) showPanel() else hidePanel()
        render()
    }

    private fun showPanel() {
        if (panel != null) return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#F0101A24"))
                setStroke(dp(1), Color.parseColor("#661ABC9C"))
            }
        }
        fun label(t: String) = TextView(this).apply {
            text = t; setTextColor(Color.WHITE); textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        }
        box.addView(TextView(this).apply {
            text = "사진 줄을 끌면 이동 · 주황 점을 끌면 길이 조절"
            setTextColor(Color.parseColor("#F39C12")); textSize = 12f
        })

        // 배치
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(label("배치  "))
        val hBtn = Button(this); val vBtn = Button(this)
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
        box.addView(SeekBar(this).apply {
            setMax(120 - 24)
            setProgress(Prefs.overlayMaxDp(this@OverlayService) - 24)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    if (!u) return
                    Prefs.setOverlayMaxDp(this@OverlayService, p + 24); syncSize(); render()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        fun check(t: String, get: () -> Boolean, set: (Boolean) -> Unit) = CheckBox(this).apply {
            text = t; setTextColor(Color.WHITE); textSize = 13f
            isChecked = get()
            setOnCheckedChangeListener { _, v -> set(v); render() }
        }
        box.addView(check("보스 사진 전체 표시", { Prefs.overlayShowAll(this) }) { Prefs.setOverlayShowAll(this, it) })
        box.addView(check("침공 특수보스 사진 표시", { Prefs.showSpecial(this) }) { Prefs.setShowSpecial(this, it) })
        box.addView(check("자동 축소 (보스가 늘면 줄여서 다 보이게)", { Prefs.autoShrink(this) }) { Prefs.setAutoShrink(this, it) })

        val done = Button(this).apply {
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
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(24)
        }
        panel = box
        try { wm?.addView(box, panelLp) } catch (_: Exception) { panel = null }
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
            CaptureService.requestSnapshot { file ->
                main.post {
                    strip?.visibility = View.VISIBLE
                    chip?.visibility = View.VISIBLE
                    render()
                    if (file == null) {
                        Toast.makeText(this, "화면을 찍지 못했습니다. 다시 눌러 주세요", Toast.LENGTH_SHORT).show()
                    } else {
                        try {
                            startActivity(Intent(this, CropActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                .putExtra(CropActivity.EXTRA_PATH, file.absolutePath)
                                .putExtra(CropActivity.EXTRA_MODE, Bus.mode.key))
                        } catch (e: Exception) {
                            Bus.log("⚠️ 자르기 화면을 열지 못했습니다: ${e.message}")
                        }
                    }
                }
            }
        }, 250)
    }

    // ── 그리기 ───────────────────────────────────────────────────

    private fun render() {
        val v = strip ?: return
        val lp = stripLp ?: return
        val showAll = Prefs.overlayShowAll(this)
        val showSpecial = Prefs.showSpecial(this)

        // 조절 중인데 아직 보스가 없으면 크기를 맞춰볼 수 있게 예시 11장을 보여준다
        val src = if (editing && Bus.activated.isEmpty()) PREVIEW else Bus.activated

        val items = ArrayList<BossStripView.Item>()
        var mainNo = 0
        for (b in src) {
            val sp = BossCatalog.isSpecial(b)
            if (!sp) mainNo++
            if (sp && !showSpecial) continue
            items.add(BossStripView.Item(b, if (sp) "특수" else "${mainNo * 10}R", sp))
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

    override fun onDestroy() {
        Bus.removeChangeListener(listener)
        hidePanel()
        try { if (strip != null) wm?.removeView(strip) } catch (_: Exception) {}
        try { if (chip != null) wm?.removeView(chip) } catch (_: Exception) {}
        strip = null; chip = null
        super.onDestroy()
    }

    companion object {
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
 */
class BossStripView(ctx: Context) : View(ctx) {

    data class Item(val boss: String, val label: String, val special: Boolean)

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
    private var d = maxD
    private val gap get() = dp(4f).toInt()
    private val labelH get() = max(dp(11f), d * 0.26f).toInt()
    private val pad get() = dp(12f).toInt()

    private val circleCache = HashMap<String, Bitmap?>()
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
        style = Paint.Style.STROKE; color = Color.BLACK
    }
    private val editPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.parseColor("#F39C12"); strokeWidth = dp(2.5f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F39C12") }
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

    /** 보스 수와 길이에 맞춰 사진 지름을 정한다 */
    private fun computeD(): Int {
        val n = max(1, items.size)
        if (!shrink) return maxD
        val per = (lengthPx - (n - 1) * gap) / n
        val fit = if (vertical) {
            // 세로는 사진 아래에 글자 줄이 붙는다: per = d + d*0.26
            (per / 1.26f).toInt()
        } else per
        return fit.coerceIn(dp(16f).toInt(), maxD)
    }

    /** 사진 줄의 실제 길이(px) — 길이 조절의 시작점 */
    fun currentLengthPx(): Int = if (vertical) height - pad * 2 else width - pad * 2

    /** 끝의 주황 점 위인지 */
    fun isOnHandle(x: Float, y: Float): Boolean {
        val r = dp(26f)
        val (hx, hy) = handlePos()
        return kotlin.math.abs(x - hx) < r && kotlin.math.abs(y - hy) < r
    }

    private fun handlePos(): Pair<Float, Float> =
        if (vertical) (width / 2f) to (height - pad / 2f - dp(2f))
        else (width - pad / 2f - dp(2f)) to (height / 2f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val n = items.size
        if (n == 0) {
            if (editing) setMeasuredDimension(dp(160f).toInt(), dp(48f).toInt())
            else setMeasuredDimension(1, 1)
            return
        }
        val cellMain = if (vertical) d + labelH + gap else d + gap
        val len = n * cellMain - gap
        val w: Int; val h: Int
        if (vertical) { w = d + pad * 2; h = len + pad * 2 }
        else { w = len + pad * 2; h = d + labelH + pad * 2 }
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        if (items.isEmpty()) {
            if (editing) {
                canvas.drawRoundRect(RectF(2f, 2f, width - 2f, height - 2f), dp(10f), dp(10f), editPaint)
                canvas.drawText("보스 대기 중", dp(14f), height / 2f + dp(5f), emptyPaint)
            }
            return
        }
        textPaint.textSize = labelH * 0.92f
        strokePaint.textSize = labelH * 0.92f
        strokePaint.strokeWidth = max(dp(2f), labelH * 0.18f)

        for ((i, it) in items.withIndex()) {
            val cx: Float; val cy: Float
            if (vertical) {
                cx = pad + d / 2f
                cy = pad + i * (d + labelH + gap) + d / 2f
            } else {
                cx = pad + i * (d + gap) + d / 2f
                cy = pad + d / 2f
            }
            circle(it.boss)?.let { bmp ->
                canvas.drawBitmap(bmp, null, RectF(cx - d / 2f, cy - d / 2f, cx + d / 2f, cy + d / 2f), null)
            }
            ringPaint.color = if (it.special) Color.parseColor("#E74C3C") else Color.WHITE
            ringPaint.strokeWidth = if (it.special) max(dp(2f), d * 0.06f) else max(dp(1.5f), d * 0.035f)
            canvas.drawCircle(cx, cy, d / 2f - ringPaint.strokeWidth / 2f, ringPaint)

            if (highlightLast && i == items.size - 1) {
                ringPaint.color = Color.parseColor("#F39C12")
                ringPaint.strokeWidth = max(dp(2.5f), d * 0.07f)
                canvas.drawCircle(cx, cy, d / 2f + ringPaint.strokeWidth, ringPaint)
            }

            val ty = cy + d / 2f + labelH * 0.85f
            canvas.drawText(it.label, cx, ty, strokePaint)
            textPaint.color = if (it.special) Color.parseColor("#FF6B5B") else Color.WHITE
            canvas.drawText(it.label, cx, ty, textPaint)
        }

        if (editing) {
            canvas.drawRoundRect(RectF(dp(2f), dp(2f), width - dp(2f), height - dp(2f)), dp(12f), dp(12f), editPaint)
            val (hx, hy) = handlePos()
            canvas.drawCircle(hx, hy, dp(9f), handlePaint)
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
            // 동그라미 밖으로 나가는 부분은 잘라낸다
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
            // 표시용 사진이 없으면 보스 이름 앞 두 글자
            p.color = Color.WHITE; p.textAlign = Paint.Align.CENTER
            p.textSize = size * 0.32f; p.typeface = Typeface.DEFAULT_BOLD
            c.drawText(boss.take(2), size / 2f, size / 2f + p.textSize / 3f, p)
        }
        circleCache[key] = out
        return out
    }
}
