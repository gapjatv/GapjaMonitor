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
    private var sourceBtn: Button? = null

    private val changeListener: () -> Unit = { refresh() }

    // ── 권한 / 선택 런처 ─────────────────────────────────────────

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            Bus.running = true
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

    /** 폰 스크린샷을 골라 보스만 잘라내기 */
    private val pickScreenshot = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        startActivity(Intent(this, CropActivity::class.java)
            .putExtra(CropActivity.EXTRA_URI, uri.toString())
            .putExtra(CropActivity.EXTRA_MODE, mode.key)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
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
        Bus.log(if (n > 0) "🗂 PC 기록 ${n}판을 가져왔습니다. (누적 ${store.games.size}판)"
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
        val usingPc = Prefs.usePcTemplates(this)
        val mine = Prefs.userTemplates(this).size
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("인식용 보스 사진을 어떻게 할까요?")
            .setMessage(
                "PC 화면과 폰 화면은 크기·모양이 조금씩 달라서, 폰에서 직접 캡처한 사진이 인식이 가장 정확하고 " +
                "엉뚱한 곳(로비 그림 등)에 덜 걸립니다.\n\n" +
                "• 새로 등록 (추천): 내가 폰에서 캡처한 사진만 씁니다. 게임 중 [보스설정 → 캡처] 로 " +
                "보스와 로비 화면을 한 장씩 등록하세요. 지금 등록된 내 사진: ${mine}장\n\n" +
                "• PC 사진 그대로: 앱에 들어 있는 PC 캡처를 씁니다. 바로 쓸 수 있습니다.\n\n" +
                "언제든 메인 화면 '인식용 사진' 에서 바꿀 수 있습니다." +
                if (Prefs.templateSourceAsked(this)) "\n\n(지금: ${if (usingPc) "PC 사진 그대로" else "새로 등록"})" else ""
            )
            .setPositiveButton("새로 등록") { _, _ ->
                Prefs.setUsePcTemplates(this, false)
                Bus.log("🆕 [인식용 사진] 폰에서 새로 등록한 사진만 씁니다. 등록 현황은 '저장된 보스 사진 보기' 에서 확인하세요.")
                refresh()
                if (Prefs.userTemplates(this).isEmpty()) showRegisterGuide()
            }
            .setNegativeButton("PC 사진 그대로") { _, _ ->
                Prefs.setUsePcTemplates(this, true)
                Bus.log("🖥 [인식용 사진] 앱에 들어 있는 PC 사진을 씁니다. (직접 캡처한 사진도 함께 씁니다)")
                refresh()
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
            text = "갑자TV 관제탑"
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
                if (Bus.running) CaptureService.send(this@MainActivity, CaptureService.ACTION_STOP)
                else startCapture()
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
        col.addView(slider("사진 최대 크기", 24, 120, Prefs.overlayMaxDp(this), "dp") {
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
        col.addView(h("기본 자료 (PC에서 가져옴)"))
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
        col.addView(btn("✂️ 인식 안 될 때: 폰 스크린샷에서 보스·로비 잘라 넣기", "#34495E") {
            pickScreenshot.launch(arrayOf("image/*"))
        })

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
        col.addView(btn("↺ PC 기본값으로 되돌리기", "#7F8C8D") {
            Prefs.resetWarnings(this)
            for ((et, get) in fields) et.setText(get().toString())
            toast("위험 경보 시간을 PC 기본값으로 되돌렸습니다")
        })
        col.addView(body("경보음은 PC sounds 폴더의 보스별 MP3(${AssetInstaller.alarmCount(this)}개)를 씁니다. " +
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

        col.addView(h("인식용 사진 파일"))
        col.addView(row(
            btn("🖼 PNG 추가", "#2980B9") { pickTemplates.launch(arrayOf("image/png")) },
            btn("📊 기록 분석", "#16A085") { startActivity(Intent(this, AnalysisActivity::class.java)) }
        ))

        col.addView(h("PC와 기록 주고받기"))
        col.addView(row(
            btn("📥 PC 기록 가져오기", "#2980B9") { pickSeqJson.launch(arrayOf("application/json", "*/*")) },
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
        projectionLauncher.launch(CaptureService.captureIntent(this))
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
        sourceView?.text = (if (usePc) "🖥 PC 사진 그대로 쓰는 중 (직접 캡처한 사진도 함께 씀)\n"
                else "🆕 폰에서 새로 등록한 사진만 쓰는 중\n") +
                "내가 등록한 사진: ${Prefs.userTemplates(this).size}장 · ${m.label} 등록 현황 $done/${targets.size}"
        sourceBtn?.text = if (usePc) "🆕 폰에서 새로 등록해서 쓰기로 바꾸기" else "🖥 PC 사진도 같이 쓰기로 바꾸기"
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
