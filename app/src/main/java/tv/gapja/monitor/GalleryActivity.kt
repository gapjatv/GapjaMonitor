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
import java.io.File
import kotlin.math.max

/**
 * 저장된 사진 보기 — 어떤 보스·로비 화면이 등록되어 있는지 이름과 함께 보여준다.
 *
 *   ① 게임 위에 뜨는 보스 사진 (display/)
 *   ② 인식용 사진 (templates/)
 *        태초 보스 / 침공 보스(침공_) / 로비 화면 / 배속 표시 / 보조 사진(_2 ~ _4)
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

        val tplDir = File(filesDir, "templates")
        val all = (tplDir.listFiles { f -> f.name.lowercase().endsWith(".png") } ?: emptyArray())
            .sortedBy { it.name }
        val extraRe = Regex("_[2-9]\\.png$")
        val lobbies = all.filter { f -> PcDefaults.LOBBIES.any { f.name.startsWith(it) } }
        val speeds = all.filter { it.name.contains("배속상태") }
        val extras = all.filter { it !in lobbies && it !in speeds && extraRe.containsMatchIn(it.name) }
        val inv = all.filter {
            it.name.startsWith(TemplateStore.INVASION_PREFIX) && it !in speeds && it !in extras
        }
        val normal = all.filter { it !in lobbies && it !in speeds && it !in extras && it !in inv }

        section(col, "로비 화면 (인식되면 보스 사진 초기화)", lobbies, "아직 없습니다. 자르기 화면에서 '태초로비 · 침공로비 · 공통로비' 를 골라 넣어 주세요.")
        section(col, "인식용 사진 · 태초 보스", normal, null)
        section(col, "인식용 사진 · 침공 보스 (침공_)", inv, null)
        section(col, "직접 캡처한 보조 사진 (_2 ~ _4)", extras, "아직 없습니다. 게임 위 [보스설정 → 캡처] 로 추가할 수 있습니다.")
        section(col, "배속 표시 (배속 자동 인식용)", speeds, null)

        val disp = (File(filesDir, "display").listFiles { f -> f.name.lowercase().endsWith(".png") } ?: emptyArray())
            .sortedBy { it.name }
        section(col, "게임 위에 뜨는 기본 보스 사진", disp, null, round = true)
    }

    private fun section(col: LinearLayout, title: String, files: List<File>, emptyText: String?,
                        round: Boolean = false) {
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
            box.addView(TextView(this).apply {
                text = f.name.removeSuffix(".png")
                setTextColor(Color.parseColor("#FFD5DEE6")); textSize = 11f
                gravity = Gravity.CENTER
                maxLines = 2
            })
            grid.addView(box, GridLayout.LayoutParams().apply { width = itemW })
        }
        col.addView(grid)
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
