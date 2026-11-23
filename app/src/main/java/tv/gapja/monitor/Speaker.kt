package tv.gapja.monitor

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
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
 * 재생 중에 새 안내가 오면 끊지 않고 순서대로 이어서 재생한다. (PC판 음성 큐와 같다)
 * 위험 경보는 타이밍이 중요해서 줄 맨 앞에 끼워 넣는다.
 *
 * 🔊 소리 크기 (v2.7): MP3·내장 TTS 모두 '미디어' 소리로 낸다.
 *    예전에는 음성비서용 소리(USAGE_ASSISTANT)로 내서, 기기에 따라 폰 볼륨을 줄여도 크게 나왔다.
 *    이제 폰 옆 볼륨 버튼(미디어 볼륨)을 따라 커지고 작아지며, 0 이면 소리가 나지 않는다.
 */
class Speaker(private val ctx: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var player: MediaPlayer? = null

    /** 재생을 기다리는 MP3 (앞쪽이 먼저) */
    private val queue = ArrayDeque<File>()
    private val lock = Any()

    private val voiceDir: File by lazy {
        File(ctx.filesDir, "voices").apply { mkdirs() }
    }

    init {
        try {
            tts = TextToSpeech(ctx) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    // 내장 TTS 도 미디어 볼륨을 따르게 한다
                    try { tts?.setAudioAttributes(MEDIA_ATTRS) } catch (_: Exception) {}
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
        // ① [보스별 음성 바꾸기] 로 지정한 파일 ② 보스이름.mp3 ③ 줄임말.mp3(기본 음성) ④ 내장 TTS
        val cands = listOf(userVoice(ctx, name), File(voiceDir, "$name.mp3"), File(voiceDir, "$text.mp3"))
        for (mp3 in cands) {
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
            tts?.speak(text, TextToSpeech.QUEUE_ADD, mediaParams(), "gapja")
        } catch (e: Exception) {
            Log.w(TAG, "읽기 실패", e)
        }
    }

    /**
     * 위험 경보를 울린다 — PC schedule_warning 과 같은 순서.
     *   ① alarms/{보스}.mp3 (PC sounds 폴더의 경보음)
     *   ② 없으면 경보 멘트(예: "미티어대비") — voices/{멘트}.mp3 또는 내장 TTS
     * @return 무엇을 재생했는지 (로그용), 아무것도 없으면 null
     */
    fun playWarning(boss: String): String? {
        if (!Prefs.voiceEnabled(ctx)) return null
        // [보스별 음성 바꾸기] 로 지정한 경보 음성이 있으면 그것을 먼저
        val mine = userAlarm(ctx, boss)
        if (mine.exists() && mine.length() > MIN_AUDIO_BYTES) {
            playFile(mine, front = true)
            return "내 경보 음성"
        }
        val alarm = AssetInstaller.alarmFile(ctx, boss)
        if (alarm.exists() && alarm.length() > MIN_AUDIO_BYTES) {
            playFile(alarm, front = true)
            return "경보음"
        }
        val text = PcDefaults.WARN_TEXT[boss]?.takeIf { it.isNotBlank() } ?: return null
        val mp3 = File(voiceDir, "${sanitize(text)}.mp3")
        if (mp3.exists() && mp3.length() > MIN_AUDIO_BYTES) {
            playFile(mp3, front = true)
        } else if (ready) {
            try {
                tts?.setSpeechRate(Prefs.speechRate(ctx))
                tts?.speak(text, TextToSpeech.QUEUE_ADD, mediaParams(), "gapja_warn")
            } catch (_: Exception) {}
        }
        return "멘트 \"$text\""
    }

    /** 기다리던 안내를 모두 버린다 (판이 끝났을 때 이전 판 안내가 뒤늦게 나오지 않게) */
    fun clearQueue() {
        synchronized(lock) { queue.clear() }
        try { tts?.stop() } catch (_: Exception) {}
    }

    private fun playFile(f: File, front: Boolean = false) {
        synchronized(lock) {
            if (player != null) {
                // 재생 중이면 끊지 않고 줄을 세운다 (너무 밀리면 오래된 것부터 버린다)
                if (front) queue.addFirst(f) else queue.addLast(f)
                while (queue.size > 6) queue.removeLast()
                return
            }
            startNow(f)
        }
    }

    private fun startNow(f: File) {
        try {
            player = MediaPlayer().apply {
                // 미디어 소리로 재생 → 폰 볼륨(미디어 볼륨)에 따라 크기가 바뀐다
                setAudioAttributes(MEDIA_ATTRS)
                setDataSource(f.absolutePath)
                setOnCompletionListener { playNext() }
                setOnErrorListener { _, _, _ -> playNext(); true }
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "MP3 재생 실패: ${f.name}", e)
            try { player?.release() } catch (_: Exception) {}
            player = null
        }
    }

    /** 하나가 끝나면 다음 것을 재생한다 */
    private fun playNext() {
        synchronized(lock) {
            try { player?.release() } catch (_: Exception) {}
            player = null
            while (queue.isNotEmpty()) {
                startNow(queue.removeFirst())
                if (player != null) break
            }
        }
    }

    private fun stopPlayer() {
        synchronized(lock) {
            queue.clear()
            try { player?.release() } catch (_: Exception) {}
            player = null
        }
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

    /** 미리 듣기 — 줄을 서지 않고 바로 재생 */
    fun preview(f: File?, fallbackText: String?) {
        stopPlayer()
        try { tts?.stop() } catch (_: Exception) {}
        if (f != null && f.exists() && f.length() > MIN_AUDIO_BYTES) playFile(f)
        else if (!fallbackText.isNullOrBlank()) say(fallbackText)
    }

    /** 이 보스가 나왔을 때 실제로 재생될 인식 음성 파일 (없으면 null = 내장 TTS) */
    fun bossVoiceFile(name: String): File? {
        val text = PcDefaults.VOICE_TEXT[name] ?: name
        return listOf(userVoice(ctx, name), File(voiceDir, "$name.mp3"), File(voiceDir, "$text.mp3"))
            .firstOrNull { it.exists() && it.length() > MIN_AUDIO_BYTES }
    }

    /** 내장 TTS 에 '미디어 소리로 읽어라' 고 알려 주는 값 (setAudioAttributes 를 무시하는 엔진 대비) */
    private fun mediaParams(): Bundle = Bundle().apply {
        putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
    }

    companion object {
        private const val TAG = "Speaker"

        /** 미디어 볼륨을 따르는 소리 설정 (MP3·TTS 공통) */
        private val MEDIA_ATTRS: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        /** [보스별 음성 바꾸기] 로 지정한 보스 인식 음성 (원본은 건드리지 않는다) */
        fun userVoice(ctx: Context, boss: String): File =
            File(File(ctx.filesDir, "voices_user").apply { mkdirs() }, "$boss.mp3")

        /** [보스별 음성 바꾸기] 로 지정한 경보 음성 */
        fun userAlarm(ctx: Context, boss: String): File =
            File(File(ctx.filesDir, "alarms_user").apply { mkdirs() }, "$boss.mp3")

        /** 이보다 작은 mp3는 깨진 파일로 본다 (PC판에서 0바이트 파일로 생긴 문제 방지) */
        private const val MIN_AUDIO_BYTES = 800L
    }
}
