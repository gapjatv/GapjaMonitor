package tv.gapja.monitor

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import tv.gapja.monitor.core.BossCatalog
import tv.gapja.monitor.core.GameMode
import java.io.File
import kotlin.math.max

/**
 * 저장된 사진 보기 — 어떤 보스·로비 화면이 등록되어 있는지 이름과 함께 보여준다.
 *
 *   ① 게임 위에 뜨는 보스 사진 (display/)
 *   ② 인식용 사진 (templates/)
 *        태초 보스 / 침공 보스(침공_) / 로비 화면 / 배속 표시 / 보조 사진(_2)
 *
 * 보조 사진은 짧게 누르면 바로 지울 수 있다 (한 번 더 확인). (v2.8)
 */
class GalleryActivity : AppCompatActivity() {

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#FF18222C")) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(24))
        }
        scroll.addView(col)
        setContentView(scroll)
        UiInsets.apply(scroll)

        col.addView(TextView(this).apply {
            text = "저장된 보스 사진"
            setTextColor(Color.parseColor("#FF1ABC9C")); textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        })

        // ── 등록 현황 (폰에서 직접 등록한 사진 기준) ──
        val store = TemplateStore(this)
        col.addView(TextView(this).apply {
            text = "각 칸에는 지금 인식에 실제로 쓰이는 사진 한 장만 보여 줍니다.\n" +
                    "• ✔ 내 사진 — 내가 직접 등록한 사진 (있으면 앱 기본 사진보다 우선합니다)\n" +
                    "• 기본 사진 — 앱에 처음부터 들어 있는 사진 (등록하지 않아도 이 사진으로 인식합니다)\n" +
                    "• ＋ 등록 필요 — 아직 사진이 없는 칸입니다. 눌러서 등록 방법을 확인하세요."
            setTextColor(Color.parseColor("#FF8C9BA6")); textSize = 12f
            setPadding(0, dp(6), 0, 0)
            setLineSpacing(0f, 1.25f)
        })
        for (m in listOf(GameMode.NORMAL, GameMode.INVASION)) {
            val targets = BossCatalog.watchList(m) + PcDefaults.lobbiesFor(m)
            val done = targets.count { store.hasUserShot(it, m) }
            col.addView(TextView(this).apply {
                text = "${m.label} 등록 현황  $done / ${targets.size}"
                setTextColor(Color.WHITE); textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(14), 0, dp(4))
            })
            val avail = UiInsets.contentWidth(this, 900) - dp(28)
            val cols = max(2, avail / dp(110))
            val grid = GridLayout(this).apply { columnCount = cols }
            for (t in targets) {
                val ok = store.hasUserShot(t, m)
                grid.addView(TextView(this).apply {
                    text = (if (ok) "✔ " else "○ ") + t
                    setTextColor(Color.parseColor(if (ok) "#FF2ECC71" else "#FFE0E6EB"))
                    textSize = 12f
                    setTypeface(typeface, if (PcDefaults.isLobby(t)) Typeface.BOLD else Typeface.NORMAL)
                    setPadding(dp(4), dp(3), dp(4), dp(3))
                }, GridLayout.LayoutParams().apply { width = avail / cols })
            }
            col.addView(grid)
        }

        val tplDir = File(filesDir, "templates")
        val all = (tplDir.listFiles { f -> f.name.lowercase().endsWith(".png") } ?: emptyArray())
            .sortedBy { it.name }

        fun cell(label: String, fileName: String?): Cell {
            val f = fileName?.let { File(tplDir, it) }?.takeIf { it.exists() }
            return Cell(label, f, f != null && f.name in Prefs.userTemplates(this))
        }

        col.addView(TextView(this).apply {
            text = "※ 사진을 길게 누르면 지우기 · 이전 사진으로 되돌리기 · 앱 기본 사진으로 되돌리기"
            setTextColor(Color.parseColor("#FFF39C12")); textSize = 12f
            setPadding(0, dp(12), 0, 0)
        })
        val normalCells = BossCatalog.watchList(GameMode.NORMAL).map { cell(it, store.activeBossFile(it, GameMode.NORMAL)) }
        val invCells = BossCatalog.watchList(GameMode.INVASION).map { cell(it, store.activeBossFile(it, GameMode.INVASION)) }
        val lobbyCells = listOf(PcDefaults.LOBBY_NORMAL, PcDefaults.LOBBY_INVASION).map { cell(it, store.lobbyShots(it).firstOrNull()) } +
                PcDefaults.COMMON_SLOTS.map { cell(it, PcDefaults.commonFile(it)) }
        val speedCells = PcDefaults.SPEED_SHOTS.map { cell("태초 $it", "$it.png") } +
                PcDefaults.SPEED_SHOTS.map { cell("침공 $it", "${TemplateStore.INVASION_PREFIX}$it.png") }
        cellSection(col, "로비 화면 (인식되면 보스 사진 초기화)", lobbyCells)
        cellSection(col, "인식용 사진 · 태초 보스", normalCells)
        cellSection(col, "인식용 사진 · 침공 보스 · 침공특수보스 · 침공 히든보스", invCells)
        // 보조 사진(_2) — 내가 등록한 것만 (공통로비_2 는 공통로비2 칸이라 여기서 뺀다)
        val extras = all.filter {
            it.name.endsWith("_2.png") && it.name in Prefs.userTemplates(this) && it.name !in PcDefaults.COMMON_FILES
        }
        section(col, "내 보조 사진 (_2) · 눌러서 지우기 — 보스마다 1장", extras,
            "아직 없습니다. 같은 보스를 한 번 더 등록하면 보조 사진이 됩니다. (게임 위 [보스설정 → 보스캡처])",
            tapDelete = true)
        cellSection(col, "배속 표시 (배속 자동 인식용 — 태초·침공 따로)", speedCells)

        val disp = (File(filesDir, "display").listFiles { f -> f.name.lowercase().endsWith(".png") } ?: emptyArray())
            .sortedBy { it.name }
        section(col, "게임 위에 뜨는 기본 보스 사진", disp, null, round = true)
    }

    private data class Cell(val label: String, val file: File?, val mine: Boolean)

    /** 칸마다 '지금 쓰는 사진 한 장'을 보여 준다. 사진이 없으면 이름만 보이는 빈 칸(＋ 등록 필요). */
    private fun cellSection(col: LinearLayout, title: String, cells: List<Cell>) {
        val have = cells.count { it.file != null }
        col.addView(TextView(this).apply {
            text = "$title  ·  $have / ${cells.size}"
            setTextColor(Color.WHITE); textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(6))
        })
        val avail = UiInsets.contentWidth(this, 900) - dp(28)
        val cols = max(2, avail / dp(120))
        val grid = GridLayout(this).apply { columnCount = cols }
        val itemW = avail / cols
        for (c in cells) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(4), dp(4), dp(6))
            }
            val h = (itemW * 0.7f).toInt()
            if (c.file != null) {
                box.addView(ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(Color.parseColor("#FF0E1A24"))
                    setImageBitmap(loadThumb(c.file, itemW))
                }, LinearLayout.LayoutParams(itemW - dp(8), h))
                box.setOnLongClickListener { manage(c.file); true }
            } else {
                // 사진이 없으면 비워 두고 이름만 — 눌러서 등록 방법 확인
                box.addView(TextView(this).apply {
                    text = "＋"
                    gravity = Gravity.CENTER
                    setTextColor(Color.parseColor("#FF5D6D7E")); textSize = 22f
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#FF0E1A24"))
                        setStroke(dp(1), Color.parseColor("#FF3A4B5C"), dp(4).toFloat(), dp(3).toFloat())
                        cornerRadius = dp(6).toFloat()
                    }
                }, LinearLayout.LayoutParams(itemW - dp(8), h))
                box.setOnClickListener { showRegisterHelp(c.label) }
            }
            box.addView(TextView(this).apply {
                text = c.label + when {
                    c.file == null -> "\n＋ 등록 필요"
                    c.mine -> "\n✔ 내 사진"
                    else -> "\n기본 사진"
                }
                setTextColor(Color.parseColor(if (c.file == null) "#FFF39C12" else "#FFD5DEE6")); textSize = 11f
                gravity = Gravity.CENTER
                maxLines = 2
            })
            grid.addView(box, GridLayout.LayoutParams().apply { width = itemW })
        }
        col.addView(grid)
    }

    /** 사진이 없는 칸을 눌렀을 때 — 등록 방법 */
    private fun showRegisterHelp(label: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("'$label' 사진이 아직 없습니다")
            .setMessage("이 칸은 아직 인식용 사진이 없어서 인식되지 않습니다.\n\n" +
                    "등록하는 법\n" +
                    "1. 게임에서 이 화면(또는 보스)이 보일 때 게임 위 [보스설정 → 보스캡처] 를 누릅니다.\n" +
                    "2. 자르기 화면에서 위쪽 [태초/침공] 과 '어떤 보스(로비)인가요?' 에서 '$label' 에 맞는 항목을 고릅니다.\n" +
                    "3. 주황 네모로 감싸고 [등록] 을 누릅니다.\n\n" +
                    "이미 찍어 둔 화면이 있으면 메인 화면 [캡처함] 에서 잘라 등록해도 됩니다.")
            .setPositiveButton("캡처함 열기") { _, _ ->
                try { startActivity(android.content.Intent(this, SnapsActivity::class.java)) } catch (_: Exception) {}
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun section(col: LinearLayout, title: String, files: List<File>, emptyText: String?,
                        round: Boolean = false, tapDelete: Boolean = false) {
        col.addView(TextView(this).apply {
            text = "$title  ·  ${files.size}장"
            setTextColor(Color.WHITE); textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(6))
        })
        if (files.isEmpty()) {
            col.addView(TextView(this).apply {
                text = emptyText ?: "없습니다."
                setTextColor(Color.parseColor("#FF8C9BA6")); textSize = 12f
            })
            return
        }
        val avail = UiInsets.contentWidth(this, 900) - dp(28)
        val cell = dp(if (round) 92 else 120)
        val cols = max(2, avail / cell)
        val grid = GridLayout(this).apply { columnCount = cols }
        val itemW = avail / cols
        for (f in files) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(4), dp(4), dp(6))
            }
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.parseColor(if (round) "#00000000" else "#FF0E1A24"))
                setImageBitmap(loadThumb(f, itemW))
            }
            box.addView(iv, LinearLayout.LayoutParams(itemW - dp(8), if (round) itemW - dp(8) else (itemW * 0.7f).toInt()))
            val mine = f.name in Prefs.userTemplates(this)
            // 내 사진이 있는 보스의 기본 사진은 인식에 쓰지 않는다 — 흐리게 + '사용 안 함' 표시
            val shadowed = f.parentFile?.name == "templates" && !mine && TemplateStore(this).shadowedByUser(f.name)
            // 내 사진은 보조 1장(_2)까지만 쓴다 — 예전에 등록한 _3·_4 는 쓰지 않는다 (v2.8)
            val overLimit = mine && Regex("_[3-9]\\.png$").containsMatchIn(f.name)
            if (shadowed || overLimit) iv.alpha = 0.35f
            box.addView(TextView(this).apply {
                text = f.name.removeSuffix(".png") + when {
                    overLimit -> "\n⛔ 사용 안 함 (보조는 1장만)"
                    mine -> "\n✔ 내 사진"
                    shadowed -> "\n⛔ 사용 안 함 (내 사진 우선)"
                    else -> ""
                }
                setTextColor(Color.parseColor(if (shadowed) "#FF7F8C8D" else "#FFD5DEE6")); textSize = 11f
                gravity = Gravity.CENTER
                maxLines = 2
            })
            // 인식용 사진은 길게 누르면 지우기·되돌리기
            if (f.parentFile?.name == "templates") box.setOnLongClickListener { manage(f); true }
            // 보조 사진은 짧게 눌러도 지울 수 있다
            if (tapDelete && f.parentFile?.name == "templates") box.setOnClickListener { deleteExtra(f) }
            grid.addView(box, GridLayout.LayoutParams().apply { width = itemW })
        }
        col.addView(grid)
    }

    /**
     * 인식용 사진 관리 — 지우기 / 이전 사진으로 되돌리기 / 앱 기본 사진으로 되돌리기.
     * 잘못 누르지 않게 고른 뒤 한 번 더 확인한다.
     */
    private fun manage(f: File) {
        val store = TemplateStore(this)
        val name = f.name
        val actions = ArrayList<Pair<String, () -> Boolean>>()
        if (store.hasPrevious(name)) actions.add("이전 사진으로 되돌리기" to { store.restorePrevious(name) })
        if (store.hasBundled(name) && name in Prefs.userTemplates(this))
            actions.add("앱 기본 사진으로 되돌리기" to { store.restoreBundled(name) })
        actions.add("이 사진 지우기" to { store.delete(name) })
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(name.removeSuffix(".png"))
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                val (label, act) = actions[which]
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("정말 할까요?")
                    .setMessage("'${name.removeSuffix(".png")}' — $label\n\n" +
                            "바뀌기 전 사진은 보관해 두므로 '이전 사진으로 되돌리기' 로 다시 복구할 수 있습니다.")
                    .setPositiveButton("확인") { _, _ ->
                        val ok = act()
                        android.widget.Toast.makeText(this, if (ok) "$label 완료" else "실패했습니다",
                            android.widget.Toast.LENGTH_SHORT).show()
                        if (ok) Bus.log("🗂 [사진 관리] ${name.removeSuffix(".png")} — $label")
                        recreate()
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    /** 보조 사진 지우기 — 한 번 더 확인하고 지운다 */
    private fun deleteExtra(f: File) {
        val store = TemplateStore(this)
        val name = f.name
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("보조 사진을 지울까요?")
            .setMessage("'${name.removeSuffix(".png")}'\n\n" +
                    "등록할 때 '${Backup.LOCATION}' 에도 한 장 남겨 두었으니, 필요하면 메인 화면 " +
                    "[♻️ 백업에서 되살리기] 로 다시 넣을 수 있습니다.")
            .setPositiveButton("지우기") { _, _ ->
                val ok = store.delete(name)
                android.widget.Toast.makeText(this, if (ok) "보조 사진을 지웠습니다" else "지우지 못했습니다",
                    android.widget.Toast.LENGTH_SHORT).show()
                if (ok) Bus.log("🗂 [사진 관리] ${name.removeSuffix(".png")} — 보조 사진 지우기")
                recreate()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 미리보기용으로 작게 읽는다 (메모리 절약) */
    private fun loadThumb(f: File, target: Int) = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= target && o.outHeight / (sample * 2) >= target / 2) sample *= 2
        BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (_: Exception) { null }
}
