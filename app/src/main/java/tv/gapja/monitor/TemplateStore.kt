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

    /** 모드에 맞는 기본 템플릿 — 침공이면 '침공_' 사진을 먼저 쓴다 */
    fun forBoss(name: String, mode: GameMode): Mat? {
        if (mode == GameMode.INVASION) get("${INVASION_PREFIX}$name.png")?.let { return it }
        return get("$name.png")
    }

    /** 보스의 보조 스샷 파일 이름 목록 (존재하는 것만) */
    fun extraShots(name: String): List<String> =
        BossCatalog.EXTRA_SHOT_SUFFIXES
            .map { "$name$it.png" }
            .filter { File(dir, it).exists() }

    /** 모드에 맞는 보조 스샷 — 침공이면 '침공_' 보조 스샷도 함께 */
    fun extraShots(name: String, mode: GameMode): List<String> {
        val inv = if (mode == GameMode.INVASION) extraShots("${INVASION_PREFIX}$name") else emptyList()
        return inv + extraShots(name)
    }

    /** 템플릿이 있는 보스만 돌려준다 */
    fun availableBosses(all: List<String>): List<String> =
        all.filter { File(dir, "$it.png").exists() }

    /** 그 모드에서 인식할 수 있는 보스 */
    fun availableBosses(all: List<String>, mode: GameMode): List<String> =
        all.filter {
            File(dir, "$it.png").exists() ||
                    (mode == GameMode.INVASION && File(dir, "${INVASION_PREFIX}$it.png").exists())
        }

    /**
     * 직접 캡처해서 잘라낸 사진을 저장한다.
     *
     * @param asExtra true 면 보조 사진(_2, _3, _4 중 빈 자리)으로, false 면 기본 사진을 바꾼다
     * @return 저장한 파일 이름 (보조 자리가 꽉 찼으면 _4 를 덮어쓴다)
     */
    fun saveCrop(bmp: Bitmap, boss: String, mode: GameMode, asExtra: Boolean): String? {
        val base = if (mode == GameMode.INVASION) "${INVASION_PREFIX}$boss" else boss
        val name = if (!asExtra) "$base.png" else {
            BossCatalog.EXTRA_SHOT_SUFFIXES.map { "$base$it.png" }
                .firstOrNull { !File(dir, it).exists() }
                ?: "$base${BossCatalog.EXTRA_SHOT_SUFFIXES.last()}.png"
        }
        return try {
            File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
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
            val name = displayName(u)
            if (name == null || !name.lowercase().endsWith(".png")) {
                skipped.add(name ?: u.toString()); continue
            }
            try {
                ctx.contentResolver.openInputStream(u)?.use { ins ->
                    File(dir, name).outputStream().use { outs -> ins.copyTo(outs) }
                }
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
