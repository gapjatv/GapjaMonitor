package tv.gapja.monitor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import tv.gapja.monitor.core.BossCatalog
import tv.gapja.monitor.core.GameMode
import java.io.File

/**
 * 보스 템플릿 PNG 저장소.
 *
 * PC판에서 쓰던 `{보스이름}.png` 파일을 그대로 쓴다.
 * 앱 내부 저장소의 `templates/` 폴더에 복사해 두고, 메모리에 캐시한다.
 *
 * 보조 스샷(`{보스}_2.png` … `_4.png`)도 같은 규칙으로 인식한다.
 * 네임텍이 겹친 화면을 직접 찍어두면 그 상황에서 특히 잘 잡힌다.
 *
 * 침공 모드에서는 PC판처럼 `침공_{보스}.png` 를 먼저 쓰고, 없으면 `{보스}.png` 를 쓴다.
 */
class TemplateStore(private val ctx: Context) {

    private val dir: File by lazy {
        File(ctx.filesDir, "templates").apply { mkdirs() }
    }

    private val cache = HashMap<String, Mat?>()

    /** 다른 화면에서 사진을 저장했는지 알아채기 위한 번호 */
    private var seenGeneration = generation

    /** templates/ 폴더의 절대 경로 — 화면에 안내용으로 보여준다 */
    fun dirPath(): String = dir.absolutePath

    /** 파일 이름(`그런트.png`)으로 템플릿을 읽는다. 없으면 null */
    fun get(fileName: String): Mat? {
        if (seenGeneration != generation) { cache.clear(); seenGeneration = generation }
        cache[fileName]?.let { return it }
        if (cache.containsKey(fileName)) return null   // 없음을 캐시해 둔다
        val f = File(dir, fileName)
        val m: Mat? = if (!f.exists()) null else try {
            // 그레이스케일로 읽는다 (CvOps 가 단일 채널로 다룬다)
            Imgcodecs.imread(f.absolutePath, Imgcodecs.IMREAD_GRAYSCALE)
                .takeIf { !it.empty() }
        } catch (e: Exception) {
            Log.w(TAG, "템플릿 읽기 실패: $fileName", e); null
        }
        cache[fileName] = m
        return m
    }

    /** 보스의 기본 템플릿 */
    fun forBoss(name: String): Mat? = get("$name.png")

    // ── 내가 등록한 사진 / PC 사진 구분 ─────────────────────────

    /** 내가 폰에서 직접 캡처했거나 가져온 사진인지 (앱에 같이 넣은 PC 사진이 아닌지) */
    fun isUser(fileName: String): Boolean = fileName in Prefs.userTemplates(ctx)

    /** 이 기기(핸드폰·뮤뮤)에서 찍어 앱에 기본으로 넣어 둔 사진인지 */
    fun isDeviceBundled(fileName: String): Boolean = fileName in AssetInstaller.deviceTemplateNames(ctx)

    /** 이 기기에서 찍은 사진인지 (내가 등록했거나, 기기용 기본 사진) — PC 사진이 아님 */
    fun isPhoneShot(fileName: String): Boolean = isUser(fileName) || isDeviceBundled(fileName)

    /**
     * 인식에 써도 되는 사진인지.
     * '새로 등록' 을 고르면 PC 사진은 쓰지 않고 내가 등록한 사진만 쓴다.
     * (PC 화면과 폰 화면은 크기·모양이 달라서 PC 사진이 엉뚱한 곳에 걸리는 일이 있다)
     * 배속 표시는 예외로 항상 쓴다.
     */
    fun usable(fileName: String): Boolean {
        if (!File(dir, fileName).exists()) return false
        if (Prefs.usePcTemplates(ctx)) return true
        return isPhoneShot(fileName) || fileName.contains("배속상태")
    }

    fun getUsable(fileName: String): Mat? = if (usable(fileName)) get(fileName) else null

    /** 로비 화면 사진들 (기본 + 보조) 중 쓸 수 있는 것 */
    fun lobbyShots(lobby: String): List<String> =
        (listOf("$lobby.png") + extraShots(lobby)).filter { usable(it) }

    /** 그 보스(또는 로비)에 내가 등록한 사진이 하나라도 있는지 — 등록 도우미용 */
    fun hasUserShot(name: String, mode: GameMode): Boolean {
        val bases = if (mode == GameMode.INVASION && !PcDefaults.isLobby(name))
            listOf("${INVASION_PREFIX}$name", name) else listOf(name)
        return bases.any { b ->
            (listOf("$b.png") + BossCatalog.EXTRA_SHOT_SUFFIXES.map { "$b$it.png" })
                .any { isUser(it) && File(dir, it).exists() }
        }
    }

    /** 그 보스 인식에 쓰는 기본 사진이 내가 등록한 사진인지 (발열 줄이기용) */
    fun bossFileIsUser(name: String, mode: GameMode): Boolean =
        forBossFile(name, mode)?.let { isPhoneShot(it) } == true

    // ── 되돌리기 ──

    private val bakDir: File get() = File(ctx.filesDir, "templates_bak").apply { mkdirs() }

    private fun backup(name: String) {
        try { File(dir, name).copyTo(File(bakDir, name), overwrite = true) } catch (_: Exception) {}
    }

    fun exists(name: String): Boolean = File(dir, name).exists()

    /** 덮어쓰기 전 사진이 보관되어 있는지 */
    fun hasPrevious(name: String): Boolean = File(bakDir, name).exists()

    /** 앱에 들어 있는 기본 사진이 있는지 */
    fun hasBundled(name: String): Boolean =
        AssetInstaller.bundledTemplatePath(ctx, name) != null

    /** 덮어쓰기 전 사진으로 되돌린다 */
    fun restorePrevious(name: String): Boolean = try {
        val b = File(bakDir, name)
        val cur = File(dir, name)
        val tmp = File(bakDir, "$name.swap")
        if (cur.exists()) cur.copyTo(tmp, overwrite = true)
        b.copyTo(cur, overwrite = true)
        if (tmp.exists()) tmp.renameTo(b) else b.delete()
        cache.clear(); generation++; true
    } catch (_: Exception) { false }

    /** 앱에 들어 있던 기본 사진으로 되돌린다 (내 사진 표시도 지운다) */
    fun restoreBundled(name: String): Boolean = try {
        backup(name)
        val path = AssetInstaller.bundledTemplatePath(ctx, name) ?: return false
        ctx.assets.open(path).use { ins -> File(dir, name).outputStream().use { ins.copyTo(it) } }
        Prefs.removeUserTemplate(ctx, name)
        cache.clear(); generation++; true
    } catch (_: Exception) { false }

    /** 사진을 지운다 (되돌릴 수 있게 보관함에 남긴다) */
    fun delete(name: String): Boolean = try {
        backup(name)
        val ok = File(dir, name).delete()
        Prefs.removeUserTemplate(ctx, name)
        cache.clear(); generation++; ok
    } catch (_: Exception) { false }

    /** 모드에 맞는 기본 템플릿 — 침공이면 '침공_' 사진을 먼저 쓴다 */
    fun forBoss(name: String, mode: GameMode): Mat? = forBossFile(name, mode)?.let { get(it) }

    /** 그 보스 인식에 쓰는 기본 사진 파일 이름 (없으면 null) */
    fun forBossFile(name: String, mode: GameMode): String? {
        val inv = "${INVASION_PREFIX}$name.png"
        if (mode == GameMode.INVASION && usable(inv) && get(inv) != null) return inv
        val base = "$name.png"
        return if (usable(base) && get(base) != null) base else null
    }

    /** 보스의 보조 스샷 파일 이름 목록 (존재하는 것만) */
    fun extraShots(name: String): List<String> =
        BossCatalog.EXTRA_SHOT_SUFFIXES
            .map { "$name$it.png" }
            .filter { File(dir, it).exists() }

    /** 모드에 맞는 보조 스샷 — 침공이면 '침공_' 보조 스샷도 함께 */
    fun extraShots(name: String, mode: GameMode): List<String> {
        val inv = if (mode == GameMode.INVASION) extraShots("${INVASION_PREFIX}$name") else emptyList()
        return (inv + extraShots(name)).filter { usable(it) }
    }

    /** 템플릿이 있는 보스만 돌려준다 */
    fun availableBosses(all: List<String>): List<String> =
        all.filter { File(dir, "$it.png").exists() }

    /** 그 모드에서 인식할 수 있는 보스 */
    fun availableBosses(all: List<String>, mode: GameMode): List<String> =
        all.filter {
            usable("$it.png") || extraShots(it, mode).isNotEmpty() ||
                    (mode == GameMode.INVASION && usable("${INVASION_PREFIX}$it.png"))
        }

    /**
     * 직접 캡처해서 잘라낸 사진을 저장한다.
     *
     * @param asExtra true 면 보조 사진(_2, _3, _4 중 빈 자리)으로, false 면 기본 사진을 바꾼다
     * @return 저장한 파일 이름 (보조 자리가 꽉 찼으면 _4 를 덮어쓴다)
     */
    fun saveCrop(bmp: Bitmap, boss: String, mode: GameMode, asExtra: Boolean): String? {
        // 로비 화면(태초로비·침공로비·공통로비)은 이름에 모드가 들어 있어 '침공_' 을 붙이지 않는다
        val base = if (mode == GameMode.INVASION && !PcDefaults.isLobby(boss)) "${INVASION_PREFIX}$boss" else boss
        val name = if (!asExtra) "$base.png" else {
            BossCatalog.EXTRA_SHOT_SUFFIXES.map { "$base$it.png" }
                .firstOrNull { !File(dir, it).exists() }
                ?: "$base${BossCatalog.EXTRA_SHOT_SUFFIXES.last()}.png"
        }
        return try {
            val f = File(dir, name)
            // 덮어쓰기 전에 이전 사진을 한 장 보관해 둔다 → '이전 사진으로 되돌리기'
            if (f.exists()) backup(name)
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Prefs.addUserTemplate(ctx, name)
            // 앱을 지웠다 다시 깔아도 되살릴 수 있게 '다운로드/GapjaMonitor' 에도 한 장 남긴다
            Backup.saveCopy(ctx, f)
            cache.clear()
            generation++
            name
        } catch (e: Exception) {
            Log.w(TAG, "저장 실패: $name", e); null
        }
    }

    /** 등록된 PNG 개수 */
    fun count(): Int = dir.listFiles { f -> f.name.endsWith(".png") }?.size ?: 0

    /**
     * 사용자가 고른 PNG들을 templates/ 로 복사한다.
     * 파일 이름을 그대로 쓰므로 `그런트.png` 처럼 보스 이름과 같아야 한다.
     *
     * @return (복사 성공 수, 무시된 파일 이름들)
     */
    fun importAll(uris: List<Uri>): Pair<Int, List<String>> {
        var ok = 0
        val skipped = ArrayList<String>()
        for (u in uris) {
            // 백업 폴더에서 가져올 때 붙는 ' (1)' 같은 꼬리는 떼어낸다
            val name = displayName(u)?.replace(Regex(" \\(\\d+\\)(?=\\.png$)", RegexOption.IGNORE_CASE), "")
            if (name == null || !name.lowercase().endsWith(".png")) {
                skipped.add(name ?: u.toString()); continue
            }
            try {
                ctx.contentResolver.openInputStream(u)?.use { ins ->
                    File(dir, name).outputStream().use { outs -> ins.copyTo(outs) }
                }
                Prefs.addUserTemplate(ctx, name)
                ok++
            } catch (e: Exception) {
                Log.w(TAG, "복사 실패: $name", e); skipped.add(name)
            }
        }
        cache.clear()
        generation++
        return ok to skipped
    }

    /** 템플릿 전체 삭제 */
    fun clear(): Int {
        var n = 0
        dir.listFiles()?.forEach { if (it.delete()) n++ }
        Prefs.clearUserTemplates(ctx)
        cache.clear()
        generation++
        return n
    }

    private fun displayName(u: Uri): String? {
        try {
            ctx.contentResolver.query(u, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
            }
        } catch (_: Exception) {
        }
        return u.lastPathSegment?.substringAfterLast('/')
    }

    companion object {
        private const val TAG = "TemplateStore"

        /** 침공 전용 인식 사진 접두어 (PC판과 같다) */
        const val INVASION_PREFIX = "침공_"

        /** 사진이 바뀔 때마다 올라간다 — 감시 중인 서비스가 캐시를 비우게 한다 */
        @Volatile var generation: Int = 0
    }
}
