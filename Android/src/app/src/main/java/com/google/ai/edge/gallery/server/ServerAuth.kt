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

import java.security.SecureRandom

/** Bearer-token auth helpers. Pure logic, unit-testable. */
object ServerAuth {
  private const val TOKEN_BYTES = 24
  private val ALPHABET = ('A'..'Z') + ('a'..'z') + ('0'..'9')

  /** Generates a random URL-safe bearer token. */
  fun generateToken(random: SecureRandom = SecureRandom()): String {
    val sb = StringBuilder(TOKEN_BYTES)
    repeat(TOKEN_BYTES) { sb.append(ALPHABET[random.nextInt(ALPHABET.size)]) }
    return "sk-local-" + sb.toString()
  }

  /**
   * Extracts the bearer token from an Authorization header value, or null if it is missing/malformed.
   */
  fun parseBearer(authorizationHeader: String?): String? {
    if (authorizationHeader == null) return null
    val trimmed = authorizationHeader.trim()
    val prefix = "Bearer "
    if (!trimmed.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) return null
    val token = trimmed.substring(prefix.length).trim()
    return token.ifEmpty { null }
  }

  /**
   * Constant-time comparison of the presented Authorization header against the expected token.
   * Returns true only if a non-blank expected token is configured and the header carries exactly it.
   */
  fun isAuthorized(authorizationHeader: String?, expectedToken: String?): Boolean {
    if (expectedToken.isNullOrBlank()) return false
    val presented = parseBearer(authorizationHeader) ?: return false
    return constantTimeEquals(presented, expectedToken)
  }

  private fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var result = 0
    for (i in a.indices) {
      result = result or (a[i].code xor b[i].code)
    }
    return result == 0
  }
}
