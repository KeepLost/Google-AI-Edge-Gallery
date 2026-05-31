package com.google.ai.edge.gallery.customtasks.smartsurveillance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class SurveillanceRuleParserTest {
  @Test
  fun parseRuleJson_acceptsTtsActionFromMarkdownJsonBlock() {
    val rule =
      SurveillanceRuleParser.parseRuleJson(
        rawPrompt = "Tell me if someone is at the door",
        response =
          """
          ```json
          {
            "name": "Door watcher",
            "triggerCondition": "person near door",
            "action": { "type": "tts", "content": "Someone is at the door" }
          }
          ```
          """.trimIndent(),
        nowMs = 42L,
      )

    assertEquals("Door watcher", rule?.name)
    assertEquals("Tell me if someone is at the door", rule?.rawPrompt)
    assertEquals("tts", rule?.actionType)
    assertEquals("Someone is at the door", rule?.actionContent)
    assertEquals(42L, rule?.createdAt)
  }

  @Test
  fun parseRuleJson_rejectsUnsupportedAction() {
    val rule =
      SurveillanceRuleParser.parseRuleJson(
        rawPrompt = "Notify me about cats",
        response =
          """
          {"name":"Cat watcher","triggerCondition":"cat visible","action":{"type":"notification","content":"Cat"}}
          """.trimIndent(),
      )

    assertNull(rule)
  }

  @Test
  fun parseAnalysisJson_returnsOnlyTriggeredTtsEvents() {
    val events =
      SurveillanceRuleParser.parseAnalysisJson(
        response =
          """
          {"events":[{"ruleId":"r1","confidence":0.8,"action":{"type":"tts","content":"Door"}},{"ruleId":"r2","confidence":0.4,"action":{"type":"notification","content":"Ignore"}}]}
          """.trimIndent(),
        nowMs = 7L,
      )

    assertEquals(1, events.size)
    assertEquals("r1", events.first().ruleId)
    assertEquals("Door", events.first().message)
    assertEquals(7L, events.first().timestampMs)
  }

  @Test
  fun parseAnalysisResponse_returnsEventsResultForTriggeredTtsEvents() {
    val result =
      SurveillanceRuleParser.parseAnalysisResponse(
        response =
          """
          {"events":[{"ruleId":"r1","confidence":0.8,"action":{"type":"tts","content":"Door"}}]}
          """.trimIndent(),
        nowMs = 7L,
      )

    assertTrue(result is AnalysisParseResult.Events)
    val events = (result as AnalysisParseResult.Events).events
    assertEquals(1, events.size)
    assertEquals("r1", events.first().ruleId)
    assertEquals("Door", events.first().message)
    assertEquals("Matched rule conditions in the analyzed frames.", events.first().reason)
  }

  @Test
  fun parseAnalysisResponse_parsesRuleNameAndShortReason() {
    val result =
      SurveillanceRuleParser.parseAnalysisResponse(
        response =
          """
          {"events":[{"ruleId":"r1","ruleName":"Door watcher","confidence":0.8,"reason":"A person is standing next to the door.","action":{"type":"tts","content":"Door"}}]}
          """.trimIndent(),
        nowMs = 7L,
        validRuleIds = setOf("r1"),
      )

    assertTrue(result is AnalysisParseResult.Events)
    val event = (result as AnalysisParseResult.Events).events.first()
    assertEquals("r1", event.ruleId)
    assertEquals("Door watcher", event.ruleName)
    assertEquals("A person is standing next to the door.", event.reason)
  }

  @Test
  fun parseAnalysisResponse_rejectsUnknownRuleIdsWhenProvided() {
    val result =
      SurveillanceRuleParser.parseAnalysisResponse(
        response =
          """
          {"events":[{"ruleId":"unknown","confidence":0.8,"reason":"A person is near the door.","action":{"type":"tts","content":"Door"}}]}
          """.trimIndent(),
        validRuleIds = setOf("r1"),
      )

    assertTrue(result is AnalysisParseResult.Invalid)
    assertTrue((result as AnalysisParseResult.Invalid).message.contains("unknown rule"))
  }

  @Test
  fun parseAnalysisResponse_extractsFencedJsonAndRootArray() {
    val result =
      SurveillanceRuleParser.parseAnalysisResponse(
        response =
          """
          Here is the result:
          ```json
          [{"ruleId":"r1","confidence":0.8,"reason":"A person is near the door.","message":"Door"}]
          ```
          """.trimIndent(),
        validRuleIds = setOf("r1"),
      )

    assertTrue(result is AnalysisParseResult.Events)
    val event = (result as AnalysisParseResult.Events).events.first()
    assertEquals("Door", event.message)
    assertEquals("A person is near the door.", event.reason)
  }

  @Test
  fun parseAnalysisResponse_prefersOuterEventsEnvelopeOverInnerActionObject() {
    val result =
      SurveillanceRuleParser.parseAnalysisResponse(
        response =
          """
          Analysis result: {"events":[{"ruleId":"r1","confidence":0.8,"reason":"A person is near the door.","action":{"type":"tts","content":"Door"}}]}
          """.trimIndent(),
        validRuleIds = setOf("r1"),
      )

    assertTrue(result is AnalysisParseResult.Events)
    val event = (result as AnalysisParseResult.Events).events.first()
    assertEquals("r1", event.ruleId)
    assertEquals("Door", event.message)
  }

  @Test
  fun parseAnalysisResponse_prefersFencedOuterEventsEnvelopeOverInnerActionObject() {
    val result =
      SurveillanceRuleParser.parseAnalysisResponse(
        response =
          """
          ```json
          {"events":[{"ruleId":"r1","confidence":0.8,"reason":"A person is near the door.","action":{"type":"tts","content":"Door"}}]}
          ```
          """.trimIndent(),
        validRuleIds = setOf("r1"),
      )

    assertTrue(result is AnalysisParseResult.Events)
    assertEquals("Door", (result as AnalysisParseResult.Events).events.first().message)
  }

  @Test
  fun parseAnalysisResponse_onlyActionObjectReportsMissingEventEnvelope() {
    val result = SurveillanceRuleParser.parseAnalysisResponse(response = """{"type":"tts","content":"有人出现在视频片段中。"}""")

    assertTrue(result is AnalysisParseResult.Invalid)
    val invalid = result as AnalysisParseResult.Invalid
    assertEquals("Extracted JSON is a TTS action object, not an analysis event envelope.", invalid.message)
    assertEquals("{\"type\":\"tts\",\"content\":\"有人出现在视频片段中。\"}", invalid.extractedJsonPreview)
    assertTrue(invalid.candidateSummary?.contains("action_object") == true)
  }

  @Test
  fun parseAnalysisResponse_enclosingActionObjectBeatsNestedBareActionObject() {
    val result =
      SurveillanceRuleParser.parseAnalysisResponse(
        response =
          """
          Model output: {"reason":"A person appears in the video clip.","action":{"type":"tts","content":"有人出现在视频片段中。"}}
          """.trimIndent(),
        validRuleIds = setOf("r1"),
      )

    assertTrue(result is AnalysisParseResult.Invalid)
    val invalid = result as AnalysisParseResult.Invalid
    assertEquals("Analysis event is missing ruleId.", invalid.message)
    assertEquals(
      "{\"reason\":\"A person appears in the video clip.\",\"action\":{\"type\":\"tts\",\"content\":\"有人出现在视频片段中。\"}}",
      invalid.extractedJsonPreview,
    )
    assertTrue(invalid.candidateSummary?.contains("selected=event_like_object") == true)
  }

  @Test
  fun parseAnalysisResponse_acceptsEscapedJsonStringContainingEvents() {
    val result =
      SurveillanceRuleParser.parseAnalysisResponse(
        response = """"{\"events\":[{\"ruleId\":\"r1\",\"message\":\"Door\"}]}"""",
        validRuleIds = setOf("r1"),
      )

    assertTrue(result is AnalysisParseResult.Events)
    assertEquals("Door", (result as AnalysisParseResult.Events).events.first().message)
  }

  @Test
  fun parseAnalysisResponse_returnsNoEventsResultForEmptyEventsArray() {
    val result = SurveillanceRuleParser.parseAnalysisResponse(response = """{"events":[]}""")

    assertTrue(result is AnalysisParseResult.NoEvents)
  }

  @Test
  fun parseAnalysisResponse_returnsInvalidResultForNonJsonResponse() {
    val result = SurveillanceRuleParser.parseAnalysisResponse(response = "I see a person but no JSON")

    assertTrue(result is AnalysisParseResult.Invalid)
    assertTrue((result as AnalysisParseResult.Invalid).message.contains("JSON"))
  }

  @Test
  fun parseAnalysisResponse_acceptsRootSingleEventObjectAlias() {
    val result =
      SurveillanceRuleParser.parseAnalysisResponse(
        response =
          """
          {"rule_id":"r1","ruleName":"Door watcher","confidence":0.9,"reason":"A person is visible.","action":{"type":"tts","content":"Door"}}
          """.trimIndent(),
        validRuleIds = setOf("r1"),
      )

    assertTrue(result is AnalysisParseResult.Events)
    val event = (result as AnalysisParseResult.Events).events.first()
    assertEquals("r1", event.ruleId)
    assertEquals("Door", event.message)
  }

  @Test
  fun parseAnalysisResponse_acceptsEventKeyAlias() {
    val result =
      SurveillanceRuleParser.parseAnalysisResponse(
        response =
          """
          {"event":{"ruleId":"r1","confidence":0.9,"reason":"A person is visible.","message":"Door"}}
          """.trimIndent(),
        validRuleIds = setOf("r1"),
      )

    assertTrue(result is AnalysisParseResult.Events)
  }

  @Test
  fun parseAnalysisResponse_missingEventsArrayIncludesRawPreviewDiagnostic() {
    val result = SurveillanceRuleParser.parseAnalysisResponse(response = """{"detected":true,"message":"person"}""")

    assertTrue(result is AnalysisParseResult.Invalid)
    val invalid = result as AnalysisParseResult.Invalid
    assertEquals("Extracted analysis JSON is missing an events array.", invalid.message)
    assertEquals("{\"detected\":true,\"message\":\"person\"}", invalid.rawPreview)
    assertEquals("{\"detected\":true,\"message\":\"person\"}", invalid.extractedJsonPreview)
  }

  @Test
  fun parseAnalysisResponse_unknownRuleIdHasPreciseDiagnosticAndExtractedPreview() {
    val result =
      SurveillanceRuleParser.parseAnalysisResponse(
        response = """{"events":[{"ruleId":"unknown","message":"Door"}]}""",
        validRuleIds = setOf("r1"),
      )

    assertTrue(result is AnalysisParseResult.Invalid)
    val invalid = result as AnalysisParseResult.Invalid
    assertEquals("Analysis event referenced an unknown rule id: unknown.", invalid.message)
    assertEquals("{\"events\":[{\"ruleId\":\"unknown\",\"message\":\"Door\"}]}", invalid.extractedJsonPreview)
  }

  @Test
  fun parseAnalysisResponse_missingActionContentHasPreciseDiagnostic() {
    val result =
      SurveillanceRuleParser.parseAnalysisResponse(
        response = """{"events":[{"ruleId":"r1","action":{"type":"tts"}}]}""",
        validRuleIds = setOf("r1"),
      )

    assertTrue(result is AnalysisParseResult.Invalid)
    assertEquals("Analysis event for rule r1 is missing TTS content.", (result as AnalysisParseResult.Invalid).message)
  }

  @Test
  fun parseAnalysisResponse_missingActionHasPreciseDiagnostic() {
    val result =
      SurveillanceRuleParser.parseAnalysisResponse(
        response = """{"events":[{"ruleId":"r1","confidence":0.8}]}""",
        validRuleIds = setOf("r1"),
      )

    assertTrue(result is AnalysisParseResult.Invalid)
    assertEquals("Analysis event for rule r1 is missing a TTS action or message.", (result as AnalysisParseResult.Invalid).message)
  }

  @Test
  fun parseAnalysisResponse_unsupportedActionHasPreciseDiagnostic() {
    val result =
      SurveillanceRuleParser.parseAnalysisResponse(
        response = """{"events":[{"ruleId":"r1","action":{"type":"notification","content":"Door"}}]}""",
        validRuleIds = setOf("r1"),
      )

    assertTrue(result is AnalysisParseResult.Invalid)
    assertEquals("Analysis event for rule r1 used unsupported action type: notification.", (result as AnalysisParseResult.Invalid).message)
  }

  @Test
  fun parseAnalysisResponse_returnsInvalidResultWhenOnlyUnsupportedActionsExist() {
    val result =
      SurveillanceRuleParser.parseAnalysisResponse(
        response =
          """
          {"events":[{"ruleId":"r1","confidence":0.8,"action":{"type":"notification","content":"Door"}}]}
          """.trimIndent(),
      )

    assertTrue(result is AnalysisParseResult.Invalid)
  }

  @Test
  fun parseRuleCreationResponse_acceptsRuleEnvelopeWithTtsAction() {
    val result =
      SurveillanceRuleParser.parseRuleCreationResponse(
        rawPrompt = "有人靠近门口就提醒我",
        response =
          """
          {"type":"rule","rule":{"name":"门口提醒","triggerCondition":"person near door","action":{"type":"tts","content":"门口有人"}}}
          """.trimIndent(),
        nowMs = 100L,
      )

    assertTrue(result is RuleCreationParseResult.Rule)
    val rule = (result as RuleCreationParseResult.Rule).rule
    assertEquals("门口提醒", rule.name)
    assertEquals("tts", rule.actionType)
    assertEquals("门口有人", rule.actionContent)
    assertEquals(100L, rule.createdAt)
  }

  @Test
  fun parseRuleCreationResponse_returnsNotRuleWithoutSaving() {
    val result =
      SurveillanceRuleParser.parseRuleCreationResponse(
        rawPrompt = "现在画面里有什么",
        response = """{"type":"not_rule","message":"请切换到新增规则或使用 /rule。"}""",
      )

    assertTrue(result is RuleCreationParseResult.NotRule)
    assertEquals("请切换到新增规则或使用 /rule。", (result as RuleCreationParseResult.NotRule).message)
  }

  @Test
  fun parseRuleCreationResponse_rejectsNonTtsRuleEnvelope() {
    val result =
      SurveillanceRuleParser.parseRuleCreationResponse(
        rawPrompt = "有猫就通知我",
        response =
          """
          {"type":"rule","rule":{"name":"猫","triggerCondition":"cat visible","action":{"type":"notification","content":"有猫"}}}
          """.trimIndent(),
      )

    assertTrue(result is RuleCreationParseResult.Invalid)
  }

  // --- Robust rule-id attribution overload ---

  private val uuidRule =
    SurveillanceRuleParser.AnalysisRule(
      id = "a3f2c1e8-7b9d-4c2a-8f10-0123456789ab",
      alias = SurveillanceRuleParser.analysisAlias(0),
      name = "Door watcher",
    )

  @Test
  fun analysisAlias_isSequentialAndOneBased() {
    assertEquals("r1", SurveillanceRuleParser.analysisAlias(0))
    assertEquals("r2", SurveillanceRuleParser.analysisAlias(1))
    assertEquals("r3", SurveillanceRuleParser.analysisAlias(2))
  }

  @Test
  fun robust_acceptsExactStoredUuidRuleId() {
    val result =
      SurveillanceRuleParser.parseAnalysisResponse(
        response =
          """{"events":[{"ruleId":"a3f2c1e8-7b9d-4c2a-8f10-0123456789ab","confidence":0.8,"action":{"type":"tts","content":"Door"}}]}""",
        rules = listOf(uuidRule),
      )

    assertTrue(result is AnalysisParseResult.Events)
    val event = (result as AnalysisParseResult.Events).events.first()
    assertEquals("a3f2c1e8-7b9d-4c2a-8f10-0123456789ab", event.ruleId)
    assertEquals("Door", event.message)
  }

  @Test
  fun robust_resolvesShortAliasToStoredUuid() {
    val result =
      SurveillanceRuleParser.parseAnalysisResponse(
        response = """{"events":[{"ruleId":"r1","confidence":0.8,"action":{"type":"tts","content":"Door"}}]}""",
        rules = listOf(uuidRule),
      )

    assertTrue(result is AnalysisParseResult.Events)
    assertEquals("a3f2c1e8-7b9d-4c2a-8f10-0123456789ab", (result as AnalysisParseResult.Events).events.first().ruleId)
  }

  @Test
  fun robust_singleRuleAttributesEventWithUnknownRuleId() {
    val result =
      SurveillanceRuleParser.parseAnalysisResponse(
        response = """{"events":[{"ruleId":"rule-1","confidence":0.8,"action":{"type":"tts","content":"Door"}}]}""",
        rules = listOf(uuidRule),
      )

    assertTrue(result is AnalysisParseResult.Events)
    assertEquals("a3f2c1e8-7b9d-4c2a-8f10-0123456789ab", (result as AnalysisParseResult.Events).events.first().ruleId)
  }

  @Test
  fun robust_singleRuleAttributesEventWithMissingRuleId() {
    val result =
      SurveillanceRuleParser.parseAnalysisResponse(
        response = """{"events":[{"confidence":0.8,"action":{"type":"tts","content":"Door"}}]}""",
        rules = listOf(uuidRule),
      )

    assertTrue(result is AnalysisParseResult.Events)
    assertEquals("a3f2c1e8-7b9d-4c2a-8f10-0123456789ab", (result as AnalysisParseResult.Events).events.first().ruleId)
  }

  @Test
  fun robust_multiRuleRejectsMissingRuleId() {
    val rules =
      listOf(
        SurveillanceRuleParser.AnalysisRule(id = "uuid-a", alias = "r1", name = "Door"),
        SurveillanceRuleParser.AnalysisRule(id = "uuid-b", alias = "r2", name = "Window"),
      )
    val result =
      SurveillanceRuleParser.parseAnalysisResponse(
        response = """{"events":[{"confidence":0.8,"action":{"type":"tts","content":"Something"}}]}""",
        rules = rules,
      )

    assertTrue(result is AnalysisParseResult.Invalid)
    assertTrue((result as AnalysisParseResult.Invalid).message.contains("missing ruleId"))
  }

  @Test
  fun robust_multiRuleRejectsUnknownRuleId() {
    val rules =
      listOf(
        SurveillanceRuleParser.AnalysisRule(id = "uuid-a", alias = "r1", name = "Door"),
        SurveillanceRuleParser.AnalysisRule(id = "uuid-b", alias = "r2", name = "Window"),
      )
    val result =
      SurveillanceRuleParser.parseAnalysisResponse(
        response = """{"events":[{"ruleId":"totally-unknown","confidence":0.8,"action":{"type":"tts","content":"Something"}}]}""",
        rules = rules,
      )

    assertTrue(result is AnalysisParseResult.Invalid)
    assertTrue((result as AnalysisParseResult.Invalid).message.contains("unknown rule id"))
  }

  @Test
  fun robust_multiRuleResolvesEachAliasToCorrectUuid() {
    val rules =
      listOf(
        SurveillanceRuleParser.AnalysisRule(id = "uuid-a", alias = "r1", name = "Door"),
        SurveillanceRuleParser.AnalysisRule(id = "uuid-b", alias = "r2", name = "Window"),
      )
    val result =
      SurveillanceRuleParser.parseAnalysisResponse(
        response =
          """{"events":[{"ruleId":"r2","confidence":0.9,"action":{"type":"tts","content":"Window!"}}]}""",
        rules = rules,
      )

    assertTrue(result is AnalysisParseResult.Events)
    val event = (result as AnalysisParseResult.Events).events.first()
    assertEquals("uuid-b", event.ruleId)
    assertEquals("Window!", event.message)
  }

  @Test
  fun robust_resolvesByUniqueRuleNameFallback() {
    val rules =
      listOf(
        SurveillanceRuleParser.AnalysisRule(id = "uuid-a", alias = "r1", name = "Door watcher"),
        SurveillanceRuleParser.AnalysisRule(id = "uuid-b", alias = "r2", name = "Window watcher"),
      )
    val result =
      SurveillanceRuleParser.parseAnalysisResponse(
        response =
          """{"events":[{"ruleId":"Window watcher","confidence":0.9,"action":{"type":"tts","content":"Window!"}}]}""",
        rules = rules,
      )

    assertTrue(result is AnalysisParseResult.Events)
    assertEquals("uuid-b", (result as AnalysisParseResult.Events).events.first().ruleId)
  }

  @Test
  fun robust_multiRuleStillEnforcesTtsOnly() {
    val rules =
      listOf(
        SurveillanceRuleParser.AnalysisRule(id = "uuid-a", alias = "r1", name = "Door"),
        SurveillanceRuleParser.AnalysisRule(id = "uuid-b", alias = "r2", name = "Window"),
      )
    val result =
      SurveillanceRuleParser.parseAnalysisResponse(
        response =
          """{"events":[{"ruleId":"r1","confidence":0.8,"action":{"type":"notification","content":"Door"}}]}""",
        rules = rules,
      )

    assertTrue(result is AnalysisParseResult.Invalid)
    assertTrue((result as AnalysisParseResult.Invalid).message.contains("unsupported action type"))
  }

  @Test
  fun robust_singleRuleStillEnforcesTtsOnly() {
    val result =
      SurveillanceRuleParser.parseAnalysisResponse(
        response =
          """{"events":[{"ruleId":"r1","confidence":0.8,"action":{"type":"agent","content":"Door"}}]}""",
        rules = listOf(uuidRule),
      )

    assertTrue(result is AnalysisParseResult.Invalid)
    assertTrue((result as AnalysisParseResult.Invalid).message.contains("unsupported action type"))
  }

  @Test
  fun robust_emptyEventsReturnsNoEvents() {
    val result =
      SurveillanceRuleParser.parseAnalysisResponse(response = """{"events":[]}""", rules = listOf(uuidRule))

    assertTrue(result is AnalysisParseResult.NoEvents)
  }

  @Test
  fun robust_singleRuleStillRejectsMissingTtsContent() {
    val result =
      SurveillanceRuleParser.parseAnalysisResponse(
        response = """{"events":[{"ruleId":"r1","action":{"type":"tts"}}]}""",
        rules = listOf(uuidRule),
      )

    assertTrue(result is AnalysisParseResult.Invalid)
    assertTrue((result as AnalysisParseResult.Invalid).message.contains("missing TTS content"))
  }

  @Test
  fun robust_noActiveRulesRejectsEvent() {
    val result =
      SurveillanceRuleParser.parseAnalysisResponse(
        response = """{"events":[{"ruleId":"r1","action":{"type":"tts","content":"Door"}}]}""",
        rules = emptyList(),
      )

    assertTrue(result is AnalysisParseResult.Invalid)
  }
}
