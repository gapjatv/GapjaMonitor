package tv.gapja.monitor

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import tv.gapja.monitor.core.ImageOps
import tv.gapja.monitor.core.MatchPeak

/**
 * [ImageOps] 의 OpenCV 구현.
 *
 * 모든 이미지는 **그레이스케일 단일 채널**로 다룬다.
 * 색을 버리면 매칭 속도가 3배 가까이 빨라지고, 네임텍처럼 밝기만 바뀌는
 * 반투명 가림막에도 조금 더 견딘다. (색 정보가 필요한 보스는 없었다)
 */
object CvOps : ImageOps<Mat> {

    override fun size(img: Mat): IntArray = intArrayOf(img.cols(), img.rows())

    override fun resize(img: Mat, w: Int, h: Int): Mat {
        val out = Mat()
        // 축소는 INTER_AREA 가 가장 깨끗하다 (모아레를 줄여 매칭 점수를 지켜준다)
        val interp = if (w < img.cols()) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR
        Imgproc.resize(img, out, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, interp)
        return out
    }

    override fun crop(img: Mat, x: Int, y: Int, w: Int, h: Int): Mat {
        val xx = x.coerceIn(0, maxOf(0, img.cols() - 1))
        val yy = y.coerceIn(0, maxOf(0, img.rows() - 1))
        val ww = w.coerceAtMost(img.cols() - xx)
        val hh = h.coerceAtMost(img.rows() - yy)
        if (ww <= 0 || hh <= 0) return Mat()
        // submat 은 원본을 참조하므로, 이후 연산이 안전하도록 복사해서 돌려준다
        return Mat(img, Rect(xx, yy, ww, hh)).clone()
    }

    override fun match(screen: Mat, tpl: Mat): MatchPeak? {
        if (screen.empty() || tpl.empty()) return null
        if (tpl.cols() > screen.cols() || tpl.rows() > screen.rows()) return null
        val res = Mat()
        try {
            Imgproc.matchTemplate(screen, tpl, res, Imgproc.TM_CCOEFF_NORMED)
            val mm = Core.minMaxLoc(res)
            // PSR 계산용 평균·표준편차 (매칭 결과면 전체의 분포)
            val mean = MatOfDouble()
            val std = MatOfDouble()
            Core.meanStdDev(res, mean, std)
            val mu = if (mean.empty()) 0.0 else mean.toArray().firstOrNull() ?: 0.0
            val sd = if (std.empty()) 0.0 else std.toArray().firstOrNull() ?: 0.0
            mean.release(); std.release()
            return MatchPeak(mm.maxVal, mm.maxLoc.x.toInt(), mm.maxLoc.y.toInt(), mu, sd)
        } catch (_: Exception) {
            return null
        } finally {
            res.release()
        }
    }

    /**
     * 윤곽선(외곽선) 기준 일치도.
     *
     * 네임텍 같은 반투명 어두운 막은 '밝기'를 바꾸지만 '윤곽'은 상당히 남긴다.
     * 그래서 색상만 비슷한 엉뚱한 곳을 걸러내는 마지막 관문으로 쓴다.
     * (실측: 진짜 보스 0.60~0.98 vs 색상만 비슷한 가짜 0.18~0.40)
     */
    override fun edgeScore(screen: Mat, tpl: Mat): Double {
        if (screen.empty() || tpl.empty()) return 0.0
        val e1 = Mat()
        val e2 = Mat()
        val res = Mat()
        try {
            Imgproc.Canny(screen, e1, 60.0, 160.0)
            Imgproc.Canny(tpl, e2, 60.0, 160.0)
            if (Core.countNonZero(e2) == 0) return 0.0
            if (e2.cols() > e1.cols() || e2.rows() > e1.rows()) return 0.0
            Imgproc.matchTemplate(e1, e2, res, Imgproc.TM_CCOEFF_NORMED)
            return Core.minMaxLoc(res).maxVal
        } catch (_: Exception) {
            return 0.0
        } finally {
            e1.release(); e2.release(); res.release()
        }
    }

    /** ARGB_8888 바이트 버퍼를 그레이스케일 Mat 으로 */
    fun fromRgba(buffer: java.nio.ByteBuffer, width: Int, height: Int, rowStride: Int): Mat {
        val rowPadding = rowStride - width * 4
        val full = Mat(height, width + rowPadding / 4, CvType.CV_8UC4)
        full.put(0, 0, ByteArray(buffer.remaining()).also { buffer.get(it) })
        val rgba = if (rowPadding == 0) full else Mat(full, Rect(0, 0, width, height))
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        if (rowPadding != 0) rgba.release()
        full.release()
        return gray
    }
}
