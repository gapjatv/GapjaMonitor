package tv.gapja.monitor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * 게임 위에 뜨는 '알림 보스 사진' 만들기 (배포용).
 *
 * 인식용 보스 사진을 등록할 때, 같은 사진으로 알림용 동그란 보스 사진도 자동으로 만든다.
 *   • 보스 윤곽만 따서(OpenCV GrabCut) 배경은 투명하게 하고,
 *   • 보스가 가운데 오도록 정사각형 캔버스에 맞춘다.
 *   • 윤곽을 믿을 수 없는 사진이면(너무 작거나 너무 크게 잡힘) 윤곽을 따지 않고 사진 전체를 가운데에 둔다.
 *
 * 인식용 사진(보스 스크린샷)은 이 과정과 상관없이 자른 그대로 저장된다.
 */
object DisplayPhoto {

    /** 알림용 사진 한 변 (px) */
    private const val OUT = 256
    /** 윤곽을 따는 작업용 크기 (긴 변) */
    private const val WORK = 200
    private const val TAG = "DisplayPhoto"

    /** 알림용 사진이 바뀔 때마다 올라간다 — 그리는 쪽이 이 값이 바뀌면 사진을 다시 읽는다 */
    @Volatile var generation = 0

    /** 등록한 보스 사진으로 알림용 사진을 만들어 저장한다. 성공하면 true */
    fun saveFor(ctx: Context, boss: String, crop: Bitmap): Boolean = try {
        val out = make(crop)
        val f = AssetInstaller.displayFile(ctx, boss)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "$boss.png.tmp")
        tmp.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (f.exists()) f.delete()
        val ok = tmp.renameTo(f)
        out.recycle()
        generation++
        ok
    } catch (e: Throwable) {
        Log.w(TAG, "알림용 사진 만들기 실패: $boss", e); false
    }

    /** 알림용 정사각형 사진 (배경 투명) */
    fun make(crop: Bitmap): Bitmap {
        val cut = try { cutOut(crop) } catch (e: Throwable) { Log.w(TAG, "윤곽 따기 실패", e); null }
        val subject = cut ?: crop
        return fitSquare(subject)
    }

    /** 보스 윤곽만 남기고 배경은 투명하게 한 사진 — 윤곽을 믿을 수 없으면 null */
    private fun cutOut(crop: Bitmap): Bitmap? {
        if (!OpenCVLoader.initLocal()) return null
        if (crop.width < 24 || crop.height < 24) return null

        val rgba = Mat()
        // 하드웨어 비트맵 등은 바로 읽을 수 없어서 보통 비트맵으로 한 번 복사한다
        val plain = if (crop.config == Bitmap.Config.ARGB_8888 && !crop.isRecycled) crop
            else crop.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        Utils.bitmapToMat(plain, rgba)
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)

        // 작업용으로 줄인다 (GrabCut 은 작은 사진에서도 충분하고 훨씬 빠르다)
        val k = WORK.toDouble() / max(bgr.cols(), bgr.rows())
        val small = Mat()
        if (k < 1.0) Imgproc.resize(bgr, small, Size(max(1.0, bgr.cols() * k), max(1.0, bgr.rows() * k)), 0.0, 0.0, Imgproc.INTER_AREA)
        else bgr.copyTo(small)
        val sw = small.cols(); val sh = small.rows()

        // 보스는 사진 가운데에 있고, 몸통은 아래쪽 가장자리까지 이어질 수 있다 → 아래는 가장자리까지 열어 둔다
        val m = max(2, (min(sw, sh) * 0.05).toInt())
        val rect = org.opencv.core.Rect(m, m, sw - 2 * m, sh - m)
        val mask = Mat(sh, sw, CvType.CV_8UC1, Scalar(Imgproc.GC_BGD.toDouble()))
        val bgModel = Mat(); val fgModel = Mat()
        Imgproc.grabCut(small, mask, rect, bgModel, fgModel, 5, Imgproc.GC_INIT_WITH_RECT)

        // 보스(확실 · 아마도)만 남긴다
        val fg1 = Mat(); val fg3 = Mat(); val fg = Mat()
        Core.compare(mask, Scalar(Imgproc.GC_FGD.toDouble()), fg1, Core.CMP_EQ)
        Core.compare(mask, Scalar(Imgproc.GC_PR_FGD.toDouble()), fg3, Core.CMP_EQ)
        Core.bitwise_or(fg1, fg3, fg)

        // 가장 큰 덩어리만
        val labels = Mat(); val stats = Mat(); val cents = Mat()
        val n = Imgproc.connectedComponentsWithStats(fg, labels, stats, cents)
        if (n <= 1) return null
        var best = 1; var bestArea = -1.0
        for (i in 1 until n) {
            val a = stats.get(i, Imgproc.CC_STAT_AREA)[0]
            if (a > bestArea) { bestArea = a; best = i }
        }
        val one = Mat()
        Core.compare(labels, Scalar(best.toDouble()), one, Core.CMP_EQ)

        // 구멍(눈 · 입 · 장식)은 메운다
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(one.clone(), contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        val filled = Mat.zeros(sh, sw, CvType.CV_8UC1)
        Imgproc.drawContours(filled, contours, -1, Scalar(255.0), -1)

        val cover = Core.countNonZero(filled).toDouble() / (sw * sh)
        // 보스가 사진의 12%도 안 되거나 85%를 넘으면 윤곽이 엉뚱하게 잡힌 것 → 쓰지 않는다
        if (cover < 0.12 || cover > 0.85) return null

        // 원래 크기로 키워서 가장자리를 살짝 부드럽게 한다
        val full = Mat()
        Imgproc.resize(filled, full, Size(bgr.cols().toDouble(), bgr.rows().toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        Imgproc.GaussianBlur(full, full, Size(3.0, 3.0), 0.0)

        // 보스 둘레 네모 (사진 좌표)
        val pts = Mat()
        Core.findNonZero(full, pts)
        if (pts.empty()) return null
        val box = Imgproc.boundingRect(pts)
        if (box.width < 8 || box.height < 8) return null

        // 알파 = 윤곽
        val ch = ArrayList<Mat>()
        Core.split(rgba, ch)
        ch[3] = full
        val merged = Mat()
        Core.merge(ch, merged)
        val result = Bitmap.createBitmap(bgr.cols(), bgr.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(merged, result)

        // 보스 둘레 네모로 자른다 (바깥 투명 영역은 버린다)
        val l = box.x.coerceIn(0, result.width - 1)
        val t = box.y.coerceIn(0, result.height - 1)
        val w = box.width.coerceIn(1, result.width - l)
        val h = box.height.coerceIn(1, result.height - t)
        return Bitmap.createBitmap(result, l, t, w, h)
    }

    /** 사진을 정사각형 캔버스 가운데에 맞춘다 (남는 곳은 투명) */
    private fun fitSquare(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(OUT, OUT, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val pad = (OUT * 0.04f)
        val avail = OUT - 2 * pad
        val scale = min(avail / src.width, avail / src.height)
        val w = src.width * scale; val h = src.height * scale
        val l = (OUT - w) / 2f; val t = (OUT - h) / 2f
        c.drawBitmap(src, Rect(0, 0, src.width, src.height),
            android.graphics.RectF(l, t, l + w, t + h),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return out
    }
}
