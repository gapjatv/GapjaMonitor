package tv.gapja.monitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * 게임 화면 위에 인식 위치 네모(태초보스·침공보스·시공보스)를 그리고, 끌어서 옮기거나 모서리를 끌어 크기를 바꾸는 뷰.
 *
 *   • 위치마다 색이 다르고, 위쪽 선 가운데에 이름표가 선 위에 얹혀 있다 (선이 이름표 뒤로 지나간다)
 *   • 네모 안을 끌면 이동, 모서리 점을 끌면 크기 변경
 *   • 손가락을 따라 '부드럽게' 따라오도록, 화면에 그리는 값이 목표값으로 조금씩 다가간다
 *   • 아직 등록하지 않은 위치는 점선 + (미등록). 건드려서 옮기면 그때부터 등록된다
 */
class ZoneEditView(ctx: Context) : View(ctx) {

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    class Box(val zone: String, val color: Int, var registered: Boolean) {
        // 목표값 (손가락이 정한 값, 0~1 비율)
        var tl = 0f; var tt = 0f; var tr = 0f; var tb = 0f
        // 화면에 그리는 값 (목표를 향해 부드럽게 다가간다)
        var dl = 0f; var dt = 0f; var dr = 0f; var db = 0f
        var touched = false
    }

    val boxes = ArrayList<Box>()

    /** 손을 뗄 때마다 (저장하라는 신호) */
    var onChanged: (() -> Unit)? = null

    /** 화면 안의 [완료] 버튼을 눌렀을 때 */
    var onDone: (() -> Unit)? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(3f) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = dp(1.5f) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dim = Paint().apply { color = Color.parseColor("#22000000") }
    private val pillFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.parseColor("#F0101A24") }
    private val pillLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.5f) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = dp(13f); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    private val btnFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.parseColor("#F016A085") }

    private val doneRect = RectF()

    /** 저장된 값(없으면 기본 자리)으로 박스를 만든다 */
    fun load(ctx: Context, land: Boolean) {
        boxes.clear()
        for (z in WatchZones.ALL) {
            val saved = WatchZones.saved(ctx, z, land)
            val r = saved ?: WatchZones.defaultRect(z)
            val b = Box(z, WatchZones.color(z), saved != null)
            b.tl = r[0]; b.tt = r[1]; b.tr = r[2]; b.tb = r[3]
            b.dl = b.tl; b.dt = b.tt; b.dr = b.tr; b.db = b.tb
            boxes.add(b)
        }
        invalidate()
    }

    /** 등록돼 있었거나 이번에 건드린 박스만 저장한다 (건드리지 않은 '미등록' 박스는 등록하지 않는다) */
    fun saveAll(ctx: Context) {
        val land = width > height
        for (b in boxes) {
            if (b.registered || b.touched) {
                WatchZones.save(ctx, b.zone, b.tl, b.tt, b.tr, b.tb, land)
                b.registered = true
            }
        }
    }

    // ── 부드럽게 따라오기 ──

    private var ticking = false
    private val tick = Runnable { ticking = false; step() }

    private fun step() {
        var moving = false
        for (b in boxes) {
            b.dl = ease(b.dl, b.tl); b.dt = ease(b.dt, b.tt); b.dr = ease(b.dr, b.tr); b.db = ease(b.db, b.tb)
            if (b.dl != b.tl || b.dt != b.tt || b.dr != b.tr || b.db != b.tb) moving = true
        }
        invalidate()
        if (moving) ensureTick()
    }

    private fun ease(cur: Float, target: Float): Float {
        val d = target - cur
        return if (abs(d) < 0.0006f) target else cur + d * EASE
    }

    private fun ensureTick() {
        if (ticking) return
        ticking = true
        postOnAnimation(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick); ticking = false
        super.onDetachedFromWindow()
    }

    // ── 그리기 ──

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        canvas.drawRect(0f, 0f, w, h, dim)
        for (b in boxes) drawBox(canvas, b, w, h)
        drawBanner(canvas, w)
        drawDone(canvas, w, h)
    }

    private fun drawBox(c: Canvas, b: Box, w: Float, h: Float) {
        val r = RectF(b.dl * w, b.dt * h, b.dr * w, b.db * h)
        val shown = b.registered || b.touched
        fill.color = (b.color and 0x00FFFFFF) or 0x24000000
        c.drawRect(r, fill)
        stroke.color = b.color
        stroke.pathEffect = if (shown) null else DashPathEffect(floatArrayOf(dp(10f), dp(7f)), 0f)
        c.drawRect(r, stroke)
        stroke.pathEffect = null
        dot.color = b.color
        for ((x, y) in corners(r)) { c.drawCircle(x, y, dp(8f), dot); c.drawCircle(x, y, dp(8f), ring) }
        // 이름표 — 위쪽 선 가운데. 선이 이름표 뒤로 지나가도록 어두운 바탕을 얹고 글자를 쓴다
        val label = if (shown) b.zone else "${b.zone} (미등록)"
        val tw = text.measureText(label)
        val ph = dp(24f)
        val cy = r.top.coerceAtLeast(ph / 2f + dp(2f))
        val pill = RectF(r.centerX() - tw / 2f - dp(10f), cy - ph / 2f, r.centerX() + tw / 2f + dp(10f), cy + ph / 2f)
        pillLine.color = b.color
        c.drawRoundRect(pill, ph / 2f, ph / 2f, pillFill)
        c.drawRoundRect(pill, ph / 2f, ph / 2f, pillLine)
        val fm = text.fontMetrics
        c.drawText(label, pill.centerX(), pill.centerY() - (fm.ascent + fm.descent) / 2f, text)
    }

    private fun drawBanner(c: Canvas, w: Float) {
        val msg = "인식위치 조정 중 — 인식은 잠시 멈췄습니다"
        val sub = "네모 안을 끌면 이동, 모서리 점을 끌면 크기 변경"
        val tw = maxOf(text.measureText(msg), text.measureText(sub)) + dp(24f)
        val r = RectF(w / 2f - tw / 2f, dp(30f), w / 2f + tw / 2f, dp(30f) + dp(48f))
        c.drawRoundRect(r, dp(12f), dp(12f), pillFill)
        val fm = text.fontMetrics
        c.drawText(msg, r.centerX(), r.top + dp(20f) - (fm.ascent + fm.descent) / 2f, text)
        c.drawText(sub, r.centerX(), r.top + dp(38f) - (fm.ascent + fm.descent) / 2f, text)
    }

    private fun drawDone(c: Canvas, w: Float, h: Float) {
        doneRect.set(w / 2f - dp(100f), h - dp(84f), w / 2f + dp(100f), h - dp(36f))
        c.drawRoundRect(doneRect, dp(14f), dp(14f), btnFill)
        val fm = text.fontMetrics
        c.drawText("✔ 완료 (저장)", doneRect.centerX(), doneRect.centerY() - (fm.ascent + fm.descent) / 2f, text)
    }

    private fun corners(r: RectF) = listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom)

    // ── 손가락 ──

    private var active: Box? = null
    private var mode = -1            // 0~3 모서리(왼위·오른위·왼아래·오른아래), 4 이동
    private var lastX = 0f
    private var lastY = 0f
    private var downOnDone = false

    /** 손가락 아래의 박스와 방식을 찾는다 — 모서리가 먼저, 그다음 안쪽(겹치면 더 작은 박스) */
    private fun hit(x: Float, y: Float): Pair<Box, Int>? {
        val w = width.toFloat(); val h = height.toFloat()
        for (b in boxes.asReversed()) {
            val r = RectF(b.tl * w, b.tt * h, b.tr * w, b.tb * h)
            val i = corners(r).indexOfFirst { (cx, cy) -> abs(x - cx) < dp(30f) && abs(y - cy) < dp(30f) }
            if (i >= 0) return b to i
        }
        var best: Box? = null; var bestArea = Float.MAX_VALUE
        for (b in boxes) {
            val r = RectF(b.tl * w, b.tt * h, b.tr * w, b.tb * h)
            if (r.contains(x, y)) {
                val a = r.width() * r.height()
                if (a < bestArea) { bestArea = a; best = b }
            }
        }
        return best?.let { it to 4 }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downOnDone = doneRect.contains(ev.x, ev.y)
                if (downOnDone) return true
                val hh = hit(ev.x, ev.y)
                if (hh == null) { active = null; return true }
                active = hh.first; mode = hh.second
                lastX = ev.x; lastY = ev.y
                // 잡은 박스를 맨 위로 (겹쳐 있어도 이름표가 보이게)
                boxes.remove(hh.first); boxes.add(hh.first)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val b = active ?: return true
                val dx = (ev.x - lastX) / w
                val dy = (ev.y - lastY) / h
                lastX = ev.x; lastY = ev.y
                if (dx == 0f && dy == 0f) return true
                apply(b, dx, dy)
                b.touched = true
                ensureTick()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (downOnDone && doneRect.contains(ev.x, ev.y)) { downOnDone = false; onDone?.invoke(); return true }
                downOnDone = false
                if (active?.touched == true) onChanged?.invoke()
                active = null
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                downOnDone = false
                if (active?.touched == true) onChanged?.invoke()
                active = null
                return true
            }
        }
        return true
    }

    private fun apply(b: Box, dx: Float, dy: Float) {
        when (mode) {
            4 -> {
                val bw = b.tr - b.tl; val bh = b.tb - b.tt
                val nl = (b.tl + dx).coerceIn(0f, 1f - bw)
                val nt = (b.tt + dy).coerceIn(0f, 1f - bh)
                b.tl = nl; b.tr = nl + bw; b.tt = nt; b.tb = nt + bh
            }
            0 -> { b.tl = (b.tl + dx).coerceIn(0f, b.tr - MIN); b.tt = (b.tt + dy).coerceIn(0f, b.tb - MIN) }
            1 -> { b.tr = (b.tr + dx).coerceIn(b.tl + MIN, 1f); b.tt = (b.tt + dy).coerceIn(0f, b.tb - MIN) }
            2 -> { b.tl = (b.tl + dx).coerceIn(0f, b.tr - MIN); b.tb = (b.tb + dy).coerceIn(b.tt + MIN, 1f) }
            3 -> { b.tr = (b.tr + dx).coerceIn(b.tl + MIN, 1f); b.tb = (b.tb + dy).coerceIn(b.tt + MIN, 1f) }
        }
    }

    companion object {
        /** 화면에 그리는 값이 목표값으로 다가가는 비율(프레임당) — 작을수록 부드럽고 느리다 */
        private const val EASE = 0.38f

        /** 박스 최소 크기 (화면 비율) */
        private const val MIN = 0.04f
    }
}
