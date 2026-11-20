package tv.gapja.monitor

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 앱에 같이 넣어 둔 기본 자료를 앱 저장소로 풀어 놓는다.
 *
 *   assets/templates/ → templates/  인식용 캡처 사진 (PC '캡쳐된 보스 사진')
 *   assets/display/   → display/    게임 위에 띄울 보스 사진 (PC 'ui_images')
 *   assets/voices/    → voices/     보스 음성·80R 멘트 MP3 (PC 'voices')
 *
 * 이미 있는 파일은 덮어쓰지 않는다 — 사용자가 직접 캡처해서 바꾼 사진을 지키기 위해서다.
 */
object AssetInstaller {

    /** 자료를 바꿔서 새로 넣을 때 올린다 */
    private const val VERSION = 1

    private val FOLDERS = listOf("templates", "display", "voices")

    fun installIfNeeded(ctx: Context): Int {
        if (Prefs.assetsVersion(ctx) >= VERSION) return 0
        var n = 0
        for (folder in FOLDERS) {
            val names = try { ctx.assets.list(folder) ?: emptyArray() } catch (_: Exception) { emptyArray() }
            val dir = File(ctx.filesDir, folder).apply { mkdirs() }
            for (name in names) {
                val out = File(dir, name)
                if (out.exists() && out.length() > 0) continue
                try {
                    ctx.assets.open("$folder/$name").use { ins ->
                        out.outputStream().use { o -> ins.copyTo(o) }
                    }
                    n++
                } catch (e: Exception) {
                    Log.w("AssetInstaller", "복사 실패: $folder/$name", e)
                }
            }
        }
        Prefs.setAssetsVersion(ctx, VERSION)
        return n
    }

    /** 게임 위 표시용 보스 사진 파일 */
    fun displayFile(ctx: Context, boss: String): File = File(File(ctx.filesDir, "display"), "$boss.png")

    fun displayCount(ctx: Context): Int =
        File(ctx.filesDir, "display").listFiles { f -> f.name.endsWith(".png") }?.size ?: 0
}
