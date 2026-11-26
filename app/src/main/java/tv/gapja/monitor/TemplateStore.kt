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
     * 인식에 써도 되는 사진인지 — 파일이 있으면 쓴다. (v3.2)
     * 처음 설치하면 앱에 들어 있는 기본 사진으로 바로 인식하고,
     * 내가 등록한 사진이 있는 보스(로비)만 내 사진이 우선한다. ('기본 / 직접 새로 등록' 어느 쪽을 골라도 같다)
     */
    fun usable(fileName: String): Boolean = File(dir, fileName).exists()

    fun getUsable(fileName: String): Mat? = if (usable(fileName)) get(fileName) else null

    // ── 내가 등록한 사진 최우선 (v2.0) ─────────────────────────
    //  그 보스(또는 로비)에 내가 직접 등록한 사진이 하나라도 있으면 내 사진만 쓰고,
    //  앱에 들어 있던 기본 사진(핸드폰 기본·PC 사진)은 인식에서 뺀다.
    //  기본 사진 파일은 지우지 않고 남겨 둔다 → 내 사진을 지우면 기본 사진이 다시 쓰인다.

    /**
     * 그 이름(`그런트` 또는 `침공_그런트`)으로 내가 등록한 사진들 — 기본 자리 먼저, 그다음 보조.
     * 내 사진은 기본 1장 + 보조 1장(_2)까지만 쓴다 (v2.8). 예전에 등록한 _3·_4 는 쓰지 않는다.
     */
    fun userShots(base: String): List<String> =
        listOf("$base.png", "$base$USER_EXTRA.png")
            .filter { isUser(it) && File(dir, it).exists() }

    /**
     * 그 보스 인식에 쓸 내 사진 묶음 — 없으면 빈 목록.
     * 침공: 침공_ 내 사진 → (없으면) 태초 내 사진. 태초: 태초 내 사진.
     */
    fun userShotsFor(name: String, mode: GameMode): List<String> {
        if (PcDefaults.isCommonSlot(name)) return PcDefaults.commonFile(name).let { if (isUser(it) && exists(it)) listOf(it) else emptyList() }
        if (PcDefaults.isLobby(name)) return userShots(name)
        if (mode == GameMode.INVASION) {
            val inv = userShots("${INVASION_PREFIX}$name")
            if (inv.isNotEmpty()) return inv
            // 침공 히든보스는 태초 히든보스 사진으로 대신하지 않는다 (v2.3)
            if (invasionOnlyShots(name, mode)) return emptyList()
        }
        return userShots(name)
    }

    /**
     * 침공에서 '침공_' 사진만 써야 하는 보스인지 — 침공 히든보스.
     * 태초 히든보스와 화면이 달라서, 태초 사진으로 찾으면 엉뚱한 곳에 맞을 수 있다.
     */
    fun invasionOnlyShots(name: String, mode: GameMode): Boolean =
        BossCatalog.isInvasionHidden(name, mode)

    /** 앱 기본 사진인데 같은 보스(로비)에 내 사진이 있어서 인식에서 빠진 사진인지 — 사진 보기 화면 표시용 */
    fun shadowedByUser(fileName: String): Boolean {
        if (isUser(fileName) || fileName.contains("배속상태")) return false
        val stem = fileName.removeSuffix(".png").replace(Regex("_[2-9]$"), "")
        if (PcDefaults.isLobby(stem)) return userShots(stem).isNotEmpty()
        return if (stem.startsWith(INVASION_PREFIX))
            userShotsFor(stem.removePrefix(INVASION_PREFIX), GameMode.INVASION).isNotEmpty()
        else userShots(stem).isNotEmpty()
    }

    /** 로비 화면 사진들 — 내 사진이 있으면 내 사진만, 없으면 기본 + 보조 중 쓸 수 있는 것 */
    fun lobbyShots(lobby: String): List<String> {
        // 공통로비는 화면이 3가지 — 있는 사진을 모두 쓴다 (서로 다른 화면이라 '내 사진 우선' 을 적용하지 않는다)
        if (lobby == PcDefaults.LOBBY_COMMON) return PcDefaults.COMMON_FILES.filter { usable(it) }
        val mine = userShots(lobby)
        if (mine.isNotEmpty()) return mine
        return (listOf("$lobby.png") + extraShots(lobby)).filter { usable(it) }
    }

    /** 그 보스(또는 로비)에 내가 등록한 사진이 하나라도 있는지 — 등록 도우미용 */
    fun hasUserShot(name: String, mode: GameMode): Boolean {
        if (PcDefaults.isCommonSlot(name)) return userShotsFor(name, mode).isNotEmpty()
        val bases = when {
            invasionOnlyShots(name, mode) -> listOf("${INVASION_PREFIX}$name")
            mode == GameMode.INVASION && !PcDefaults.isLobby(name) -> listOf("${INVASION_PREFIX}$name", name)
            else -> listOf(name)
        }
        return bases.any { b -> userShots(b).isNotEmpty() }
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
        cache.clear(); generation++
        GitHubSync.syncFile(ctx, name)
        true
    } catch (_: Exception) { false }

    /** 앱에 들어 있던 기본 사진으로 되돌린다 (내 사진 표시도 지운다) */
    fun restoreBundled(name: String): Boolean = try {
        backup(name)
        val path = AssetInstaller.bundledTemplatePath(ctx, name) ?: return false
        ctx.assets.open(path).use { ins -> File(dir, name).outputStream().use { ins.copyTo(it) } }
        Prefs.removeUserTemplate(ctx, name)
        cache.clear(); generation++
        GitHubSync.syncFile(ctx, name)     // 내 사진이 아니게 됐으니 깃허브에서도 지운다
        true
    } catch (_: Exception) { false }

    /** 사진을 지운다 (되돌릴 수 있게 보관함에 남긴다) */
    fun delete(name: String): Boolean = try {
        backup(name)
        val ok = File(dir, name).delete()
        Prefs.removeUserTemplate(ctx, name)
        cache.clear(); generation++
        GitHubSync.syncFile(ctx, name)     // 깃허브에서도 지운다 (깃허브 기록에는 남아서 되살릴 수 있다)
        ok
    } catch (_: Exception) { false }

    /** 모드에 맞는 기본 템플릿 — 침공이면 '침공_' 사진을 먼저 쓴다 */
    fun forBoss(name: String, mode: GameMode): Mat? = forBossFile(name, mode)?.let { get(it) }

    /** 그 보스 인식에 쓰는 기본 사진 파일 이름 (없으면 null) */
    fun forBossFile(name: String, mode: GameMode): String? {
        // 내가 등록한 사진이 있으면 그중 첫 장이 기본 사진 (기본 자리 → 보조 순서)
        userShotsFor(name, mode).firstOrNull { get(it) != null }?.let { return it }
        val inv = "${INVASION_PREFIX}$name.png"
        if (mode == GameMode.INVASION && usable(inv) && get(inv) != null) return inv
        if (invasionOnlyShots(name, mode)) return null
        val base = "$name.png"
        return if (usable(base) && get(base) != null) base else null
    }

    /**
     * 지금 그 보스 인식에 실제로 쓰이는 기본 사진 파일 이름 (없으면 null) — 사진 보기 화면용 (v3.2).
     * [forBossFile] 과 같은 순서(내 사진 → 침공_ 기본 → 기본)이지만 사진을 읽지 않고 파일이 있는지만 본다.
     */
    fun activeBossFile(name: String, mode: GameMode): String? {
        userShotsFor(name, mode).firstOrNull()?.let { return it }
        val inv = "${INVASION_PREFIX}$name.png"
        if (mode == GameMode.INVASION && usable(inv)) return inv
        if (invasionOnlyShots(name, mode)) return null
        val base = "$name.png"
        return if (usable(base)) base else null
    }

    /** 보스의 보조 스샷 파일 이름 목록 (존재하는 것만) */
    fun extraShots(name: String): List<String> =
        BossCatalog.EXTRA_SHOT_SUFFIXES
            .map { "$name$it.png" }
            .filter { File(dir, it).exists() }

    /** 모드에 맞는 보조 스샷 — 침공이면 '침공_' 보조 스샷도 함께 */
    fun extraShots(name: String, mode: GameMode): List<String> {
        // 내 사진이 있으면 기본 사진으로 고른 첫 장을 뺀 나머지 내 사진만 보조로 쓴다
        val mine = userShotsFor(name, mode)
        if (mine.isNotEmpty()) {
            val primary = forBossFile(name, mode)
            return mine.filter { it != primary }
        }
        val inv = if (mode == GameMode.INVASION) extraShots("${INVASION_PREFIX}$name") else emptyList()
        if (invasionOnlyShots(name, mode)) return inv.filter { usable(it) }
        return (inv + extraShots(name)).filter { usable(it) }
    }

    /** 템플릿이 있는 보스만 돌려준다 */
    fun availableBosses(all: List<String>): List<String> =
        all.filter { File(dir, "$it.png").exists() }

    /** 그 모드에서 인식할 수 있는 보스 */
    fun availableBosses(all: List<String>, mode: GameMode): List<String> =
        all.filter {
            if (invasionOnlyShots(it, mode))
                return@filter userShotsFor(it, mode).isNotEmpty() ||
                        usable("${INVASION_PREFIX}$it.png") || extraShots(it, mode).isNotEmpty()
            userShotsFor(it, mode).isNotEmpty() ||
            usable("$it.png") || extraShots(it, mode).isNotEmpty() ||
                    (mode == GameMode.INVASION && usable("${INVASION_PREFIX}$it.png"))
        }

    /** 보스(로비) 이름과 모드로 사진 이름의 앞부분을 만든다 — `그런트` / `침공_그런트` / `태초로비` */
    fun baseName(boss: String, mode: GameMode): String =
        // 로비 화면(태초로비·침공로비·공통로비)은 이름에 모드가 들어 있어 '침공_' 을 붙이지 않는다
        if (mode == GameMode.INVASION && !PcDefaults.isLobby(boss)) "${INVASION_PREFIX}$boss" else boss

    /** 이미 내가 등록한 보조 사진(_2)이 있는지 — 있으면 새로 등록할 때 그 보조 사진을 바꾼다 */
    fun hasUserExtra(boss: String, mode: GameMode): Boolean {
        val n = "${baseName(boss, mode)}$USER_EXTRA.png"
        return isUser(n) && File(dir, n).exists()
    }

    /**
     * 직접 캡처해서 잘라낸 사진을 저장한다.
     *
     * 내 사진은 보스마다 기본 1장 + 보조 1장까지 (v2.8).
     *   • asExtra = false → 기본 사진 자리({이름}.png) 에 넣는다
     *   • asExtra = true  → 보조 사진 자리({이름}_2.png) 에 넣는다. 이미 보조 사진이 있으면 그것을 지우고 새 사진으로 바꾼다
     *     (예전에 등록한 _3·_4 내 사진도 이때 같이 정리한다)
     * 바뀌거나 지워지는 사진은 templates_bak 에 보관 → '이전 사진으로 되돌리기'
     *
     * @return 저장한 파일 이름
     */
    fun saveCrop(bmp: Bitmap, boss: String, mode: GameMode, asExtra: Boolean): String? {
        val base = baseName(boss, mode)
        // 공통로비 1·2·3 은 칸마다 파일이 정해져 있다 (보조 없이 그 칸을 새 사진으로 바꾼다)
        val name = if (PcDefaults.isCommonSlot(boss)) PcDefaults.commonFile(boss)
                   else if (!asExtra) "$base.png" else "$base$USER_EXTRA.png"
        val removed = ArrayList<String>()
        if (asExtra && !PcDefaults.isCommonSlot(boss)) {
            for (sfx in BossCatalog.EXTRA_SHOT_SUFFIXES) {
                val n = "$base$sfx.png"
                if (n == name || !isUser(n)) continue
                val old = File(dir, n)
                if (old.exists()) { backup(n); old.delete() }
                Prefs.removeUserTemplate(ctx, n)
                removed.add(n)
            }
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
            // 깃허브 사진 저장소에도 올린다 (열쇠가 있고 자동 저장이 켜져 있을 때만)
            GitHubSync.syncFiles(ctx, listOf(name) + removed)
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
        val imported = ArrayList<String>()
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
                imported.add(name)
                ok++
            } catch (e: Exception) {
                Log.w(TAG, "복사 실패: $name", e); skipped.add(name)
            }
        }
        cache.clear()
        generation++
        GitHubSync.syncFiles(ctx, imported)
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

        /** 내 사진의 보조 자리 — 보스마다 보조는 1장만 (v2.8) */
        const val USER_EXTRA = "_2"

        /** 사진이 바뀔 때마다 올라간다 — 감시 중인 서비스가 캐시를 비우게 한다 */
        @Volatile var generation: Int = 0
    }
}
