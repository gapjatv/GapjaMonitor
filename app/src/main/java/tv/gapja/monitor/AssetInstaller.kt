package tv.gapja.monitor

import android.content.Context
import android.util.Log
import java.io.File
import kotlin.math.max

/**
 * 앱에 같이 넣어 둔 기본 자료를 앱 저장소로 풀어 놓는다.
 *
 *   assets/templates/ → templates/  인식용 캡처 사진 (PC '캡쳐된 보스 사진')
 *   assets/display/   → display/    (배포용: 비어 있음 — 알림용 보스 사진은 사용자가 보스를 등록할 때 자동으로 만들어진다)
 *   assets/voices/    → voices/     보스 음성·80R 멘트·경보 멘트 MP3 (PC 'voices')
 *   assets/alarms/    → alarms/     위험 경보음 MP3 (PC 'sounds/{보스}.mp3')
 *
 * 인식용 사진에는 로비 화면 3종(태초로비·침공로비·공통로비)과 배속 표시(1배속상태·2배속상태)도 들어 있다.
 *
 * 기기별 인식용 사진 (있으면 templates 위에 덮어 넣는다):
 *   assets/templates_mumu/  → 뮤뮤 플레이어(x86) 에서 찍은 사진
 *   assets/templates_phone/ → 핸드폰(arm) 에서 찍은 사진
 *
 * 새 버전(VERSION 이 올라감) 으로 업데이트하면:
 *   • 사용자가 이 기기에서 직접 등록한 사진(✔ 내 사진)은 절대 덮어쓰지 않는다
 *   • 나머지 기본 사진은 새로 넣은 사진으로 바꾼다 (바뀌기 전 사진은 templates_bak 에 보관)
 */
object AssetInstaller {

    /**
     * 자료를 바꿔서 새로 넣을 때 올린다
     *   2: 로비 3종·배속 사진·경보음·경보 멘트 추가
     *   3: 핸드폰 기본 사진 15장 (github.com/gapjatv/png, 2026-11-21)
     *   4: 윔 경보음(alarms/윔.mp3) 추가, 핸드폰 기본 사진 고기슬라임·블랑카·침공로비 교체 (2026-11-21 19:35)
     *   5: 뮤뮤 기본 사진 17장 (templates_mumu — 보스 14장 + 태초로비·침공로비(마계 포탈)·공통로비, 2026-11-21 21:43)
     *   6: 핸드폰 양술사 사진을 캐릭터 중심으로 좁게 다시 자름 (앱 화면 오인식 방지, 2026-11-22)
     */
    private const val VERSION = 6

    private val FOLDERS = listOf("templates", "display", "voices", "alarms")

    /** 이 기기용 인식 사진 폴더 이름 (뮤뮤·에뮬레이터는 x86) */
    fun deviceTemplateFolder(): String {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: ""
        return if (abi.startsWith("x86")) "templates_mumu" else "templates_phone"
    }

    @Volatile private var deviceNames: Set<String>? = null

    /** 이 기기용으로 앱에 넣어 둔 폰(뮤뮤) 사진 이름들 */
    fun deviceTemplateNames(ctx: Context): Set<String> =
        deviceNames ?: list(ctx, deviceTemplateFolder()).toSet().also { deviceNames = it }

    private fun list(ctx: Context, folder: String): Array<String> =
        try { ctx.assets.list(folder) ?: emptyArray() } catch (_: Exception) { emptyArray() }

    /** 앱에 들어 있는 기본 인식 사진의 assets 경로 (기기용이 있으면 그것) — 없으면 null */
    fun bundledTemplatePath(ctx: Context, name: String): String? {
        val dev = deviceTemplateFolder()
        if (name in list(ctx, dev)) return "$dev/$name"
        if (name in list(ctx, "templates")) return "templates/$name"
        return null
    }

    /**
     * 빌드할 때 만든 기본 자료 지문 (assets/asset_stamp.txt, v2.8).
     * 깃허브 사진 저장소의 phone/ · mumu/ 사진이 바뀌면 지문이 달라져서, VERSION 을 안 올려도 새 사진을 풀어 놓는다.
     * (Android Studio 로 직접 빌드하면 파일이 없어서 "" — 그때는 VERSION 만 본다)
     */
    private fun bundledStamp(ctx: Context): String = try {
        ctx.assets.open("asset_stamp.txt").bufferedReader().use { it.readText().trim() }
    } catch (_: Exception) { "" }

    fun installIfNeeded(ctx: Context): Int {
        val old = Prefs.assetsVersion(ctx)
        val stamp = bundledStamp(ctx)
        if (old >= VERSION && stamp == Prefs.assetsStamp(ctx)) return 0
        val firstInstall = old <= 0
        val mine = Prefs.userTemplates(ctx)
        var n = 0
        // 기기용 사진 폴더를 templates 다음에 넣어 같은 이름이면 기기용이 이긴다
        val sources = FOLDERS.map { it to it } + (deviceTemplateFolder() to "templates")
        for ((src, dst) in sources) {
            val dir = File(ctx.filesDir, dst).apply { mkdirs() }
            for (name in list(ctx, src)) {
                val out = File(dir, name)
                if (out.exists() && out.length() > 0) {
                    // 처음 설치가 아니면 기본 사진을 새 것으로 바꾼다 — 내가 등록한 사진은 지킨다
                    if (firstInstall && src == dst) continue
                    if (dst == "templates" && name in mine) continue
                    if (sameAsAsset(ctx, "$src/$name", out)) continue
                    if (dst == "templates") backup(ctx, out)
                }
                try {
                    ctx.assets.open("$src/$name").use { ins ->
                        out.outputStream().use { o -> ins.copyTo(o) }
                    }
                    n++
                } catch (e: Exception) {
                    Log.w("AssetInstaller", "복사 실패: $src/$name", e)
                }
            }
        }
        Prefs.setAssetsVersion(ctx, max(old, VERSION))
        Prefs.setAssetsStamp(ctx, stamp)
        if (n > 0) TemplateStore.generation++
        return n
    }

    private fun backup(ctx: Context, f: File) {
        try { f.copyTo(File(File(ctx.filesDir, "templates_bak").apply { mkdirs() }, f.name), overwrite = true) }
        catch (_: Exception) {}
    }

    /** 이미 같은 파일이면 다시 쓰지 않는다 */
    private fun sameAsAsset(ctx: Context, path: String, f: File): Boolean = try {
        ctx.assets.open(path).use { a ->
            f.inputStream().buffered().use { b ->
                var same = true
                while (true) {
                    val x = a.read(); val y = b.read()
                    if (x != y) { same = false; break }
                    if (x < 0) break
                }
                same
            }
        }
    } catch (_: Exception) { false }

    /** 게임 위 표시용 보스 사진 파일 */
    fun displayFile(ctx: Context, boss: String): File = File(File(ctx.filesDir, "display"), "$boss.png")

    /** 위험 경보음 파일 (PC sounds/{보스}.mp3) */
    fun alarmFile(ctx: Context, boss: String): File = File(File(ctx.filesDir, "alarms"), "$boss.mp3")

    fun alarmCount(ctx: Context): Int =
        File(ctx.filesDir, "alarms").listFiles { f -> f.name.lowercase().endsWith(".mp3") }?.size ?: 0

    fun displayCount(ctx: Context): Int =
        File(ctx.filesDir, "display").listFiles { f -> f.name.endsWith(".png") }?.size ?: 0
}
