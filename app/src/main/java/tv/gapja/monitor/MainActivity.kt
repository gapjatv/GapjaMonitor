package tv.gapja.monitor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
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
 * 메인 화면 — 시작 버튼 하나, 모드 2개, 꼭 필요한 설정만.
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

        setContentView(buildUi())

        if (installed > 0) {
            Bus.log("📦 기본 자료 ${installed}개를 준비했습니다. (보스 사진 · 인식용 사진 · 음성)")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        Bus.onLog = { appendLog(it) }
        Bus.addChangeListener(changeListener)
        Bus.recordedGames = seqRepo.load().games.size
        refresh()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val outer = ScrollView(this).apply { setBackgroundColor(BG) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        outer.addView(col)

        // ── 제목 ──
        col.addView(TextView(this).apply {
            text = "갑자TV 관제탑"
            setTextColor(ACCENT); textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(body("운빨존많겜 보스 알림 · 폰판"))

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

        // ── 모드 ──
        col.addView(h("모드"))
        val normalBtn = btn("태초\n2배 고정", "#34495E") {}
        val invBtn = btn("침공\n1배 고정", "#8E44AD") {}
        fun syncMode() {
            normalBtn.setBackgroundColor(Color.parseColor(if (mode == GameMode.NORMAL) "#2C8C6E" else "#2A3644"))
            invBtn.setBackgroundColor(Color.parseColor(if (mode == GameMode.INVASION) "#8E44AD" else "#2A3644"))
        }
        normalBtn.setOnClickListener {
            mode = GameMode.NORMAL; Prefs.setMode(this, mode.key)
            CaptureService.send(this, CaptureService.ACTION_MODE, mode); syncMode(); refresh()
        }
        invBtn.setOnClickListener {
            mode = GameMode.INVASION; Prefs.setMode(this, mode.key)
            CaptureService.send(this, CaptureService.ACTION_MODE, mode); syncMode(); refresh()
        }
        syncMode()
        col.addView(row(normalBtn, invBtn))

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

        // ── 기본 자료 ──
        col.addView(h("기본 자료 (PC에서 가져옴)"))
        resView = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 12f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(CARD)
            setLineSpacing(0f, 1.3f)
        }
        col.addView(resView)
        col.addView(btn("✂️ 인식 안 될 때: 폰 스크린샷에서 보스 잘라 넣기", "#34495E") {
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
        if (!OverlayService.canDraw(this)) {
            toast("게임 위에 사진을 띄우려면 '다른 앱 위에 표시'를 허용해 주세요")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            return
        }
        val mgr = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mgr.createScreenCaptureIntent())
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
        val all = BossCatalog.watchList(mode)
        val avail = templates.availableBosses(all, mode).size
        val voices = java.io.File(filesDir, "voices").listFiles { f -> f.name.lowercase().endsWith(".mp3") }?.size ?: 0
        resView.text = "게임 위 보스 사진  ${AssetInstaller.displayCount(this)}종\n" +
                "인식용 사진  ${templates.count()}장 (이 모드에서 ${avail}/${all.size}종 인식 가능)\n" +
                "음성 MP3  ${voices}개 (없는 보스는 폰 내장 TTS)"

        startBtn.text = if (Bus.running) "멈춤" else "시작"
        startBtn.setBackgroundColor(Color.parseColor(if (Bus.running) "#C0392B" else "#16A085"))

        val order = Bus.activated
        val sb = StringBuilder()
        sb.append(if (Bus.running) "● 감시 중" else "○ 멈춤").append("   ")
        sb.append(mode.label).append(" 모드 · ").append(PcDefaults.speedLabel(mode)).append('\n')
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
        Bus.addChangeListener(changeListener)
        Bus.recordedGames = seqRepo.load().games.size
        refresh()
    }

    override fun onDestroy() {
        Bus.onLog = null
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
