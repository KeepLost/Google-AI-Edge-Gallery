package com.google.ai.edge.gallery.customtasks.smartsurveillance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleSimilarityTest {
  @Test
  fun identicalFeature_scoresOne() {
    assertEquals(1f, RuleSimilarity.score("a person near the door", "a person near the door"), 0.0001f)
  }

  @Test
  fun blankFeature_scoresZero() {
    assertEquals(0f, RuleSimilarity.score("", "a person"), 0.0001f)
    assertEquals(0f, RuleSimilarity.score("a person", "   "), 0.0001f)
  }

  @Test
  fun paraphraseWithSharedTokens_scoresHigh() {
    val score =
      RuleSimilarity.score("a person is standing near the door", "person standing close to the door")
    assertTrue("expected high similarity, got $score", score >= 0.5f)
  }

  @Test
  fun unrelatedFeatures_scoreLow() {
    val score = RuleSimilarity.score("a person near the door", "a red car on the street")
    assertTrue("expected low similarity, got $score", score < 0.5f)
  }

  @Test
  fun shortlist_returnsSimilarRuleAboveFloor() {
    val existing =
      listOf(
        "uuid-door" to "a person near the door",
        "uuid-car" to "a red car on the street",
      )
    val shortlist = RuleSimilarity.shortlist("person standing by the door", existing, k = 3, floor = 0.4f)
    assertTrue(shortlist.isNotEmpty())
    assertEquals("uuid-door", shortlist.first().id)
  }

  @Test
  fun shortlist_excludesBelowFloorAndBlank() {
    val existing =
      listOf(
        "uuid-car" to "a red car on the street",
        "uuid-blank" to "",
      )
    val shortlist = RuleSimilarity.shortlist("a person near the door", existing, k = 3, floor = 0.5f)
    assertTrue(shortlist.isEmpty())
  }

  @Test
  fun shortlist_isOrderedByDescendingScoreAndCapped() {
    val existing =
      listOf(
        "uuid-door1" to "a person near the door",
        "uuid-door2" to "a person standing at the door entrance",
        "uuid-car" to "a red car",
      )
    val shortlist = RuleSimilarity.shortlist("a person near the door", existing, k = 1, floor = 0.1f)
    assertEquals(1, shortlist.size)
    assertEquals("uuid-door1", shortlist.first().id)
  }
}
