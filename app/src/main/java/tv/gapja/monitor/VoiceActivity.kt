package tv.gapja.monitor

import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import tv.gapja.monitor.core.BossCatalog
import java.io.File

/**
 * 보스별 음성 바꾸기.
 *
 *   • 인식 음성 — 보스가 처음 나왔을 때 알려주는 소리 ("그런트", "샤먼" …)
 *   • 경보 음성 — 보스가 나온 뒤 위험 경보 시간에 울리는 소리 (경보음 · "변신주의" 등)
 *
 * [변경] 으로 고른 MP3 는 따로 보관하고(voices_user / alarms_user) 원본은 건드리지 않는다.
 * [원본] 을 누르면 내가 지정한 파일을 지우고 앱에 들어 있던 원래 소리로 돌아간다.
 */
class VoiceActivity : AppCompatActivity() {

    private lateinit var col: LinearLayout
    private lateinit var speaker: Speaker

    /** 지금 파일을 고르는 대상 (보스, 경보인지) */
    private var picking: Pair<String, Boolean>? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val pickMp3 = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = picking ?: return@registerForActivityResult
        picking = null
        if (uri != null) setFile(target.first, target.second, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speaker = Speaker(this)
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#FF18222C")) }
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(24))
        }
        scroll.addView(col)
        setContentView(scroll)
        UiInsets.apply(scroll)
        build()
    }

    override fun onDestroy() {
        speaker.release()
        super.onDestroy()
    }

    private fun bosses(): List<String> =
        (BossCatalog.BOSS_NAMES + BossCatalog.INVASION_SPECIAL).distinct()

    private fun build() {
        col.removeAllViews()
        col.addView(TextView(this).apply {
            text = "🎵 보스별 음성 바꾸기"
            setTextColor(Color.parseColor("#FF1ABC9C")); textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = "• 인식 음성: 보스가 처음 나왔을 때 알려주는 소리\n" +
                    "• 경보 음성: 보스가 나온 뒤 위험 경보 시간에 울리는 소리\n\n" +
                    "[변경] 을 누르고 MP3 파일을 고르면 그 보스에만 바로 적용됩니다. 파일 이름은 아무거나 괜찮아요.\n" +
                    "[원본] 을 누르면 앱에 들어 있던 원래 소리로 돌아갑니다. ▶ 는 미리 듣기."
            setTextColor(Color.parseColor("#FF8C9BA6")); textSize = 12f
            setPadding(0, dp(6), 0, dp(10))
        })
        for (b in bosses()) addBoss(b)
    }

    private fun addBoss(boss: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat(); setColor(Color.parseColor("#FF223040"))
            }
        }
        card.addView(TextView(this).apply {
            text = boss + if (BossCatalog.isSpecial(boss)) "  (침공특수보스)" else ""
            setTextColor(Color.WHITE); textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        })
        card.addView(line(boss, alarm = false))
        card.addView(line(boss, alarm = true))
        col.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.setMargins(0, dp(4), 0, dp(6)) })
    }

    /** 한 줄: 상태 글자 + ▶ + 변경 + 원본 */
    private fun line(boss: String, alarm: Boolean): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        val mine = if (alarm) Speaker.userAlarm(this, boss) else Speaker.userVoice(this, boss)
        val custom = mine.exists() && mine.length() > 0
        row.addView(TextView(this).apply {
            text = (if (alarm) "경보: " else "인식: ") + status(boss, alarm, custom)
            setTextColor(Color.parseColor(if (custom) "#FFF39C12" else "#FFD5DEE6")); textSize = 12f
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(small("▶", "#16A085") { play(boss, alarm) })
        row.addView(small("변경", "#2980B9") {
            picking = boss to alarm
            try { pickMp3.launch(arrayOf("audio/*")) } catch (_: Exception) {
                Toast.makeText(this, "파일 고르기를 열 수 없습니다", Toast.LENGTH_SHORT).show()
            }
        })
        row.addView(small("원본", if (custom) "#C0392B" else "#3C4B5A") { askRestore(boss, alarm, custom) })
        return row
    }

    private fun small(t: String, color: String, act: () -> Unit) = Button(this).apply {
        text = t; setTextColor(Color.WHITE); isAllCaps = false; textSize = 12f
        minWidth = 0; minimumWidth = 0; setPadding(dp(8), 0, dp(8), 0)
        setBackgroundColor(Color.parseColor(color))
        setOnClickListener { act() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36))
            .also { it.setMargins(dp(3), 0, 0, 0) }
    }

    private fun status(boss: String, alarm: Boolean, custom: Boolean): String {
        if (custom) return "내 파일 사용 중"
        return if (alarm) {
            when {
                AssetInstaller.alarmFile(this, boss).exists() -> "기본 경보음"
                PcDefaults.WARN_TEXT[boss] != null -> "기본 멘트 \"${PcDefaults.WARN_TEXT[boss]}\""
                else -> "기본 없음"
            }
        } else {
            if (speaker.bossVoiceFile(boss) != null) "기본 음성" else "기본 (기계 음성)"
        }
    }

    private fun play(boss: String, alarm: Boolean) {
        if (alarm) {
            val mine = Speaker.userAlarm(this, boss)
            val f = when {
                mine.exists() -> mine
                AssetInstaller.alarmFile(this, boss).exists() -> AssetInstaller.alarmFile(this, boss)
                else -> PcDefaults.WARN_TEXT[boss]?.let { File(File(filesDir, "voices"), "$it.mp3") }
            }
            speaker.preview(f, PcDefaults.WARN_TEXT[boss])
        } else {
            speaker.preview(speaker.bossVoiceFile(boss), PcDefaults.VOICE_TEXT[boss] ?: boss)
        }
    }

    private fun setFile(boss: String, alarm: Boolean, uri: Uri) {
        val out = if (alarm) Speaker.userAlarm(this, boss) else Speaker.userVoice(this, boss)
        try {
            contentResolver.openInputStream(uri)?.use { ins -> out.outputStream().use { ins.copyTo(it) } }
            Toast.makeText(this, "$boss ${if (alarm) "경보" else "인식"} 음성을 바꿨습니다", Toast.LENGTH_SHORT).show()
            Bus.log("🎵 [음성 바꾸기] $boss ${if (alarm) "경보" else "인식"} 음성 → 내 파일")
        } catch (e: Exception) {
            out.delete()
            Toast.makeText(this, "파일을 넣지 못했습니다", Toast.LENGTH_SHORT).show()
        }
        build()
    }

    private fun askRestore(boss: String, alarm: Boolean, custom: Boolean) {
        val kind = if (alarm) "경보" else "인식"
        AlertDialog.Builder(this)
            .setTitle("원본으로 되돌릴까요?")
            .setMessage("'$boss' $kind 음성을 앱에 들어 있던 원래 소리로 되돌립니다." +
                    if (custom) "\n(내가 넣은 파일은 지워집니다)" else "")
            .setPositiveButton("되돌리기") { _, _ ->
                restore(boss, alarm)
                Toast.makeText(this, "원본으로 되돌렸습니다", Toast.LENGTH_SHORT).show()
                build()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 내가 지정한 파일을 지우고, [MP3 추가] 로 덮어썼을 수 있는 기본 파일도 앱 원본으로 되살린다 */
    private fun restore(boss: String, alarm: Boolean) {
        if (alarm) {
            Speaker.userAlarm(this, boss).delete()
            copyAsset("alarms/$boss.mp3", AssetInstaller.alarmFile(this, boss))
        } else {
            Speaker.userVoice(this, boss).delete()
            val text = PcDefaults.VOICE_TEXT[boss] ?: boss
            val dir = File(filesDir, "voices")
            for (n in listOf(boss, text).distinct()) {
                val f = File(dir, "$n.mp3")
                if (!copyAsset("voices/$n.mp3", f)) f.delete()   // 앱에 없던 파일이면 [MP3 추가] 로 넣은 것 — 지운다
            }
        }
        Bus.log("🎵 [음성 바꾸기] $boss ${if (alarm) "경보" else "인식"} 음성 → 원본")
    }

    private fun copyAsset(path: String, out: File): Boolean = try {
        assets.open(path).use { ins -> out.outputStream().use { ins.copyTo(it) } }; true
    } catch (_: Exception) { false }
}
