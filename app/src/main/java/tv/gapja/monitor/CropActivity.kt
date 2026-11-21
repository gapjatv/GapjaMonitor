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
import android.widget.SeekBar
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
 *
 * 이 화면에서 바로 [태초]/[침공] 을 고를 수 있다. 고른 모드에 맞는 보스와
 * 로비 화면(태초: 태초로비·공통로비 / 침공: 침공로비·공통로비)이 목록에 나온다.
 */
class CropActivity : AppCompatActivity() {

    private lateinit var cropView: CropView
    private lateinit var preview: ImageView
    private lateinit var bossSpinner: Spinner
    private lateinit var saveInfo: TextView
    private var mode = GameMode.INVASION
    private var source: Bitmap? = null
    /** 지금 네모가 어느 종류(보스/로비) 기억값으로 맞춰져 있는지 */
    private var boxKind: String? = null
    private var zoomBar: SeekBar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = GameMode.from(intent.getStringExtra(EXTRA_MODE) ?: Prefs.mode(this))

        source = loadSource()
        if (source == null) {
            Toast.makeText(this, "사진을 불러오지 못했습니다", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        val root = buildUi()
        setContentView(root)
        // 게임 위에 떠 있던 '캡처 준비 중…' 안내를 닫는다
        Bus.cropOpened()
        // 맨 아래 버튼이 뒤로가기 줄과 겹치지 않게 시스템 바 만큼 띄우고, 조금 더 올린다
        UiInsets.apply(root, extraBottomDp = 12)
    }

    private fun loadSource(): Bitmap? = try {
        val path = intent.getStringExtra(EXTRA_PATH)
        val uri = intent.getStringExtra(EXTRA_URI)
        val mem = pendingBitmap
        when {
            // 게임 위 [캡처] — 메모리에 든 화면을 바로 받는다 (파일을 거치지 않아 빠르다)
            intent.getBooleanExtra(EXTRA_FROM_MEMORY, false) && mem != null -> { pendingBitmap = null; mem }
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
            text = "두 손가락으로 확대한 뒤, 주황 네모로 보스만 감싸세요. 모서리 점을 끌면 크기가 바뀝니다.\n" +
                    "지난번에 등록한 네모 위치·크기·확대를 기억해서 그대로 시작합니다."
            setTextColor(Color.parseColor("#FF8C9BA6")); textSize = 12f
        })

        cropView = CropView(this, source!!) { updatePreview() }
        col.addView(cropView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).also { it.setMargins(0, dp(8), 0, dp(4)) })

        // 확대·축소 버튼 — 두 손가락이 불편할 때
        val zoomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((t, act) in listOf<Pair<String, () -> Unit>>(
            "－" to { cropView.zoomBy(1f / 1.5f) },
            "＋" to { cropView.zoomBy(1.5f) },
            "네모로 확대" to { cropView.zoomToCrop() },
            "맞춤" to { cropView.resetZoom() }
        )) {
            zoomRow.addView(Button(this).apply {
                text = t; setTextColor(Color.WHITE); isAllCaps = false; textSize = 13f
                setBackgroundColor(Color.parseColor("#34495E"))
                setOnClickListener { act() }
            }, LinearLayout.LayoutParams(0, dp(40), 1f).also { it.setMargins(dp(2), 0, dp(2), dp(6)) })
        }
        col.addView(zoomRow)
        // 마우스(뮤뮤플레이어 등)로도 쉽게 — 확대 막대를 끌거나, 사진 위에서 휠을 굴린다
        zoomBar = SeekBar(this).apply {
            setMax(70)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) cropView.zoomAt(1f + p / 10f)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        col.addView(TextView(this).apply {
            text = "확대 막대 (마우스: 사진 위에서 휠 굴리기 / 막대 끌기)"
            setTextColor(Color.parseColor("#FF8C9BA6")); textSize = 11f
        })
        col.addView(zoomBar)
        cropView.onZoomChanged = { z -> zoomBar?.progress = ((z - 1f) * 10f).toInt() }

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
        // 모드 — 여기서 바로 고른다
        val pickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val normalBtn = Button(this); val invBtn = Button(this)
        bossSpinner = Spinner(this)
        fun syncMode() {
            normalBtn.setBackgroundColor(Color.parseColor(if (mode == GameMode.NORMAL) "#2C8C6E" else "#2A3644"))
            invBtn.setBackgroundColor(Color.parseColor(if (mode == GameMode.INVASION) "#8E44AD" else "#2A3644"))
            val prev = bossSpinner.selectedItem as? String
            val names = targetsFor(mode)
            bossSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
            if (prev != null && prev in names) bossSpinner.setSelection(names.indexOf(prev))
            else defaultTarget(names)?.let { bossSpinner.setSelection(names.indexOf(it)) }
        }
        for ((b, t, m) in listOf(Triple(normalBtn, "태초", GameMode.NORMAL), Triple(invBtn, "침공", GameMode.INVASION))) {
            b.text = t; b.setTextColor(Color.WHITE); b.isAllCaps = false; b.textSize = 13f
            b.setOnClickListener { mode = m; syncMode() }
            pickRow.addView(b, LinearLayout.LayoutParams(0, dp(40), 1f).also { it.setMargins(dp(2), 0, dp(2), dp(4)) })
        }
        right.addView(pickRow)
        right.addView(TextView(this).apply {
            text = "어떤 보스(로비)인가요?"
            setTextColor(Color.parseColor("#FF8C9BA6")); textSize = 12f
        })
        right.addView(bossSpinner)
        syncMode()
        // 보스 ↔ 로비를 바꿔 고르면 그 종류의 기억한 네모로 바꾼다
        bossSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) { restoreBox() }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        row.addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row)

        // 저장 방식은 자동 — 처음이면 기본 사진, 이미 이 기기 사진이 있으면 보조 사진으로 추가 (기존 사진은 지키기)
        saveInfo = TextView(this).apply {
            setTextColor(Color.parseColor("#FF8C9BA6")); textSize = 12f
            text = "보스(로비)를 고르고 [등록] 을 누르세요. 기존 사진은 지우지 않고 추가로 등록합니다."
            setPadding(0, dp(6), 0, 0)
        }
        col.addView(saveInfo)

        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(Button(this).apply {
            text = "취소"; setTextColor(Color.WHITE); isAllCaps = false
            setBackgroundColor(Color.parseColor("#7F8C8D"))
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(0, dp(50), 1f).also { it.setMargins(dp(2), dp(8), dp(2), 0) })
        btns.addView(Button(this).apply {
            text = "등록"; setTextColor(Color.WHITE); isAllCaps = false
            setBackgroundColor(Color.parseColor("#16A085"))
            setOnClickListener { save() }
        }, LinearLayout.LayoutParams(0, dp(50), 1f).also { it.setMargins(dp(2), dp(8), dp(2), 0) })
        col.addView(btns)

        updatePreview()
        restoreBox()
        return col
    }

    // ── 네모 위치 기억 ──

    private fun boxKey(target: String?): String {
        val kind = if (target != null && PcDefaults.isLobby(target)) "lobby" else "boss"
        val src = source
        val land = src != null && src.width > src.height
        return kind + if (land) "_land" else "_port"
    }

    /** 고른 항목 종류(보스/로비)에 맞는 기억한 네모로 맞춘다 (종류가 바뀔 때만) */
    private fun restoreBox() {
        val key = boxKey(bossSpinner.selectedItem as? String)
        if (key == boxKind) return
        boxKind = key
        val v = Prefs.cropBox(this, key) ?: return
        cropView.restore(RectF(v[0], v[1], v[2], v[3]), v[4])
    }

    private fun rememberBox(target: String) {
        val (r, z) = cropView.state()
        Prefs.setCropBox(this, boxKey(target), floatArrayOf(r.left, r.top, r.right, r.bottom, z))
    }

    /**
     * 처음 고를 항목을 짐작한다.
     *   • 지금 로비에 있으면 → 그 모드의 로비
     *   • 방금 인식된 보스가 있으면 → 그 보스 (오인식을 바로잡거나 사진을 늘릴 때)
     *   • 새로 등록 중이면 → 아직 등록 안 한 첫 항목
     */
    private fun defaultTarget(names: List<String>): String? {
        // 캡처함에서 열었으면 그 캡처 때 인식된 보스를 먼저
        intent.getStringExtra(EXTRA_HINT)?.let { if (it in names) return it }
        if (Bus.lobby) return names.firstOrNull { PcDefaults.isLobby(it) }
        Bus.activated.lastOrNull()?.let { if (it in names) return it }
        if (!Prefs.usePcTemplates(this)) {
            val store = TemplateStore(this)
            return names.firstOrNull { !store.hasUserShot(it, mode) }
        }
        return null
    }

    /** 그 모드에서 고를 수 있는 항목 — 보스 + 로비 화면 */
    private fun targetsFor(m: GameMode): List<String> =
        BossCatalog.watchList(m) + PcDefaults.lobbiesFor(m)

    private fun currentCrop(): Bitmap? {
        val src = source ?: return null
        val r = cropView.cropRectInBitmap()
        val l = r.left.toInt().coerceIn(0, src.width - 1)
        val t = r.top.toInt().coerceIn(0, src.height - 1)
        val w = r.width().toInt().coerceIn(1, src.width - l)
        val h = r.height().toInt().coerceIn(1, src.height - t)
        return try { Bitmap.createBitmap(src, l, t, w, h) } catch (_: Exception) { null }
    }

    /**
     * 자른 사진을 '짧은 변 1080' 기준 크기로 맞춘다 (인식도 같은 기준으로 한다).
     * 그래서 폰에서 찍은 사진이 태블릿·뮤뮤플레이어에서도 그대로 맞는다.
     * 이미 잘라 둔 작은 사진을 고른 경우(짧은 변 600 미만)는 그대로 둔다.
     */
    private fun normalized(crop: Bitmap): Bitmap {
        val src = source ?: return crop
        val short = min(src.width, src.height)
        if (short < 600) return crop
        val k = CaptureService.REF_SHORT_SIDE / short
        if (kotlin.math.abs(k - 1.0) < 0.01) return crop
        val w = max(1, (crop.width * k).toInt()); val h = max(1, (crop.height * k).toInt())
        return Bitmap.createScaledBitmap(crop, w, h, true)
    }

    private fun updatePreview() {
        if (!::preview.isInitialized) return
        preview.setImageBitmap(currentCrop())
    }

    /** 저장할 파일 이름을 정한다 — 처음이면 기본 사진 자리, 이미 내가 등록한 사진이 있으면 보조 사진 */
    private fun planSave(store: TemplateStore, boss: String): Boolean {
        val base = if (mode == GameMode.INVASION && !PcDefaults.isLobby(boss))
            "${TemplateStore.INVASION_PREFIX}$boss.png" else "$boss.png"
        // 없거나 앱 기본 사진(핸드폰 기본·PC)이면 기본 사진 자리에 넣는다 (기본 사진은 보관해 두어 되돌릴 수 있다)
        // 내 사진이 생기면 그 보스의 기본 사진은 인식에서 빠진다 (TemplateStore.userShotsFor)
        return store.exists(base) && store.isUser(base)
    }

    private fun save() {
        val crop = currentCrop() ?: return
        if (crop.width < 16 || crop.height < 16) {
            Toast.makeText(this, "너무 작습니다. 네모를 조금 더 크게 잡아 주세요", Toast.LENGTH_SHORT).show()
            return
        }
        val boss = bossSpinner.selectedItem as? String ?: return
        val asExtra = planSave(TemplateStore(this), boss)
        val what = if (PcDefaults.isLobby(boss)) boss else "$boss (${mode.label})"
        // 물어보는 것은 한 번만: 등록 / 취소
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("등록할까요?")
            .setMessage("'$what' 사진으로 등록합니다." +
                    if (asExtra) "\n(이미 있는 사진은 그대로 두고 하나 더 추가합니다)" else "")
            .setPositiveButton("등록") { _, _ -> doSave(crop, boss, asExtra) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun doSave(crop: Bitmap, boss: String, asExtra: Boolean) {
        val name = TemplateStore(this).saveCrop(normalized(crop), boss, mode, asExtra)
        if (name == null) {
            Toast.makeText(this, "저장하지 못했습니다", Toast.LENGTH_SHORT).show()
            return
        }
        rememberBox(boss)
        Bus.log("✂️ [사진 등록] '$name' 저장 (${crop.width}×${crop.height}, ${mode.label}) — 바로 인식에 쓰입니다. " +
                "사진첩 › 운빨보스알림 에도 넣었습니다.")
        val snapPath = intent.getStringExtra(EXTRA_PATH)
        val snap = snapPath?.let { java.io.File(it) }?.takeIf { it.parentFile?.name == "snaps" }
        if (snap != null) {
            // 캡처함에 '이 캡처에서 무엇을 등록했는지' 표시
            Snaps.markRegistered(this, snap, name)
        } else {
            // 자르기 전 전체 화면도 사진첩에 남긴다 (캡처함에서 연 화면은 캡처할 때 이미 남겼다)
            source?.let { Backup.saveFullScreen(this, it, name.removeSuffix(".png")) }
        }
        Toast.makeText(this, "${name.removeSuffix(".png")} 등록했습니다", Toast.LENGTH_SHORT).show()
        // 등록이 끝나면 캡처 사진이 모여 있는 화면(캡처함)으로
        if (snap == null && !intent.getBooleanExtra(EXTRA_FROM_MEMORY, false)) {
            try { startActivity(android.content.Intent(this, SnapsActivity::class.java)) } catch (_: Exception) {}
        }
        finish()
    }

    override fun onResume() {
        super.onResume()
        Bus.cropVisible = true
    }

    override fun onPause() {
        Bus.cropVisible = false
        Bus.cropClosedAt = System.currentTimeMillis()
        super.onPause()
    }

    override fun onDestroy() {
        Bus.cropVisible = false
        try { source?.recycle() } catch (_: Exception) {}
        source = null
        super.onDestroy()
    }

    companion object {
        /** 게임 위 [캡처] 로 찍은 화면 (메모리로 넘긴다) */
        @Volatile var pendingBitmap: Bitmap? = null

        const val EXTRA_FROM_MEMORY = "mem"
        const val EXTRA_HINT = "hint"
        const val EXTRA_PATH = "path"
        const val EXTRA_URI = "uri"
        const val EXTRA_MODE = "mode"
    }
}

/**
 * 사진 위에 자를 네모를 그리고, 끌어서 옮기거나 모서리로 크기를 바꾸는 뷰.
 *
 *   • 두 손가락으로 벌리거나 오므리면 사진을 확대·축소한다 (최대 8배)
 *   • 확대한 상태에서 네모 밖을 한 손가락으로 끌면 사진이 움직인다
 *   • 네모 안을 끌면 네모가 움직이고, 모서리 점을 끌면 크기가 바뀐다
 *   • 두 번 톡톡 누르면 그 자리를 2.5배로 확대 / 다시 두 번 누르면 원래대로
 */
@SuppressLint("ViewConstructor")
class CropView(ctx: Context, private val bmp: Bitmap, private val onChange: () -> Unit) : View(ctx) {

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    /** 확대 안 했을 때 사진이 화면에 맞춰 들어가는 영역 */
    private val fitRect = RectF()
    /** 지금 화면에 그린 사진 영역 (확대·이동 반영) */
    private val imgRect = RectF()
    /** 확대 배율 (1 = 화면에 맞춤) */
    private var zoom = 1f
    /** 이동량 (px) */
    private var panX = 0f
    private var panY = 0f

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
    private val zoomLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = dp(12f); typeface = Typeface.DEFAULT_BOLD
    }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** 배율이 바뀌면 알려준다 (확대 막대 맞추기) */
    var onZoomChanged: ((Float) -> Unit)? = null

    /** 마우스 휠 — 뮤뮤플레이어처럼 마우스 하나로 쓸 때 확대·축소 */
    override fun onGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_SCROLL) {
            val v = ev.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (v != 0f) {
                zoomAt(zoom * (if (v > 0) 1.2f else 1f / 1.2f), ev.x, ev.y)
                return true
            }
        }
        return super.onGenericMotionEvent(ev)
    }

    private val scaleDetector = android.view.ScaleGestureDetector(ctx,
        object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
                zoomAt(zoom * d.scaleFactor, d.focusX, d.focusY)
                return true
            }
        })

    private val tapDetector = android.view.GestureDetector(ctx,
        object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (zoom > 1.2f) resetZoom() else zoomAt(2.5f, e.x, e.y)
                return true
            }
        })

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val s = min(w / bmp.width.toFloat(), h / bmp.height.toFloat())
        val dw = bmp.width * s; val dh = bmp.height * s
        fitRect.set((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
        updateImgRect()
        applyRestore()
    }

    /** 지금 네모(0~1 비율)와 확대 배율 — 다음 등록 때 그대로 쓰려고 기억한다 */
    fun state(): Pair<RectF, Float> = RectF(crop) to zoom

    private var pendingRestore: Pair<RectF, Float>? = null

    /** 기억해 둔 네모·배율로 맞추고, 네모가 화면 가운데 오게 한다 */
    fun restore(r: RectF, z: Float) {
        val l = r.left.coerceIn(0f, 0.98f); val t = r.top.coerceIn(0f, 0.98f)
        val rr = r.right.coerceIn(l + 0.01f, 1f); val b = r.bottom.coerceIn(t + 0.01f, 1f)
        pendingRestore = RectF(l, t, rr, b) to z.coerceIn(1f, 8f)
        if (width > 0 && height > 0) applyRestore()
    }

    private fun applyRestore() {
        val (r, z) = pendingRestore ?: return
        if (width <= 0 || height <= 0) return
        pendingRestore = null
        crop.set(r)
        zoom = 1f; panX = 0f; panY = 0f
        updateImgRect()
        if (z > 1.01f) {
            val c = cropOnScreen()
            zoomAt(z, c.centerX(), c.centerY())
            val c2 = cropOnScreen()
            panX += width / 2f - c2.centerX(); panY += height / 2f - c2.centerY()
            updateImgRect()
        }
        invalidate()
        onZoomChanged?.invoke(zoom)
        onChange()
    }

    /** 확대·이동을 반영해 사진 영역을 다시 계산하고, 화면 밖으로 너무 나가지 않게 막는다 */
    private fun updateImgRect() {
        val w = fitRect.width() * zoom; val h = fitRect.height() * zoom
        val cx = width / 2f + panX; val cy = height / 2f + panY
        imgRect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        // 사진 가장자리가 화면 안쪽으로 들어오지 않게 (확대했을 때만)
        if (w > width) {
            if (imgRect.left > 0) { panX -= imgRect.left; imgRect.offset(-imgRect.left, 0f) }
            if (imgRect.right < width) { val d = width - imgRect.right; panX += d; imgRect.offset(d, 0f) }
        } else { panX = 0f; imgRect.offsetTo((width - w) / 2f, imgRect.top) }
        if (h > height) {
            if (imgRect.top > 0) { panY -= imgRect.top; imgRect.offset(0f, -imgRect.top) }
            if (imgRect.bottom < height) { val d = height - imgRect.bottom; panY += d; imgRect.offset(0f, d) }
        } else { panY = 0f; imgRect.offsetTo(imgRect.left, (height - h) / 2f) }
    }

    /** (fx, fy) 화면 점을 중심으로 배율을 바꾼다 */
    fun zoomAt(newZoom: Float, fx: Float = width / 2f, fy: Float = height / 2f) {
        val z = newZoom.coerceIn(1f, 8f)
        // 확대 전에 손가락 아래에 있던 사진 위치가 확대 후에도 손가락 아래에 오게 한다
        val rx = (fx - imgRect.left) / imgRect.width()
        val ry = (fy - imgRect.top) / imgRect.height()
        zoom = z
        val w = fitRect.width() * zoom; val h = fitRect.height() * zoom
        val left = fx - rx * w; val top = fy - ry * h
        panX = left + w / 2f - width / 2f
        panY = top + h / 2f - height / 2f
        updateImgRect()
        invalidate()
        onZoomChanged?.invoke(zoom)
    }

    /** 자를 네모가 화면 가운데 오도록 확대한다 */
    fun zoomToCrop() {
        val r = cropOnScreen()
        val target = min(width / (r.width() * 2.2f), height / (r.height() * 2.2f)) * zoom
        zoomAt(target, r.centerX(), r.centerY())
        val r2 = cropOnScreen()
        panX += width / 2f - r2.centerX(); panY += height / 2f - r2.centerY()
        updateImgRect(); invalidate()
    }

    fun resetZoom() { zoom = 1f; panX = 0f; panY = 0f; updateImgRect(); invalidate(); onZoomChanged?.invoke(zoom) }

    fun zoomBy(f: Float) = zoomAt(zoom * f)

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
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(bmp, null, imgRect, bmpPaint)
        val r = cropOnScreen()
        val vis = RectF(max(0f, imgRect.left), max(0f, imgRect.top),
            min(width.toFloat(), imgRect.right), min(height.toFloat(), imgRect.bottom))
        canvas.drawRect(vis.left, vis.top, vis.right, r.top, dim)
        canvas.drawRect(vis.left, r.bottom, vis.right, vis.bottom, dim)
        canvas.drawRect(vis.left, r.top, r.left, r.bottom, dim)
        canvas.drawRect(r.right, r.top, vis.right, r.bottom, dim)
        canvas.drawRect(r, border)
        for ((x, y) in corners(r)) canvas.drawCircle(x, y, dp(9f), handle)
        val px = cropRectInBitmap()
        canvas.drawText("${px.width().toInt()} × ${px.height().toInt()}", r.left, r.bottom + dp(16f), label)
        canvas.drawText("확대 ${"%.1f".format(zoom)}배  (두 손가락으로 확대·축소)", dp(8f), dp(16f), zoomLabel)
        canvas.restore()
    }

    private fun corners(r: RectF) = listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom)

    private var drag = -1          // -1 없음, 0~3 모서리, 4 네모 이동, 5 사진 이동
    private var lastX = 0f; private var lastY = 0f
    private var multiTouch = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        tapDetector.onTouchEvent(ev)
        parent?.requestDisallowInterceptTouchEvent(true)
        if (ev.pointerCount > 1) {
            // 두 손가락 = 확대·축소만 한다 (네모가 같이 움직이지 않게)
            multiTouch = true; drag = -1
            return true
        }
        val r = cropOnScreen()
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multiTouch = false
                lastX = ev.x; lastY = ev.y
                drag = corners(r).indexOfFirst { (x, y) -> abs(ev.x - x) < dp(28f) && abs(ev.y - y) < dp(28f) }
                if (drag < 0 && r.contains(ev.x, ev.y)) drag = 4
                if (drag < 0) {
                    if (zoom > 1.01f) drag = 5          // 확대 중이면 사진을 움직인다
                    else { moveTo(ev.x, ev.y); drag = 4 } // 아니면 네모를 그 자리로
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (multiTouch) { lastX = ev.x; lastY = ev.y; return true }
                if (drag == 5) {
                    panX += ev.x - lastX; panY += ev.y - lastY
                    lastX = ev.x; lastY = ev.y
                    updateImgRect(); invalidate()
                    return true
                }
                val dx = (ev.x - lastX) / imgRect.width()
                val dy = (ev.y - lastY) / imgRect.height()
                lastX = ev.x; lastY = ev.y
                val minW = dp(12f) / imgRect.width(); val minH = dp(12f) / imgRect.height()
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
