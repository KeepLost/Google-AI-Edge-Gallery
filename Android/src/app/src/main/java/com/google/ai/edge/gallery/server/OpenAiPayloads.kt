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

/**
 * Builds OpenAI-compatible payloads (non-streaming objects and SSE chunk lines). Kept pure for unit
 * testing. SSE framing follows the OpenAI convention: each event is `data: <json>\n\n`, and the
 * stream terminates with `data: [DONE]\n\n`.
 */
object OpenAiPayloads {
  const val DONE_LINE = "data: [DONE]\n\n"

  fun chatCompletionId(): String = "chatcmpl-" + java.util.UUID.randomUUID().toString().replace("-", "")

  fun completionId(): String = "cmpl-" + java.util.UUID.randomUUID().toString().replace("-", "")

  /** Rough whitespace-based token estimate; LiteRT-LM does not expose exact counts. */
  fun estimateTokens(text: String): Int {
    if (text.isBlank()) return 0
    return text.trim().split(Regex("\\s+")).size
  }

  fun nonStreamingChat(
    id: String,
    created: Long,
    model: String,
    content: String,
    promptText: String,
  ): ChatCompletionResponse {
    val promptTokens = estimateTokens(promptText)
    val completionTokens = estimateTokens(content)
    return ChatCompletionResponse(
      id = id,
      created = created,
      model = model,
      choices = listOf(ChatChoice(index = 0, message = ResponseMessage(content = content))),
      usage =
        Usage(
          promptTokens = promptTokens,
          completionTokens = completionTokens,
          totalTokens = promptTokens + completionTokens,
        ),
    )
  }

  /** The initial role chunk that OpenAI emits before any content delta. */
  fun roleChunkLine(id: String, created: Long, model: String): String {
    val chunk =
      ChatCompletionChunk(
        id = id,
        created = created,
        model = model,
        choices = listOf(ChatChunkChoice(index = 0, delta = Delta(role = "assistant"))),
      )
    return sseLine(ServerJson.encodeToString(ChatCompletionChunk.serializer(), chunk))
  }

  /** A content delta chunk for one incremental token batch. */
  fun contentChunkLine(id: String, created: Long, model: String, delta: String): String {
    val chunk =
      ChatCompletionChunk(
        id = id,
        created = created,
        model = model,
        choices = listOf(ChatChunkChoice(index = 0, delta = Delta(content = delta))),
      )
    return sseLine(ServerJson.encodeToString(ChatCompletionChunk.serializer(), chunk))
  }

  /** The terminal chunk carrying finish_reason, followed separately by [DONE]. */
  fun finishChunkLine(
    id: String,
    created: Long,
    model: String,
    finishReason: String = "stop",
  ): String {
    val chunk =
      ChatCompletionChunk(
        id = id,
        created = created,
        model = model,
        choices =
          listOf(ChatChunkChoice(index = 0, delta = Delta(), finishReason = finishReason)),
      )
    return sseLine(ServerJson.encodeToString(ChatCompletionChunk.serializer(), chunk))
  }

  fun sseLine(jsonPayload: String): String = "data: $jsonPayload\n\n"

  fun errorJson(message: String, type: String = "invalid_request_error"): String =
    ServerJson.encodeToString(
      ApiErrorResponse.serializer(),
      ApiErrorResponse(ApiError(message = message, type = type)),
    )
}
