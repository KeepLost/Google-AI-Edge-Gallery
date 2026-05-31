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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAuthTest {
  @Test
  fun parseBearer_extractsToken() {
    assertEquals("abc123", ServerAuth.parseBearer("Bearer abc123"))
    assertEquals("abc123", ServerAuth.parseBearer("bearer abc123"))
  }

  @Test
  fun parseBearer_nullForMalformed() {
    assertNull(ServerAuth.parseBearer(null))
    assertNull(ServerAuth.parseBearer(""))
    assertNull(ServerAuth.parseBearer("Token abc"))
    assertNull(ServerAuth.parseBearer("Bearer "))
  }

  @Test
  fun isAuthorized_trueOnExactMatch() {
    assertTrue(ServerAuth.isAuthorized("Bearer secret-token", "secret-token"))
  }

  @Test
  fun isAuthorized_falseOnMismatch() {
    assertFalse(ServerAuth.isAuthorized("Bearer wrong", "secret-token"))
  }

  @Test
  fun isAuthorized_falseWhenNoExpectedTokenConfigured() {
    assertFalse(ServerAuth.isAuthorized("Bearer anything", ""))
    assertFalse(ServerAuth.isAuthorized("Bearer anything", null))
  }

  @Test
  fun isAuthorized_falseWhenHeaderMissing() {
    assertFalse(ServerAuth.isAuthorized(null, "secret-token"))
  }

  @Test
  fun generateToken_hasPrefixAndIsRandom() {
    val a = ServerAuth.generateToken()
    val b = ServerAuth.generateToken()
    assertTrue(a.startsWith("sk-local-"))
    assertNotEquals(a, b)
  }
}
