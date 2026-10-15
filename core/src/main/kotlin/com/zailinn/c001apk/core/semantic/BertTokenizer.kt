package com.zailinn.c001apk.core.semantic

/**
 * BERT WordPiece tokenizer for the Chinese embedding model: every CJK character
 * is its own token, other text splits on whitespace and punctuation and then
 * into the longest vocabulary pieces. Matches the HuggingFace BertTokenizer
 * with lower casing and no accent stripping.
 */
class BertTokenizer(vocab: List<String>) {
    private val ids: Map<String, Int> = vocab.withIndex().associate { (i, token) -> token to i }
    private val unk = id("[UNK]")
    val cls = id("[CLS]")
    val sep = id("[SEP]")
    val pad = ids["[PAD]"] ?: 0

    private fun id(token: String) = requireNotNull(ids[token]) { "vocabulary has no $token" }

    /** Token ids with [CLS] and [SEP], at most [maxLength] long. */
    fun encode(text: String, maxLength: Int = 128): IntArray {
        val out = ArrayList<Int>(maxLength)
        out.add(cls)
        for (word in words(text)) {
            if (out.size >= maxLength - 1) break
            wordPiece(word, out)
        }
        if (out.size > maxLength - 1) out.subList(maxLength - 1, out.size).clear()
        out.add(sep)
        return out.toIntArray()
    }

    private fun words(text: String): List<String> {
        val words = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) words.add(current.toString())
            current.setLength(0)
        }
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == 0 || cp == 0xFFFD || isControl(cp) -> Unit
                Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> flush()
                isCjk(cp) || isPunctuation(cp) -> {
                    flush()
                    words.add(String(Character.toChars(cp)))
                }
                else -> current.append(String(Character.toChars(cp)).lowercase())
            }
        }
        flush()
        return words
    }

    private fun wordPiece(word: String, out: MutableList<Int>) {
        if (word.length > MAX_WORD_CHARS) return run { out.add(unk) }
        val pieces = ArrayList<Int>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var found: Int? = null
            while (start < end) {
                val piece = (if (start > 0) "##" else "") + word.substring(start, end)
                found = ids[piece]
                if (found != null) break
                end--
            }
            if (found == null) return run { out.add(unk) }
            pieces.add(found)
            start = end
        }
        out.addAll(pieces)
    }

    private fun isControl(cp: Int): Boolean {
        if (cp == '\t'.code || cp == '\n'.code || cp == '\r'.code) return false
        val type = Character.getType(cp)
        return type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()
    }

    private fun isCjk(cp: Int) = cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x20000..0x2A6DF ||
        cp in 0x2A700..0x2B73F || cp in 0x2B740..0x2B81F || cp in 0x2B820..0x2CEAF ||
        cp in 0xF900..0xFAFF || cp in 0x2F800..0x2FA1F

    private fun isPunctuation(cp: Int): Boolean {
        if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
        return when (Character.getType(cp)) {
            Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt() -> true
            else -> false
        }
    }

    companion object {
        private const val MAX_WORD_CHARS = 100

        fun fromVocabText(text: String) = BertTokenizer(text.lineSequence().map { it.trimEnd('\r') }.toList().dropLastWhile { it.isEmpty() })
    }
}
