/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.graphics.scale

object VideoFrameExtractor {
  fun extractFrames(context: Context, uri: Uri, fps: Float): List<Bitmap> {
    val retriever = MediaMetadataRetriever()
    return try {
      retriever.setDataSource(context, uri)
      val durationMs =
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
      val sampleTimes = VideoFrameSampler.sampleTimesUs(durationUs = durationMs * 1000L, fps = fps)
      sampleTimes.mapNotNull { timeUs ->
        retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.fitWithin()
      }
    } finally {
      retriever.release()
    }
  }

  private fun Bitmap.fitWithin(maxSide: Int = 1024): Bitmap {
    if (width <= maxSide && height <= maxSide) return this
    val ratio = width.toFloat() / height.toFloat()
    val newWidth: Int
    val newHeight: Int
    if (ratio > 1f) {
      newWidth = maxSide
      newHeight = (maxSide / ratio).toInt()
    } else {
      newHeight = maxSide
      newWidth = (maxSide * ratio).toInt()
    }
    return scale(newWidth, newHeight)
  }
}
