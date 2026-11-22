package tv.gapja.monitor

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * 캡처함 — 게임 중 [보스설정 → 보스캡처] 로 저장한 화면 목록.
 *   • 누르면 자르기 화면이 열린다 (한 장에서 여러 보스를 차례로 잘라 넣을 수 있다)
 *   • 길게 누르면 지우기
 */
class SnapsActivity : AppCompatActivity() {

    private lateinit var col: LinearLayout
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#FF18222C")) }
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(24))
        }
        scroll.addView(col)
        setContentView(scroll)
        UiInsets.apply(scroll)
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        col.removeAllViews()
        val files = Snaps.list(this)
        col.addView(TextView(this).apply {
            text = "📸 캡처함  ·  ${files.size}장"
            setTextColor(Color.parseColor("#FF1ABC9C")); textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = "게임 중 [보스설정 → 보스캡처] 로 저장한 화면이에요. 누르면 잘라서 보스·로비 사진으로 등록하고, " +
                    "길게 누르면 지웁니다.\n초록 테두리 + ✔ 표시 = 이미 잘라서 등록한 캡처 (무엇을 등록했는지 아래에 적혀 있어요)."
            setTextColor(Color.parseColor("#FF8C9BA6")); textSize = 12f
            setPadding(0, dp(6), 0, dp(8))
        })
        if (files.isNotEmpty()) {
            col.addView(Button(this).apply {
                text = "🗑 모두 지우기"; setTextColor(Color.WHITE); isAllCaps = false
                setBackgroundColor(Color.parseColor("#C0392B"))
                setOnClickListener {
                    AlertDialog.Builder(this@SnapsActivity)
                        .setTitle("캡처함 비우기")
                        .setMessage("캡처한 화면 ${files.size}장을 모두 지울까요?\n(이미 잘라서 등록한 보스 사진은 지워지지 않습니다)")
                        .setPositiveButton("모두 지우기") { _, _ ->
                            Snaps.list(this@SnapsActivity).forEach { it.delete() }
                            Snaps.forgetAll(this@SnapsActivity); build()
                        }
                        .setNegativeButton("취소", null).show()
                }
            })
        } else {
            col.addView(TextView(this).apply {
                text = "아직 캡처한 화면이 없습니다."
                setTextColor(Color.WHITE); textSize = 14f
                setPadding(0, dp(20), 0, 0)
            })
            return
        }
        val avail = UiInsets.contentWidth(this, 900) - dp(28)
        val cols = max(2, avail / dp(150))
        val itemW = avail / cols
        val grid = GridLayout(this).apply { columnCount = cols }
        val fmt = SimpleDateFormat("M/d HH:mm:ss", Locale.KOREA)
        val done = files.count { Snaps.registered(this, it).isNotEmpty() }
        col.addView(TextView(this).apply {
            text = "✔ 등록함 ${done}장  ·  ○ 아직 ${files.size - done}장"
            setTextColor(Color.parseColor("#FF2ECC71")); textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(4))
        })
        for (f in files) {
            val reg = Snaps.registered(this, f)
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(6), dp(6), dp(6), dp(8))
                // 등록한 캡처는 초록 테두리 + 흐린 초록 바탕으로 한눈에 구분
                if (reg.isNotEmpty()) background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.parseColor("#3327AE60"))
                    setStroke(dp(3), Color.parseColor("#FF2ECC71"))
                }
                setOnClickListener { openCrop(f) }
                setOnLongClickListener { confirmDelete(f); true }
            }
            box.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.parseColor("#FF0E1A24"))
                setImageBitmap(thumb(f, itemW))
            }, LinearLayout.LayoutParams(itemW - dp(12), ((itemW - dp(12)) * 1.6f).toInt()))
            if (reg.isNotEmpty()) box.addView(TextView(this).apply {
                text = "✔ 등록: " + reg.joinToString(", ")
                setTextColor(Color.parseColor("#FF2ECC71")); textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            })
            val hint = Snaps.hintOf(f)
            box.addView(TextView(this).apply {
                text = "${Snaps.modeOf(f).label} · ${fmt.format(Date(f.lastModified()))}" +
                        (hint?.let { "\n($it 인식 때)" } ?: "")
                setTextColor(Color.parseColor("#FFD5DEE6")); textSize = 11f
                gravity = Gravity.CENTER
            })
            grid.addView(box, GridLayout.LayoutParams().apply { width = itemW })
        }
        col.addView(grid)
    }

    private fun openCrop(f: File) {
        startActivity(Intent(this, CropActivity::class.java)
            .putExtra(CropActivity.EXTRA_PATH, f.absolutePath)
            .putExtra(CropActivity.EXTRA_MODE, Snaps.modeOf(f).key)
            .putExtra(CropActivity.EXTRA_HINT, Snaps.hintOf(f)))
    }

    private fun confirmDelete(f: File) {
        AlertDialog.Builder(this)
            .setTitle("캡처 지우기")
            .setMessage("이 캡처 화면을 지울까요?")
            .setPositiveButton("지우기") { _, _ ->
                Snaps.forget(this, f); f.delete()
                Toast.makeText(this, "지웠습니다", Toast.LENGTH_SHORT).show(); build()
            }
            .setNegativeButton("취소", null).show()
    }

    private fun thumb(f: File, target: Int) = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= target) sample *= 2
        BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (_: Exception) { null }
}
