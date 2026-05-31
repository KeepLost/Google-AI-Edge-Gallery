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
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiPayloadsTest {
  @Test
  fun sseLine_wrapsWithDataPrefixAndDoubleNewline() {
    assertEquals("data: {\"x\":1}\n\n", OpenAiPayloads.sseLine("{\"x\":1}"))
  }

  @Test
  fun doneLine_isOpenAiTerminal() {
    assertEquals("data: [DONE]\n\n", OpenAiPayloads.DONE_LINE)
  }

  @Test
  fun roleChunkLine_carriesAssistantRole() {
    val line = OpenAiPayloads.roleChunkLine("id1", 10L, "gemma")
    assertTrue(line.startsWith("data: "))
    assertTrue(line.endsWith("\n\n"))
    assertTrue(line.contains("\"object\":\"chat.completion.chunk\""))
    assertTrue(line.contains("\"role\":\"assistant\""))
  }

  @Test
  fun contentChunkLine_carriesDelta() {
    val line = OpenAiPayloads.contentChunkLine("id1", 10L, "gemma", "Hello")
    assertTrue(line.contains("\"content\":\"Hello\""))
    assertTrue(line.contains("\"id\":\"id1\""))
  }

  @Test
  fun finishChunkLine_carriesFinishReason() {
    val line = OpenAiPayloads.finishChunkLine("id1", 10L, "gemma")
    assertTrue(line.contains("\"finish_reason\":\"stop\""))
  }

  @Test
  fun nonStreamingChat_populatesUsageEstimate() {
    val response =
      OpenAiPayloads.nonStreamingChat(
        id = "chatcmpl-1",
        created = 5L,
        model = "gemma",
        content = "one two three",
        promptText = "a b",
      )
    assertEquals("gemma", response.model)
    assertEquals("one two three", response.choices.first().message.content)
    assertEquals(2, response.usage.promptTokens)
    assertEquals(3, response.usage.completionTokens)
    assertEquals(5, response.usage.totalTokens)
  }

  @Test
  fun estimateTokens_handlesBlank() {
    assertEquals(0, OpenAiPayloads.estimateTokens("   "))
    assertEquals(3, OpenAiPayloads.estimateTokens("a b c"))
  }

  @Test
  fun errorJson_isValidOpenAiError() {
    val json = OpenAiPayloads.errorJson("nope", type = "authentication_error")
    assertTrue(json.contains("\"message\":\"nope\""))
    assertTrue(json.contains("\"type\":\"authentication_error\""))
  }
}
