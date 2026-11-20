package tv.gapja.monitor

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * 내가 등록한 인식용 사진을 폰의 '다운로드/GapjaMonitor' 폴더에도 한 장씩 남긴다.
 *
 * 앱 안의 사진은 앱을 업데이트해도 그대로 남지만, 앱을 '삭제' 하면 같이 지워진다.
 * 다운로드 폴더의 사본은 앱을 지워도 남으므로, 다시 깐 뒤
 * 메인 화면 [백업에서 사진 되살리기] 로 한 번에 가져올 수 있다.
 *
 * (Android 10 이상은 자기 앱이 만드는 다운로드 파일에 별도 권한이 필요 없다)
 */
object Backup {

    private const val TAG = "Backup"
    private const val FOLDER = "GapjaMonitor"
    private val RELATIVE = Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/"

    /** 사진 한 장을 다운로드 폴더에 복사한다 (같은 이름이 있으면 바꿔 넣는다) */
    fun saveCopy(ctx: Context, src: File) {
        try {
            val resolver = ctx.contentResolver
            val coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            // 이 앱이 예전에 남긴 같은 이름 사본은 지운다 (안 지우면 '이름 (1).png' 이 쌓인다)
            resolver.delete(
                coll,
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(src.name, RELATIVE)
            )
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, src.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(coll, values) ?: return
            resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "백업 실패: ${src.name}", e)
        }
    }

    /** 내가 등록한 사진 전부를 다시 백업한다 (메인 화면 [지금 백업]) */
    fun saveAll(ctx: Context): Int {
        val dir = File(ctx.filesDir, "templates")
        var n = 0
        for (name in Prefs.userTemplates(ctx)) {
            val f = File(dir, name)
            if (f.exists()) { saveCopy(ctx, f); n++ }
        }
        return n
    }

    /** 사용자에게 보여줄 백업 위치 */
    const val LOCATION = "다운로드 › GapjaMonitor"
}
