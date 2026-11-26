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
 *
 * ★ 화면이 여러 개인 환경 (뮤뮤플레이어의 앱 탭 등)
 *   뮤뮤는 앱마다 탭을 나누고, 탭마다 별도의 화면(디스플레이)을 쓴다.
 *   그래서 게임 창이 뜰 때 그 창이 어느 화면에 있는지 기억해 두고,
 *   ① 그 화면을 찍고 ② 게임 위 보스 사진·버튼도 그 화면에 띄운다.
 *   게임은 앱 이름에 '운빨' 이 들어간 앱으로 알아본다 (한 번 찾으면 기억).
 */
class BossA11yService : AccessibilityService() {

    private val executor = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Bus.log("♿ 접근성 화면 읽기가 켜졌습니다. (확인창 없이 시작할 수 있습니다)")
        findGame()
    }

    private var lastScanAt = 0L

    /**
     * 모든 화면의 창을 훑어서 게임 창이 어느 화면에 있는지 찾는다.
     * (뮤뮤 탭을 옮길 때 창 이벤트가 안 올 수도 있어서, 시작할 때와 주기적으로도 확인한다)
     * @return 찾았으면 true
     */
    fun findGame(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        lastScanAt = android.os.SystemClock.uptimeMillis()
        val all = try { windowsOnAllDisplays } catch (_: Exception) { return false }
        for (i in 0 until all.size()) {
            val disp = all.keyAt(i)
            for (w in all.valueAt(i)) {
                if (w.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val pkg = try { w.root?.packageName?.toString() } catch (_: Exception) { null }
                val title = w.title?.toString() ?: ""
                // 우리 앱 창은 절대 게임으로 보지 않는다
                if (pkg == packageName || title.contains(getString(R.string.app_name))) continue
                val byPkg = pkg != null && isGame(pkg)
                val byTitle = pkg == null && GAME_KEYWORDS.any { title.contains(it, ignoreCase = true) }
                if (byPkg || byTitle) {
                    setGame(disp, pkg ?: gamePackage ?: title)
                    return true
                }
            }
        }
        return false
    }

    private fun setGame(disp: Int, pkg: String) {
        if (disp < 0) return
        if (gameDisplay == disp && gamePackage == pkg) return
        gameDisplay = disp
        gamePackage = pkg
        val label = labelOf(pkg)
        Bus.setGameDisplay(disp, label)
        Bus.log("🎮 게임 화면을 찾았습니다: $label (화면 #$disp) — 이 화면을 감시하고 보스 사진도 여기에 띄웁니다.")
    }

    /** 창이 바뀔 때 — 게임 창이면 그 화면 번호를 기억한다 (화면 내용은 읽지 않는다) */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                // 결제·금융 앱이 앞에 뜨면 화면 공유를 끊는다 (공유 중이면 이런 앱이 결제를 막는다)
                if (Bus.running && Prefs.stopOnSecureApp(this) && isSecureApp(pkg)) {
                    CaptureService.send(this, CaptureService.ACTION_SECURE_APP, pkg = pkg)
                    return
                }
                if (!isGame(pkg)) return
                val disp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) event.displayId else Display.DEFAULT_DISPLAY
                setGame(disp, pkg)
            }
            // 창 구성이 바뀜 (탭 이동 등) — 너무 자주 훑지 않게 1초 간격
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (android.os.SystemClock.uptimeMillis() - lastScanAt > 1000) findGame()
            }
        }
    }

    /** 결제·간편결제·은행 앱인지 — 앱 이름(패키지)이 이 목록으로 시작하면 그렇다고 본다 */
    private fun isSecureApp(pkg: String): Boolean = SECURE_APP_PREFIXES.any { pkg.startsWith(it) }

    private val labelCache = HashMap<String, String>()

    private fun labelOf(pkg: String): String = labelCache.getOrPut(pkg) {
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }
    }

    /**
     * 게임 창인지 — 앱 이름에 '운빨' 이 들어가면 게임으로 본다.
     * 한 번 찾은 게임 앱은 저장해 두고 다음부터 바로 알아본다.
     */
    private fun isGame(pkg: String): Boolean {
        if (pkg == packageName) return false
        val saved = Prefs.gamePackage(this)
        // 예전 버전이 잘못 기억한 값(우리 앱)이면 버린다
        if (saved == packageName) Prefs.setGamePackage(this, null)
        else if (saved != null) return pkg == saved
        val label = labelOf(pkg)
        if (GAME_KEYWORDS.any { label.contains(it, ignoreCase = true) }) {
            Prefs.setGamePackage(this, pkg)
            return true
        }
        return false
    }

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
            takeScreenshot(targetDisplay(), executor, object : TakeScreenshotCallback {
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
        /** 화면 공유 중이면 결제·인증이 막히는 대표 앱들 (삼성페이·간편결제·은행·증권) */
        private val SECURE_APP_PREFIXES = listOf(
            "com.samsung.android.spay", "com.samsung.android.samsungpay", "com.samsung.android.spaylite",
            "com.nhn.android.npay", "com.kakaopay", "com.kakaobank", "viva.republica.toss",
            "com.kbankwith", "com.kbstar", "com.shinhan", "com.wooribank", "com.nonghyup", "com.hanabank",
            "com.kebhana", "com.ibk", "com.scbank", "com.epost.psf", "kr.co.citibank", "com.lguplus.paynow",
            "com.google.android.apps.walletnfcrel", "com.nhnent.payapp", "com.lotte.lpay", "com.ssg.pay",
            "kr.danal", "com.paycoapp", "com.mobiletoss"
        )

        private const val TAG = "BossA11y"

        /**
         * 게임 앱 이름에 들어 있는 말 (운빨존많겜 / Lucky Defense).
         * ⚠️ '운빨' 만으로 찾으면 우리 앱(운빨보스알림)도 걸려서, 버튼이 게임 탭과 우리 탭 사이를
         *    왔다 갔다 하며 사라지는 문제가 있었다 → '운빨존많' 처럼 게임 이름으로만 찾는다.
         */
        private val GAME_KEYWORDS = listOf("운빨존많", "Lucky Defense", "LuckyDefense")

        /** 게임 창이 있는 화면 번호 (뮤뮤 탭마다 다르다. 폰은 0) */
        @Volatile var gameDisplay: Int = Display.DEFAULT_DISPLAY
        @Volatile var gamePackage: String? = null

        /** 감시할 화면 — 게임 화면을 찾았으면 그 화면, 아니면 기본 화면 */
        fun targetDisplay(): Int = gameDisplay

        /** 저장해 둔 게임 앱을 잊고 다시 찾는다 (다른 게임을 잘못 잡았을 때) */
        fun forgetGame(ctx: Context) {
            Prefs.setGamePackage(ctx, null)
            gamePackage = null
            gameDisplay = Display.DEFAULT_DISPLAY
            instance?.findGame()
        }

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
