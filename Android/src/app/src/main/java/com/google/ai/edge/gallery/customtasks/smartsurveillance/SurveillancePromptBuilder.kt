package com.google.ai.edge.gallery.customtasks.smartsurveillance

data class AskTurn(
  val user: String,
  val assistant: String,
)

object SurveillancePromptBuilder {
  fun buildRulePrompt(userText: String, guidance: String): String =
    """
    You convert explicit smart-surveillance rule requests into JSON.
    Treat this as a single-turn stateless request. Do not use or infer any prior Ask conversation.
    Only create a rule if the current user message clearly asks for future monitoring behavior.
    If the current message is asking a question, chatting, or unclear, return:
    {"type":"not_rule","message":"这不像一条明确的监控规则。请描述要持续监控的条件和 TTS 提醒内容。"}

    User guidance:
    $guidance

    Non-overridable constraints:
    - Return JSON only.
    - Supported executable action is tts only.
    - Never create notification or agent actions.
    - For a rule, return exactly: {"type":"rule","rule":{"name":"...","triggerCondition":"...","action":{"type":"tts","content":"..."}}}
    - For non-rule input, return exactly: {"type":"not_rule","message":"..."}

    User rule: $userText
    """.trimIndent()

  fun buildAskPrompt(
    userText: String,
    frameCount: Int,
    lookbackSeconds: Int,
    guidance: String,
    askHistory: List<AskTurn>,
  ): String {
    val hasFrame = frameCount > 0
    val historyText =
      if (askHistory.isEmpty()) {
        "No prior ordinary Ask conversation."
      } else {
        askHistory.joinToString("\n") { "User: ${it.user}\nAssistant: ${it.assistant}" }
      }
    return """
      You are the ordinary visual/chat assistant inside the Smart Surveillance page.
      This is a fresh ordinary Ask request. Ignore any prior rule-creation or monitoring-analysis JSON instructions.
      Answer the user's question naturally. Do not create, modify, delete, or persist surveillance rules.
      Only mention rule creation or /rule if the user clearly asks to create future monitoring behavior.
      ${if (hasFrame) "${frameCount} sampled camera frame(s) are attached, ordered oldest to newest from about the last ${lookbackSeconds} seconds; use them when relevant." else "No current camera frame is available; answer text-only and mention that no frame is available if the question needs vision."}

      User guidance:
      $guidance

      Prior ordinary Ask conversation:
      $historyText

      Non-overridable constraints:
      - Normal Ask must not save rules.
      - Do not output rule JSON unless the user explicitly asks for JSON as content.
      - Do not follow monitoring-analysis JSON schemas.
      - Do not claim notification or agent execution.

      Current user question: $userText
      """.trimIndent()
  }

  fun buildAnalysisPrompt(
    rules: List<SurveillanceRuleEntity>,
    guidance: String,
    settings: SurveillanceRuntimeSettings = SurveillanceRuntimeSettings.defaults(),
  ): String =
    """
    Analyze these camera frames against active surveillance rules.
    Treat this as a single stateless analysis cycle. Do not use ordinary Ask messages or rule-creation conversation.
    The attached frames are sampled from one non-overlapping window, ordered oldest to newest, covering about ${settings.lookbackSeconds} seconds.
    There are up to ${settings.maxFramesPerRequest} frames attached separately; images are not embedded in this text prompt.
    Only emit events for clear trigger matches in the provided frames.
    Do not trigger just because a rule exists.
    If there is no clear event, return {"events":[]}.
    Only use action.type "tts".
    Prefer no event when confidence is low.
    Return exactly one JSON object. No markdown. No prose. No code fence.
    If unsure, return {"events":[]} rather than explanation.

    Rule attribution:
    - Set "ruleId" to the exact short id of the matched rule from the Active rules list below (for example "r1", "r2").
    - Copy the short id verbatim. Do not invent ids and do not use the rule name as the id.

    User guidance:
    $guidance

    Required JSON schema:
    {"events":[{"ruleId":"short id copied from active rules, e.g. r1","ruleName":"name copied from active rules","confidence":0.0,"reason":"one short sentence explaining visible evidence","action":{"type":"tts","content":"speech"}}]}

    Example with no match:
    {"events":[]}

    Example with match:
    {"events":[{"ruleId":"r1","ruleName":"Door watcher","confidence":0.82,"reason":"A person is standing next to the door.","action":{"type":"tts","content":"Someone is at the door"}}]}

    Active rules:
    ${rules.mapIndexed { index, rule -> "{\"ruleId\":\"${SurveillanceRuleParser.analysisAlias(index)}\",\"ruleName\":\"${rule.name}\",\"triggerCondition\":${rule.triggerJson},\"ttsContent\":\"${rule.actionContent}\"}" }.joinToString("\n")}
    """.trimIndent()

  fun buildAnalysisPromptPreview(
    rules: List<SurveillanceRuleEntity>,
    guidance: String,
    settings: SurveillanceRuntimeSettings,
  ): String =
    """
    Realtime analysis prompt preview
    Frame sampling FPS: ${settings.frameSamplingFps}
    Lookback seconds: ${settings.lookbackSeconds}
    Frames attached separately: ${settings.maxFramesPerRequest}
    Active rules: ${rules.size}

    ${buildAnalysisPrompt(rules = rules, guidance = guidance, settings = settings)}
    """.trimIndent()
}
