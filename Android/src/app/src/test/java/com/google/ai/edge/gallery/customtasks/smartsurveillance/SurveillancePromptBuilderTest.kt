package com.google.ai.edge.gallery.customtasks.smartsurveillance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveillancePromptBuilderTest {
  @Test
  fun askPrompt_includesAskConversationContext() {
    val prompt =
          SurveillancePromptBuilder.buildAskPrompt(
            userText = "它还在吗?",
            frameCount = 4,
            lookbackSeconds = 4,
            guidance = "中文回答",
            askHistory =
          listOf(
            AskTurn(user = "画面里有什么?", assistant = "画面里有一只猫。"),
          ),
      )

    assertTrue(prompt.contains("Prior ordinary Ask conversation"))
    assertTrue(prompt.contains("User: 画面里有什么?"))
    assertTrue(prompt.contains("Assistant: 画面里有一只猫。"))
  }

  @Test
  fun rulePrompt_excludesAskConversationContext() {
    val prompt = SurveillancePromptBuilder.buildRulePrompt(userText = "有人靠近就提醒", guidance = "中文")

    assertFalse(prompt.contains("Prior ordinary Ask conversation"))
    assertFalse(prompt.contains("画面里有一只猫"))
  }

  @Test
  fun analysisPrompt_excludesAskAndRuleConversationContext() {
    val rule =
      SurveillanceRuleEntity(
        id = "rule-1",
        name = "Person",
        rawPrompt = "有人靠近就提醒",
        triggerJson = "{\"condition\":\"person near door\"}",
        actionJson = "{\"type\":\"tts\",\"content\":\"有人靠近\"}",
        actionType = "tts",
        actionContent = "有人靠近",
        active = true,
        createdAt = 1L,
        updatedAt = 1L,
      )

    val prompt =
      SurveillancePromptBuilder.buildAnalysisPrompt(
        rules = listOf(rule),
        guidance = "少误报",
        settings = SurveillanceRuntimeSettings.defaults(),
      )

    assertTrue(prompt.contains("r1"))
    assertFalse(prompt.contains("Prior ordinary Ask conversation"))
    assertFalse(prompt.contains("User rule:"))
  }

  @Test
  fun analysisPrompt_usesShortAliasNotStoredUuidAndDropsMisleadingRuleOneExample() {
    val rule =
      SurveillanceRuleEntity(
        id = "a3f2c1e8-7b9d-4c2a-8f10-0123456789ab",
        name = "Door watcher",
        rawPrompt = "有人靠近门口就提醒",
        triggerJson = "{\"condition\":\"person near door\"}",
        actionJson = "{\"type\":\"tts\",\"content\":\"门口有人\"}",
        actionType = "tts",
        actionContent = "门口有人",
        active = true,
        createdAt = 1L,
        updatedAt = 1L,
      )

    val prompt =
      SurveillancePromptBuilder.buildAnalysisPrompt(
        rules = listOf(rule),
        guidance = "少误报",
        settings = SurveillanceRuntimeSettings.defaults(),
      )

    // The stored UUID must not be injected as the id Gemma is asked to echo.
    assertFalse(prompt.contains("a3f2c1e8-7b9d-4c2a-8f10-0123456789ab"))
    // The short alias is used instead.
    assertTrue(prompt.contains("\"ruleId\":\"r1\""))
    // The misleading literal example id must be gone.
    assertFalse(prompt.contains("rule-1"))
  }

  @Test
  fun analysisPrompt_assignsSequentialAliasesForMultipleRules() {
    val rules =
      listOf(
        SurveillanceRuleEntity(
          id = "uuid-aaaa",
          name = "Door watcher",
          rawPrompt = "门口",
          triggerJson = "{\"condition\":\"door\"}",
          actionJson = "{\"type\":\"tts\",\"content\":\"门口有人\"}",
          actionType = "tts",
          actionContent = "门口有人",
          active = true,
          createdAt = 1L,
          updatedAt = 1L,
        ),
        SurveillanceRuleEntity(
          id = "uuid-bbbb",
          name = "Window watcher",
          rawPrompt = "窗户",
          triggerJson = "{\"condition\":\"window\"}",
          actionJson = "{\"type\":\"tts\",\"content\":\"窗户有人\"}",
          actionType = "tts",
          actionContent = "窗户有人",
          active = true,
          createdAt = 1L,
          updatedAt = 1L,
        ),
      )

    val prompt =
      SurveillancePromptBuilder.buildAnalysisPrompt(
        rules = rules,
        guidance = "少误报",
        settings = SurveillanceRuntimeSettings.defaults(),
      )

    assertTrue(prompt.contains("\"ruleId\":\"r1\""))
    assertTrue(prompt.contains("\"ruleId\":\"r2\""))
    assertFalse(prompt.contains("uuid-aaaa"))
    assertFalse(prompt.contains("uuid-bbbb"))
  }

  @Test
  fun analysisPrompt_requiresJsonOnlyReasonSchemaAndRuleAttribution() {
    val rule =
      SurveillanceRuleEntity(
        id = "rule-1",
        name = "Door watcher",
        rawPrompt = "有人靠近门口就提醒",
        triggerJson = "{\"condition\":\"person near door\"}",
        actionJson = "{\"type\":\"tts\",\"content\":\"门口有人\"}",
        actionType = "tts",
        actionContent = "门口有人",
        active = true,
        createdAt = 1L,
        updatedAt = 1L,
      )

    val prompt =
      SurveillancePromptBuilder.buildAnalysisPrompt(
        rules = listOf(rule),
        guidance = "少误报",
        settings = SurveillanceRuntimeSettings.defaults(),
      )

    assertTrue(prompt.contains("Return exactly one JSON object"))
    assertTrue(prompt.contains("No markdown"))
    assertTrue(prompt.contains("{\"events\":[]}"))
    assertTrue(prompt.contains("ruleName"))
    assertTrue(prompt.contains("reason"))
    assertTrue(prompt.contains("Door watcher"))
    assertTrue(prompt.contains("门口有人"))
    assertTrue(prompt.contains("non-overlapping window"))
    assertTrue(prompt.contains("oldest to newest"))
  }

  @Test
  fun renderedAnalysisPromptPreviewIncludesRuntimeMetadataAndActiveRules() {
    val rule =
      SurveillanceRuleEntity(
        id = "rule-1",
        name = "Door watcher",
        rawPrompt = "有人靠近门口就提醒",
        triggerJson = "{\"condition\":\"person near door\"}",
        actionJson = "{\"type\":\"tts\",\"content\":\"门口有人\"}",
        actionType = "tts",
        actionContent = "门口有人",
        active = true,
        createdAt = 1L,
        updatedAt = 1L,
      )
    val settings = SurveillanceRuntimeSettings.sanitize(frameSamplingFps = 2f, lookbackSeconds = 5)

    val preview = SurveillancePromptBuilder.buildAnalysisPromptPreview(listOf(rule), "少误报", settings)

    assertTrue(preview.contains("Frame sampling FPS: 2.0"))
    assertTrue(preview.contains("Lookback seconds: 5"))
    assertTrue(preview.contains("Frames attached separately: 10"))
    assertTrue(preview.contains("Door watcher"))
    assertTrue(preview.contains("Required JSON schema"))
  }

  @Test
  fun askPrompt_doesNotTellUserToCreateRuleUnlessAsked() {
    val prompt =
          SurveillancePromptBuilder.buildAskPrompt(
            userText = "画面里有什么?",
            frameCount = 0,
            lookbackSeconds = 4,
            guidance = "中文回答",
            askHistory = emptyList(),
      )

    assertTrue(prompt.contains("Only mention rule creation"))
    assertFalse(prompt.contains("请切换到新增规则"))
  }
}
