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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Encodes the bound-model rules whose absence let the "0 Models" bug ship: when the downloaded model
 * set changes, the bound model must be re-validated (kept if still present, auto-bound/cleared
 * otherwise), and the server must never be startable without a resolvable bound model.
 */
class ServerModelSelectionTest {

  @Test
  fun resolveBoundModelName_emptyInventory_bindsNothing() {
    assertEquals("", ServerModelSelection.resolveBoundModelName(available = emptyList(), current = ""))
    assertEquals(
      "",
      ServerModelSelection.resolveBoundModelName(available = emptyList(), current = "gemma-2b"),
    )
  }

  @Test
  fun resolveBoundModelName_noCurrent_autoBindsFirst() {
    assertEquals(
      "gemma-2b",
      ServerModelSelection.resolveBoundModelName(
        available = listOf("gemma-2b", "gemma-7b"),
        current = "",
      ),
    )
  }

  @Test
  fun resolveBoundModelName_currentStillPresent_keepsIt() {
    assertEquals(
      "gemma-7b",
      ServerModelSelection.resolveBoundModelName(
        available = listOf("gemma-2b", "gemma-7b"),
        current = "gemma-7b",
      ),
    )
  }

  @Test
  fun resolveBoundModelName_currentRemoved_rebindsToFirst() {
    // Bound model was deleted elsewhere; rebind to the first remaining model.
    assertEquals(
      "gemma-2b",
      ServerModelSelection.resolveBoundModelName(
        available = listOf("gemma-2b", "gemma-7b"),
        current = "deleted-model",
      ),
    )
  }

  @Test
  fun canStart_falseWhenNoModels() {
    assertFalse(ServerModelSelection.canStart(available = emptyList(), boundModelName = ""))
    assertFalse(
      ServerModelSelection.canStart(available = emptyList(), boundModelName = "gemma-2b")
    )
  }

  @Test
  fun canStart_falseWhenBoundBlankOrMissing() {
    assertFalse(
      ServerModelSelection.canStart(available = listOf("gemma-2b"), boundModelName = "")
    )
    assertFalse(
      ServerModelSelection.canStart(available = listOf("gemma-2b"), boundModelName = "ghost")
    )
  }

  @Test
  fun canStart_trueWhenBoundModelPresent() {
    assertTrue(
      ServerModelSelection.canStart(
        available = listOf("gemma-2b", "gemma-7b"),
        boundModelName = "gemma-2b",
      )
    )
  }
}
