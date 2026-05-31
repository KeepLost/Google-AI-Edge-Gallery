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

class OpenAiMessagesTest {
  private fun msg(role: String, content: String): ChatMessage =
    ChatMessage(role = role, content = ServerJson.parseToJsonElement("\"$content\""))

  private fun multimodalMsg(role: String, text: String, imageUrl: String): ChatMessage {
    val json =
      """
      [{"type":"text","text":"$text"},
       {"type":"image_url","image_url":{"url":"$imageUrl"}}]
      """
        .trimIndent()
    return ChatMessage(role = role, content = ServerJson.parseToJsonElement(json))
  }

  @Test
  fun extractText_fromPlainString() {
    assertEquals("hello", OpenAiMessages.extractText(ServerJson.parseToJsonElement("\"hello\"")))
  }

  @Test
  fun extractText_fromContentPartArray() {
    val msg = multimodalMsg("user", "describe this", "data:image/png;base64,AAAA")
    assertEquals("describe this", OpenAiMessages.extractText(msg.content))
  }

  @Test
  fun extractImageUrls_fromContentPartArray() {
    val msg = multimodalMsg("user", "describe", "data:image/png;base64,AAAA")
    val images = OpenAiMessages.extractImageUrls(msg.content)
    assertEquals(1, images.size)
    assertEquals("data:image/png;base64,AAAA", images.first().url)
  }

  @Test
  fun extractImageUrls_emptyForPlainString() {
    assertTrue(OpenAiMessages.extractImageUrls(ServerJson.parseToJsonElement("\"hi\"")).isEmpty())
  }

  @Test
  fun flatten_singleUserTurn_sendsRawText() {
    val result = OpenAiMessages.flatten(listOf(msg("user", "What is 2+2?")))
    assertEquals("", result.systemInstruction)
    assertEquals("What is 2+2?", result.prompt)
  }

  @Test
  fun flatten_separatesSystemInstruction() {
    val result =
      OpenAiMessages.flatten(
        listOf(msg("system", "You are concise."), msg("user", "Hi"))
      )
    assertEquals("You are concise.", result.systemInstruction)
    // Only one non-system turn => raw user text.
    assertEquals("Hi", result.prompt)
  }

  @Test
  fun flatten_multiTurnAppendsAssistantCue() {
    val result =
      OpenAiMessages.flatten(
        listOf(
          msg("user", "Hi"),
          msg("assistant", "Hello!"),
          msg("user", "How are you?"),
        )
      )
    assertTrue(result.prompt.contains("User: Hi"))
    assertTrue(result.prompt.contains("Assistant: Hello!"))
    assertTrue(result.prompt.trimEnd().endsWith("Assistant:"))
  }

  @Test
  fun allImages_collectsAcrossMessages() {
    val messages =
      listOf(
        multimodalMsg("user", "a", "data:image/png;base64,AAAA"),
        multimodalMsg("user", "b", "data:image/jpeg;base64,BBBB"),
      )
    assertEquals(2, OpenAiMessages.allImages(messages).size)
  }
}
