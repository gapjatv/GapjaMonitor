package tv.gapja.monitor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.opencv.android.OpenCVLoader
import tv.gapja.monitor.core.BossCatalog
import tv.gapja.monitor.core.GameMode

/**
 * 메인 화면 — 시작 버튼 하나, 모드 버튼 하나(PC판처럼), 꼭 필요한 설정만.
 *
 * XML 레이아웃 없이 코드로 화면을 만든다.
 * 자주 안 쓰는 설정(인식 세부값, 기록 주고받기 등)은 맨 아래 '고급 설정'에 접어 둔다.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var templates: TemplateStore
    private lateinit var seqRepo: SeqRepo
    private lateinit var speaker: Speaker

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var resView: TextView
    private lateinit var startBtn: Button

    private var mode = GameMode.INVASION
    private var modeBtn: Button? = null
    private var sourceView: TextView? = null
    private var methodView: TextView? = null
    private var projBtn: Button? = null
    private var a11yBtn: Button? = null
    private var a11yOnBtn: Button? = null
    private var findGameBtn: Button? = null
    private var diagBtn: Button? = null

    /**
     * 화면(디스플레이) 진단 — 뮤뮤처럼 탭마다 화면이 따로인 환경에서
     * 게임이 몇 번 화면에 있고 보스 사진이 몇 번 화면에 붙었는지 로그에 남긴다.
     */
    private fun diagnoseDisplays() {
        Bus.log("🖥 ── 화면 진단 ──")
        try {
            val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
            for (d in dm.displays) {
                val f = d.flags
                val tags = buildList {
                    if (f and android.view.Display.FLAG_PRIVATE != 0) add("비공개")
                    if (f and android.view.Display.FLAG_SECURE != 0) add("보안")
                    if (f and android.view.Display.FLAG_PRESENTATION != 0) add("프레젠테이션")
                }.joinToString(",").ifEmpty { "일반" }
                val m = android.util.DisplayMetrics().also { @Suppress("DEPRECATION") d.getRealMetrics(it) }
                Bus.log("   화면 #${d.displayId} '${d.name}' ${m.widthPixels}×${m.heightPixels} [$tags]")
            }
        } catch (e: Exception) { Bus.log("   화면 목록을 읽지 못했습니다: ${e.message}") }
        Bus.log("   접근성: ${if (BossA11yService.instance != null) "켜짐" else "꺼짐"} · " +
                "게임: ${Bus.gameLabel ?: "못 찾음"} (화면 #${Bus.gameDisplay}) · " +
                "보스 사진 표시: 화면 #${Bus.overlayDisplay} · 저장된 게임 앱: ${Prefs.gamePackage(this) ?: "없음"}")
        BossA11yService.instance?.findGame()
    }
    private var addBtn: Button? = null
    private var addWhereBtn: Button? = null
    private var sourceBtn: Button? = null

    private val changeListener: () -> Unit = { refresh() }

    // ── 권한 / 선택 런처 ─────────────────────────────────────────

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            // 실제로 감시가 시작됐는지는 서비스가 정한다.
            // (예전에는 여기서 먼저 '감시 중' 으로 바꿔서, 시작이 실패하면 [멈춤] 버튼만 남고
            //  눌러도 아무 반응이 없는 '먹통' 상태가 됐다 — 뮤뮤플레이어에서 동의 창이 사라질 때 등)
            CaptureService.start(
                this, res.resultCode, res.data!!, mode,
                Prefs.downscale(this), Prefs.intervalMs(this)
            )
            refresh()
            // 시작하면 바로 게임으로 넘어갈 수 있게 홈으로 내린다
            Toast.makeText(this, "감시를 시작했습니다. 게임을 켜세요", Toast.LENGTH_SHORT).show()
            moveTaskToBack(true)
        } else {
            toast("화면 캡처를 허용하지 않으면 보스를 인식할 수 없습니다.")
        }
    }

    private val notifLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 거부해도 동작은 한다 — 알림만 안 보인다 */ }

    /** 백업(다운로드/GapjaMonitor)에서 내 사진 되살리기 */
    private val pickBackup = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        val (ok, skipped) = templates.importAll(uris)
        Bus.log("📦 백업에서 내 사진 $ok 장을 되살렸습니다." +
                if (skipped.isNotEmpty()) " (PNG가 아니어서 건너뜀: ${skipped.size}개)" else "")
        toast("내 사진 $ok 장을 되살렸습니다")
        refresh()
    }

    private val pickTemplates = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        val (ok, skipped) = templates.importAll(uris)
        Bus.log("🖼 인식용 사진 $ok 장을 등록했습니다." +
                if (skipped.isNotEmpty()) " (PNG가 아니어서 건너뜀: ${skipped.size}개)" else "")
        refresh()
    }

    /** 폰 스크린샷을 골라 보스만 잘라내기 (파일에서) */
    private val pickScreenshot = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) openCrop(uri) }

    /** 사진첩(카메라 사진·스크린샷)에서 골라 보스만 잘라내기 — 기본 */
    private val pickFromGallery = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) openCrop(uri) }

    /**
     * 고른 사진을 여기서 읽어서 자르기 화면에 넘긴다.
     * (사진첩에서 받은 읽기 권한은 이 화면에만 있어서, 주소만 넘기면 자르기 화면이 못 읽는 기기가 있다)
     */
    private fun openCrop(uri: Uri) {
        val bmp = try {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
            var sample = 1
            // 아주 큰 카메라 사진은 줄여서 읽는다 (메모리 부족 방지) — 스크린샷은 그대로
            while (o.outWidth / sample > 4000 || o.outHeight / sample > 4000) sample *= 2
            contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            }
        } catch (_: Throwable) { null }
        if (bmp == null) { toast("사진을 읽지 못했습니다"); return }
        CropActivity.pendingBitmap = bmp
        startActivity(Intent(this, CropActivity::class.java)
            .putExtra(CropActivity.EXTRA_FROM_MEMORY, true)
            .putExtra(CropActivity.EXTRA_MODE, mode.key))
    }

    /**
     * [사진 추가] — 기본은 사진첩에서 스크린샷을 골라 보스만 잘라 넣는다.
     * '파일' 로 바꾸면 이미 잘라 둔 PNG 파일들을 이름 그대로 한꺼번에 가져온다.
     */
    private fun addPictures() {
        if (Prefs.pickFromGallery(this)) {
            try {
                pickFromGallery.launch(androidx.activity.result.PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly))
            } catch (_: Exception) {
                pickScreenshot.launch(arrayOf("image/*"))
            }
        } else {
            pickTemplates.launch(arrayOf("image/png"))
        }
    }

    private val pickVoices = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        var n = 0
        val dir = java.io.File(filesDir, "voices").apply { mkdirs() }
        for (u in uris) {
            try {
                val name = queryName(u) ?: continue
                if (!name.lowercase().endsWith(".mp3")) continue
                contentResolver.openInputStream(u)?.use { ins ->
                    java.io.File(dir, name).outputStream().use { o -> ins.copyTo(o) }
                }
                n++
            } catch (_: Exception) {
            }
        }
        Bus.log("🔊 음성 파일 $n 개를 등록했습니다.")
        refresh()
    }

    private val pickSeqJson = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val store = seqRepo.load()
        val n = seqRepo.importFrom(uri, store)
        Bus.log(if (n > 0) "🗂 기록 ${n}판을 가져왔습니다. (누적 ${store.games.size}판)"
                else "🗂 새로 가져올 판이 없습니다. (이미 있는 기록이거나 형식이 다릅니다)")
        refresh()
    }

    // ── 화면 ─────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val installed = AssetInstaller.installIfNeeded(this)
        templates = TemplateStore(this)
        seqRepo = SeqRepo(this)
        speaker = Speaker(this)
        mode = GameMode.from(Prefs.mode(this))

        if (!OpenCVLoader.initLocal()) {
            toast("OpenCV를 불러오지 못했습니다. 앱을 다시 설치해 주세요.")
        }

        val root = buildUi()
        setContentView(root)
        UiInsets.apply(root)

        if (installed > 0) {
            Bus.log("📦 기본 자료 ${installed}개를 준비했습니다. (보스 사진 · 인식용 사진 · 음성)")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        Bus.onLog = { appendLog(it) }
        Bus.onExit = { finishAndRemoveTask() }
        Bus.addChangeListener(changeListener)
        Bus.recordedGames = seqRepo.load().games.size
        refresh()

        // 처음 켤 때 한 번 — PC 사진을 그대로 쓸지, 폰에서 새로 등록할지 묻는다
        if (!Prefs.templateSourceAsked(this)) askTemplateSource()
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (_: Exception) { "" }

    /**
     * 인식용 사진을 어떻게 쓸지 고른다.
     *   • PC 사진 그대로: 앱에 넣어 둔 PC 캡처를 쓴다. 바로 쓸 수 있지만 폰 화면과 달라 덜 맞을 수 있다.
     *   • 폰에서 새로 등록: 내가 폰에서 캡처한 사진만 쓴다. 처음에 등록하는 수고가 있지만 인식이 가장 정확하다.
     */
    private fun askTemplateSource() {
        val usingDefault = Prefs.usePcTemplates(this)
        val mine = Prefs.userTemplates(this).size
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("인식용 보스 사진을 어떻게 할까요?")
            .setMessage(
                "• 기본 사진 그대로 (추천): 앱에 들어 있는 보스·로비 사진으로 바로 시작합니다. " +
                "인식이 잘 안 되는 보스만 게임 중 [보스설정 → 캡처] 로 추가하면 함께 씁니다.\n\n" +
                "• 직접 새로 등록: 내가 캡처한 사진만 씁니다. 기기마다 화면이 조금씩 달라서 가장 정확하지만, " +
                "보스와 로비 화면을 한 장씩 등록해야 인식이 시작됩니다. 지금 등록된 내 사진: ${mine}장\n\n" +
                "언제든 메인 화면 '인식용 사진' 에서 바꿀 수 있습니다." +
                if (Prefs.templateSourceAsked(this)) "\n\n(지금: ${if (usingDefault) "기본 사진 그대로" else "직접 새로 등록"})" else ""
            )
            .setPositiveButton("기본 사진 그대로") { _, _ ->
                Prefs.setUsePcTemplates(this, true)
                Bus.log("🧩 [인식용 사진] 기본 사진으로 인식합니다. (직접 캡처한 사진도 함께 씁니다)")
                refresh()
            }
            .setNegativeButton("직접 새로 등록") { _, _ ->
                Prefs.setUsePcTemplates(this, false)
                Bus.log("🆕 [인식용 사진] 직접 등록한 사진만 씁니다. 등록 현황은 '저장된 보스 사진 보기' 에서 확인하세요.")
                refresh()
                if (Prefs.userTemplates(this).isEmpty()) showRegisterGuide()
            }
            .setCancelable(Prefs.templateSourceAsked(this))
            .show()
    }

    /** 새로 등록할 때 순서 안내 */
    private fun showRegisterGuide() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("보스 사진 등록하는 법")
            .setMessage(
                "1. [시작] 을 누르고 게임을 켭니다.\n" +
                "2. 보스가 나오면 게임 위 [보스설정 → 캡처] 를 누릅니다.\n" +
                "3. 두 손가락으로 확대해서 주황 네모로 보스만 감싸고, 보스 이름을 고른 뒤 [저장].\n" +
                "4. 로비 화면(태초로비 · 침공로비)과 '다시하기' 화면(공통로비)도 같은 방법으로 등록하면 " +
                "판이 끝날 때 보스 사진이 자동으로 초기화됩니다.\n\n" +
                "태초와 침공은 보스 그림이 조금 달라서 각각 등록해야 합니다. " +
                "등록 현황은 '저장된 보스 사진 보기' 맨 위에 나옵니다."
            )
            .setPositiveButton("알겠어요", null)
            .show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val outer = ScrollView(this).apply { setBackgroundColor(BG) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        // 태블릿처럼 넓은 화면에서는 내용 폭을 제한하고 가운데에 둔다
        val frame = FrameLayout(this)
        frame.addView(col, FrameLayout.LayoutParams(
            UiInsets.contentWidth(this), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        outer.addView(frame)

        // ── 제목 (갑자 캐릭터 + 이름) ──
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.gapja_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(64), dp(64)).also { it.rightMargin = dp(12) })
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(TextView(this).apply {
            text = "운빨보스알림"
            setTextColor(ACCENT); textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })
        titleBox.addView(body("운빨존많겜 보스 알림 · 폰판 v${versionName()}"))
        header.addView(titleBox)
        col.addView(header)

        // ── 상태 ──
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(CARD)
            setLineSpacing(0f, 1.25f)
        }
        col.addView(statusView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).also { it.setMargins(0, dp(12), 0, 0) })

        // ── 시작 ──
        startBtn = Button(this).apply {
            text = "시작"
            textSize = 24f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            isAllCaps = false
            setOnClickListener {
                if (Bus.running && CaptureService.isAlive()) {
                    CaptureService.send(this@MainActivity, CaptureService.ACTION_STOP)
                } else {
                    // 서비스가 없는데 '감시 중' 으로 남아 있으면 바로잡고 다시 시작한다
                    Bus.running = false
                    startCapture()
                }
            }
        }
        col.addView(startBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(72)).also { it.setMargins(0, dp(12), 0, 0) })
        col.addView(row(
            btn("↩️ 되돌리기", "#7F8C8D") { CaptureService.send(this, CaptureService.ACTION_UNDO) },
            btn("🔄 판 종료", "#2980B9") {
                CaptureService.send(this, CaptureService.ACTION_RESET)
                toast("이번 판을 끝내고 초기화했습니다 (침공 판은 기록됩니다)")
            }
        ))

        // ── 인식 방식: 화면 공유 / 접근성 ──
        col.addView(h("인식 방식"))
        methodView = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 12f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(CARD)
            setLineSpacing(0f, 1.3f)
        }
        col.addView(methodView)
        projBtn = btn("📺 화면 공유", "#3C4B5A") {
            Prefs.setUseA11y(this, false); refresh()
            Bus.log("📺 [인식 방식] 화면 공유 — 빠르지만 시작할 때 확인창이 뜹니다.")
        }
        a11yBtn = btn("♿ 접근성 (확인창 없음)", "#3C4B5A") {
            if (!BossA11yService.supported()) { toast("안드로이드 11 이상에서만 쓸 수 있습니다"); return@btn }
            Prefs.setUseA11y(this, true); refresh()
            Bus.log("♿ [인식 방식] 접근성 — 확인창 없이 시작합니다.")
            if (!BossA11yService.isOn(this)) showA11yGuide()
        }
        col.addView(row(projBtn!!, a11yBtn!!))
        a11yOnBtn = btn("⚙️ 접근성 켜러 가기", "#8E44AD") { showA11yGuide() }
        col.addView(a11yOnBtn)
        findGameBtn = btn("🔍 게임 화면 다시 찾기", "#2A3644") {
            BossA11yService.forgetGame(this)
            toast("게임(운빨존많겜)을 켜 두면 자동으로 다시 찾습니다")
            refresh()
        }
        col.addView(findGameBtn)
        col.addView(btn("🖥 화면 진단 (안 보일 때 로그로 확인)", "#2A3644") { diagnoseDisplays() }.also { diagBtn = it })

        // ── 모드 · 배속 (PC판처럼 버튼 하나씩) ──
        col.addView(h("모드 · 배속"))
        modeBtn = btn("", "#8E44AD") {
            mode = if (mode == GameMode.INVASION) GameMode.NORMAL else GameMode.INVASION
            Prefs.setMode(this, mode.key)
            CaptureService.send(this, CaptureService.ACTION_MODE, mode)
            refresh()
        }
        col.addView(modeBtn)
        col.addView(body("누르면 태초 ⇄ 침공. 감시 중 로비 화면이 보이면 자동으로 바뀝니다."))
        val speedBtn = btn("", "#16A085") {}
        fun syncSpeed() {
            val locked = Prefs.speedLock(this)
            speedBtn.text = if (locked) "🔒 배속 고정 (2x 고정 중)" else "🔓 배속 자동 인식 중"
            speedBtn.setBackgroundColor(Color.parseColor(if (locked) "#16A085" else "#9B59B6"))
        }
        speedBtn.setOnClickListener {
            Prefs.setSpeedLock(this, !Prefs.speedLock(this)); syncSpeed()
            Bus.log(if (Prefs.speedLock(this))
                "🔒 [배속 고정] 태초는 2배속, 침공은 앞 ${PcDefaults.INVASION_EARLY_2X_PCT}% 만 2배속으로 계산합니다."
                else "🔓 [배속 고정 해제] 화면의 1배속/2배속 표시를 읽어서 계산합니다.")
        }
        syncSpeed()
        col.addView(speedBtn)
        col.addView(body("기본은 2배속 고정 (태초는 항상 2배속, 침공은 앞 ${PcDefaults.INVASION_EARLY_2X_PCT}% 구간만 2배속). " +
                "누르면 화면의 배속 표시를 읽는 자동 인식으로 바뀝니다."))

        // ── 보스 사진 표시 ──
        col.addView(h("보스 사진 표시"))
        col.addView(check("보스 사진 전체 표시  (끄면 음성만)", Prefs.overlayShowAll(this)) {
            Prefs.setOverlayShowAll(this, it); Bus.settingsChanged()
        })
        col.addView(check("침공 특수보스 사진 표시  (끄면 사진만 숨김, 인식·80R 안내는 그대로)", Prefs.showSpecial(this)) {
            Prefs.setShowSpecial(this, it); Bus.settingsChanged()
        })
        col.addView(check("자동 축소  (보스가 늘면 사진을 줄여 다 보이게)", Prefs.autoShrink(this)) {
            Prefs.setAutoShrink(this, it); Bus.settingsChanged()
        })
        col.addView(check("사진 터치 통과  (켜면 사진 위도 게임이 눌림 · 끄면 사진을 손가락으로 끌어 옮김)",
            Prefs.stripPassThrough(this)) {
            Prefs.setStripPassThrough(this, it); Bus.settingsChanged()
        })
        val hBtn = btn("가로", "#3C4B5A") {}
        val vBtn = btn("세로", "#3C4B5A") {}
        fun syncLayout() {
            val v = Prefs.overlayVertical(this)
            vBtn.setBackgroundColor(Color.parseColor(if (v) "#1ABC9C" else "#3C4B5A"))
            hBtn.setBackgroundColor(Color.parseColor(if (!v) "#1ABC9C" else "#3C4B5A"))
        }
        hBtn.setOnClickListener {
            Prefs.setOverlayVertical(this, false); Prefs.setOverlayLengthDp(this, 0)
            syncLayout(); Bus.settingsChanged()
        }
        vBtn.setOnClickListener {
            Prefs.setOverlayVertical(this, true); Prefs.setOverlayLengthDp(this, 0)
            syncLayout(); Bus.settingsChanged()
        }
        syncLayout()
        col.addView(body("사진 배치"))
        col.addView(row(hBtn, vBtn))
        col.addView(slider("사진 최대 크기", 20, 160, Prefs.overlayMaxDp(this), "dp") {
            Prefs.setOverlayMaxDp(this, it); Bus.settingsChanged()
        })
        col.addView(body("위치·길이는 게임 화면 위의 [조절] 버튼으로 바꿉니다. " +
                "[캡처] 버튼은 지금 화면에서 보스 사진을 잘라 등록합니다."))
        col.addView(btn("🔓 다른 앱 위에 표시 권한 허용", "#8E44AD") {
            if (OverlayService.canDraw(this)) toast("이미 허용되어 있습니다")
            else startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        })

        // ── 음성 ──
        col.addView(h("음성"))
        col.addView(check("보스 인식 음성", Prefs.voiceEnabled(this)) { Prefs.setVoiceEnabled(this, it) })
        col.addView(row(
            btn("🔈 음성 테스트", "#16A085") { speaker.sayBoss("그런트") },
            btn("🔊 MP3 추가", "#2980B9") { pickVoices.launch(arrayOf("audio/mpeg")) }
        ))

        // ── 보스 위험 경보 ──
        buildWarnings(col)

        // ── 인식용 사진: PC 사진 / 새로 등록 ──
        col.addView(h("인식용 사진"))
        sourceView = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(CARD)
            setLineSpacing(0f, 1.3f)
        }
        col.addView(sourceView)
        sourceBtn = btn("", "#8E44AD") { askTemplateSource() }
        col.addView(sourceBtn)
        col.addView(row(
            btn("📦 지금 백업", "#2C3E50") {
                val n = Backup.saveAll(this)
                toast(if (n > 0) "내 사진 ${n}장을 '${Backup.LOCATION}' 에 백업했습니다" else "백업할 내 사진이 아직 없습니다")
            },
            btn("♻️ 백업에서 되살리기", "#2C3E50") {
                toast("'${Backup.LOCATION}' 폴더에서 사진을 모두 골라 주세요")
                pickBackup.launch(arrayOf("image/png"))
            }
        ))
        col.addView(body("직접 캡처한 사진은 앱을 껐다 켜도, 업데이트해도 그대로 남습니다. " +
                "앱을 '삭제' 할 때만 지워지므로, 캡처할 때마다 '${Backup.LOCATION}' 에도 자동으로 한 장씩 남겨 둡니다."))

        // ── 기본 자료 ──
        col.addView(h("기본 자료"))
        resView = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 12f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(CARD)
            setLineSpacing(0f, 1.3f)
        }
        col.addView(resView)
        col.addView(btn("🖼 저장된 보스 사진 · 로비 화면 보기", "#2980B9") {
            startActivity(Intent(this, GalleryActivity::class.java))
        })
        addBtn = btn("", "#34495E") { addPictures() }
        col.addView(addBtn)
        addWhereBtn = btn("", "#2A3644") {
            Prefs.setPickFromGallery(this, !Prefs.pickFromGallery(this)); refresh()
            toast(if (Prefs.pickFromGallery(this)) "사진첩에서 고르도록 바꿨습니다" else "파일에서 PNG를 고르도록 바꿨습니다")
        }
        col.addView(addWhereBtn)

        // ── 로그 ──
        col.addView(h("로그"))
        logScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#FF0E1A24"))
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        logView = TextView(this).apply {
            setTextColor(Color.parseColor("#FFD5DEE6")); textSize = 11f
            typeface = Typeface.MONOSPACE
            setLineSpacing(0f, 1.2f)
        }
        logScroll.addView(logView)
        col.addView(logScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(200)))
        col.addView(btn("로그 지우기", "#7F8C8D") { Bus.clearLogs(); logView.text = "" })

        // ── 고급 설정 (접힘) ──
        val adv = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        col.addView(btn("⚙️ 고급 설정 펼치기 / 접기", "#2A3644") {
            adv.visibility = if (adv.visibility == View.GONE) View.VISIBLE else View.GONE
        }.also { (it.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(16) })
        col.addView(adv)
        buildAdvanced(adv)

        col.addView(body(
            "\n⚠️ 안드로이드 제약\n" +
            "· 앱을 다시 켜면 화면 캡처 동의가 다시 필요합니다 (OS 규칙이라 생략 불가)\n" +
            "· 상태바에 '화면 공유 중' 표시가 계속 남습니다\n" +
            "· 화면이 꺼지면 캡처가 끊깁니다. 게임 중에는 문제되지 않습니다"
        ))

        return outer
    }

    /**
     * 보스 위험 경보 목표 시간 — PC판 설정 화면과 같은 구성.
     * 보스가 인식되고 이 시간(게임 안 시간)이 지나면 경보음(또는 경보 멘트)이 울린다.
     */
    private fun buildWarnings(col: LinearLayout) {
        col.addView(h("보스 위험 경보 목표 시간 (기준 초)"))
        col.addView(check("위험 경보 사용", Prefs.warningEnabled(this)) { Prefs.setWarningEnabled(this, it) })
        col.addView(body("일반은 2배속, 침공은 1배속 플레이가 많아 시간을 다르게 설정할 수 있어요."))

        val fields = ArrayList<Pair<EditText, () -> Int>>()
        fun numField(initial: Int, onSet: (Int) -> Unit): EditText = EditText(this).apply {
            setText(initial.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.BLACK); textSize = 14f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: Editable?) {
                    e?.toString()?.trim()?.toIntOrNull()?.let(onSet)
                }
            })
        }
        fun pair(label: String, color: Int, et: EditText) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(3))
            addView(TextView(this@MainActivity).apply {
                text = label; setTextColor(color); textSize = 13f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(et, LinearLayout.LayoutParams(dp(72), dp(36)))
        }

        val green = Color.parseColor("#FF2ECC71"); val red = Color.parseColor("#FFE74C3C")
        val white = Color.WHITE; val special = Color.parseColor("#FFE74C3C")

        val baseN = numField(Prefs.warnBase(this, GameMode.NORMAL)) { Prefs.setWarnBase(this, GameMode.NORMAL, it) }
        val baseI = numField(Prefs.warnBase(this, GameMode.INVASION)) { Prefs.setWarnBase(this, GameMode.INVASION, it) }
        fields.add(baseN to { Prefs.warnBase(this, GameMode.NORMAL) })
        fields.add(baseI to { Prefs.warnBase(this, GameMode.INVASION) })
        col.addView(pair("🟢 일반(태초) 모드", green, baseN))
        col.addView(pair("🔥 침공 모드", red, baseI))

        col.addView(TextView(this).apply {
            text = "특수 보스 개별 경보 타이머 (초)"
            setTextColor(Color.parseColor("#FF3498DB")); textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(2))
        })
        for ((m, list, title, tc) in listOf(
            Quad(GameMode.NORMAL, PcDefaults.WARN_SPECIAL_NORMAL_LIST, "🟢 일반(태초) 모드", green),
            Quad(GameMode.INVASION, PcDefaults.WARN_SPECIAL_INVASION_LIST, "🔥 침공 모드", red)
        )) {
            col.addView(TextView(this).apply {
                text = title; setTextColor(tc); textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(6), 0, 0)
            })
            for (b in list) {
                val et = numField(Prefs.warnSpecial(this, m, b) ?: Prefs.warnBase(this, m)) {
                    Prefs.setWarnSpecial(this, m, b, it)
                }
                fields.add(et to { Prefs.warnSpecial(this, m, b) ?: Prefs.warnBase(this, m) })
                col.addView(pair(b, if (BossCatalog.isSpecial(b)) special else white, et))
            }
        }
        col.addView(btn("↺ 기본값으로 되돌리기", "#7F8C8D") {
            Prefs.resetWarnings(this)
            for ((et, get) in fields) et.setText(get().toString())
            toast("위험 경보 시간을 기본값으로 되돌렸습니다")
        })
        col.addView(body("경보음은 보스별 기본 경보음 MP3(${AssetInstaller.alarmCount(this)}개)를 씁니다. " +
                "경보음이 없는 보스는 경보 멘트(준비·변신주의·미티어대비 등)를 읽어 줍니다."))
    }

    private data class Quad(val a: GameMode, val b: List<String>, val c: String, val d: Int)

    /** 고급 설정 — 기존 기능을 그대로 두되 평소엔 접어 둔다 */
    private fun buildAdvanced(col: LinearLayout) {
        col.addView(h("성능"))
        col.addView(body("버벅이면 화면 축소율을 낮추고 검사 간격을 늘리세요. (다음 '시작'부터 적용)"))
        col.addView(slider("화면 축소", 25, 100, (Prefs.downscale(this) * 100).toInt(), "%") {
            Prefs.setDownscale(this, it)
        })
        col.addView(slider("검사 간격", 50, 1000, Prefs.intervalMs(this).toInt(), "ms") {
            Prefs.setIntervalMs(this, it)
        })
        col.addView(check("보스 이름 읽기", Prefs.speakBossName(this)) { Prefs.setSpeakBossName(this, it) })
        col.addView(slider("읽기 속도 (내장 TTS)", 50, 200, (Prefs.speechRate(this) * 100).toInt(), "%") {
            Prefs.setSpeechRate(this, it)
        })

        col.addView(h("네임텍 가려짐 구제"))
        col.addView(slider("조각 동의 수 (0=끔)", 0,
            BossCatalog.TILE_ROWS * BossCatalog.TILE_COLS, Prefs.tileMinAgree(this), "/16") {
            Prefs.setTileMinAgree(this, it)
        })
        col.addView(slider("조각 평균 점수", 50, 99, (Prefs.tileMinScore(this) * 100).toInt(), "%") {
            Prefs.setTileMinScore(this, it)
        })

        col.addView(h("80R 안내"))
        col.addView(slider("확정 후 안내 지연", 0, 10000, Prefs.announceDelayMs(this).toInt(), "ms") {
            Prefs.setAnnounceDelayMs(this, it)
        })

        col.addView(h("화면 공유 확인창 없애기 (뮤뮤플레이어 · 루팅 기기)"))
        col.addView(body(
            "[시작] 할 때 뜨는 '화면을 공유하시겠습니까?' 창은 안드로이드 규칙이라 보통은 없앨 수 없습니다. " +
            "다만 루트 권한이 있는 기기(뮤뮤플레이어 설정 → 루트 권한 켜기 등)에서는 한 번 허용해 두면 " +
            "다음부터 창 없이 바로 시작됩니다. (안드로이드 13 이하에서 동작, 기기에 따라 안 될 수 있음)\n" +
            "PC에 연결해 직접 할 때: adb shell appops set $packageName PROJECT_MEDIA allow"
        ))
        col.addView(row(
            btn("🔓 확인창 없애기 (루트)", "#8E44AD") { setProjectionAppOp(true) },
            btn("↺ 되돌리기", "#7F8C8D") { setProjectionAppOp(false) }
        ))

        col.addView(h("인식용 사진 파일"))
        col.addView(row(
            btn("🖼 PNG 파일 추가", "#2980B9") { pickTemplates.launch(arrayOf("image/png")) },
            btn("📊 기록 분석", "#16A085") { startActivity(Intent(this, AnalysisActivity::class.java)) }
        ))

        col.addView(h("기록 주고받기"))
        col.addView(row(
            btn("📥 기록 가져오기", "#2980B9") { pickSeqJson.launch(arrayOf("application/json", "*/*")) },
            btn("📤 내보내기", "#16A085") { shareJson() }
        ))
    }

    // ── 화면 부품 ───────────────────────────────────────────────

    private fun h(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(ACCENT); textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(16), 0, dp(6))
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(DIM); textSize = 12f
        setLineSpacing(0f, 1.2f)
    }

    private fun btn(text: String, color: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor(color))
        isAllCaps = false
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.setMargins(0, dp(4), 0, dp(4)) }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in views) addView(v, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { it.setMargins(dp(2), dp(2), dp(2), dp(2)) })
    }

    private fun check(text: String, initial: Boolean, onSet: (Boolean) -> Unit) = CheckBox(this).apply {
        this.text = text; setTextColor(Color.WHITE); textSize = 13f
        isChecked = initial
        setOnCheckedChangeListener { _, v -> onSet(v) }
    }

    private fun slider(label: String, from: Int, to: Int, initial: Int,
                       unit: String, onSet: (Int) -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 12f
            text = "$label: $initial$unit"
        }
        val sb = SeekBar(this).apply {
            // setMax/setProgress 를 직접 호출한다 (max 는 kotlin.math.max 와 이름이 겹친다)
            setMax(to - from)
            setProgress((initial - from).coerceIn(0, to - from))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    tv.text = "$label: ${from + p}$unit"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    onSet(from + (s?.progress ?: 0))
                }
            })
        }
        box.addView(tv); box.addView(sb)
        return box
    }

    // ── 동작 ─────────────────────────────────────────────────────

    /**
     * 루트 권한으로 '화면 공유 항상 허용' (앱 권한 PROJECT_MEDIA) 을 켜거나 끈다.
     * 켜 두면 안드로이드 13 이하에서는 [시작] 할 때 확인창 없이 바로 시작된다.
     * 루트 요청 창이 뜰 수 있어 백그라운드에서 돌리고, 15초 안에 끝나지 않으면 실패로 본다.
     */
    private fun setProjectionAppOp(allow: Boolean) {
        val cmd = "appops set $packageName PROJECT_MEDIA ${if (allow) "allow" else "default"}"
        toast("루트 권한을 요청합니다… (뜨는 창에서 허용해 주세요)")
        Thread {
            val ok = try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                val done = p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
                if (!done) { p.destroy(); false } else p.exitValue() == 0
            } catch (_: Exception) { false }
            runOnUiThread {
                if (ok) {
                    toast(if (allow) "설정했습니다. 다음 [시작] 부터 확인창 없이 시작됩니다" else "되돌렸습니다")
                    Bus.log(if (allow) "🔓 화면 공유 확인창을 없앴습니다. (루트)" else "↺ 화면 공유 확인창을 다시 띄우도록 되돌렸습니다.")
                } else {
                    toast("루트 권한이 없거나 거부되었습니다")
                    Bus.log("⚠️ 루트 권한이 없어 확인창을 없애지 못했습니다. 뮤뮤플레이어는 설정에서 '루트 권한' 을 켠 뒤 다시 눌러 주세요.")
                }
            }
        }.start()
    }

    private fun startCapture() {
        if (templates.count() == 0) {
            toast("인식용 보스 사진이 없습니다. 고급 설정에서 PNG를 추가해 주세요"); return
        }
        if (!Prefs.usePcTemplates(this) && Prefs.userTemplates(this).isEmpty()) {
            // 새로 등록 모드인데 아직 한 장도 없으면 인식은 안 되지만, 캡처하려면 시작은 해야 한다
            toast("아직 등록한 사진이 없습니다. 게임에서 [보스설정 → 캡처] 로 등록해 주세요")
        }
        if (!OverlayService.canDraw(this)) {
            toast("게임 위에 사진을 띄우려면 '다른 앱 위에 표시'를 허용해 주세요")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            return
        }
        if (Prefs.useA11y(this)) {
            // 접근성 방식 — 확인창 없이 바로 시작
            if (!BossA11yService.isOn(this) || BossA11yService.instance == null) { showA11yGuide(); return }
            CaptureService.startA11y(this, GameMode.from(Prefs.mode(this)),
                Prefs.downscale(this), Prefs.intervalMs(this))
            Toast.makeText(this, "감시를 시작했습니다. 게임을 켜세요", Toast.LENGTH_SHORT).show()
            moveTaskToBack(true)
            return
        }
        projectionLauncher.launch(CaptureService.captureIntent(this))
    }

    /**
     * 접근성 켜는 법 안내.
     * 직접 설치한 앱(APK)은 안드로이드 13부터 접근성을 켜기 전에 '제한된 설정 허용' 을 먼저 해야 한다.
     */
    private fun showA11yGuide() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("접근성 켜기 (확인창 없이 시작)")
            .setMessage(
                "1. [접근성 설정 열기] → '설치된 앱' (또는 '다운로드한 앱') → '운빨보스알림' → 사용 켜기\n\n" +
                "2. '제한된 설정' 이라며 켜지지 않으면:\n" +
                "   [앱 정보 열기] → 오른쪽 위 ⋮ → '제한된 설정 허용' → 다시 1번\n\n" +
                "켜 두면 다음부터 [시작] 할 때 '화면을 공유하시겠습니까?' 창이 뜨지 않습니다. " +
                "화면은 폰 안에서만 보스와 비교하고 어디로도 보내지 않습니다.\n\n" +
                "※ 안드로이드가 화면 찍는 간격을 제한해서 화면 공유 방식보다 조금 느립니다 (약 0.4초마다)."
            )
            .setPositiveButton("접근성 설정 열기") { _, _ ->
                try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {}
            }
            .setNeutralButton("앱 정보 열기") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")))
                } catch (_: Exception) {}
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun shareJson() {
        val store = seqRepo.load()
        if (store.games.isEmpty()) { toast("내보낼 기록이 없습니다"); return }
        val f = seqRepo.exportJson(store) ?: run { toast("내보내기 실패"); return }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "기록 보내기"))
        } catch (e: Exception) {
            toast("공유 실패: ${e.message}")
        }
    }

    private fun refresh() {
        if (!::statusView.isInitialized) return
        val usePc = Prefs.usePcTemplates(this)
        val m = GameMode.from(Prefs.mode(this))
        val targets = BossCatalog.watchList(m) + PcDefaults.lobbiesFor(m)
        val done = targets.count { templates.hasUserShot(it, m) }
        sourceView?.text = (if (usePc) "🧩 기본 사진으로 인식하는 중 (직접 캡처한 사진도 함께 씀)\n"
                else "🆕 직접 등록한 사진만 쓰는 중\n") +
                "내가 등록한 사진: ${Prefs.userTemplates(this).size}장 · ${m.label} 등록 현황 $done/${targets.size}"
        val a11y = Prefs.useA11y(this)
        val a11yOn = BossA11yService.isOn(this)
        projBtn?.setBackgroundColor(Color.parseColor(if (!a11y) "#1ABC9C" else "#3C4B5A"))
        a11yBtn?.setBackgroundColor(Color.parseColor(if (a11y) "#1ABC9C" else "#3C4B5A"))
        a11yOnBtn?.visibility = if (a11y && !a11yOn) View.VISIBLE else View.GONE
        findGameBtn?.visibility = if (a11y && a11yOn) View.VISIBLE else View.GONE
        methodView?.text = if (!a11y)
            "📺 화면 공유 — 가장 빠름 (0.15초마다). [시작] 할 때 '화면을 공유하시겠습니까?' 확인창이 뜹니다.\n" +
            "뮤뮤플레이어에서 화면이 어두워지고 멈추면 → 뒤로가기 → '접근성' 을 고르세요."
        else if (a11yOn)
            "♿ 접근성 — 켜져 있음 ✔. 확인창 없이 바로 시작합니다. (약 0.4초마다 확인)\n" +
            "감시 화면: " + (Bus.gameLabel?.let { "$it (화면 #${Bus.gameDisplay})" }
                ?: "게임을 켜면 자동으로 찾아서 그 화면(뮤뮤 탭)에 보스 사진을 띄웁니다") +
            (if (Bus.overlayDisplay >= 0) " · 보스 사진 표시: 화면 #${Bus.overlayDisplay}" else "")
        else
            "♿ 접근성 — 아직 꺼져 있음. 아래 [접근성 켜러 가기] 로 한 번만 켜 주세요."
        val gal = Prefs.pickFromGallery(this)
        addBtn?.text = if (gal) "📷 사진 추가 — 사진첩에서 스크린샷 골라 보스·로비 잘라 넣기"
        else "📁 사진 추가 — 파일에서 잘라 둔 PNG 가져오기"
        addWhereBtn?.text = "가져올 곳: ${if (gal) "사진첩 (기본)" else "파일"}  · 눌러서 바꾸기"
        sourceBtn?.text = if (usePc) "🆕 직접 새로 등록해서 쓰기로 바꾸기" else "🧩 기본 사진도 같이 쓰기로 바꾸기"
        // 로비 인식으로 서비스가 모드를 바꿨을 수 있으니 저장된 값을 다시 읽는다
        mode = GameMode.from(Prefs.mode(this))
        modeBtn?.let { b ->
            b.text = if (mode == GameMode.INVASION) "🔥 현재 모드: 침공  (눌러서 태초로)" else "🟢 현재 모드: 일반(태초)  (눌러서 침공으로)"
            b.setBackgroundColor(Color.parseColor(if (mode == GameMode.INVASION) "#8E44AD" else "#2C8C6E"))
        }
        val all = BossCatalog.watchList(mode)
        val avail = templates.availableBosses(all, mode).size
        val voices = java.io.File(filesDir, "voices").listFiles { f -> f.name.lowercase().endsWith(".mp3") }?.size ?: 0
        val lobbies = PcDefaults.LOBBIES.count { java.io.File(java.io.File(filesDir, "templates"), "$it.png").exists() }
        resView.text = "게임 위 보스 사진  ${AssetInstaller.displayCount(this)}종\n" +
                "인식용 사진  ${templates.count()}장 (이 모드에서 ${avail}/${all.size}종 인식 가능)\n" +
                "로비 화면  ${lobbies}/3 (태초로비 · 침공로비 · 공통로비)\n" +
                "음성 MP3  ${voices}개 · 위험 경보음 ${AssetInstaller.alarmCount(this)}개"

        startBtn.text = if (Bus.running) "멈춤" else "시작"
        startBtn.setBackgroundColor(Color.parseColor(if (Bus.running) "#C0392B" else "#16A085"))

        val order = Bus.activated
        val sb = StringBuilder()
        sb.append(when {
            !Bus.running -> "○ 멈춤"
            Bus.paused -> "⏸ 일시정지 (게임 위 [보스설정 → 재개])"
            Bus.lobby -> "● 감시 중 · 로비"
            else -> "● 감시 중"
        }).append("   ")
        sb.append(mode.label).append(" 모드 · ").append(PcDefaults.speedLabel(mode, Prefs.speedLock(this))).append('\n')
        sb.append("감시 영역: 폰 전체 화면 (자동)\n")
        if (order.isEmpty()) {
            sb.append("아직 인식된 보스가 없습니다")
        } else {
            val main = order.count { !BossCatalog.isSpecial(it) }
            sb.append("인식 ${order.size}마리 (일반 ${main}마리)\n")
            sb.append(order.joinToString(" → "))
        }
        Bus.boss80R?.let { sb.append("\n🎯 80R 특수 확정: $it") }
        Bus.predicted?.let { sb.append("\n🔮 80R 예상: $it (${Bus.predictedConf.toInt()}%)") }
        statusView.text = sb.toString()

        if (logView.text.isEmpty() && Bus.logs.isNotEmpty()) {
            logView.text = Bus.logs.joinToString("\n")
        }
    }

    private fun appendLog(line: String) {
        logView.append(if (logView.text.isEmpty()) line else "\n$line")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun queryName(u: Uri): String? {
        try {
            contentResolver.query(u, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) return c.getString(i)
            }
        } catch (_: Exception) {
        }
        return u.lastPathSegment
    }

    private fun toast(s: String) =
        Toast.makeText(this, s, Toast.LENGTH_SHORT).also { it.setGravity(Gravity.BOTTOM, 0, 200) }.show()

    override fun onResume() {
        super.onResume()
        Bus.onLog = { appendLog(it) }
        Bus.onExit = { finishAndRemoveTask() }
        Bus.addChangeListener(changeListener)
        Bus.recordedGames = seqRepo.load().games.size
        refresh()
    }

    override fun onDestroy() {
        Bus.onLog = null
        Bus.onExit = null
        Bus.removeChangeListener(changeListener)
        try { speaker.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        private val BG = Color.parseColor("#FF18222C")
        private val CARD = Color.parseColor("#FF223040")
        private val ACCENT = Color.parseColor("#FF1ABC9C")
        private val DIM = Color.parseColor("#FF8C9BA6")
    }
}
