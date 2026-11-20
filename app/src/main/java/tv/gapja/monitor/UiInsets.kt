package tv.gapja.monitor

import android.content.Context
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.min

/**
 * 화면 가장자리 여백.
 *
 * Android 15(targetSdk 35)부터 앱 화면이 상태바·내비게이션 바(뒤로가기 줄) 밑까지 그려진다.
 * 그대로 두면 맨 아래 [취소]·[저장] 같은 버튼이 뒤로가기 줄과 겹쳐 눌리지 않는다.
 * → 시스템 바 높이만큼 안쪽 여백을 자동으로 더해 준다.
 */
object UiInsets {

    /**
     * 뷰에 시스템 바(상태바·내비게이션 바·카메라 구멍) 만큼 여백을 더한다.
     * @param extraBottomDp 아래쪽에 더 띄울 여백(dp) — 손가락이 닿기 편하게
     */
    fun apply(v: View, extraBottomDp: Int = 0) {
        val l = v.paddingLeft; val t = v.paddingTop
        val r = v.paddingRight; val b = v.paddingBottom
        val extra = (extraBottomDp * v.resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(v) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(l + bars.left, t + bars.top, r + bars.right, b + bars.bottom + extra)
            insets
        }
        ViewCompat.requestApplyInsets(v)
    }

    /**
     * 태블릿처럼 화면이 넓으면 내용 폭을 maxDp 로 제한한다 (가운데 정렬은 부모가 한다).
     * @return 쓸 폭(px)
     */
    fun contentWidth(ctx: Context, maxDp: Int = 640): Int {
        val dm = ctx.resources.displayMetrics
        return min(dm.widthPixels, (maxDp * dm.density).toInt())
    }
}
