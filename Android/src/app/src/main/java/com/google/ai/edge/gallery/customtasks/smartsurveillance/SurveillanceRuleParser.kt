/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.customtasks.smartsurveillance

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID

data class ParsedSurveillanceRule(
  val id: String,
  val name: String,
  val rawPrompt: String,
  val triggerJson: String,
  val actionJson: String,
  val actionType: String,
  val actionContent: String,
  val active: Boolean,
  val createdAt: Long,
  val updatedAt: Long,
)

data class SurveillanceEvent(
  val ruleId: String,
  val ruleName: String?,
  val message: String,
  val reason: String,
  val confidence: Float,
  val timestampMs: Long,
)

sealed interface RuleCreationParseResult {
  data class Rule(val rule: ParsedSurveillanceRule) : RuleCreationParseResult

  data class NotRule(val message: String) : RuleCreationParseResult

  data class Invalid(val message: String) : RuleCreationParseResult
}

sealed interface AnalysisParseResult {
  data class Events(val events: List<SurveillanceEvent>) : AnalysisParseResult

  data object NoEvents : AnalysisParseResult

  data class Invalid(
    val message: String,
    val rawPreview: String? = null,
    val extractedJsonPreview: String? = null,
    val candidateSummary: String? = null,
  ) : AnalysisParseResult
}

object SurveillanceRuleParser {
  private val gson = Gson()

  fun parseRuleCreationResponse(
    rawPrompt: String,
    response: String,
    nowMs: Long = System.currentTimeMillis(),
  ): RuleCreationParseResult {
    val json = extractJson(response) ?: return RuleCreationParseResult.Invalid("Gemma did not return JSON.")
    val root =
      runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
        ?: return RuleCreationParseResult.Invalid("Gemma returned invalid JSON.")
    return when (root.getString("type")?.lowercase()) {
      "rule" -> {
        val ruleObject = runCatching { root.getAsJsonObject("rule") }.getOrNull()
        if (ruleObject == null) {
          RuleCreationParseResult.Invalid("Rule JSON is missing the rule object.")
        } else {
          parseRuleObject(rawPrompt = rawPrompt, root = ruleObject, nowMs = nowMs)?.let {
            RuleCreationParseResult.Rule(it)
          } ?: RuleCreationParseResult.Invalid("Rule JSON must use a TTS action with content.")
        }
      }
      "not_rule" ->
        RuleCreationParseResult.NotRule(
          root.getString("message") ?: "这不像一条明确的监控规则。请切换到新增规则或使用 /rule。"
        )
      else -> RuleCreationParseResult.Invalid("Rule response must include type=rule or type=not_rule.")
    }
  }

  fun parseRuleJson(
    rawPrompt: String,
    response: String,
    nowMs: Long = System.currentTimeMillis(),
  ): ParsedSurveillanceRule? {
    val json = extractJson(response) ?: return null
    val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return null
    return parseRuleObject(rawPrompt = rawPrompt, root = root, nowMs = nowMs)
  }

  private fun parseRuleObject(
    rawPrompt: String,
    root: JsonObject,
    nowMs: Long,
  ): ParsedSurveillanceRule? {
    val action = runCatching { root.getAsJsonObject("action") }.getOrNull() ?: return null
    val actionType = action.getString("type")?.lowercase() ?: return null
    if (actionType != "tts") return null
    val actionContent = action.getString("content") ?: return null
    val name = root.getString("name") ?: rawPrompt.take(40).ifBlank { "Surveillance rule" }
    val triggerElement = root.get("triggerCondition") ?: root.get("trigger") ?: return null
    val triggerJson = if (triggerElement.isJsonPrimitive) gson.toJson(mapOf("condition" to triggerElement.asString)) else triggerElement.toString()
    val id = root.getString("id") ?: UUID.randomUUID().toString()

    return ParsedSurveillanceRule(
      id = id,
      name = name,
      rawPrompt = rawPrompt,
      triggerJson = triggerJson,
      actionJson = action.toString(),
      actionType = actionType,
      actionContent = actionContent,
      active = true,
      createdAt = nowMs,
      updatedAt = nowMs,
    )
  }

  fun parseAnalysisJson(
    response: String,
    nowMs: Long = System.currentTimeMillis(),
    validRuleIds: Set<String> = emptySet(),
  ): List<SurveillanceEvent> {
    val result = parseAnalysisResponse(response = response, nowMs = nowMs, validRuleIds = validRuleIds)
    return if (result is AnalysisParseResult.Events) result.events else emptyList()
  }

  /**
   * Stable per-cycle view of an active rule used for robust analysis attribution.
   *
   * [alias] is the short, model-friendly id (for example `r1`) injected into the analysis prompt;
   * [id] is the stored Room UUID that events must ultimately carry. Callers MUST build this list in
   * the same order they pass to [SurveillancePromptBuilder.buildAnalysisPrompt] so aliases line up.
   */
  data class AnalysisRule(
    val id: String,
    val alias: String,
    val name: String?,
  )

  /** Single source of truth for the short prompt alias of the rule at [index] in the active list. */
  fun analysisAlias(index: Int): String = "r${index + 1}"

  private sealed interface RuleResolution {
    data class Resolved(val ruleId: String, val ruleName: String?) : RuleResolution

    data class Rejected(val reason: String) : RuleResolution
  }

  /**
   * Legacy/strict overload. An event id must be present and (when [validRuleIds] is non-empty) must
   * match a known id exactly, otherwise the event is dropped. Behavior is intentionally unchanged so
   * existing callers/tests keep their exact semantics.
   */
  fun parseAnalysisResponse(
    response: String,
    nowMs: Long = System.currentTimeMillis(),
    validRuleIds: Set<String> = emptySet(),
  ): AnalysisParseResult =
    parseAnalysisCore(response = response, nowMs = nowMs, modeLabel = "strict(validIds=${validRuleIds.size})") { rawRuleId, _ ->
      when {
        rawRuleId == null -> RuleResolution.Rejected("Analysis event is missing ruleId.")
        validRuleIds.isNotEmpty() && rawRuleId !in validRuleIds ->
          RuleResolution.Rejected("Analysis event referenced an unknown rule id: $rawRuleId.")
        else -> RuleResolution.Resolved(rawRuleId, null)
      }
    }

  /**
   * Robust overload used by realtime monitoring. Resolves each event to a stored rule by exact id,
   * then short alias (`r1`), then unique rule name; and, only when exactly one rule is active, safely
   * attributes an otherwise-valid but unmatched/missing-id event to that single rule. Multi-rule
   * ambiguity is still rejected with a precise diagnostic. TTS-only/action-safety checks are
   * unchanged, and every accepted event carries the resolved stored UUID.
   */
  fun parseAnalysisResponse(
    response: String,
    rules: List<AnalysisRule>,
    nowMs: Long = System.currentTimeMillis(),
  ): AnalysisParseResult {
    val byId = rules.associateBy { it.id }
    val byAlias = rules.associateBy { it.alias.trim().lowercase() }
    val byName = rules.filter { !it.name.isNullOrBlank() }.groupBy { it.name!!.trim().lowercase() }
    return parseAnalysisCore(response = response, nowMs = nowMs, modeLabel = "robust(rules=${rules.size})") { rawRuleId, event ->
      resolveRobust(rawRuleId = rawRuleId, event = event, rules = rules, byId = byId, byAlias = byAlias, byName = byName)
    }
  }

  private fun resolveRobust(
    rawRuleId: String?,
    event: JsonObject,
    rules: List<AnalysisRule>,
    byId: Map<String, AnalysisRule>,
    byAlias: Map<String, AnalysisRule>,
    byName: Map<String, List<AnalysisRule>>,
  ): RuleResolution {
    val token = rawRuleId?.trim()?.takeIf { it.isNotEmpty() }
    if (token != null) {
      byId[token]?.let { return RuleResolution.Resolved(it.id, it.name) }
      byAlias[token.lowercase()]?.let { return RuleResolution.Resolved(it.id, it.name) }
      byName[token.lowercase()]?.singleOrNull()?.let { return RuleResolution.Resolved(it.id, it.name) }
    }
    val nameField = (event.getString("ruleName") ?: event.getString("rule_name"))?.trim()?.lowercase()
    if (nameField != null) {
      byName[nameField]?.singleOrNull()?.let { return RuleResolution.Resolved(it.id, it.name) }
    }
    // Safe single-rule attribution: only when exactly one rule can possibly own the event.
    if (rules.size == 1) return RuleResolution.Resolved(rules[0].id, rules[0].name)
    return if (token == null) {
      RuleResolution.Rejected("Analysis event is missing ruleId and cannot be safely attributed among ${rules.size} active rules.")
    } else {
      RuleResolution.Rejected("Analysis event referenced an unknown rule id: $token.")
    }
  }

  private fun parseAnalysisCore(
    response: String,
    nowMs: Long,
    modeLabel: String,
    resolver: (rawRuleId: String?, event: JsonObject) -> RuleResolution,
  ): AnalysisParseResult {
    logI("parseAnalysisResponse: rawLength=${response.length}, mode=$modeLabel, rawPreview=${response.logPreview()}")
    val extraction = extractJsonForAnalysis(response)
      ?: return AnalysisParseResult.Invalid("Gemma did not return extractable analysis JSON.", response.preview()).also {
        logW("parseAnalysisResponse: no extractable JSON")
      }
    logI(
      "parseAnalysisResponse: selectedType=${extraction.type}, score=${extraction.score}, candidates=${extraction.candidateCount}, extractedLength=${extraction.json.length}, extractedPreview=${extraction.json.logPreview()}, summary=${extraction.summary}",
    )
    val parsed = parseJsonPossiblyEncoded(extraction.json)
      ?: return AnalysisParseResult.Invalid("Gemma returned malformed analysis JSON.", response.preview(), extraction.json.preview(), extraction.summary).also {
        logW("parseAnalysisResponse: malformed selected JSON, summary=${extraction.summary}")
      }
    if (extraction.type == CandidateType.ACTION_OBJECT) {
      return AnalysisParseResult.Invalid(
        "Extracted JSON is a TTS action object, not an analysis event envelope.",
        response.preview(),
        parsed.toString().preview(),
        extraction.summary,
      ).also {
        logW("parseAnalysisResponse: selected action object instead of event envelope")
      }
    }
    val normalizedJson = parsed.toString()
    val events = parsed.toAnalysisEventsArray()
      ?: return AnalysisParseResult.Invalid("Extracted analysis JSON is missing an events array.", response.preview(), normalizedJson.preview(), extraction.summary).also {
        logW("parseAnalysisResponse: missing event envelope, selectedType=${extraction.type}, summary=${extraction.summary}")
      }
    if (events.size() == 0) {
      logI("parseAnalysisResponse: no events, selectedType=${extraction.type}")
      return AnalysisParseResult.NoEvents
    }

    val dropReasons = mutableListOf<String>()
    val parsedEvents = events.mapNotNull { element ->
      val event = runCatching { element.asJsonObject }.getOrNull() ?: run {
        dropReasons += "Analysis events array contained a non-object event."
        logW("parseAnalysisResponse: dropped non-object event")
        return@mapNotNull null
      }
      val action = runCatching { event.getAsJsonObject("action") }.getOrNull()
      val actionType = action?.getString("type")?.lowercase()
      val rawRuleId = event.getString("ruleId") ?: event.getString("rule_id")
      val content = action?.getString("content") ?: event.getString("message") ?: event.getString("content") ?: event.getString("tts")
      val resolved = when (val resolution = resolver(rawRuleId, event)) {
        is RuleResolution.Rejected -> {
          dropReasons += resolution.reason
          logW("parseAnalysisResponse: dropped event rawRuleId=${rawRuleId ?: "<none>"} reason=${resolution.reason}")
          return@mapNotNull null
        }
        is RuleResolution.Resolved -> resolution
      }
      val eventLabel = resolved.ruleId
      if (action != null && actionType != "tts") {
        dropReasons += "Analysis event for rule $eventLabel used unsupported action type: ${actionType ?: "missing"}."
        logW("parseAnalysisResponse: dropped event ruleId=$eventLabel unsupported action=$actionType")
        return@mapNotNull null
      }
      if (action == null && !event.hasAnyContentAlias()) {
        dropReasons += "Analysis event for rule $eventLabel is missing a TTS action or message."
        logW("parseAnalysisResponse: dropped event ruleId=$eventLabel missing action/message")
        return@mapNotNull null
      }
      if (content == null) {
        dropReasons += "Analysis event for rule $eventLabel is missing TTS content."
        logW("parseAnalysisResponse: dropped event ruleId=$eventLabel missing TTS content")
        return@mapNotNull null
      }
      SurveillanceEvent(
        ruleId = resolved.ruleId,
        ruleName = event.getString("ruleName") ?: event.getString("rule_name") ?: resolved.ruleName,
        message = content,
        reason = (event.getString("reason") ?: event.getString("observedEvidence") ?: DEFAULT_EVENT_REASON).take(MAX_REASON_CHARS),
        confidence = event.getFloat("confidence") ?: 0f,
        timestampMs = nowMs,
      )
    }
    return when {
      parsedEvents.isNotEmpty() -> AnalysisParseResult.Events(parsedEvents).also {
        logI("parseAnalysisResponse: parsedEvents=${parsedEvents.size}, mode=$modeLabel")
      }
      dropReasons.isNotEmpty() -> AnalysisParseResult.Invalid(dropReasons.first(), response.preview(), normalizedJson.preview(), extraction.summary).also {
        logW("parseAnalysisResponse: all events filtered, firstReason=${dropReasons.first()}")
      }
      else -> AnalysisParseResult.NoEvents.also { logI("parseAnalysisResponse: no parsed events after processing") }
    }
  }

  private fun extractJson(text: String): String? {
    val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE).find(text)
    val candidate = fenced?.groupValues?.getOrNull(1) ?: text
    val trimmed = candidate.trim()
    if (runCatching { JsonParser.parseString(trimmed) }.isSuccess) return trimmed
    return jsonCandidates(candidate).firstOrNull { runCatching { JsonParser.parseString(it) }.isSuccess }
  }

  private fun extractJsonForAnalysis(text: String): JsonExtraction? = extractJsonCandidate(text)

  private fun extractJsonCandidate(text: String): JsonExtraction? {
    val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE).find(text)
    val candidate = fenced?.groupValues?.getOrNull(1) ?: text
    val trimmed = candidate.trim()
    val candidates = mutableListOf<JsonExtraction>()
    parseJsonPossiblyEncoded(trimmed)?.let { candidates += JsonExtraction(json = trimmed, type = it.analysisCandidateType(), candidateCount = 0, allTypes = emptyList()) }
    jsonCandidates(candidate).forEach { json ->
      parseJsonPossiblyEncoded(json)?.let { candidates += JsonExtraction(json = json, type = it.analysisCandidateType(), candidateCount = 0, allTypes = emptyList()) }
    }
    val distinct = candidates.distinctBy { it.json }
    if (distinct.isEmpty()) return null
    val allTypes = distinct.groupingBy { it.type }.eachCount().map { (type, count) -> "${type.label}=$count" }
    val selected = distinct
      .filter { it.type != CandidateType.OTHER_OBJECT || distinct.none { candidate -> candidate.type.isAnalysisShape } }
      .minWithOrNull(compareBy<JsonExtraction> { it.score }.thenByDescending { it.json.length })
      ?: distinct.minWithOrNull(compareBy<JsonExtraction> { it.score }.thenByDescending { it.json.length })
      ?: return null
    return selected.copy(candidateCount = distinct.size, allTypes = allTypes)
  }

  private fun jsonCandidates(text: String): List<String> {
    val candidates = mutableListOf<String>()
    listOf('{' to '}', '[' to ']').forEach { (open, close) ->
      text.forEachIndexed { index, char ->
        if (char == open) {
          for (end in text.length - 1 downTo index) {
            if (text[end] == close) candidates += text.substring(index, end + 1)
          }
        }
      }
    }
    return candidates.distinct()
  }

  private fun parseJsonPossiblyEncoded(json: String, depth: Int = 0): JsonElement? {
    val parsed = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return null
    if (depth >= MAX_ENCODED_JSON_DEPTH || !parsed.isJsonPrimitive || !parsed.asJsonPrimitive.isString) return parsed
    val inner = parsed.asString.trim()
    if (!inner.startsWith("{") && !inner.startsWith("[")) return parsed
    return parseJsonPossiblyEncoded(inner, depth + 1) ?: parsed
  }

  private fun JsonElement.analysisCandidateType(): CandidateType =
    when {
      isJsonObject && asJsonObject.has("events") -> CandidateType.EVENTS_OBJECT
      isJsonObject && asJsonObject.has("event") -> CandidateType.EVENT_OBJECT
      isJsonArray -> CandidateType.EVENT_ARRAY
      isJsonObject && (asJsonObject.getString("ruleId") != null || asJsonObject.getString("rule_id") != null) -> CandidateType.SINGLE_EVENT
      isJsonObject && asJsonObject.has("action") -> CandidateType.EVENT_LIKE_OBJECT
      isJsonObject && isActionObject(asJsonObject) -> CandidateType.ACTION_OBJECT
      isJsonObject -> CandidateType.OTHER_OBJECT
      else -> CandidateType.OTHER_JSON
    }

  private fun JsonElement.toAnalysisEventsArray() =
    when {
      isJsonArray -> asJsonArray
      isJsonObject -> {
        val root = asJsonObject
        runCatching { root.getAsJsonArray("events") }.getOrNull()
          ?: if (root.has("event")) runCatching { JsonArray().apply { add(root.getAsJsonObject("event")) } }.getOrNull() else null
          ?: if (root.getString("ruleId") != null || root.getString("rule_id") != null || root.has("action")) JsonArray().apply { add(root) } else null
      }
      else -> null
    }

  private fun JsonObject.getString(name: String): String? =
    get(name)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }

  private fun JsonObject.getFloat(name: String): Float? =
    runCatching { get(name)?.takeIf { !it.isJsonNull }?.asFloat }.getOrNull()

  private fun JsonObject.hasAnyContentAlias(): Boolean =
    getString("message") != null || getString("content") != null || getString("tts") != null

  private fun isActionObject(root: JsonObject): Boolean =
    root.getString("type") != null && (root.getString("content") != null || root.getString("message") != null) && !root.has("ruleId") && !root.has("rule_id")

  private fun String.preview(maxChars: Int = 300): String = trim().take(maxChars)

  private fun String.logPreview(maxChars: Int = 800): String = trim().replace(Regex("\\s+"), " ").take(maxChars)

  private fun logI(message: String) {
    runCatching { Log.i(PARSER_TAG, message) }
  }

  private fun logW(message: String) {
    runCatching { Log.w(PARSER_TAG, message) }
  }

  private data class JsonExtraction(
    val json: String,
    val type: CandidateType,
    val candidateCount: Int,
    val allTypes: List<String>,
  ) {
    val score: Int = type.score
    val summary: String = "count=$candidateCount selected=${type.label} score=$score types=${allTypes.joinToString(",")}"
  }

  private enum class CandidateType(val label: String, val score: Int, val isAnalysisShape: Boolean) {
    EVENTS_OBJECT("events_object", 0, true),
    EVENT_OBJECT("event_object", 1, true),
    EVENT_ARRAY("event_array", 2, true),
    SINGLE_EVENT("single_event", 3, true),
    EVENT_LIKE_OBJECT("event_like_object", 4, true),
    ACTION_OBJECT("action_object", 50, false),
    OTHER_OBJECT("other_object", 60, false),
    OTHER_JSON("other_json", 70, false),
  }

  private const val PARSER_TAG = "SurveillanceParser"
  private const val DEFAULT_EVENT_REASON = "Matched rule conditions in the analyzed frames."
  private const val MAX_REASON_CHARS = 160
  private const val MAX_ENCODED_JSON_DEPTH = 1
}
