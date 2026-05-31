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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageDataUriTest {
  // "AAAA" base64 decodes to 3 bytes.
  private val pngDataUri = "data:image/png;base64,AAAA"

  @Test
  fun parseDataUri_extractsMimeAndBytes() {
    val parsed = ImageDataUri.parseDataUri(pngDataUri)
    assertEquals("image/png", parsed.mimeType)
    assertEquals(3, parsed.bytes.size)
  }

  @Test
  fun parseDataUri_defaultsMimeWhenAbsent() {
    val parsed = ImageDataUri.parseDataUri("data:;base64,AAAA")
    assertEquals("image/*", parsed.mimeType)
  }

  @Test
  fun parseDataUri_rejectsRemoteUrl() {
    val ex =
      assertThrows(ImageDataUri.UnsupportedImageException::class.java) {
        ImageDataUri.parseDataUri("https://example.com/cat.png")
      }
    assertTrue(ex.message!!.contains("data URI"))
  }

  @Test
  fun parseDataUri_rejectsNonBase64() {
    assertThrows(ImageDataUri.UnsupportedImageException::class.java) {
      ImageDataUri.parseDataUri("data:image/png,nothex")
    }
  }

  @Test
  fun parseDataUri_rejectsMissingComma() {
    assertThrows(ImageDataUri.UnsupportedImageException::class.java) {
      ImageDataUri.parseDataUri("data:image/png;base64")
    }
  }
}

class SingleFlightGuardTest {
  @Test
  fun tryAcquire_succeedsOnceThenBlocks() {
    val guard = SingleFlightGuard()
    assertTrue(guard.tryAcquire())
    assertFalse(guard.tryAcquire())
    assertTrue(guard.isBusy)
    guard.release()
    assertFalse(guard.isBusy)
    assertTrue(guard.tryAcquire())
    guard.release()
  }

  @Test
  fun withSlotOrNull_returnsNullWhenBusy() {
    val guard = SingleFlightGuard()
    assertTrue(guard.tryAcquire())
    val result = guard.withSlotOrNull { "ran" }
    assertNull(result)
    guard.release()
  }

  @Test
  fun withSlotOrNull_runsAndReleasesWhenFree() {
    val guard = SingleFlightGuard()
    val result = guard.withSlotOrNull { "ran" }
    assertEquals("ran", result)
    assertFalse(guard.isBusy)
  }
}

class ServerConfigTest {
  @Test
  fun bindHost_loopbackByDefault() {
    assertEquals(BIND_LOOPBACK, ServerConfig().bindHost)
  }

  @Test
  fun bindHost_allWhenLanEnabled() {
    assertEquals(BIND_ALL, ServerConfig(lanEnabled = true).bindHost)
  }

  @Test
  fun hasToken_reflectsTokenPresence() {
    assertFalse(ServerConfig().hasToken())
    assertTrue(ServerConfig(token = "x").hasToken())
  }
}
