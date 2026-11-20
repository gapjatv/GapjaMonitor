package tv.gapja.monitor

import android.content.Context
import android.net.Uri
import android.util.Log
import tv.gapja.monitor.core.SeqJson
import tv.gapja.monitor.core.SeqStore
import java.io.File

/**
 * 판별 시퀀스 기록 파일 담당.
 *
 * 파일 이름과 형식이 PC판과 완전히 같다(`boss_sequences.json`).
 * 그래서 폰에서 쌓은 기록을 PC로 옮기거나, 반대로 PC 기록을 가져와
 * 이어서 쓸 수 있다. (가져오기/내보내기 버튼으로 주고받는다)
 */
class SeqRepo(private val ctx: Context) {

    private val file: File get() = File(ctx.filesDir, FILE_NAME)

    fun load(): SeqStore {
        return try {
            if (!file.exists()) SeqStore()
            else SeqJson.parse(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            Log.w(TAG, "기록 읽기 실패", e); SeqStore()
        }
    }

    fun save(store: SeqStore) {
        try {
            // 쓰다가 앱이 죽어도 기존 기록이 남도록 임시 파일에 먼저 쓴다
            val tmp = File(ctx.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(store.toJson(), Charsets.UTF_8)
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        } catch (e: Exception) {
            Log.w(TAG, "기록 저장 실패", e)
        }
    }

    fun path(): String = file.absolutePath

    fun exists(): Boolean = file.exists()

    /** PC에서 가져온 boss_sequences.json 을 합친다 (같은 판은 건너뜀) */
    fun importFrom(uri: Uri, into: SeqStore): Int {
        return try {
            val text = ctx.contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: return 0
            val incoming = SeqJson.parse(text)
            val seen = into.games.map { it.t + "|" + it.main.joinToString(",") }.toHashSet()
            var n = 0
            for (g in incoming.games) {
                val key = g.t + "|" + g.main.joinToString(",")
                if (seen.add(key) && into.add(g)) n++
            }
            if (n > 0) save(into)
            n
        } catch (e: Exception) {
            Log.w(TAG, "가져오기 실패", e); 0
        }
    }

    /** 공유용 CSV를 만들어 경로를 돌려준다 (엑셀에서 바로 열리는 utf-8-sig) */
    fun exportCsv(store: SeqStore): File? {
        return try {
            val out = File(ctx.cacheDir, "boss_sequences.csv")
            val sb = StringBuilder()
            sb.append('﻿')   // BOM — 엑셀 한글 깨짐 방지
            sb.append("날짜,메인보스수")
            for (i in 1..8) sb.append(",${i * 10}R")
            sb.append(",특수30R,특수50R,특수80R\n")
            for (g in store.games) {
                sb.append(csv(g.t)).append(',').append(g.depth)
                for (i in 0 until 8) sb.append(',').append(csv(g.main.getOrElse(i) { "" }))
                for (i in 0 until 3) sb.append(',').append(csv(g.special.getOrElse(i) { "" }))
                sb.append('\n')
            }
            out.writeText(sb.toString(), Charsets.UTF_8)
            out
        } catch (e: Exception) {
            Log.w(TAG, "CSV 내보내기 실패", e); null
        }
    }

    /** 기록 파일을 공유할 수 있게 캐시로 복사 */
    fun exportJson(store: SeqStore): File? {
        return try {
            val out = File(ctx.cacheDir, FILE_NAME)
            out.writeText(store.toJson(), Charsets.UTF_8)
            out
        } catch (e: Exception) {
            Log.w(TAG, "내보내기 실패", e); null
        }
    }

    private fun csv(s: String): String =
        if (s.contains(',') || s.contains('"')) "\"" + s.replace("\"", "\"\"") + "\"" else s

    companion object {
        private const val TAG = "SeqRepo"
        const val FILE_NAME = "boss_sequences.json"
    }
}
