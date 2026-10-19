// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import java.io.BufferedReader
import java.util.Locale
import kotlin.math.max

/**
 * Read-only n-gram model used by the Labs-only prediction shadow evaluation.
 *
 * The parser accepts exactly the `v1` format documented in `MODEL_FORMAT.md`: an optional UTF-8
 * BOM, then `v1`, `#U`, unigram records, `#B`, bigram records, `#T`, trigram records and a final
 * `#END<TAB>TOTAL_TOKENS<TAB>D` line. It never logs, and the caller owns the file.
 *
 * The model is immutable after construction. The prefix index maps one- and two-character
 * prefixes to sorted vocabulary lists, so candidate retrieval is a single map lookup. Scoring
 * happens off the touch path in [LabsShadowEval].
 */
internal class ShadowModel(
    private val unigramProbabilities: Map<String, Double>,
    private val bigramCounts: Map<String, Map<String, Int>>,
    private val bigramTotals: Map<String, Long>,
    private val bigramLambdas: Map<String, Double>,
    private val trigramCounts: Map<String, Map<String, Map<String, Int>>>,
    private val trigramTotals: Map<String, Map<String, Long>>,
    private val trigramLambdas: Map<String, Map<String, Double>>,
    private val prefixIndex: Map<String, List<String>>,
    val totalTokens: Long,
    val discount: Double,
) {
    /**
     * Returns the highest-scoring vocabulary word that starts with [prefix], using the stored
     * context and the format's absolute-discount backoff. [context] contains the previous
     * committed words, oldest first, and must be non-empty. Ties go to the lexicographically
     * first candidate, matching the model writer's ordering.
     */
    fun predict(prefix: String, context: List<String>): String? {
        if (prefix.isEmpty() || context.isEmpty()) return null
        val candidates = prefixIndex[prefix] ?: return null
        var best: String? = null
        var bestScore = -1.0
        if (context.size >= 2) {
            val u = context[context.size - 2]
            val v = context[context.size - 1]
            for (word in candidates) {
                val score = trigramProbability(u, v, word)
                if (score > bestScore) {
                    bestScore = score
                    best = word
                }
            }
        } else {
            val v = context[0]
            for (word in candidates) {
                val score = bigramProbability(v, word)
                if (score > bestScore) {
                    bestScore = score
                    best = word
                }
            }
        }
        return best
    }

    private fun unigramProbability(word: String): Double =
        unigramProbabilities[word] ?: 0.0

    private fun bigramProbability(v: String, word: String): Double {
        val total = bigramTotals[v] ?: return unigramProbability(word)
        val counts = bigramCounts[v] ?: return unigramProbability(word)
        val count = counts[word] ?: 0
        val discounted = if (count > 0) max(count - discount, 0.0) / total else 0.0
        return discounted + (bigramLambdas[v] ?: 1.0) * unigramProbability(word)
    }

    private fun trigramProbability(u: String, v: String, word: String): Double {
        val counts = trigramCounts[u]?.get(v) ?: return bigramProbability(v, word)
        val total = trigramTotals[u]?.get(v) ?: return bigramProbability(v, word)
        val count = counts[word] ?: 0
        val discounted = if (count > 0) max(count - discount, 0.0) / total else 0.0
        return discounted + (trigramLambdas[u]?.get(v) ?: 1.0) * bigramProbability(v, word)
    }
}

internal data class ParsedShadowModel(
    val model: ShadowModel,
    val lineCount: Int,
)

/**
 * Strict, allocation-conscious parser for the private model file. Returns null when the input
 * does not match the documented format. It does not read from any fixed path and does not log.
 */
internal object ShadowModelParser {
    private const val VERSION = "v1"
    private const val BOUNDARY = "<s>"
    private const val SECTION_UNIGRAM = "#U"
    private const val SECTION_BIGRAM = "#B"
    private const val SECTION_TRIGRAM = "#T"
    private const val END = "#END"

    fun parse(reader: BufferedReader): ParsedShadowModel? {
        var lineCount = 0
        fun nextLine(): String? {
            val line = reader.readLine() ?: return null
            lineCount++
            return line
        }

        var first = nextLine() ?: return null
        if (first.startsWith("\uFEFF")) first = first.substring(1)
        if (first != VERSION) return null

        val unigrams = HashMap<String, Int>()
        val bigrams = HashMap<String, HashMap<String, Int>>()
        val trigrams = HashMap<String, HashMap<String, HashMap<String, Int>>>()

        var section = 0
        var totalTokens = 0L
        var discount = 0.0
        var ended = false

        while (!ended) {
            val line = nextLine() ?: return null
            when {
                line == SECTION_UNIGRAM -> {
                    if (section != 0) return null
                    section = 1
                }
                line == SECTION_BIGRAM -> {
                    if (section != 1) return null
                    section = 2
                }
                line == SECTION_TRIGRAM -> {
                    if (section != 2) return null
                    section = 3
                }
                line.startsWith(END) -> {
                    if (section != 3) return null
                    val fields = line.split('\t')
                    if (fields.size != 3 || fields[0] != END) return null
                    totalTokens = fields[1].toLongOrNull() ?: return null
                    discount = fields[2].toDoubleOrNull() ?: return null
                    if (totalTokens <= 0L || !discount.isFinite() || discount < 0.0) return null
                    ended = true
                }
                else -> {
                    val ok = when (section) {
                        1 -> parseUnigram(line, unigrams)
                        2 -> parseBigram(line, bigrams)
                        3 -> parseTrigram(line, trigrams)
                        else -> false
                    }
                    if (!ok) return null
                }
            }
        }
        if (nextLine() != null) return null
        if (unigrams.isEmpty()) return null

        val vocabulary = HashSet<String>()
        unigrams.keys.forEach { vocabulary.add(it) }
        for ((firstWord, continuations) in bigrams) {
            if (firstWord != BOUNDARY) vocabulary.add(firstWord)
            vocabulary.addAll(continuations.keys)
        }
        for ((firstWord, bySecond) in trigrams) {
            if (firstWord != BOUNDARY) vocabulary.add(firstWord)
            for ((secondWord, continuations) in bySecond) {
                if (secondWord != BOUNDARY) vocabulary.add(secondWord)
                vocabulary.addAll(continuations.keys)
            }
        }
        vocabulary.remove(BOUNDARY)

        val prefixIndex = buildPrefixIndex(vocabulary)
        val unigramProbabilities = HashMap<String, Double>(unigrams.size)
        for ((word, count) in unigrams) {
            unigramProbabilities[word] = count.toDouble() / totalTokens
        }

        val bigramTotals = HashMap<String, Long>(bigrams.size)
        val bigramLambdas = HashMap<String, Double>(bigrams.size)
        for ((context, counts) in bigrams) {
            val (total, lambda) = contextStats(counts, discount)
            bigramTotals[context] = total
            bigramLambdas[context] = lambda
        }

        val trigramTotals = HashMap<String, HashMap<String, Long>>(trigrams.size)
        val trigramLambdas = HashMap<String, HashMap<String, Double>>(trigrams.size)
        for ((firstWord, bySecond) in trigrams) {
            val totals = HashMap<String, Long>(bySecond.size)
            val lambdas = HashMap<String, Double>(bySecond.size)
            for ((secondWord, counts) in bySecond) {
                val (total, lambda) = contextStats(counts, discount)
                totals[secondWord] = total
                lambdas[secondWord] = lambda
            }
            trigramTotals[firstWord] = totals
            trigramLambdas[firstWord] = lambdas
        }

        val model = ShadowModel(
            unigramProbabilities = unigramProbabilities,
            bigramCounts = bigrams,
            bigramTotals = bigramTotals,
            bigramLambdas = bigramLambdas,
            trigramCounts = trigrams,
            trigramTotals = trigramTotals,
            trigramLambdas = trigramLambdas,
            prefixIndex = prefixIndex,
            totalTokens = totalTokens,
            discount = discount,
        )
        return ParsedShadowModel(model, lineCount)
    }

    private fun parseUnigram(line: String, target: MutableMap<String, Int>): Boolean {
        val fields = line.split('\t')
        if (fields.size != 2) return false
        val word = fields[0]
        val count = fields[1].toIntOrNull() ?: return false
        if (!isWord(word) || count <= 0 || target.containsKey(word)) return false
        target[word] = count
        return true
    }

    private fun parseBigram(
        line: String,
        target: MutableMap<String, HashMap<String, Int>>,
    ): Boolean {
        val fields = line.split('\t')
        if (fields.size != 3) return false
        val first = fields[0]
        val second = fields[1]
        val count = fields[2].toIntOrNull() ?: return false
        if (!isContextToken(first) || !isWord(second) || count <= 0) return false
        val continuations = target.getOrPut(first) { HashMap() }
        if (continuations.containsKey(second)) return false
        continuations[second] = count
        return true
    }

    private fun parseTrigram(
        line: String,
        target: MutableMap<String, HashMap<String, HashMap<String, Int>>>,
    ): Boolean {
        val fields = line.split('\t')
        if (fields.size != 4) return false
        val first = fields[0]
        val second = fields[1]
        val third = fields[2]
        val count = fields[3].toIntOrNull() ?: return false
        if (!isContextToken(first) || !isContextToken(second) || !isWord(third) || count <= 0) {
            return false
        }
        val bySecond = target.getOrPut(first) { HashMap() }
        val continuations = bySecond.getOrPut(second) { HashMap() }
        if (continuations.containsKey(third)) return false
        continuations[third] = count
        return true
    }

    private fun contextStats(counts: Map<String, Int>, discount: Double): Pair<Long, Double> {
        var total = 0L
        var discounted = 0.0
        for (count in counts.values) {
            total += count
            discounted += max(count - discount, 0.0)
        }
        val lambda = if (total == 0L) 1.0 else 1.0 - discounted / total
        return total to lambda
    }

    private fun buildPrefixIndex(vocabulary: Set<String>): Map<String, List<String>> {
        val mutable = HashMap<String, MutableList<String>>()
        for (word in vocabulary) {
            if (word.isEmpty()) continue
            mutable.getOrPut(word.substring(0, 1)) { ArrayList() }.add(word)
            if (word.length >= 2) {
                mutable.getOrPut(word.substring(0, 2)) { ArrayList() }.add(word)
            }
        }
        return mutable.mapValues { (_, words) -> words.sorted() }
    }

    private fun isContextToken(token: String): Boolean =
        token == BOUNDARY || isWord(token)

    private fun isWord(token: String): Boolean {
        if (token.isEmpty() || token == BOUNDARY) return false
        if (token != token.lowercase(Locale.ROOT)) return false
        for (character in token) {
            if (character == '\'' || character == '\u2019') continue
            if (!character.isLetter()) return false
        }
        return true
    }
}
