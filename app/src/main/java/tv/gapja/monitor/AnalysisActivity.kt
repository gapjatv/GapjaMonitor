package tv.gapja.monitor

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import tv.gapja.monitor.core.BossCatalog
import tv.gapja.monitor.core.Predictor
import tv.gapja.monitor.core.SeqStore

/**
 * 📊 판별 기록 분석 화면 — PC판의 분석 창과 같은 내용을 보여준다.
 *
 *   ① 침공특수보스 순서 비율 (30R/50R/80R 각각 몇 번)
 *   ② 전체 등장 순서 조합 6가지
 *   ③ 메인보스 순서가 반복되는가 (패턴표 존재 여부)
 *   ④ 이번 판 현황
 */
class AnalysisActivity : AppCompatActivity() {

    private lateinit var repo: SeqRepo
    private lateinit var out: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = SeqRepo(this)

        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FF18222C"))
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        col.addView(TextView(this).apply {
            text = "📊 판별 기록 분석"
            setTextColor(Color.parseColor("#FF1ABC9C")); textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
        })

        val sv = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#FF0E1A24"))
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        out = TextView(this).apply {
            setTextColor(Color.parseColor("#FFD5DEE6")); textSize = 12f
            typeface = Typeface.MONOSPACE
            setLineSpacing(0f, 1.25f)
        }
        sv.addView(out)
        col.addView(sv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).also {
            it.setMargins(0, dp(10), 0, dp(10))
        })

        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun b(text: String, color: String, action: () -> Unit) = Button(this).apply {
            this.text = text; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(color)); isAllCaps = false
            setOnClickListener { action() }
        }
        btns.addView(b("🔄 새로고침", "#2980B9") { render() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        btns.addView(b("📁 CSV 내보내기", "#16A085") { shareCsv() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        btns.addView(b("닫기", "#7F8C8D") { finish() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(btns)

        setContentView(col)
        UiInsets.apply(col)
        render()
    }

    private fun render() {
        out.text = buildReport(repo.load())
    }

    private fun buildReport(seq: SeqStore): String {
        val sb = StringBuilder()
        val n = seq.games.size
        if (n == 0) {
            sb.append("아직 기록된 판이 없습니다.\n\n")
            sb.append("침공 모드로 한 판을 진행하고 '판 종료·기록'을 누르면\n")
            sb.append("순서가 저장됩니다.\n")
            sb.append("(자동 인식이 한 번도 없었던 판은 저장되지 않습니다)\n\n")
            sb.append("PC에서 쓰던 boss_sequences.json 이 있으면\n")
            sb.append("메인 화면 → 'PC 기록 가져오기' 로 이어서 쓸 수 있습니다.")
            return sb.toString()
        }
        val full = seq.games.count { it.main.size >= 8 }
        sb.append("누적 ${n}판 · 80라운드까지 간 판 ${full}판\n")
        sb.append("최근 기록 ${seq.games.last().t}\n")

        // ① 침공특수보스 순서 비율
        val st = seq.specialOrderStats()
        sb.append("\n① 침공특수보스 순서 비율\n")
        sb.append("   1번째=30R · 2번째=50R · 3번째=80R\n")
        if (st.n == 0) {
            sb.append("   아직 기록 없음\n")
        } else {
            sb.append("   %-13s %8s %8s %8s %6s\n".format("보스", "30R", "50R", "80R", "합계"))
            for (b in BossCatalog.INVASION_SPECIAL) {
                val p = st.pos[b] ?: IntArray(3)
                val tot = p.sum()
                if (tot == 0) {
                    sb.append("   %-13s %8s %8s %8s %6d\n".format(b, "-", "-", "-", 0))
                } else {
                    sb.append("   %-13s".format(b))
                    for (c in p) sb.append(" %3d(%3.0f%%)".format(c, c * 100.0 / tot))
                    sb.append(" %6d\n".format(tot))
                }
            }
            sb.append("   → 세 숫자가 비슷해야 정상입니다 (각 33%씩 무작위 배정)\n")
            for (b in BossCatalog.INVASION_SPECIAL) {
                val p = st.pos[b] ?: continue
                val tot = p.sum()
                if (tot >= 9) {
                    val hi = p.max(); val lo = p.min()
                    if (hi >= lo * 2.5) {
                        sb.append("   ⚠️ $b: 특정 라운드에 치우쳐 있습니다 " +
                                "(${p[0]}/${p[1]}/${p[2]}) — 표본이 더 쌓이면 다시 확인하세요\n")
                    }
                }
            }
            // 인식 누락 경고 — 3종은 등장 횟수가 같아야 한다
            val totals = BossCatalog.INVASION_SPECIAL.map { (st.pos[it] ?: IntArray(3)).sum() }
            if (totals.sum() >= 30) {
                val exp = totals.sum() / 3.0
                for ((i, b) in BossCatalog.INVASION_SPECIAL.withIndex()) {
                    val dev = (totals[i] - exp) / exp * 100
                    if (dev < -25) {
                        sb.append("   ⚠️ $b 인식이 기대보다 %.0f%% 적습니다. ".format(-dev))
                        sb.append("네임텍 가려짐일 수 있으니\n      보조 스샷(${b}_2.png)을 찍어두세요\n")
                    }
                }
            }
        }

        // ② 전체 순서 조합
        if (st.nFull > 0) {
            sb.append("\n② 전체 등장 순서 조합  (3종 다 나온 ${st.nFull}판)\n")
            sb.append("   6가지가 고르면 완전 무작위, 한쪽으로 몰리면 패턴 존재\n")
            val rows = st.perm.entries.sortedByDescending { it.value }
            for ((k, c) in rows) {
                val pct = c * 100.0 / st.nFull
                sb.append("   %-34s %3d회 %5.1f%%  %s\n".format(
                    k.replace(">", " → "), c, pct, "█".repeat((pct / 4).toInt())))
            }
            val exp = st.nFull / 6.0
            var chi = 0.0
            for (p in Predictor.allSpecialPerms()) {
                val o = st.perm[p] ?: 0
                chi += (o - exp) * (o - exp) / exp
            }
            sb.append("   균등하다면 각 %.1f회씩 · 카이제곱 %.1f (자유도 5)\n".format(exp, chi))
            when {
                st.nFull < 30 -> sb.append("   (판단하려면 30판 이상 필요 — 현재 ${st.nFull}판)\n")
                chi > 15.09 -> sb.append("   ✅ 특정 순서가 더 자주 나옵니다! 무작위가 아닙니다\n")
                else -> sb.append("   → 아직은 무작위와 구분되지 않습니다\n")
            }
        }

        // ③ 메인보스 순서 반복
        sb.append("\n③ 메인보스 순서가 반복되는가\n")
        val pool = seq.poolSize()
        var perms = 1L
        for (i in 0 until 8) perms *= maxOf(1, pool - i).toLong()
        val need = seq.minMatchLen()
        sb.append("   보스 풀 ${pool}종에서 8칸 순서 = %,d 가지\n".format(perms))
        sb.append("   기록 ${n}판에서는 앞 ${need}마리 이상 겹쳐야 '우연 아님'입니다\n")
        sb.append("   (무작위라면 ${need}마리 일치 기대 %.3f회 — 기준 %.2f)\n"
            .format(seq.expectedCollisions(need), SeqStore.MAX_CHANCE))
        val v = seq.patternVerdict(need)
        if (v.groups.isEmpty()) {
            sb.append("   앞 ${need}마리 이상 겹치는 판: 없음\n")
            if (n < 30) sb.append("   (아직 ${n}판 — 30판쯤 쌓이면 판단이 분명해집니다)\n")
            else sb.append("   → 표본이 충분한데도 겹침이 없으니 '완전 셔플'일 가능성이 높습니다\n")
        } else {
            for (g in v.groups.take(8)) {
                val dist = HashMap<String, Int>()
                for (r in g.rows) if (r.main.size >= 8) {
                    dist[r.main[7]] = (dist[r.main[7]] ?: 0) + 1
                }
                val tail = if (dist.isEmpty()) "" else
                    "  80R: " + dist.entries.sortedByDescending { it.value }
                        .joinToString(", ") { "${it.key} ${it.value}회" }
                sb.append("   🎯 앞 ${g.k}마리 일치 ${g.rows.size}판 — " +
                        "${g.head.joinToString(" → ")}$tail\n")
            }
            sb.append("   겹침 ${v.observed}쌍 발견 / 우연 기대 %.3f쌍  →  p = %.2g\n"
                .format(v.expected, v.pValue))
            if (v.isPattern) {
                sb.append("   ✅ 순서가 반복되고 있습니다! 패턴표가 존재할 가능성이 큽니다\n")
                sb.append("      → 앞부분이 같은 판을 찾으면 80R을 미리 알 수 있습니다\n")
            } else {
                sb.append("   ⚠️ 겹침이 있지만 이 정도는 우연히도 생깁니다 — 더 지켜보세요\n")
            }
        }

        // ④ 이번 판 현황
        val cur = Bus.activated.filter { !BossCatalog.isSpecial(it) }
        if (cur.isNotEmpty()) {
            sb.append("\n④ 이번 판 현황\n")
            sb.append("   지금까지: ${cur.joinToString(" → ")}\n")
            val rem = Predictor.remainingMainCandidates(Bus.activated)
            sb.append("   아직 안 나온 보스 ${rem.size}종: ${rem.joinToString(", ")}\n")
            val (k, rows, dist) = seq.findMatches(cur, need)
            if (k >= need && dist.isNotEmpty()) {
                val top = dist.maxByOrNull { it.value }!!
                sb.append("   🔮 앞 ${k}마리가 과거 ${rows.size}판과 일치 → " +
                        "80R 예상 '${top.key}' (${top.value}/${dist.values.sum()}, " +
                        "우연 기대 %.3f회)\n".format(seq.expectedCollisions(k)))
            } else if (k >= need) {
                sb.append("   앞 ${k}마리 일치 판 ${rows.size}개 (아직 80R까지 간 기록 없음)\n")
            }
        }

        sb.append("\n기록 파일: ${repo.path()}\n")
        return sb.toString()
    }

    private fun shareCsv() {
        val store = repo.load()
        if (store.games.isEmpty()) {
            Toast.makeText(this, "내보낼 기록이 없습니다", Toast.LENGTH_SHORT).show(); return
        }
        val f = repo.exportCsv(store) ?: run {
            Toast.makeText(this, "CSV 만들기 실패", Toast.LENGTH_SHORT).show(); return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "CSV 보내기"))
        } catch (e: Exception) {
            Toast.makeText(this, "공유 실패: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
