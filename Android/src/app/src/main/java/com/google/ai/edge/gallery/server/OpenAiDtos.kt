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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Shared JSON codec for the OpenAI-compatible server. */
val ServerJson: Json = Json {
  ignoreUnknownKeys = true
  encodeDefaults = true
  explicitNulls = false
  isLenient = true
}

// ---------------------------------------------------------------------------
// Request DTOs
// ---------------------------------------------------------------------------

/**
 * A single chat message. [content] is intentionally a raw [JsonElement] because the OpenAI API
 * allows it to be either a plain string or an array of content parts (text + image_url). Use
 * [OpenAiMessages.extractText] / [OpenAiMessages.extractImageUrls] to interpret it.
 */
@Serializable
data class ChatMessage(val role: String = "user", val content: JsonElement? = null)

@Serializable
data class ChatCompletionRequest(
  val model: String = "",
  val messages: List<ChatMessage> = emptyList(),
  val temperature: Float? = null,
  @SerialName("top_p") val topP: Float? = null,
  @SerialName("max_tokens") val maxTokens: Int? = null,
  val stream: Boolean = false,
)

@Serializable
data class CompletionRequest(
  val model: String = "",
  val prompt: String = "",
  val temperature: Float? = null,
  @SerialName("top_p") val topP: Float? = null,
  @SerialName("max_tokens") val maxTokens: Int? = null,
  val stream: Boolean = false,
)

// ---------------------------------------------------------------------------
// Response DTOs
// ---------------------------------------------------------------------------

@Serializable
data class ModelObject(
  val id: String,
  @SerialName("object") val objectType: String = "model",
  val created: Long = 0L,
  @SerialName("owned_by") val ownedBy: String = "local",
)

@Serializable
data class ModelsResponse(
  @SerialName("object") val objectType: String = "list",
  val data: List<ModelObject> = emptyList(),
)

@Serializable
data class ResponseMessage(val role: String = "assistant", val content: String = "")

@Serializable
data class ChatChoice(
  val index: Int = 0,
  val message: ResponseMessage,
  @SerialName("finish_reason") val finishReason: String? = "stop",
)

@Serializable
data class Usage(
  @SerialName("prompt_tokens") val promptTokens: Int = 0,
  @SerialName("completion_tokens") val completionTokens: Int = 0,
  @SerialName("total_tokens") val totalTokens: Int = 0,
)

@Serializable
data class ChatCompletionResponse(
  val id: String,
  @SerialName("object") val objectType: String = "chat.completion",
  val created: Long,
  val model: String,
  val choices: List<ChatChoice> = emptyList(),
  val usage: Usage = Usage(),
)

@Serializable
data class Delta(val role: String? = null, val content: String? = null)

@Serializable
data class ChatChunkChoice(
  val index: Int = 0,
  val delta: Delta,
  @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ChatCompletionChunk(
  val id: String,
  @SerialName("object") val objectType: String = "chat.completion.chunk",
  val created: Long,
  val model: String,
  val choices: List<ChatChunkChoice> = emptyList(),
)

// Legacy /v1/completions response objects.
@Serializable
data class TextChoice(
  val index: Int = 0,
  val text: String = "",
  @SerialName("finish_reason") val finishReason: String? = "stop",
)

@Serializable
data class CompletionResponse(
  val id: String,
  @SerialName("object") val objectType: String = "text_completion",
  val created: Long,
  val model: String,
  val choices: List<TextChoice> = emptyList(),
  val usage: Usage = Usage(),
)

@Serializable data class ApiError(val message: String, val type: String = "invalid_request_error")

@Serializable data class ApiErrorResponse(val error: ApiError)

@Serializable
data class HealthResponse(
  val status: String,
  val running: Boolean,
  val model: String? = null,
  val busy: Boolean = false,
)
