package tv.gapja.monitor

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * 깃허브 자동 저장 설정 화면 (v2.9).
 *
 * 메인 화면 › 고급 설정 펼치기 › 맨 아래 작은 '깃허브' 글자를 눌러야만 들어온다.
 * (열쇠를 넣는 곳이라 다른 사람이 쉽게 눌러 보지 않게 메인 화면에서 뺐다)
 *
 *   • 열쇠(토큰) 저장 · 지우기 · 확인
 *   • 등록·삭제할 때 자동으로 깃허브에 맞추기 켜기/끄기
 *   • 내 사진 전부 올리기
 *   • 지금 상태 (열쇠 · 대기 · 최근 결과)
 */
class GitHubActivity : AppCompatActivity() {

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private lateinit var statusView: TextView
    private val changeListener: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(BG) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        scroll.addView(col)
        setContentView(scroll)
        UiInsets.apply(scroll)

        col.addView(TextView(this).apply {
            text = "☁️ 깃허브 자동 저장"
            setTextColor(ACCENT); textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(body("등록한 인식 사진(보스마다 기본 1장 + 보조 1장)을 깃허브 사진 저장소 " +
                "(${GitHubSync.REPO}) 의 '${GitHubSync.folder()}' 폴더에 자동으로 올립니다. " +
                "사진을 지우면 깃허브에서도 지웁니다. 앱을 새로 빌드할 때 이 사진들이 자동으로 앱 기본 사진이 됩니다.\n" +
                "열쇠는 이 기기 안에만 저장됩니다. 열쇠가 없으면 아무것도 올리지 않습니다."))

        statusView = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f
            setLineSpacing(0f, 1.3f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(CARD)
        }
        col.addView(statusView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(12) })

        col.addView(h("열쇠"))
        val tokenEt = EditText(this).apply {
            hint = "github_pat_… 로 시작하는 열쇠를 붙여 넣으세요"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
            setTextColor(Color.WHITE); setHintTextColor(DIM); textSize = 13f
        }
        col.addView(tokenEt)
        col.addView(row(
            btn("💾 열쇠 저장", "#16A085") {
                val t = tokenEt.text.toString().trim()
                if (t.isEmpty()) { toast("열쇠를 먼저 붙여 넣어 주세요"); return@btn }
                if (t.contains(' ') || t.length < 20) { toast("열쇠가 아닌 것 같습니다. 다시 복사해 주세요"); return@btn }
                Prefs.setGhToken(this, t)
                tokenEt.setText("")
                toast("열쇠를 저장했습니다. 연결을 확인합니다…")
                GitHubSync.test(this, t) { ok, msg ->
                    toast(if (ok) "✅ 연결됐습니다" else "열쇠를 확인해 주세요")
                    Bus.log(msg)
                    if (ok) GitHubSync.flush(this)
                    refresh()
                }
                refresh()
            },
            btn("🗑 열쇠 지우기", "#7F8C8D") {
                if (Prefs.ghToken(this).isBlank()) { toast("저장된 열쇠가 없습니다"); return@btn }
                AlertDialog.Builder(this)
                    .setTitle("열쇠를 지울까요?")
                    .setMessage("지우면 사진을 깃허브에 올리지 않습니다. (깃허브에 이미 올린 사진은 그대로 남습니다)")
                    .setPositiveButton("지우기") { _, _ ->
                        Prefs.setGhToken(this, ""); Prefs.setGhStatus(this, "")
                        toast("열쇠를 지웠습니다"); refresh()
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
        ))

        col.addView(h("자동 저장"))
        col.addView(CheckBox(this).apply {
            text = "사진을 등록·삭제할 때 자동으로 깃허브에 맞추기"
            setTextColor(Color.WHITE); textSize = 13f
            isChecked = Prefs.ghSyncOn(this@GitHubActivity)
            setOnCheckedChangeListener { _, on ->
                Prefs.setGhSyncOn(this@GitHubActivity, on)
                if (on) GitHubSync.flush(this@GitHubActivity)
                refresh()
            }
        })
        col.addView(row(
            btn("🔍 열쇠 확인", "#2980B9") {
                val t = Prefs.ghToken(this)
                if (t.isBlank()) { toast("먼저 열쇠를 붙여 넣고 [열쇠 저장] 을 눌러 주세요"); return@btn }
                toast("확인 중…")
                GitHubSync.test(this, t) { _, msg -> toast(msg.take(40)); Bus.log(msg); refresh() }
            },
            btn("⬆️ 내 사진 전부 올리기", "#8E44AD") {
                if (!GitHubSync.active(this)) { toast("먼저 열쇠를 저장하고 자동 저장을 켜 주세요"); return@btn }
                val n = GitHubSync.queueAllMine(this)
                toast(if (n > 0) "내 사진 ${n}장을 확인해서 올립니다 (같은 사진은 건너뜀)" else "올릴 내 사진이 아직 없습니다")
                refresh()
            }
        ))
        col.addView(body("사진이 올라가면 앱 로그에 '☁️ [깃허브 저장] …' 이 남습니다. " +
                "인터넷이 끊겨 있으면 기억해 두었다가 연결되면 자동으로 올립니다."))

        col.addView(btn("← 돌아가기", "#2A3644") { finish() }.also {
            (it.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(18)
        })
        refresh()
    }

    override fun onResume() {
        super.onResume()
        Bus.addChangeListener(changeListener)
        GitHubSync.flush(this)
        refresh()
    }

    override fun onPause() {
        Bus.removeChangeListener(changeListener)
        super.onPause()
    }

    /** 지금 상태 — 열쇠 · 켜짐 · 대기 · 최근 결과 */
    private fun refresh() {
        if (!::statusView.isInitialized) return
        val t = Prefs.ghToken(this)
        val key = if (t.isBlank()) "🔑 열쇠: 없음 — 아래에 붙여 넣고 [열쇠 저장] 을 눌러 주세요"
                  else "🔑 열쇠: 저장됨 (…${t.takeLast(4)})"
        val on = if (Prefs.ghSyncOn(this)) "켜짐" else "꺼짐"
        val wait = Prefs.ghPending(this).size
        val last = Prefs.ghStatus(this)
        statusView.text = "$key\n자동 저장: $on · 올릴 대기: ${wait}장\n" +
                "폴더: ${GitHubSync.REPO}/${GitHubSync.folder()}" +
                (if (last.isNotBlank()) "\n최근: $last" else "")
    }

    // ── 화면 부품 (메인 화면과 같은 모양) ─────────────────────────

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
        setPadding(0, dp(6), 0, 0)
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
        gravity = Gravity.CENTER_VERTICAL
        for (v in views) addView(v, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { it.setMargins(dp(2), dp(2), dp(2), dp(2)) })
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private val BG = Color.parseColor("#FF18222C")
        private val CARD = Color.parseColor("#FF223040")
        private val ACCENT = Color.parseColor("#FF1ABC9C")
        private val DIM = Color.parseColor("#FF8C9BA6")
    }
}
