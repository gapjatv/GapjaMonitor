package tv.gapja.monitor

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 등록한 인식 사진을 갑자님 깃허브 사진 저장소(gapjatv/png)에 자동으로 맞춘다. (v2.8)
 *
 *   • 핸드폰에서 등록한 사진 → png 저장소의 phone/ 폴더
 *   • 뮤뮤에서 등록한 사진   → png 저장소의 mumu/ 폴더
 *   • 사진을 등록하면 올리고, 지우면 깃허브에서도 지운다 (지운 것도 깃허브 기록에는 남아서 되살릴 수 있다)
 *   • 올릴 사진은 '대기 줄' 에 이름만 적어 두고, 올릴 때 그 순간의 폰 상태(있으면 올리기 · 없으면 지우기)를 맞춘다
 *     → 인터넷이 끊겨 있어도 잊지 않고, 연결되면 알아서 다시 올린다
 *   • 깃허브에 이미 똑같은 사진이 있으면 건너뛴다 (쓸데없는 기록이 쌓이지 않게)
 *
 * 앱을 새로 빌드할 때(GitHub Actions) phone/ · mumu/ 사진이 자동으로 앱 기본 사진이 된다.
 * (.github/scripts/sync_png.py)
 *
 * ⚠️ 열쇠(토큰)는 코드에 넣지 않는다. 앱 설정에서 붙여 넣은 열쇠를 이 기기 안에만 저장한다.
 *    친구에게 준 앱에는 열쇠가 없으므로 아무것도 올라가지 않는다.
 */
object GitHubSync {

    const val REPO = "gapjatv/png"
    const val BRANCH = "main"
    private const val API = "https://api.github.com"
    private const val TAG = "GitHubSync"

    /** 이 기기 사진을 올릴 폴더 — 뮤뮤(x86)는 mumu, 핸드폰은 phone */
    fun folder(): String =
        if (AssetInstaller.deviceTemplateFolder() == "templates_mumu") "mumu" else "phone"

    /** 깃허브에 맞출 사진 이름인지 — 기본 사진({이름}.png)과 보조 1장({이름}_2.png) 만 */
    fun isSyncName(name: String): Boolean =
        name.endsWith(".png") && !name.contains('/') && !name.contains('\\') &&
                !Regex("_[3-9]\\.png$").containsMatchIn(name) && !name.contains("배속상태")

    /** 열쇠가 있고 자동 저장이 켜져 있는지 */
    fun active(ctx: Context): Boolean =
        Prefs.ghSyncOn(ctx) && Prefs.ghToken(ctx).isNotBlank()

    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val scheduled = AtomicBoolean(false)
    @Volatile private var netWatched = false
    @Volatile private var lastAuthLogAt = 0L

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // ── 바깥에서 부르는 것 ─────────────────────────────────────────

    /** 이 사진의 지금 상태를 깃허브에 맞춘다 (등록·지우기·되돌리기 뒤에 부른다) */
    fun syncFile(ctx: Context, name: String) = syncFiles(ctx, listOf(name))

    fun syncFiles(ctx: Context, names: Collection<String>) {
        if (names.isEmpty() || !active(ctx)) return
        Prefs.addGhPending(ctx, names)
        flush(ctx)
    }

    /** 이 기기에서 등록한 사진(기본 1장 + 보조 1장)을 모두 대기 줄에 넣는다 — 몇 장인지 돌려준다 */
    fun queueAllMine(ctx: Context): Int {
        val dir = File(ctx.filesDir, "templates")
        val names = Prefs.userTemplates(ctx).filter { isSyncName(it) && File(dir, it).exists() }
        Prefs.addGhPending(ctx, names)
        flush(ctx)
        return names.size
    }

    /** 대기 줄에 있는 사진을 올린다 (이미 올리는 중이면 그 작업이 이어서 처리한다) */
    fun flush(ctx: Context) {
        val app = ctx.applicationContext
        watchNetwork(app)
        if (!active(app) || Prefs.ghPending(app).isEmpty()) return
        if (!scheduled.compareAndSet(false, true)) return
        exec.execute {
            var finished = false
            try { finished = drain(app) } catch (e: Exception) { Log.w(TAG, "올리기 실패", e) }
            finally { scheduled.set(false) }
            // 다 올린 직후에 새로 들어온 사진이 있으면 한 번 더 (오류로 멈췄으면 다음 기회에)
            if (finished && Prefs.ghPending(app).isNotEmpty()) flush(app)
        }
    }

    /**
     * 열쇠가 저장소에 닿는지 확인한다. (결과는 화면 스레드에서 cb 로)
     * 쓰기 권한은 실제로 올려 봐야 알 수 있어서, 첫 사진을 올릴 때 함께 확인된다.
     */
    fun test(ctx: Context, token: String, cb: (Boolean, String) -> Unit) {
        val app = ctx.applicationContext
        exec.execute {
            val (ok, msg) = try {
                http.newCall(req(token, "$API/repos/$REPO").get().build()).execute().use { r ->
                    when (r.code) {
                        200 -> true to "✅ 열쇠가 깃허브 사진 저장소($REPO)에 연결됐습니다. " +
                                "(올리기 권한은 첫 사진을 올릴 때 확인됩니다)"
                        401 -> false to "❌ 열쇠가 틀렸거나 기한이 지났습니다. 새 열쇠를 만들어 붙여 넣어 주세요."
                        403, 404 -> false to "❌ 이 열쇠로는 $REPO 저장소에 들어갈 수 없습니다. " +
                                "열쇠를 만들 때 'Only select repositories' 에서 png 를 골랐는지 확인해 주세요."
                        else -> false to "⚠️ 깃허브 응답 ${r.code} — 잠시 뒤에 다시 눌러 주세요."
                    }
                }
            } catch (e: IOException) {
                false to "⚠️ 인터넷에 연결할 수 없습니다. 와이파이·데이터를 확인해 주세요."
            } catch (e: Exception) {
                false to "⚠️ 확인 실패: ${e.message}"
            }
            Prefs.setGhStatus(app, msg)
            main.post { cb(ok, msg) }
            Bus.settingsChanged()
        }
    }

    // ── 안쪽 ─────────────────────────────────────────────────────

    /** 인터넷이 다시 연결되면 기다리던 사진을 올린다 */
    private fun watchNetwork(app: Context) {
        if (netWatched) return
        netWatched = true
        try {
            val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // 연결 직후에는 아직 불안정할 수 있어 3초 뒤에
                    main.postDelayed({ flush(app) }, 3000)
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "인터넷 연결 감시 실패", e)
        }
    }

    private enum class Res { PUT, DEL, SKIP, AUTH, NET, BAD }

    /** @return 대기 줄을 다 비웠으면 true, 오류로 멈췄으면 false */
    private fun drain(app: Context): Boolean {
        var put = 0; var del = 0
        val tried = HashSet<String>()
        while (true) {
            val token = Prefs.ghToken(app)
            if (token.isBlank() || !Prefs.ghSyncOn(app)) return false
            val name = Prefs.ghPending(app).sorted().firstOrNull { it !in tried } ?: break
            tried.add(name)
            when (syncOne(app, token, name)) {
                Res.PUT -> { put++; Prefs.removeGhPending(app, name) }
                Res.DEL -> { del++; Prefs.removeGhPending(app, name) }
                Res.SKIP -> Prefs.removeGhPending(app, name)
                Res.BAD -> {
                    // 이 사진만 포기한다 (계속 실패하는 사진 때문에 나머지가 막히지 않게)
                    Prefs.removeGhPending(app, name)
                    Bus.log("⚠️ [깃허브] '${name.removeSuffix(".png")}' 를 올리지 못해 건너뜁니다.")
                }
                Res.AUTH -> {
                    val msg = "❌ 깃허브 열쇠가 맞지 않거나 올리기 권한이 없습니다. " +
                            "(메인 화면 › 깃허브 자동 저장 › 열쇠 확인) — 사진은 대기 줄에 남겨 둡니다."
                    Prefs.setGhStatus(app, msg)
                    val now = System.currentTimeMillis()
                    if (now - lastAuthLogAt > 10 * 60_000L) { lastAuthLogAt = now; Bus.log(msg) }
                    return false
                }
                Res.NET -> {
                    val n = Prefs.ghPending(app).size
                    Prefs.setGhStatus(app, "📶 인터넷이 연결되면 대기 중인 사진 ${n}장을 올립니다.")
                    Bus.settingsChanged()
                    return false
                }
            }
        }
        if (put + del > 0) {
            val msg = "☁️ 깃허브 사진 저장소와 맞췄습니다 — 올림 ${put}장" + (if (del > 0) " · 지움 ${del}장" else "") +
                    " (${folder()} 폴더)"
            Prefs.setGhStatus(app, msg)
        } else if (Prefs.ghStatus(app).startsWith("📶") || Prefs.ghStatus(app).startsWith("❌")) {
            Prefs.setGhStatus(app, "✅ 깃허브와 같습니다. (올릴 사진 없음)")
        }
        Bus.settingsChanged()
        return true
    }

    /** 사진 한 장을 깃허브에 맞춘다 — 폰에 내 사진으로 있으면 올리고, 없으면 지운다 */
    private fun syncOne(app: Context, token: String, name: String): Res {
        val f = File(File(app.filesDir, "templates"), name)
        val want = isSyncName(name) && name in Prefs.userTemplates(app) && f.exists() && f.length() > 0
        val path = "${folder()}/$name"
        val url = "$API/repos/$REPO/contents/${enc(path)}"
        val bytes = if (want) try { f.readBytes() } catch (_: Exception) { return Res.BAD } else null

        // 저장소 쪽 파일이 그 사이에 바뀌었으면(409·422) 한 번 더 시도한다
        for (attempt in 0 until 2) {
            val remote: String? = try {
                http.newCall(req(token, "$url?ref=$BRANCH").get().build()).execute().use { r ->
                    when (r.code) {
                        200 -> JSONObject(r.body?.string() ?: "{}").optString("sha").ifBlank { null }
                        404 -> null
                        401, 403 -> return Res.AUTH
                        else -> return if (r.code >= 500 || r.code == 429) Res.NET else Res.BAD
                    }
                }
            } catch (e: IOException) { return Res.NET }

            if (bytes != null) {
                if (remote != null && remote == blobSha(bytes)) return Res.SKIP   // 이미 같은 사진
                val body = JSONObject().apply {
                    put("message", "📱 앱 자동 저장: $path")
                    put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    put("branch", BRANCH)
                    if (remote != null) put("sha", remote)
                }.toString().toRequestBody(JSON)
                try {
                    http.newCall(req(token, url).put(body).build()).execute().use { r ->
                        when (r.code) {
                            200, 201 -> {
                                Bus.log("☁️ [깃허브 저장] $path 올렸습니다.")
                                return Res.PUT
                            }
                            409, 422 -> {}                       // sha 가 바뀜 → 다시
                            401, 403, 404 -> return Res.AUTH
                            else -> return if (r.code >= 500 || r.code == 429) Res.NET else Res.BAD
                        }
                    }
                } catch (e: IOException) { return Res.NET }
            } else {
                if (remote == null) return Res.SKIP                               // 깃허브에도 없음
                val body = JSONObject().apply {
                    put("message", "🗑 앱에서 지움: $path")
                    put("sha", remote)
                    put("branch", BRANCH)
                }.toString().toRequestBody(JSON)
                try {
                    http.newCall(req(token, url).delete(body).build()).execute().use { r ->
                        when (r.code) {
                            200 -> {
                                Bus.log("🗑 [깃허브] $path 지웠습니다.")
                                return Res.DEL
                            }
                            404 -> return Res.SKIP
                            409, 422 -> {}
                            401, 403 -> return Res.AUTH
                            else -> return if (r.code >= 500 || r.code == 429) Res.NET else Res.BAD
                        }
                    }
                } catch (e: IOException) { return Res.NET }
            }
        }
        return Res.BAD
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun req(token: String, url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "UnbbalBossAlarm-Android")

    /** 한글 파일 이름을 주소에 넣을 수 있게 바꾼다 (폴더 구분 / 는 그대로) */
    private fun enc(path: String): String =
        path.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    /** 깃허브가 파일마다 붙이는 이름표(blob sha) — 같으면 똑같은 사진이다 */
    private fun blobSha(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8))
        md.update(bytes)
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
