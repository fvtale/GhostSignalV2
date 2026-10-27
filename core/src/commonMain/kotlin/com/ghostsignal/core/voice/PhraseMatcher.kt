package com.ghostsignal.core.voice

import kotlin.math.max
import kotlin.math.min

/**
 * Forgiving phrase matching for speech-recognizer output.
 *
 * Recognizers mishear short commands in a noisy street ("decode" -> "the code"), so a phrase scores
 * well if the utterance contains it, if any window of the utterance is a close edit-distance match,
 * or if the two sound alike.
 */
object PhraseMatcher {
  const val DEFAULT_THRESHOLD = 0.72

  data class Match<K>(val key: K, val phrase: String, val score: Double)

  fun normalize(text: String): String {
    val lowered = text.lowercase().replace('’', '\'')
    val words =
        lowered
            .replace(Regex("[^a-z0-9' ]"), " ")
            .replace("'", "")
            .split(' ')
            .filter { it.isNotBlank() }
            .map { DIGITS[it] ?: it }
    return words.joinToString(" ")
  }

  /** Similarity of [utterance] to [phrase] in [0, 1]. */
  fun score(utterance: String, phrase: String): Double {
    val u = normalize(utterance)
    val p = normalize(phrase)
    if (u.isEmpty() || p.isEmpty()) return 0.0
    if (u == p) return 1.0
    val uTokens = u.split(' ')
    val pTokens = p.split(' ')
    if (containsSequence(uTokens, pTokens)) return 0.97

    var best = ratio(u, p)
    // Compare the phrase with every window of the utterance of similar length.
    for (size in max(1, pTokens.size - 1)..min(uTokens.size, pTokens.size + 1)) {
      for (start in 0..uTokens.size - size) {
        val window = uTokens.subList(start, start + size).joinToString(" ")
        best = max(best, ratio(window, p))
        // Glued words: "the code" vs "decode".
        best = max(best, ratio(window.replace(" ", ""), p.replace(" ", "")))
        if (soundsAlike(window, p)) best = max(best, 0.86)
      }
    }
    return best
  }

  fun <K> best(utterance: String, candidates: Map<K, List<String>>): Match<K>? {
    var top: Match<K>? = null
    for ((key, phrases) in candidates) {
      for (phrase in phrases) {
        val s = score(utterance, phrase)
        if (top == null || s > top.score) top = Match(key, phrase, s)
      }
    }
    return top
  }

  fun <K> bestAbove(
      utterance: String,
      candidates: Map<K, List<String>>,
      threshold: Double = DEFAULT_THRESHOLD,
  ): Match<K>? = best(utterance, candidates)?.takeIf { it.score >= threshold }

  fun matchesAny(utterance: String, phrases: List<String>, threshold: Double = DEFAULT_THRESHOLD) =
      phrases.any { score(utterance, it) >= threshold }

  private fun containsSequence(haystack: List<String>, needle: List<String>): Boolean {
    if (needle.size > haystack.size) return false
    for (i in 0..haystack.size - needle.size) {
      if (haystack.subList(i, i + needle.size) == needle) return true
    }
    return false
  }

  /** 1 - normalized Levenshtein distance. */
  fun ratio(a: String, b: String): Double {
    if (a == b) return 1.0
    val longest = max(a.length, b.length)
    if (longest == 0) return 1.0
    return 1.0 - levenshtein(a, b).toDouble() / longest
  }

  private fun levenshtein(a: String, b: String): Int {
    var prev = IntArray(b.length + 1) { it }
    var cur = IntArray(b.length + 1)
    for (i in 1..a.length) {
      cur[0] = i
      for (j in 1..b.length) {
        val cost = if (a[i - 1] == b[j - 1]) 0 else 1
        cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
      }
      val t = prev
      prev = cur
      cur = t
    }
    return prev[b.length]
  }

  /**
   * Two strings sound alike if their consonant skeletons match: letters mapped to sound classes,
   * vowels dropped, repeats collapsed. "the code" and "decode" both reduce to 323. Short skeletons
   * collide too easily, so they never count.
   */
  private fun soundsAlike(a: String, b: String): Boolean {
    val ka = phoneticKey(a)
    return ka.length >= 3 && ka == phoneticKey(b)
  }

  fun phoneticKey(text: String): String {
    val out = StringBuilder()
    var last = ' '
    for (ch in text) {
      val code = SOUND_CLASSES[ch] ?: if (ch in "aeiouyhw ") ' ' else continue
      if (code != ' ' && code != last) out.append(code)
      if (code != ' ') last = code
    }
    return out.toString()
  }

  private val SOUND_CLASSES: Map<Char, Char> = buildMap {
    "bfpv".forEach { put(it, '1') }
    "cgjkqsxz".forEach { put(it, '2') }
    "dt".forEach { put(it, '3') }
    put('l', '4')
    "mn".forEach { put(it, '5') }
    put('r', '6')
  }

  private val DIGITS =
      mapOf(
          "0" to "zero", "1" to "one", "2" to "two", "3" to "three", "4" to "four",
          "5" to "five", "6" to "six", "7" to "seven", "8" to "eight", "9" to "nine",
      )
}

/** Commands that work in any listening window. */
enum class GlobalCommand {
  REPEAT,
  HINT,
  STATUS,
  PAUSE,
  RESUME;

  companion object {
    val phrases: Map<GlobalCommand, List<String>> =
        mapOf(
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
