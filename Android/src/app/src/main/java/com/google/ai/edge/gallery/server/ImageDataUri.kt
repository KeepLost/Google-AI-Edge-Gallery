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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.Base64

/**
 * Decodes OpenAI `image_url` values. Only base64 `data:` URIs are supported for the first pass;
 * remote `http(s)` URLs are explicitly rejected so callers can return a clear error.
 *
 * The data-URI parsing is split into a pure step ([parseDataUri]) for unit testing and an
 * Android-only decode step ([decodeBitmap]).
 */
object ImageDataUri {
  /** Parsed representation of a base64 data URI. */
  data class Parsed(val mimeType: String, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is Parsed) return false
      return mimeType == other.mimeType && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * mimeType.hashCode() + bytes.contentHashCode()
  }

  class UnsupportedImageException(message: String) : IllegalArgumentException(message)

  /**
   * Parses a `data:<mime>;base64,<payload>` URI into its mime type and decoded bytes.
   *
   * @throws UnsupportedImageException for non-data URIs (e.g. remote http URLs) or malformed input.
   */
  fun parseDataUri(url: String): Parsed {
    val trimmed = url.trim()
    if (!trimmed.startsWith("data:", ignoreCase = true)) {
      throw UnsupportedImageException(
        "Only base64 data URIs are supported for image_url; remote URLs are not fetched."
      )
    }
    val comma = trimmed.indexOf(',')
    if (comma < 0) throw UnsupportedImageException("Malformed data URI: missing comma separator.")
    val header = trimmed.substring("data:".length, comma)
    val payload = trimmed.substring(comma + 1)
    if (!header.contains("base64", ignoreCase = true)) {
      throw UnsupportedImageException("Only base64-encoded data URIs are supported.")
    }
    val mimeType = header.substringBefore(';', missingDelimiterValue = "").ifBlank { "image/*" }
    val bytes =
      try {
        Base64.getMimeDecoder().decode(payload)
      } catch (e: IllegalArgumentException) {
        throw UnsupportedImageException("Invalid base64 image payload: ${e.message}")
      }
    if (bytes.isEmpty()) throw UnsupportedImageException("Empty image payload.")
    return Parsed(mimeType = mimeType, bytes = bytes)
  }

  /**
   * Decodes a base64 data URI into a [Bitmap].
   *
   * @throws UnsupportedImageException if parsing fails or the bytes are not a decodable image.
   */
  fun decodeBitmap(url: String): Bitmap {
    val parsed = parseDataUri(url)
    return BitmapFactory.decodeByteArray(parsed.bytes, 0, parsed.bytes.size)
      ?: throw UnsupportedImageException("Could not decode image bytes (mime=${parsed.mimeType}).")
  }
}
