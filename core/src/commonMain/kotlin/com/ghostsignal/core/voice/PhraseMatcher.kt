package com.ghostsignal.core.voice

import kotlin.math.max
import kotlin.math.min

/**
 * Scores how well a speech-recognition transcript matches an expected phrase.
 *
 * Street noise and cheap microphones mangle short utterances ("decode" comes
 * back as "the code"), so a single strict comparison would reject correct
 * answers. Instead several weak signals are combined and the best one wins:
 * exact containment, edit-distance similarity over sliding windows, the same
 * with spaces removed (split/merged words), and a consonant-skeleton check
 * for words that merely sound alike.
 */
object PhraseMatcher {

  const val DEFAULT_THRESHOLD = 0.72

  data class Match<K>(val key: K, val phrase: String, val score: Double)

  private val WORD_NUMBERS = mapOf(
      "0" to "zero", "1" to "one", "2" to "two", "3" to "three", "4" to "four",
      "5" to "five", "6" to "six", "7" to "seven", "8" to "eight", "9" to "nine",
  )

  // Consonants grouped by how they sound; vowels and glides carry no weight.
  private val SOUND_GROUPS: Map<Char, Char> = buildMap {
    for (c in "bfpv") put(c, '1')
    for (c in "cgjkqsxz") put(c, '2')
    for (c in "dt") put(c, '3')
    put('l', '4')
    for (c in "mn") put(c, '5')
    put('r', '6')
  }

  /** Canonical form for comparison: lowercase words, digits spelled out. */
  fun normalize(text: String): String {
    val cleaned = text.lowercase()
        .replace('’', '\'')
        .map { ch -> if (ch.isLetterOrDigit() || ch == '\'') ch else ' ' }
        .joinToString("")
    return cleaned
        .split(' ')
        .filter { it.isNotBlank() }
        .map { token -> val bare = token.replace("'", ""); WORD_NUMBERS[bare] ?: bare }
        .joinToString(" ")
  }

  /** Similarity between [utterance] and [phrase], in [0, 1]. */
  fun score(utterance: String, phrase: String): Double {
    val heard = normalize(utterance)
    val wanted = normalize(phrase)
    if (heard.isEmpty() || wanted.isEmpty()) return 0.0
    if (heard == wanted) return 1.0

    val heardWords = heard.split(' ')
    val wantedWords = wanted.split(' ')
    if (containsRun(heardWords, wantedWords)) return 0.97

    var best = ratio(heard, wanted)
    val smallestWindow = max(1, wantedWords.size - 1)
    val largestWindow = min(heardWords.size, wantedWords.size + 1)
    for (size in smallestWindow..largestWindow) {
      for (start in 0..heardWords.size - size) {
        val windowWords = heardWords.subList(start, start + size)
        val window = windowWords.joinToString(" ")
        best = max(best, ratio(window, wanted))
        best = max(best, ratio(window.replace(" ", ""), wanted.replace(" ", "")))
        if (soundsAlike(window, wanted)) best = max(best, 0.86)
        if (best >= 0.97) return best
      }
    }
    return best
  }

  /** The highest-scoring (key, phrase) pair across all candidates, if any. */
  fun <K> best(utterance: String, candidates: Map<K, List<String>>): Match<K>? {
    var leader: Match<K>? = null
    for ((key, phrases) in candidates) {
      for (phrase in phrases) {
        val s = score(utterance, phrase)
        if (leader == null || s > leader.score) leader = Match(key, phrase, s)
      }
    }
    return leader
  }

  fun <K> bestAbove(
      utterance: String,
      candidates: Map<K, List<String>>,
      threshold: Double = DEFAULT_THRESHOLD,
  ): Match<K>? = best(utterance, candidates)?.takeIf { it.score >= threshold }

  fun matchesAny(
      utterance: String,
      phrases: List<String>,
      threshold: Double = DEFAULT_THRESHOLD,
  ): Boolean = phrases.any { score(utterance, it) >= threshold }

  private fun containsRun(haystack: List<String>, needle: List<String>): Boolean {
    if (needle.isEmpty() || needle.size > haystack.size) return false
    return (0..haystack.size - needle.size).any { offset ->
      haystack.subList(offset, offset + needle.size) == needle
    }
  }

  /** One minus Levenshtein distance normalised by the longer string. */
  fun ratio(a: String, b: String): Double {
    if (a == b) return 1.0
    val longest = max(a.length, b.length)
    if (longest == 0) return 1.0
    return 1.0 - editDistance(a, b).toDouble() / longest
  }

  private fun editDistance(a: String, b: String): Int {
    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (i in 1..a.length) {
      current[0] = i
      for (j in 1..b.length) {
        val substitute = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
        current[j] = min(min(current[j - 1] + 1, previous[j] + 1), substitute)
      }
      val swap = previous
      previous = current
      current = swap
    }
    return previous[b.length]
  }

  private fun soundsAlike(a: String, b: String): Boolean {
    val keyA = phoneticKey(a)
    return keyA.length >= 3 && keyA == phoneticKey(b)
  }

  /**
   * Consonant skeleton of a phrase: each consonant becomes its sound-group
   * digit, vowels vanish, repeats collapse. "the code" and "decode" share
   * the skeleton 323. Skeletons shorter than 3 are too collision-prone to
   * trust and are never treated as a match by [soundsAlike].
   */
  fun phoneticKey(text: String): String {
    val out = StringBuilder()
    var lastEmitted = ' '
    for (ch in text) {
      val group = SOUND_GROUPS[ch]
      when {
        group != null -> {
          if (group != lastEmitted) out.append(group)
          lastEmitted = group
        }
        ch in "aeiouyhw " -> Unit // vowel/glide/space: no skeleton entry
        else -> Unit // not part of the spoken skeleton
      }
    }
    return out.toString()
  }
}

/** Voice commands recognised in every listening window. */
enum class GlobalCommand {
  REPEAT, HINT, STATUS, PAUSE, RESUME;

  companion object {
    val phrases: Map<GlobalCommand, List<String>> = mapOf(
        REPEAT to listOf("repeat", "say again", "say that again", "come again", "repeat that"),
        HINT to listOf("hint", "help", "clue", "give me a hint", "i'm lost"),
        STATUS to listOf("status", "where is it", "how far", "which way", "where do i go"),
        PAUSE to listOf("pause", "hold the signal", "pause signal"),
        RESUME to listOf("resume", "unpause", "resume signal"),
    )

    fun match(utterance: String): GlobalCommand? =
        PhraseMatcher.bestAbove(utterance, phrases, threshold = 0.8)?.key
  }
}
