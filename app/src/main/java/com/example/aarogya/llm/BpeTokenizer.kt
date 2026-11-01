package com.example.aarogya.llm

import org.json.JSONObject
import java.io.File

/**
 * Byte-level BPE tokenizer compatible with SmolLM2's `tokenizer.json`
 * (GPT-2 style byte mapping, individual-digit pre-tokenization, added special tokens).
 * Pure JVM so it can be unit-tested off-device.
 */
class BpeTokenizer private constructor(
    private val vocab: Map<String, Int>,
    private val mergeRanks: Map<String, Int>,
    private val specialTokens: Map<String, Int>,
) {
    private val idToToken: Array<String?> = arrayOfNulls<String>(
        maxOf(vocab.values.maxOrNull() ?: 0, specialTokens.values.maxOrNull() ?: 0) + 1
    ).also { arr ->
        vocab.forEach { (t, id) -> arr[id] = t }
        specialTokens.forEach { (t, id) -> arr[id] = t }
    }
    private val specialIds = specialTokens.values.toSet()
    private val specialPattern = Regex(specialTokens.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) })
    private val cache = HashMap<String, IntArray>()

    fun tokenId(token: String): Int? = specialTokens[token] ?: vocab[token]

    fun encode(text: String): IntArray {
        val out = ArrayList<Int>(text.length / 3)
        var last = 0
        for (m in specialPattern.findAll(text)) {
            encodeOrdinary(text.substring(last, m.range.first), out)
            out += specialTokens.getValue(m.value)
            last = m.range.last + 1
        }
        encodeOrdinary(text.substring(last), out)
        return out.toIntArray()
    }

    fun decode(ids: IntArray): String {
        val bytes = java.io.ByteArrayOutputStream()
        for (id in ids) {
            if (id in specialIds) continue
            val tok = idToToken.getOrNull(id) ?: continue
            for (ch in tok) bytes.write(UNICODE_TO_BYTE[ch] ?: continue)
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    private fun encodeOrdinary(text: String, out: MutableList<Int>) {
        if (text.isEmpty()) return
        // Digits pre-tokenizer (individual_digits) runs first, then the GPT-2 ByteLevel regex.
        for (piece in splitDigits(text)) {
            for (m in GPT2_SPLIT.findAll(piece)) {
                val word = m.value
                val ids = cache.getOrPut(word) { bpe(byteEncode(word)) }
                for (id in ids) out += id
            }
        }
    }

    private fun bpe(word: String): IntArray {
        var parts = word.map { it.toString() }.toMutableList()
        while (parts.size > 1) {
            var bestIdx = -1
            var bestRank = Int.MAX_VALUE
            for (i in 0 until parts.size - 1) {
                val r = mergeRanks[parts[i] + " " + parts[i + 1]] ?: continue
                if (r < bestRank) { bestRank = r; bestIdx = i }
            }
            if (bestIdx < 0) break
            val a = parts[bestIdx]
            val b = parts[bestIdx + 1]
            val merged = ArrayList<String>(parts.size)
            var i = 0
            while (i < parts.size) {
                if (i < parts.size - 1 && parts[i] == a && parts[i + 1] == b) {
                    merged += a + b; i += 2
                } else {
                    merged += parts[i]; i++
                }
            }
            parts = merged
        }
        return IntArray(parts.size) { vocab[parts[it]] ?: 0 }
    }

    companion object {
        private val GPT2_SPLIT = Regex("""'s|'t|'re|'ve|'m|'ll|'d| ?\p{L}+| ?\p{N}+| ?[^\s\p{L}\p{N}]+|\s+(?!\S)|\s+""")

        private val BYTE_TO_UNICODE: CharArray = CharArray(256).also { table ->
            val printable = (33..126) + (161..172) + (174..255)
            var n = 0
            for (b in 0 until 256) {
                table[b] = if (b in printable) b.toChar() else (256 + n++).toChar()
            }
        }
        private val UNICODE_TO_BYTE: Map<Char, Int> = BYTE_TO_UNICODE.withIndex().associate { (b, c) -> c to b }

        private fun byteEncode(s: String): String {
            val sb = StringBuilder()
            for (b in s.toByteArray(Charsets.UTF_8)) sb.append(BYTE_TO_UNICODE[b.toInt() and 0xFF])
            return sb.toString()
        }

        private fun splitDigits(s: String): List<String> {
            val out = ArrayList<String>()
            val sb = StringBuilder()
            for (c in s) {
                if (c in '0'..'9') {
                    if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
                    out += c.toString()
                } else sb.append(c)
            }
            if (sb.isNotEmpty()) out += sb.toString()
            return out
        }

        fun load(file: File): BpeTokenizer {
            val root = JSONObject(file.readText())
            val model = root.getJSONObject("model")
            val vocabJson = model.getJSONObject("vocab")
            val vocab = HashMap<String, Int>(vocabJson.length() * 2)
            for (k in vocabJson.keys()) vocab[k] = vocabJson.getInt(k)
            val mergesJson = model.getJSONArray("merges")
            val ranks = HashMap<String, Int>(mergesJson.length() * 2)
            for (i in 0 until mergesJson.length()) {
                // Newer tokenizer.json files store merges as ["a","b"] pairs, older ones as "a b".
                val m = mergesJson.get(i)
                val key = if (m is org.json.JSONArray) m.getString(0) + " " + m.getString(1) else m as String
                ranks[key] = i
            }
            val special = HashMap<String, Int>()
            val added = root.optJSONArray("added_tokens")
            if (added != null) for (i in 0 until added.length()) {
                val t = added.getJSONObject(i)
                special[t.getString("content")] = t.getInt("id")
            }
            return BpeTokenizer(vocab, ranks, special)
        }
    }
}
