package com.google.ai.edge.gallery.customtasks.smartsurveillance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the recovery-first confidence-map analysis parser (current monitoring contract). */
class SurveillanceConfidenceMapParserTest {
  private fun rule(id: String, alias: String, name: String, action: String, feature: String = "feature for $name") =
    SurveillanceRuleParser.AnalysisRule(id = id, alias = alias, name = name, actionContent = action, detectionFeature = feature)

  private val doorRule = rule("uuid-door", "r1", "Door watcher", "Someone is at the door")
  private val windowRule = rule("uuid-window", "r2", "Window watcher", "Someone is at the window")
  private val gateRule = rule("uuid-gate", "r3", "Gate watcher", "Someone is at the gate")

  @Test
  fun emptyMap_returnsNoEvents() {
    val result = SurveillanceRuleParser.parseAnalysisConfidenceMap("{}", rules = listOf(doorRule))
    assertTrue(result is AnalysisParseResult.NoEvents)
  }

  @Test
  fun whitespaceOnly_returnsNoEvents() {
    val result = SurveillanceRuleParser.parseAnalysisConfidenceMap("   \n  ", rules = listOf(doorRule))
    assertTrue(result is AnalysisParseResult.NoEvents)
  }

  @Test
  fun singlePair_resolvesToStoredUuidAndStoredActionContent() {
    val result = SurveillanceRuleParser.parseAnalysisConfidenceMap("""{"r1":0.8}""", rules = listOf(doorRule))
    assertTrue(result is AnalysisParseResult.Events)
    val event = (result as AnalysisParseResult.Events).events.single()
    assertEquals("uuid-door", event.ruleId)
    assertEquals("Someone is at the door", event.message)
    assertEquals(0.8f, event.confidence, 0.0001f)
  }

  @Test
  fun multiplePairs_resolveEachAlias() {
    val result =
      SurveillanceRuleParser.parseAnalysisConfidenceMap(
        """{"r1":0.8,"r3":0.6}""",
        rules = listOf(doorRule, windowRule, gateRule),
        minConfidence = 0.5f,
      )
    assertTrue(result is AnalysisParseResult.Events)
    val ids = (result as AnalysisParseResult.Events).events.map { it.ruleId }.toSet()
    assertEquals(setOf("uuid-door", "uuid-gate"), ids)
  }

  @Test
  fun duplicateAlias_keepsMaxConfidence() {
    val result =
      SurveillanceRuleParser.parseAnalysisConfidenceMap("""{"r1":0.3,"r1":0.9}""", rules = listOf(doorRule), minConfidence = 0.5f)
    assertTrue(result is AnalysisParseResult.Events)
    val event = (result as AnalysisParseResult.Events).events.single()
    assertEquals(0.9f, event.confidence, 0.0001f)
  }

  @Test
  fun missingClosingBrace_recoversPairs() {
    val result =
      SurveillanceRuleParser.parseAnalysisConfidenceMap(
        """{"r1":0.8,"r2":0.9""",
        rules = listOf(doorRule, windowRule),
        minConfidence = 0.5f,
      )
    assertTrue(result is AnalysisParseResult.Events)
    assertEquals(2, (result as AnalysisParseResult.Events).events.size)
  }

  @Test
  fun truncatedConfidenceValue_firesAtThresholdDefault() {
    // r2 value is truncated ("0."); per decision #5 it is treated as fired at the threshold.
    val result =
      SurveillanceRuleParser.parseAnalysisConfidenceMap(
        """{"r1":0.8,"r2":0.""",
        rules = listOf(doorRule, windowRule),
        minConfidence = 0.5f,
      )
    assertTrue(result is AnalysisParseResult.Events)
    val byId = (result as AnalysisParseResult.Events).events.associateBy { it.ruleId }
    assertEquals(2, byId.size)
    assertEquals(0.5f, byId["uuid-window"]!!.confidence, 0.0001f)
  }

  @Test
  fun missingConfidenceValue_firesAtThresholdDefault() {
    val result =
      SurveillanceRuleParser.parseAnalysisConfidenceMap("""{"r1":}""", rules = listOf(doorRule), minConfidence = 0.5f)
    assertTrue(result is AnalysisParseResult.Events)
    assertEquals(0.5f, (result as AnalysisParseResult.Events).events.single().confidence, 0.0001f)
  }

  @Test
  fun singleQuotedKeys_recover() {
    val result = SurveillanceRuleParser.parseAnalysisConfidenceMap("""{'r1':0.8}""", rules = listOf(doorRule))
    assertTrue(result is AnalysisParseResult.Events)
    assertEquals("uuid-door", (result as AnalysisParseResult.Events).events.single().ruleId)
  }

  @Test
  fun proseWrapper_recovers() {
    val result =
      SurveillanceRuleParser.parseAnalysisConfidenceMap("Fired: {\"r1\":0.8} done", rules = listOf(doorRule))
    assertTrue(result is AnalysisParseResult.Events)
    assertEquals("uuid-door", (result as AnalysisParseResult.Events).events.single().ruleId)
  }

  @Test
  fun fencedOutput_recovers() {
    val result =
      SurveillanceRuleParser.parseAnalysisConfidenceMap("```json\n{\"r1\":0.8}\n```", rules = listOf(doorRule))
    assertTrue(result is AnalysisParseResult.Events)
    assertEquals("uuid-door", (result as AnalysisParseResult.Events).events.single().ruleId)
  }

  @Test
  fun uppercaseAlias_normalizes() {
    val result = SurveillanceRuleParser.parseAnalysisConfidenceMap("""{"R1":0.8}""", rules = listOf(doorRule))
    assertTrue(result is AnalysisParseResult.Events)
    assertEquals("uuid-door", (result as AnalysisParseResult.Events).events.single().ruleId)
  }

  @Test
  fun unknownAliasOnly_returnsInvalid() {
    val result = SurveillanceRuleParser.parseAnalysisConfidenceMap("""{"r9":0.9}""", rules = listOf(doorRule))
    assertTrue(result is AnalysisParseResult.Invalid)
    assertTrue((result as AnalysisParseResult.Invalid).message.contains("unknown rule id"))
  }

  @Test
  fun mixedKnownAndUnknown_firesKnownOnly() {
    val result =
      SurveillanceRuleParser.parseAnalysisConfidenceMap("""{"r1":0.8,"r9":0.5}""", rules = listOf(doorRule), minConfidence = 0.5f)
    assertTrue(result is AnalysisParseResult.Events)
    assertEquals(setOf("uuid-door"), (result as AnalysisParseResult.Events).events.map { it.ruleId }.toSet())
  }

  @Test
  fun belowThreshold_doesNotFire() {
    val result =
      SurveillanceRuleParser.parseAnalysisConfidenceMap("""{"r1":0.2}""", rules = listOf(doorRule), minConfidence = 0.5f)
    assertTrue(result is AnalysisParseResult.NoEvents)
  }

  @Test
  fun confidenceAboveOne_isClamped() {
    val result =
      SurveillanceRuleParser.parseAnalysisConfidenceMap("""{"r1":1.7}""", rules = listOf(doorRule), minConfidence = 0.5f)
    assertTrue(result is AnalysisParseResult.Events)
    assertEquals(1.0f, (result as AnalysisParseResult.Events).events.single().confidence, 0.0001f)
  }

  @Test
  fun garbageWithoutAnyMapOrToken_returnsInvalid() {
    val result = SurveillanceRuleParser.parseAnalysisConfidenceMap("I cannot tell", rules = listOf(doorRule))
    assertTrue(result is AnalysisParseResult.Invalid)
    assertTrue((result as AnalysisParseResult.Invalid).message.contains("did not return a rule confidence map"))
  }

  @Test
  fun ttsMessageAlwaysComesFromStoredActionContentNeverModelText() {
    // Even if model text contained other words, only the stored actionContent is spoken.
    val result =
      SurveillanceRuleParser.parseAnalysisConfidenceMap(
        """The intruder is dangerous {"r1":0.9}""",
        rules = listOf(doorRule),
      )
    assertTrue(result is AnalysisParseResult.Events)
    val event = (result as AnalysisParseResult.Events).events.single()
    assertEquals("Someone is at the door", event.message)
    assertTrue(!event.message.contains("intruder"))
  }

  @Test
  fun reasonIsTemplatedFromStoredDetectionFeature() {
    val r = rule("uuid-x", "r1", "X", "speak", feature = "a red car is parked outside")
    val result = SurveillanceRuleParser.parseAnalysisConfidenceMap("""{"r1":0.8}""", rules = listOf(r))
    assertTrue(result is AnalysisParseResult.Events)
    val reason = (result as AnalysisParseResult.Events).events.single().reason
    assertNotNull(reason)
    assertTrue(reason.contains("a red car is parked outside"))
  }

  @Test
  fun noActiveRules_unknownAliasInvalid() {
    val result = SurveillanceRuleParser.parseAnalysisConfidenceMap("""{"r1":0.8}""", rules = emptyList())
    assertTrue(result is AnalysisParseResult.Invalid)
  }
}
