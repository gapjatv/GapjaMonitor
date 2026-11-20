package tv.gapja.monitor.core

/** 패턴표 존재 판정 결과 */
data class PatternVerdict(
    /** 우연으로 설명되지 않을 만큼 겹침이 많은가 */
    val isPattern: Boolean,
    /** 발견된 겹침 건수 */
    val observed: Int,
    /** 완전 무작위라면 기대되는 겹침 건수 */
    val expected: Double,
    /** 푸아송 꼬리확률 */
    val pValue: Double,
    /** 발견된 접두 일치 묶음들 */
    val groups: List<PrefixGroup>
)

/**
 * boss_sequences.json 읽기 — PC판이 쓴 파일을 그대로 읽는다.
 *
 * 외부 라이브러리를 쓰지 않는 작은 전용 파서다. 형식이 고정되어 있어
 * (version, games[{t, depth, main[], special[]}]) 이 정도로 충분하고,
 * PC↔폰 파일 호환을 보장하기 위해 직접 구현했다.
 *
 * 깨진 파일에서는 읽을 수 있는 만큼만 읽고 나머지는 버린다 (예외를 던지지 않는다).
 */
object SeqJson {

    fun parse(text: String): SeqStore {
        val store = SeqStore()
        try {
            val p = P(text)
            p.ws()
            if (!p.eat('{')) return store
            while (true) {
                p.ws()
                if (p.eat('}')) break
                val key = p.string() ?: break
                p.ws()
                if (!p.eat(':')) break
                p.ws()
                if (key == "games") {
                    parseGames(p, store)
                } else {
                    p.skipValue()
                }
                p.ws()
                if (!p.eat(',')) {
                    p.ws(); p.eat('}'); break
                }
            }
        } catch (_: Exception) {
            // 읽은 만큼만 쓴다
        }
        return store
    }

    private fun parseGames(p: P, store: SeqStore) {
        if (!p.eat('[')) { p.skipValue(); return }
        while (true) {
            p.ws()
            if (p.eat(']')) break
            val rec = parseGame(p) ?: break
            store.add(rec)
            p.ws()
            if (!p.eat(',')) { p.ws(); p.eat(']'); break }
        }
    }

    private fun parseGame(p: P): GameRecord? {
        p.ws()
        if (!p.eat('{')) return null
        var t = ""
        var depth = -1
        var main: List<String> = emptyList()
        var special: List<String> = emptyList()
        while (true) {
            p.ws()
            if (p.eat('}')) break
            val k = p.string() ?: return null
            p.ws()
            if (!p.eat(':')) return null
            p.ws()
            when (k) {
                "t" -> t = p.string() ?: ""
                "depth" -> depth = p.number()?.toInt() ?: -1
                "main" -> main = p.stringArray()
                "special" -> special = p.stringArray()
                else -> p.skipValue()
            }
            p.ws()
            if (!p.eat(',')) { p.ws(); p.eat('}'); break }
        }
        if (depth < 0) depth = main.size
        return GameRecord(t, depth, main, special)
    }

    /** 아주 작은 전진형 파서 */
    private class P(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun eat(c: Char): Boolean {
            if (i < s.length && s[i] == c) { i++; return true }
            return false
        }

        fun string(): String? {
            ws()
            if (i >= s.length || s[i] != '"') return null
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' && i < s.length -> {
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (i + 4 <= s.length) {
                                    sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                    i += 4
                                }
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        fun number(): Double? {
            ws()
            val st = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' ||
                        s[i] == 'E' || s[i] == '-' || s[i] == '+')) i++
            return s.substring(st, i).toDoubleOrNull()
        }

        fun stringArray(): List<String> {
            ws()
            if (!eat('[')) { skipValue(); return emptyList() }
            val out = ArrayList<String>()
            while (true) {
                ws()
                if (eat(']')) break
                val v = string()
                if (v == null) { skipValue() } else out.add(v)
                ws()
                if (!eat(',')) { ws(); eat(']'); break }
            }
            return out
        }

        /** 값 하나를 건너뛴다 (모르는 키를 만났을 때) */
        fun skipValue() {
            ws()
            if (i >= s.length) return
            when (s[i]) {
                '"' -> string()
                '{', '[' -> {
                    val open = s[i]
                    val close = if (open == '{') '}' else ']'
                    var depth = 0
                    var inStr = false
                    while (i < s.length) {
                        val c = s[i]
                        if (inStr) {
                            if (c == '\\') i++
                            else if (c == '"') inStr = false
                        } else {
                            when (c) {
                                '"' -> inStr = true
                                open -> depth++
                                close -> {
                                    depth--
                                    if (depth == 0) { i++; return }
                                }
                            }
                        }
                        i++
                    }
                }
                else -> {
                    while (i < s.length && s[i] != ',' && s[i] != '}' && s[i] != ']') i++
                }
            }
        }
    }
}
