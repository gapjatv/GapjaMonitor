package tv.gapja.monitor

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import tv.gapja.monitor.core.GameMode

/**
 * 게임 위 [보스설정 → 시작] 을 누르면 잠깐 열리는 투명한 화면.
 *
 * 화면 공유 동의는 반드시 화면(Activity)에서 받아야 하므로, 아무것도 안 그리는
 * 투명한 화면을 띄워 동의 창만 보여주고 바로 닫는다. 게임 화면이 그대로 보인다.
 */
class ConsentActivity : AppCompatActivity() {

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            AssetInstaller.installIfNeeded(this)
            Bus.running = true
            CaptureService.start(
                this, res.resultCode, res.data!!, GameMode.from(Prefs.mode(this)),
                Prefs.downscale(this), Prefs.intervalMs(this)
            )
            Toast.makeText(this, "감시를 시작했습니다", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "화면 공유를 허용해야 보스를 인식할 수 있습니다", Toast.LENGTH_SHORT).show()
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            try {
                launcher.launch(CaptureService.captureIntent(this))
            } catch (e: Exception) {
                Toast.makeText(this, "화면 공유 창을 열지 못했습니다", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}
