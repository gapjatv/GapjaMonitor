package tv.gapja.monitor

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * 내가 등록한 인식용 사진을 폰 '사진첩' (Pictures/보스알림) 에도 한 장씩 남긴다.
 *
 *   • 사진첩(갤러리) 앱에서 바로 볼 수 있다 — 모아서 기본 사진으로 넣을 때 그대로 쓰면 된다
 *   • 앱을 지워도 남으므로, 다시 깐 뒤 메인 화면 [백업에서 되살리기] 로 한 번에 가져올 수 있다
 *   • 자른 사진은 '보스이름.png', 자르기 전 전체 화면은 '원본화면' 폴더에 날짜 이름으로 남긴다
 *
 * (Android 10 이상은 자기 앱이 만드는 사진에 별도 권한이 필요 없다)
 */
object Backup {

    private const val TAG = "Backup"
    private const val FOLDER = "보스알림"
    private val RELATIVE = Environment.DIRECTORY_PICTURES + "/" + FOLDER + "/"
    private val RELATIVE_FULL = Environment.DIRECTORY_PICTURES + "/" + FOLDER + "/원본화면/"

    /** 사진 한 장을 다운로드 폴더에 복사한다 (같은 이름이 있으면 바꿔 넣는다) */
    fun saveCopy(ctx: Context, src: File) {
        try {
            val resolver = ctx.contentResolver
            val coll = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
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

    /** 자르기 전 전체 화면을 사진첩 '보스알림/원본화면' 에 남긴다 (나중에 다시 자를 때 쓴다) */
    fun saveFullScreen(ctx: Context, bmp: android.graphics.Bitmap, label: String) {
        try {
            val resolver = ctx.contentResolver
            val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.KOREA).format(java.util.Date())
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "${label}_$stamp.png")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_FULL)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(uri)?.use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "원본 화면 저장 실패", e)
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
    const val LOCATION = "사진첩 › 보스알림"
}
