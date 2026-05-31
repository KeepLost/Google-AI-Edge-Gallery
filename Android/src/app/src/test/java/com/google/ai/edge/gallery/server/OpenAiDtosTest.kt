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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiDtosTest {
  @Test
  fun chatCompletionRequest_deserializesSnakeCaseFields() {
    val json =
      """
      {"model":"gemma","messages":[{"role":"user","content":"hi"}],
       "temperature":0.7,"top_p":0.9,"max_tokens":128,"stream":true}
      """
        .trimIndent()
    val request = ServerJson.decodeFromString(ChatCompletionRequest.serializer(), json)
    assertEquals("gemma", request.model)
    assertEquals(1, request.messages.size)
    assertEquals(0.7f, request.temperature!!, 0.0001f)
    assertEquals(0.9f, request.topP!!, 0.0001f)
    assertEquals(128, request.maxTokens)
    assertTrue(request.stream)
  }

  @Test
  fun chatCompletionRequest_defaultsAreSafe() {
    val request = ServerJson.decodeFromString(ChatCompletionRequest.serializer(), """{"model":"m"}""")
    assertTrue(request.messages.isEmpty())
    assertNull(request.temperature)
    assertFalse(request.stream)
  }

  @Test
  fun chatCompletionResponse_serializesObjectField() {
    val response =
      ChatCompletionResponse(
        id = "chatcmpl-1",
        created = 100L,
        model = "gemma",
        choices = listOf(ChatChoice(message = ResponseMessage(content = "hello"))),
      )
    val json = ServerJson.encodeToString(ChatCompletionResponse.serializer(), response)
    assertTrue(json.contains("\"object\":\"chat.completion\""))
    assertTrue(json.contains("\"finish_reason\":\"stop\""))
    assertTrue(json.contains("\"content\":\"hello\""))
  }

  @Test
  fun modelsResponse_serializesAsList() {
    val response = ModelsResponse(data = listOf(ModelObject(id = "gemma")))
    val json = ServerJson.encodeToString(ModelsResponse.serializer(), response)
    assertTrue(json.contains("\"object\":\"list\""))
    assertTrue(json.contains("\"owned_by\":\"local\""))
    assertTrue(json.contains("\"id\":\"gemma\""))
  }
}
