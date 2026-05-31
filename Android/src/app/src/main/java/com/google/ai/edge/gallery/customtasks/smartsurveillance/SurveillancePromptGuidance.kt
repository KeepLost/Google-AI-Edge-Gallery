package com.google.ai.edge.gallery.customtasks.smartsurveillance

data class SurveillancePromptGuidance(
  val rule: String,
  val ask: String,
  val analysis: String,
) {
  companion object {
    const val MAX_GUIDANCE_CHARS = 1000

    fun defaults(): SurveillancePromptGuidance =
      SurveillancePromptGuidance(
        rule = "使用中文生成简短规则名和 TTS 播报内容。只有用户明确要求未来监控行为时才创建规则。",
        ask = "使用中文简洁回答。可以结合当前摄像头画面，但不要创建、修改或删除规则。",
        analysis = "宁可少报，不要误报。只有画面清楚满足规则触发条件时才返回事件。",
      )

    fun sanitize(rule: String, ask: String, analysis: String): SurveillancePromptGuidance =
      SurveillancePromptGuidance(
        rule = rule.trim().take(MAX_GUIDANCE_CHARS),
        ask = ask.trim().take(MAX_GUIDANCE_CHARS),
        analysis = analysis.trim().take(MAX_GUIDANCE_CHARS),
      )
  }
}
