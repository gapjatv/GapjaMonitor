package tv.gapja.monitor

import android.content.Context
import android.graphics.Bitmap
import tv.gapja.monitor.core.GameMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 캡처함 — 게임 중 [캡처] 로 찍은 화면을 모아 둔다.
 *
 * 게임 화면을 벗어나 자르기 화면으로 가면 게임이 튕기거나 다시 접속되면서
 * 인식해 둔 보스가 초기화되는 일이 있어서, 캡처는 저장만 하고 게임에 그대로 머문다.
 * 나중에(판이 끝난 뒤) 메인 화면 [캡처함] 에서 골라 잘라 등록한다.
 *
 * 파일 이름: {모드}_{날짜시각}_{그때 마지막으로 인식된 보스}.png
 */
object Snaps {

    private const val MAX_KEEP = 60

    fun dir(ctx: Context): File = File(ctx.filesDir, "snaps").apply { mkdirs() }

    fun list(ctx: Context): List<File> =
        (dir(ctx).listFiles { f -> f.name.endsWith(".png") } ?: emptyArray())
            .sortedByDescending { it.lastModified() }

    fun count(ctx: Context): Int = list(ctx).size

    /** 화면 한 장을 저장한다 (오래된 것부터 지워 최대 60장) */
    fun save(ctx: Context, bmp: Bitmap, mode: GameMode, hintBoss: String?): File? = try {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(Date())
        val hint = hintBoss?.replace(Regex("[\\\\/:*?\"<>|_]"), "") ?: ""
        val f = File(dir(ctx), "${mode.key}_${stamp}_$hint.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        list(ctx).drop(MAX_KEEP).forEach { forget(ctx, it); it.delete() }
        f
    } catch (_: Exception) { null }

    // ── 등록 표시 — 이 캡처에서 어떤 사진을 잘라 등록했는지 ──

    private fun sp(ctx: Context) = ctx.getSharedPreferences("snaps", Context.MODE_PRIVATE)

    /** 이 캡처에서 잘라 등록한 사진 이름들 (예: 그런트, 침공_샤먼_2) */
    fun registered(ctx: Context, f: File): List<String> =
        (sp(ctx).getStringSet(f.name, null) ?: emptySet()).sorted()

    fun markRegistered(ctx: Context, f: File, savedName: String) {
        val cur = (sp(ctx).getStringSet(f.name, null) ?: emptySet()).toMutableSet()
        cur.add(savedName.removeSuffix(".png"))
        sp(ctx).edit().putStringSet(f.name, cur).apply()
    }

    fun forget(ctx: Context, f: File) = sp(ctx).edit().remove(f.name).apply()

    fun forgetAll(ctx: Context) = sp(ctx).edit().clear().apply()

    /** 파일 이름에서 모드를 읽는다 */
    fun modeOf(f: File): GameMode = GameMode.from(f.name.substringBefore('_'))

    /** 파일 이름에서 '그때 인식된 보스' 를 읽는다 (없으면 null) */
    fun hintOf(f: File): String? =
        f.name.removeSuffix(".png").split('_').getOrNull(3)?.takeIf { it.isNotBlank() }
}
