package tv.gapja.monitor

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * 음성 안내.
 *
 * PC판의 4단 폴백을 폰에 맞게 2단으로 줄였다.
 *   ① `voices/{보스}.mp3` 가 있으면 그 파일을 재생한다
 *      (PC에서 쓰던 MP3를 그대로 복사해 두면 같은 목소리가 난다)
 *   ② 없으면 안드로이드 내장 TTS로 읽는다
 *
 * 폰에는 애저·엣지 TTS를 쓸 이유가 없다. 내장 TTS가 네트워크 없이 즉시 나오고
 * 403 같은 문제도 없다. PC판에서 403으로 고생했던 부분이 폰에서는 사라진다.
 *
 * 한 번에 하나만 재생한다 (겹쳐 나오면 알아들을 수 없다).
 */
class Speaker(private val ctx: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var player: MediaPlayer? = null

    private val voiceDir: File by lazy {
        File(ctx.filesDir, "voices").apply { mkdirs() }
    }

    init {
        try {
            tts = TextToSpeech(ctx) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    tts?.language = Locale.KOREA
                    tts?.setSpeechRate(Prefs.speechRate(ctx))
                } else {
                    Log.w(TAG, "TTS 준비 실패 (status=$status)")
                    Bus.log("⚠️ 내장 TTS를 쓸 수 없습니다. 설정 → 언어에서 한국어 음성을 받아 주세요.")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "TTS 생성 실패", e)
        }
    }

    fun voiceDirPath(): String = voiceDir.absolutePath

    /** 등록된 MP3 개수 */
    fun voiceCount(): Int = voiceDir.listFiles { f -> f.name.endsWith(".mp3") }?.size ?: 0

    /**
     * 보스 이름을 알려준다 (MP3 우선).
     *
     * PC판 custom_texts 처럼 보스마다 읽을 말이 따로 있다. (고기슬라임 → "고기")
     * voices/{읽을 말}.mp3 → voices/{보스이름}.mp3 → 내장 TTS 순서로 시도한다.
     */
    fun sayBoss(name: String) {
        if (!Prefs.voiceEnabled(ctx)) return
        if (!Prefs.speakBossName(ctx)) return
        val text = PcDefaults.VOICE_TEXT[name] ?: name
        for (cand in listOf(text, name)) {
            val mp3 = File(voiceDir, "$cand.mp3")
            if (mp3.exists() && mp3.length() > MIN_AUDIO_BYTES) { playFile(mp3); return }
        }
        say(text)
    }

    /** 문장을 읽어준다 */
    fun say(text: String) {
        if (!Prefs.voiceEnabled(ctx)) return
        if (text.isBlank()) return
        // 같은 문장의 MP3가 있으면 그것을 먼저 쓴다 (PC판과 같은 목소리 유지)
        val named = File(voiceDir, "${sanitize(text)}.mp3")
        if (named.exists() && named.length() > MIN_AUDIO_BYTES) { playFile(named); return }
        if (!ready) return
        try {
            tts?.setSpeechRate(Prefs.speechRate(ctx))
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "gapja")
        } catch (e: Exception) {
            Log.w(TAG, "읽기 실패", e)
        }
    }

    private fun playFile(f: File) {
        try {
            stopPlayer()
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(f.absolutePath)
                setOnCompletionListener { stopPlayer() }
                setOnErrorListener { _, _, _ -> stopPlayer(); true }
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "MP3 재생 실패: ${f.name}", e)
            stopPlayer()
        }
    }

    private fun stopPlayer() {
        try { player?.release() } catch (_: Exception) {}
        player = null
    }

    /** 파일 이름에 쓸 수 없는 글자를 걸러낸다 */
    private fun sanitize(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(80)

    fun release() {
        stopPlayer()
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tts = null
        ready = false
    }

    companion object {
        private const val TAG = "Speaker"

        /** 이보다 작은 mp3는 깨진 파일로 본다 (PC판에서 0바이트 파일로 생긴 문제 방지) */
        private const val MIN_AUDIO_BYTES = 800L
    }
}
