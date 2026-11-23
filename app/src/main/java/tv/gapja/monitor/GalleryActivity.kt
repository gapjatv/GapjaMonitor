package tv.gapja.monitor

import android.graphics.BitmapFactory
import android.graphics.Color
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
 * 깃허브 자동 저장이 켜져 있으면 지운 사진은 깃허브 사진 저장소에서도 지운다.
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
            text = if (Prefs.usePcTemplates(this@GalleryActivity))
                "지금은 기본 사진을 함께 쓰는 중입니다. 아래 ✔ 는 직접 등록한 사진이 있는 항목이에요. " +
                        "✔ 항목은 내 사진만 쓰고 그 보스의 기본 사진은 쓰지 않습니다."
            else "지금은 직접 등록한 사진만 씁니다. ○ 인 항목은 아직 인식되지 않으니 [보스설정 → 보스캡처] 로 등록해 주세요."
            setTextColor(Color.parseColor("#FF8C9BA6")); textSize = 12f
            setPadding(0, dp(6), 0, 0)
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
        val extraRe = Regex("_[2-9]\\.png$")
        val lobbies = all.filter { f -> PcDefaults.LOBBIES.any { f.name.startsWith(it) } }
        val speeds = all.filter { it.name.contains("배속상태") }
        val extras = all.filter { it !in lobbies && it !in speeds && extraRe.containsMatchIn(it.name) }
        // 침공에만 나오는 보스(침공특수보스 3종)는 이름에 '침공_' 이 없어도 태초 칸에 넣지 않는다 (v2.3)
        val inv = all.filter {
            (it.name.startsWith(TemplateStore.INVASION_PREFIX) ||
                    BossCatalog.isInvasionOnly(it.name.removeSuffix(".png"))) &&
                    it !in speeds && it !in extras
        }
        val normal = all.filter { it !in lobbies && it !in speeds && it !in extras && it !in inv }

        col.addView(TextView(this).apply {
            text = "※ 보조 사진은 누르면 지우기 · 인식용 사진을 길게 누르면 지우기 · 이전 사진으로 되돌리기 · 앱 기본 사진으로 되돌리기"
            setTextColor(Color.parseColor("#FFF39C12")); textSize = 12f
            setPadding(0, dp(12), 0, 0)
        })
        section(col, "로비 화면 (인식되면 보스 사진 초기화)", lobbies, "아직 없습니다. 자르기 화면에서 '태초로비 · 침공로비 · 공통로비' 를 골라 넣어 주세요.")
        section(col, "인식용 사진 · 태초 보스", normal, null)
        section(col, "인식용 사진 · 침공 보스 (침공_) · 침공특수보스 · 침공 히든보스", inv, null)
        section(col, "보조 사진 (_2) · 눌러서 지우기 — 보스마다 1장", extras,
            "아직 없습니다. 같은 보스를 한 번 더 등록하면 보조 사진이 됩니다. (게임 위 [보스설정 → 보스캡처])",
            tapDelete = true)
        section(col, "배속 표시 (배속 자동 인식용)", speeds, null)

        val disp = (File(filesDir, "display").listFiles { f -> f.name.lowercase().endsWith(".png") } ?: emptyArray())
            .sortedBy { it.name }
        section(col, "게임 위에 뜨는 기본 보스 사진", disp, null, round = true)
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
        val mine = name in Prefs.userTemplates(this)
        val gh = if (mine && GitHubSync.active(this)) "\n☁️ 깃허브 사진 저장소에서도 지웁니다." else ""
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("보조 사진을 지울까요?")
            .setMessage("'${name.removeSuffix(".png")}'\n\n" +
                    "등록할 때 '${Backup.LOCATION}' 에도 한 장 남겨 두었으니, 필요하면 메인 화면 " +
                    "[♻️ 백업에서 되살리기] 로 다시 넣을 수 있습니다." + gh)
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
