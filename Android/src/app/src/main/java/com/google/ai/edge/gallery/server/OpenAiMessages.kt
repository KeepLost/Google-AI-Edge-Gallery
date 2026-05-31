/*
 * Copyright 2026 Google LLC
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

package com.google.ai.edge.gallery.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure helpers that interpret OpenAI chat messages. Kept free of Android dependencies so they can be
 * unit-tested on the JVM.
 */
object OpenAiMessages {
  /** An image referenced by a message, as a raw `image_url` string (data URI or remote URL). */
  data class ImageRef(val url: String)

  /** Returns the plain text of a single message's `content` field (string or content-part array). */
  fun extractText(content: JsonElement?): String {
    if (content == null) return ""
    return when (content) {
      is JsonPrimitive -> content.contentOrNull ?: ""
      is JsonArray -> {
        content
          .mapNotNull { part ->
            val obj = part as? JsonObject ?: return@mapNotNull null
            val type = obj["type"]?.jsonPrimitive?.contentOrNull
            if (type == "text" || (type == null && obj.containsKey("text"))) {
              obj["text"]?.jsonPrimitive?.contentOrNull
            } else {
              null
            }
          }
          .joinToString("\n")
      }
      else -> ""
    }
  }

  /** Extracts all `image_url` values from a single message's content-part array. */
  fun extractImageUrls(content: JsonElement?): List<ImageRef> {
    val array = content as? JsonArray ?: return emptyList()
    return array.mapNotNull { part ->
      val obj = part as? JsonObject ?: return@mapNotNull null
      if (obj["type"]?.jsonPrimitive?.contentOrNull != "image_url") return@mapNotNull null
      // image_url can be {"url": "..."} or a bare string.
      val urlField = obj["image_url"]
      val url =
        when (urlField) {
          is JsonObject -> urlField["url"]?.jsonPrimitive?.contentOrNull
          is JsonPrimitive -> urlField.contentOrNull
          else -> null
        }
      url?.let { ImageRef(it) }
    }
  }

  /**
   * Flattens a list of chat messages into a single prompt string for the runtime, and separates the
   * system instruction (if any). The runtime is single-turn-prompt oriented, so we render roles as
   * labeled turns. System messages are concatenated and returned separately so callers can pass them
   * as the conversation system instruction.
   */
  data class FlattenedPrompt(val systemInstruction: String, val prompt: String)

  fun flatten(messages: List<ChatMessage>): FlattenedPrompt {
    val systemParts = mutableListOf<String>()
    val turns = mutableListOf<String>()
    for (message in messages) {
      val text = extractText(message.content)
      when (message.role.lowercase()) {
        "system" -> if (text.isNotBlank()) systemParts.add(text)
        "assistant" -> turns.add("Assistant: $text")
        "user" -> turns.add("User: $text")
        else -> if (text.isNotBlank()) turns.add("${message.role}: $text")
      }
    }
    // If the conversation ends with a user turn, append an "Assistant:" cue so the model continues.
    val prompt =
      if (turns.isEmpty()) {
        ""
      } else if (messages.lastOrNull()?.role?.lowercase() == "user" && turns.size > 1) {
        turns.joinToString("\n") + "\nAssistant:"
      } else if (turns.size == 1 && messages.firstOrNull { it.role.lowercase() == "user" } != null) {
        // Single user turn: send the raw text to keep behavior close to the in-app chat.
        extractText(messages.first { it.role.lowercase() == "user" }.content)
      } else {
        turns.joinToString("\n")
      }
    return FlattenedPrompt(systemInstruction = systemParts.joinToString("\n"), prompt = prompt)
  }

  /** Collects every image reference across all messages, in order. */
  fun allImages(messages: List<ChatMessage>): List<ImageRef> =
    messages.flatMap { extractImageUrls(it.content) }
}
