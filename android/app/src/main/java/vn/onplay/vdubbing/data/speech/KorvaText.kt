package vn.onplay.vdubbing.data.speech

import java.text.Normalizer

internal object KorvaText {
    fun normalize(input: String): String {
        var text = Normalizer.normalize(input, Normalizer.Form.NFKD)
            .replace(Regex("[\\x{1F300}-\\x{1FAFF}\\x{1F1E6}-\\x{1F1FF}☀-⛿✀-➿]"), "")
        mapOf("–" to "-", "‑" to "-", "—" to "-", "_" to " ", "“" to "\"",
            "”" to "\"", "‘" to "'", "’" to "'", "´" to "'", "`" to "'",
            "[" to " ", "]" to " ", "|" to " ", "/" to " ", "#" to " ",
            "→" to " ", "←" to " ").forEach { (from, to) -> text = text.replace(from, to) }
        text = text.replace(Regex("[♥☆♡©\\\\]"), "")
            .replace("@", " at ").replace("e.g.,", "for example, ")
            .replace("i.e.,", "that is, ").replace(Regex(" ([,.!?;:'])"), "$1")
        for (quote in listOf("\"", "'")) {
            while (text.contains(quote + quote)) text = text.replace(quote + quote, quote)
        }
        text = text.replace(Regex("\\s+"), " ").trim()
        require(text.isNotEmpty())
        if (text.last() !in ".!?;:,'\")]}…。」』】〉》›»") text += "."
        return "<vi>$text</vi>"
    }

    fun chunks(text: String, limit: Int = 180): List<String> {
        require(limit >= 2)
        val result = mutableListOf<String>()
        var rest = text.trim()
        while (rest.isNotEmpty()) {
            if (rest.length <= limit) {
                result += rest
                break
            }
            val window = rest.take(limit)
            val sentence = window.indexOfLast { it in ".!?;\n" }
            var end = if (sentence >= limit / 3) sentence + 1 else window.indexOfLast { it.isWhitespace() }
            if (end <= 0) end = limit
            if (Character.isHighSurrogate(rest[end - 1])) end--
            result += rest.take(end).trim()
            rest = rest.drop(end).trimStart()
        }
        return result.filter { it.isNotEmpty() }
    }
}
