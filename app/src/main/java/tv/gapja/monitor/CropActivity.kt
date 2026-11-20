package tv.gapja.monitor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import tv.gapja.monitor.core.BossCatalog
import tv.gapja.monitor.core.GameMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 보스 사진 직접 만들기 — 게임 화면에서 보스만 잘라 인식용 사진으로 저장한다.
 *
 * 들어오는 길은 두 가지다.
 *   ① 게임 위 [캡처] 버튼 → 그 순간 화면이 EXTRA_PATH 로 넘어온다
 *   ② 메인 화면 [직접 캡처해서 자르기] → 폰 스크린샷을 갤러리에서 고른다 (EXTRA_URI)
 *
 * 저장 방식
 *   • 보조 사진 추가: {보스}_2.png (침공이면 침공_{보스}_2.png) — 기존 사진은 그대로
 *   • 기본 사진 교체: {보스}.png 를 덮어쓴다
 */
class CropActivity : AppCompatActivity() {

    private lateinit var cropView: CropView
    private lateinit var preview: ImageView
    private lateinit var bossSpinner: Spinner
    private lateinit var saveInfo: TextView
    private var mode = GameMode.INVASION
    private var asExtra = true
    private var source: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = GameMode.from(intent.getStringExtra(EXTRA_MODE) ?: Prefs.mode(this))

        source = loadSource()
        if (source == null) {
            Toast.makeText(this, "사진을 불러오지 못했습니다", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        setContentView(buildUi())
    }

    private fun loadSource(): Bitmap? = try {
        val path = intent.getStringExtra(EXTRA_PATH)
        val uri = intent.getStringExtra(EXTRA_URI)
        when {
            path != null -> BitmapFactory.decodeFile(path)
            uri != null -> contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
            else -> null
        }
    } catch (_: Exception) { null }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FF18222C"))
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        col.addView(TextView(this).apply {
            text = "인식용 보스 사진 만들기"
            setTextColor(Color.parseColor("#FF1ABC9C")); textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = "주황 네모를 끌어서 보스만 감싸세요. 모서리 점을 끌면 크기가 바뀝니다."
            setTextColor(Color.parseColor("#FF8C9BA6")); textSize = 12f
        })

        cropView = CropView(this, source!!) { updatePreview() }
        col.addView(cropView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).also { it.setMargins(0, dp(8), 0, dp(8)) })

        // 미리보기 + 보스 선택
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        preview = ImageView(this).apply {
            setBackgroundColor(Color.parseColor("#FF0E1A24"))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        row.addView(preview, LinearLayout.LayoutParams(dp(110), dp(70)))
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        right.addView(TextView(this).apply {
            text = "어떤 보스인가요? (${mode.label} 모드)"
            setTextColor(Color.parseColor("#FF8C9BA6")); textSize = 12f
        })
        bossSpinner = Spinner(this)
        val names = BossCatalog.watchList(mode)
        bossSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        right.addView(bossSpinner)
        row.addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row)

        // 저장 방식
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val extraBtn = Button(this); val baseBtn = Button(this)
        saveInfo = TextView(this).apply { setTextColor(Color.parseColor("#FF8C9BA6")); textSize = 12f }
        fun sync() {
            extraBtn.setBackgroundColor(Color.parseColor(if (asExtra) "#2980B9" else "#3C4B5A"))
            baseBtn.setBackgroundColor(Color.parseColor(if (!asExtra) "#2980B9" else "#3C4B5A"))
            saveInfo.text = if (asExtra) "→ 보조 사진으로 추가합니다 (기존 사진은 그대로)"
            else "→ 기본 사진을 이 사진으로 바꿉니다"
        }
        for ((b, t, ex) in listOf(Triple(extraBtn, "보조 사진 추가", true), Triple(baseBtn, "기본 사진 교체", false))) {
            b.text = t; b.setTextColor(Color.WHITE); b.isAllCaps = false
            b.setOnClickListener { asExtra = ex; sync() }
            modeRow.addView(b, LinearLayout.LayoutParams(0, dp(44), 1f).also { it.setMargins(dp(2), dp(6), dp(2), 0) })
        }
        sync()
        col.addView(modeRow)
        col.addView(saveInfo)

        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(Button(this).apply {
            text = "취소"; setTextColor(Color.WHITE); isAllCaps = false
            setBackgroundColor(Color.parseColor("#7F8C8D"))
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(0, dp(50), 1f).also { it.setMargins(dp(2), dp(8), dp(2), 0) })
        btns.addView(Button(this).apply {
            text = "저장"; setTextColor(Color.WHITE); isAllCaps = false
            setBackgroundColor(Color.parseColor("#16A085"))
            setOnClickListener { save() }
        }, LinearLayout.LayoutParams(0, dp(50), 1f).also { it.setMargins(dp(2), dp(8), dp(2), 0) })
        col.addView(btns)

        updatePreview()
        return col
    }

    private fun currentCrop(): Bitmap? {
        val src = source ?: return null
        val r = cropView.cropRectInBitmap()
        val l = r.left.toInt().coerceIn(0, src.width - 1)
        val t = r.top.toInt().coerceIn(0, src.height - 1)
        val w = r.width().toInt().coerceIn(1, src.width - l)
        val h = r.height().toInt().coerceIn(1, src.height - t)
        return try { Bitmap.createBitmap(src, l, t, w, h) } catch (_: Exception) { null }
    }

    private fun updatePreview() {
        if (!::preview.isInitialized) return
        preview.setImageBitmap(currentCrop())
    }

    private fun save() {
        val crop = currentCrop() ?: return
        if (crop.width < 16 || crop.height < 16) {
            Toast.makeText(this, "너무 작습니다. 네모를 조금 더 크게 잡아 주세요", Toast.LENGTH_SHORT).show()
            return
        }
        val boss = bossSpinner.selectedItem as? String ?: return
        val name = TemplateStore(this).saveCrop(crop, boss, mode, asExtra)
        if (name == null) {
            Toast.makeText(this, "저장하지 못했습니다", Toast.LENGTH_SHORT).show()
            return
        }
        Bus.log("✂️ [사진 등록] '$name' 저장 (${crop.width}×${crop.height}) — 바로 인식에 쓰입니다")
        Toast.makeText(this, "$name 저장했습니다", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onDestroy() {
        try { source?.recycle() } catch (_: Exception) {}
        source = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_URI = "uri"
        const val EXTRA_MODE = "mode"
    }
}

/**
 * 사진 위에 자를 네모를 그리고, 끌어서 옮기거나 모서리로 크기를 바꾸는 뷰.
 */
@SuppressLint("ViewConstructor")
class CropView(ctx: Context, private val bmp: Bitmap, private val onChange: () -> Unit) : View(ctx) {

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    /** 화면에 그린 사진 영역 */
    private val imgRect = RectF()
    /** 자를 영역 (사진 좌표, 0~1 비율) */
    private val crop = RectF(0.4f, 0.25f, 0.6f, 0.33f)

    private val dim = Paint().apply { color = Color.parseColor("#99000000") }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.parseColor("#F39C12"); strokeWidth = dp(2.5f)
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F39C12") }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F39C12"); textSize = dp(12f); typeface = Typeface.DEFAULT_BOLD
    }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val s = min(w / bmp.width.toFloat(), h / bmp.height.toFloat())
        val dw = bmp.width * s; val dh = bmp.height * s
        imgRect.set((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
    }

    private fun cropOnScreen(): RectF = RectF(
        imgRect.left + crop.left * imgRect.width(),
        imgRect.top + crop.top * imgRect.height(),
        imgRect.left + crop.right * imgRect.width(),
        imgRect.top + crop.bottom * imgRect.height()
    )

    /** 자를 영역을 원본 사진 픽셀 좌표로 */
    fun cropRectInBitmap(): RectF = RectF(
        crop.left * bmp.width, crop.top * bmp.height,
        crop.right * bmp.width, crop.bottom * bmp.height
    )

    override fun onDraw(canvas: Canvas) {
        canvas.drawBitmap(bmp, null, imgRect, bmpPaint)
        val r = cropOnScreen()
        canvas.drawRect(imgRect.left, imgRect.top, imgRect.right, r.top, dim)
        canvas.drawRect(imgRect.left, r.bottom, imgRect.right, imgRect.bottom, dim)
        canvas.drawRect(imgRect.left, r.top, r.left, r.bottom, dim)
        canvas.drawRect(r.right, r.top, imgRect.right, r.bottom, dim)
        canvas.drawRect(r, border)
        for ((x, y) in corners(r)) canvas.drawCircle(x, y, dp(9f), handle)
        val px = cropRectInBitmap()
        canvas.drawText("${px.width().toInt()} × ${px.height().toInt()}", r.left, r.bottom + dp(16f), label)
    }

    private fun corners(r: RectF) = listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom)

    private var drag = -1          // -1 없음, 0~3 모서리, 4 이동
    private var lastX = 0f; private var lastY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val r = cropOnScreen()
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = ev.x; lastY = ev.y
                drag = corners(r).indexOfFirst { (x, y) -> abs(ev.x - x) < dp(28f) && abs(ev.y - y) < dp(28f) }
                if (drag < 0 && r.contains(ev.x, ev.y)) drag = 4
                if (drag < 0) {
                    // 네모 밖을 누르면 그 자리로 네모를 옮긴다
                    moveTo(ev.x, ev.y); drag = 4
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (ev.x - lastX) / imgRect.width()
                val dy = (ev.y - lastY) / imgRect.height()
                lastX = ev.x; lastY = ev.y
                val minW = dp(16f) / imgRect.width(); val minH = dp(16f) / imgRect.height()
                when (drag) {
                    0 -> { crop.left = (crop.left + dx).coerceIn(0f, crop.right - minW); crop.top = (crop.top + dy).coerceIn(0f, crop.bottom - minH) }
                    1 -> { crop.right = (crop.right + dx).coerceIn(crop.left + minW, 1f); crop.top = (crop.top + dy).coerceIn(0f, crop.bottom - minH) }
                    2 -> { crop.left = (crop.left + dx).coerceIn(0f, crop.right - minW); crop.bottom = (crop.bottom + dy).coerceIn(crop.top + minH, 1f) }
                    3 -> { crop.right = (crop.right + dx).coerceIn(crop.left + minW, 1f); crop.bottom = (crop.bottom + dy).coerceIn(crop.top + minH, 1f) }
                    4 -> {
                        val w = crop.width(); val h = crop.height()
                        val nl = (crop.left + dx).coerceIn(0f, 1f - w)
                        val nt = (crop.top + dy).coerceIn(0f, 1f - h)
                        crop.set(nl, nt, nl + w, nt + h)
                    }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drag = -1
                onChange()
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun moveTo(x: Float, y: Float) {
        val w = crop.width(); val h = crop.height()
        val cx = ((x - imgRect.left) / imgRect.width()).coerceIn(0f, 1f)
        val cy = ((y - imgRect.top) / imgRect.height()).coerceIn(0f, 1f)
        val nl = (cx - w / 2f).coerceIn(0f, max(0f, 1f - w))
        val nt = (cy - h / 2f).coerceIn(0f, max(0f, 1f - h))
        crop.set(nl, nt, nl + w, nt + h)
        invalidate()
    }
}
