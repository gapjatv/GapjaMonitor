package tv.gapja.monitor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min

/**
 * 동그란 보스 사진을 만들어 두는 곳 (게임 위 사진 줄과 남은 보스 예측 창이 함께 쓴다).
 *
 * 배포용: 보스 사진은 사용자가 등록한 것만 쓴다 (인식용 사진을 등록하면 알림용 사진도 자동으로 만들어진다).
 * 등록하지 않은 보스는 흰 동그라미에 보스 이름 앞 두 글자로 보여 준다.
 *
 * 크기를 부드럽게 바꿀 때 사진을 매번 새로 만들면 버벅이므로,
 *   • 원본 사진은 한 번만 읽어 두고
 *   • 동그란 사진은 8px 단위(bucket)로만 만들어 두었다가 그리는 쪽에서 살짝 늘이거나 줄여 쓴다.
 */
class BossCircles(private val ctx: Context) {

    private val source = HashMap<String, Bitmap?>()
    private val cache = HashMap<String, Bitmap?>()

    /** 알림용 사진이 새로 등록·바뀌면 읽어 둔 것을 비운다 */
    private var seenGeneration = DisplayPhoto.generation

    private fun syncGeneration() {
        if (seenGeneration != DisplayPhoto.generation) {
            source.clear(); cache.clear(); seenGeneration = DisplayPhoto.generation
        }
    }

    /** 사진 크기를 8px 단위로 올림한 값 — 이 크기로 만들어 둔다 */
    fun bucket(d: Int): Int = ((max(8, d) + 7) / 8) * 8

    /** 보스 원본 사진 (없으면 null) — 한 번 읽으면 계속 쓴다 */
    private fun original(boss: String): Bitmap? {
        if (source.containsKey(boss)) return source[boss]
        val f = AssetInstaller.displayFile(ctx, boss)
        val bmp = try {
            if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
        } catch (_: Exception) { null }
        source[boss] = bmp
        return bmp
    }

    /** 지름이 d 쯤인 동그란 보스 사진 (실제 크기는 [bucket] 단위 — 그릴 때 d 로 맞춘다) */
    fun get(boss: String, d: Int): Bitmap? {
        syncGeneration()
        val size = bucket(d)
        val key = "$boss@$size"
        if (cache.containsKey(key)) return cache[key]
        if (cache.size > 160) cache.clear()
        val src = original(boss)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        if (src != null) {
            // 동그라미 안쪽 바탕 — 밝은 게임 화면 위에서도 보스가 잘 보이게
            p.shader = LinearGradient(0f, 0f, 0f, size.toFloat(),
                Color.parseColor("#FF463C50"), Color.parseColor("#FF282332"), Shader.TileMode.CLAMP)
            c.drawCircle(size / 2f, size / 2f, size / 2f, p)
            p.shader = null
        } else {
            // 등록한 사진이 없으면 흰 동그라미 (가장자리에 옅은 테두리)
            p.color = Color.WHITE
            c.drawCircle(size / 2f, size / 2f, size / 2f, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = max(1f, size * 0.025f)
            p.color = Color.parseColor("#66000000")
            c.drawCircle(size / 2f, size / 2f, size / 2f - p.strokeWidth / 2f, p)
            p.style = Paint.Style.FILL
        }
        if (src != null) {
            val clip = Path().apply { addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW) }
            c.save(); c.clipPath(clip)
            val s = size * 0.92f
            val scale = min(s / src.width, s / src.height)
            val w = src.width * scale; val h = src.height * scale
            val l = (size - w) / 2f; val t = (size - h) / 2f + size * 0.03f
            c.drawBitmap(src, Rect(0, 0, src.width, src.height), RectF(l, t, l + w, t + h),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            c.restore()
        } else {
            p.color = Color.parseColor("#FF33404D"); p.textAlign = Paint.Align.CENTER
            p.textSize = size * 0.32f; p.typeface = Typeface.DEFAULT_BOLD
            c.drawText(boss.take(2), size / 2f, size / 2f + p.textSize / 3f, p)
        }
        cache[key] = out
        return out
    }
}
