package tv.gapja.monitor

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.Executors

/**
 * 접근성 방식 화면 읽기 — '화면을 공유하시겠습니까?' 확인창 없이 보스를 인식한다.
 *
 * 안드로이드 11부터 접근성 서비스는 화면을 한 장씩 찍을 수 있다(takeScreenshot).
 * 설정에서 한 번 켜 두면 계속 유지되고, [시작] 할 때 확인창이 뜨지 않는다.
 * 뮤뮤플레이어처럼 확인창이 다른 탭으로 빠져서 누를 수 없는 환경에서 특히 필요하다.
 *
 * 이 서비스는 화면을 찍어서 CaptureService 에 넘기는 일만 한다.
 * 화면 내용을 어디로 보내거나 저장하지 않고, 다른 앱을 조작하지도 않는다.
 *
 * 제약: 안드로이드가 찍는 간격을 제한한다 (대략 0.33~1초에 한 장). 화면 공유 방식보다 느리다.
 */
class BossA11yService : AccessibilityService() {

    private val executor = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Bus.log("♿ 접근성 화면 읽기가 켜졌습니다. (확인창 없이 시작할 수 있습니다)")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* 화면 이벤트는 쓰지 않는다 */ }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        executor.shutdown()
        super.onDestroy()
    }

    /**
     * 지금 화면을 한 장 찍는다. 결과는 executor 스레드에서 돌아온다.
     * @param cb 찍은 화면 (실패하면 null 과 오류 코드)
     */
    fun grab(cb: (Bitmap?, Int) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { cb(null, -1); return }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hb = result.hardwareBuffer
                    val bmp = try {
                        // 하드웨어 비트맵은 픽셀을 읽을 수 없어 일반 비트맵으로 복사한다
                        Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                            ?.let { hw -> hw.copy(Bitmap.Config.ARGB_8888, false).also { hw.recycle() } }
                    } catch (e: Throwable) {
                        Log.w(TAG, "화면 변환 실패", e); null
                    } finally {
                        try { hb.close() } catch (_: Throwable) {}
                    }
                    cb(bmp, 0)
                }

                override fun onFailure(errorCode: Int) { cb(null, errorCode) }
            })
        } catch (e: Throwable) {
            Log.w(TAG, "takeScreenshot 실패", e)
            cb(null, -2)
        }
    }

    companion object {
        private const val TAG = "BossA11y"

        /** 지금 켜져 있는 서비스 */
        @Volatile var instance: BossA11yService? = null

        /** 안드로이드가 '너무 자주 찍음' 으로 거절할 때의 코드 (ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) */
        const val ERROR_TOO_SOON = 3

        /** 접근성 방식을 쓸 수 있는 안드로이드인지 (11 이상) */
        fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

        /** 설정에서 이 앱의 접근성이 켜져 있는지 */
        fun isOn(ctx: Context): Boolean {
            if (instance != null) return true
            return try {
                val enabled = Settings.Secure.getString(
                    ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
                val me = ComponentName(ctx, BossA11yService::class.java)
                enabled.split(':').any {
                    ComponentName.unflattenFromString(it)?.let { c -> c == me } ?: false
                }
            } catch (_: Exception) { false }
        }
    }
}
