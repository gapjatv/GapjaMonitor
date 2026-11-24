package tv.gapja.monitor

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 새 버전 알림 (v3.0).
 *
 * 깃허브 릴리스(gapjatv/GapjaMonitor, 공개 저장소) 의 '가장 최근 버전' 이름을 읽어서
 * 지금 설치된 버전보다 높으면 알려 준다. 열쇠(토큰)는 필요 없다.
 * 이 기기에 맞는 APK(핸드폰 / 32비트 핸드폰 / 뮤뮤) 주소를 골라서 [받기] 에 연결한다.
 *
 * 인터넷이 안 되거나 실패하면 조용히 아무것도 하지 않는다.
 */
object UpdateChecker {

    private const val URL = "https://api.github.com/repos/gapjatv/GapjaMonitor/releases/latest"

    data class Info(val tag: String, val apkUrl: String?, val pageUrl: String)

    /** 이 기기에 맞는 APK 종류 */
    fun variant(): String = when {
        AssetInstaller.deviceTemplateFolder() == "templates_mumu" -> "mumu"
        Build.SUPPORTED_64_BIT_ABIS.isEmpty() -> "phone32"
        else -> "phone"
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** 새 버전이 있으면 cb(Info), 없거나 실패하면 cb(null) — 화면 스레드에서 부른다 */
    fun check(ctx: Context, cb: (Info?) -> Unit) {
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread {
            var found: Info? = null
            try {
                val req = Request.Builder().url(URL)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "UnbbalBossAlarm-Android")
                    .build()
                http.newCall(req).execute().use { r ->
                    if (r.code == 200) {
                        val j = JSONObject(r.body?.string() ?: "{}")
                        val tag = j.optString("tag_name")
                        val local = try {
                            app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: ""
                        } catch (_: Exception) { "" }
                        if (tag.isNotBlank() && isNewer(tag, local)) {
                            val want = "-${variant()}.apk"
                            var apk: String? = null
                            val assets = j.optJSONArray("assets")
                            if (assets != null) for (i in 0 until assets.length()) {
                                val a = assets.getJSONObject(i)
                                if (a.optString("name").endsWith(want)) {
                                    apk = a.optString("browser_download_url").ifBlank { null }
                                }
                            }
                            found = Info(tag, apk, j.optString("html_url").ifBlank {
                                "https://github.com/gapjatv/GapjaMonitor/releases/latest"
                            })
                        }
                    }
                }
            } catch (_: Exception) {
            }
            val res = found
            main.post { cb(res) }
        }.start()
    }

    /** v3.1 > 3.0 처럼 점으로 나뉜 숫자를 앞에서부터 비교한다 */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V")
            .split('.', '-', '_').map { it.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        val a = parts(remote); val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
