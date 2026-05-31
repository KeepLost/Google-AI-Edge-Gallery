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

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

object VideoFrameSampler {
  const val MAX_FRAMES = 30
  const val DEFAULT_FPS = 1f
  val SUPPORTED_FPS = listOf(0.5f, 1f, 2f)

  fun sampleTimesUs(durationUs: Long, fps: Float, maxFrames: Int = MAX_FRAMES): List<Long> {
    if (durationUs <= 0L || fps <= 0f || maxFrames <= 0) return emptyList()

    val requestedFrameCount = max(1, ceil(durationUs / 1_000_000.0 * fps).toInt())
    val frameCount = min(requestedFrameCount, maxFrames)
    if (frameCount == 1) return listOf(0L)

    return if (requestedFrameCount <= maxFrames) {
      val stepUs = (1_000_000.0 / fps).toLong()
      List(frameCount) { index -> min(durationUs, index * stepUs) }
    } else {
      List(frameCount) { index -> ((durationUs.toDouble() * index) / (frameCount - 1)).toLong() }
    }
  }
}
