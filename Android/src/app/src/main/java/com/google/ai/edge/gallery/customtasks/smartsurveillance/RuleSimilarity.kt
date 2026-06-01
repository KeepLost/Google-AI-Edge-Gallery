package com.google.ai.edge.gallery.customtasks.smartsurveillance

/**
 * Deterministic, dependency-free lexical similarity used to detect when a newly described rule is a
 * near-duplicate of an existing one, so registration can offer to merge instead of always minting a
 * new rule id. Pure/JVM-testable: no Android or model dependencies.
 *
 * Similarity blends token-set Jaccard (robust to word reordering) with character-trigram cosine
 * (robust to minor wording/spelling differences). The combined score is in [0,1].
 */
object RuleSimilarity {
  /** A shortlisted existing rule that is similar enough to a new rule to warrant a merge prompt. */
  data class Candidate(val id: String, val score: Float)

  private val TOKEN_SPLIT = Regex("[^\\p{L}\\p{Nd}]+")
  private val STOPWORDS =
    setOf(
      // English
      "the", "a", "an", "is", "are", "if", "when", "whenever", "to", "me", "my", "and", "or",
      "of", "in", "on", "at", "near", "by", "with", "that", "this", "there", "it", "be", "for",
      // Common CJK functional words / verbs that carry little discriminative weight here.
      "如果", "就", "的", "了", "和", "或", "在", "有", "请", "提醒", "我", "时", "当", "出现",
    )

  /**
   * Combined similarity in [0,1] between two natural-language detection features.
   * [jaccardWeight] balances token-set Jaccard against character-trigram cosine.
   */
  fun score(a: String, b: String, jaccardWeight: Float = 0.5f): Float {
    val na = a.trim().lowercase()
    val nb = b.trim().lowercase()
    if (na.isEmpty() || nb.isEmpty()) return 0f
    if (na == nb) return 1f
    val w = jaccardWeight.coerceIn(0f, 1f)
    val jac = tokenJaccard(na, nb)
    val cos = trigramCosine(na, nb)
    return (w * jac + (1f - w) * cos).coerceIn(0f, 1f)
  }

  /**
   * Returns existing rules whose feature similarity to [newFeature] is at least [floor], ordered by
   * descending score and capped at [k]. Blank existing features are ignored.
   */
  fun shortlist(
    newFeature: String,
    existing: List<Pair<String, String>>, // (id, detectionFeature)
    k: Int = 3,
    floor: Float = 0.6f,
  ): List<Candidate> =
    existing
      .asSequence()
      .filter { it.second.isNotBlank() }
      .map { (id, feature) -> Candidate(id = id, score = score(newFeature, feature)) }
      .filter { it.score >= floor }
      .sortedByDescending { it.score }
      .take(k.coerceAtLeast(0))
      .toList()

  private fun tokens(text: String): Set<String> =
    text.split(TOKEN_SPLIT).asSequence().map { it.trim() }.filter { it.isNotEmpty() && it !in STOPWORDS }.toSet()

  private fun tokenJaccard(a: String, b: String): Float {
    val ta = tokens(a)
    val tb = tokens(b)
    if (ta.isEmpty() || tb.isEmpty()) return 0f
    val intersection = ta.count { it in tb }
    val union = ta.size + tb.size - intersection
    return if (union == 0) 0f else intersection.toFloat() / union
  }

  private fun trigrams(text: String): Map<String, Int> {
    val cleaned = text.replace(Regex("\\s+"), " ").trim()
    if (cleaned.length < 3) return if (cleaned.isEmpty()) emptyMap() else mapOf(cleaned to 1)
    val counts = HashMap<String, Int>()
    for (i in 0..cleaned.length - 3) {
      val gram = cleaned.substring(i, i + 3)
      counts[gram] = (counts[gram] ?: 0) + 1
    }
    return counts
  }

  private fun trigramCosine(a: String, b: String): Float {
    val va = trigrams(a)
    val vb = trigrams(b)
    if (va.isEmpty() || vb.isEmpty()) return 0f
    var dot = 0.0
    for ((gram, ca) in va) {
      val cb = vb[gram] ?: continue
      dot += ca.toDouble() * cb.toDouble()
    }
    if (dot == 0.0) return 0f
    val magA = Math.sqrt(va.values.sumOf { (it * it).toDouble() })
    val magB = Math.sqrt(vb.values.sumOf { (it * it).toDouble() })
    if (magA == 0.0 || magB == 0.0) return 0f
    return (dot / (magA * magB)).toFloat().coerceIn(0f, 1f)
  }
}
