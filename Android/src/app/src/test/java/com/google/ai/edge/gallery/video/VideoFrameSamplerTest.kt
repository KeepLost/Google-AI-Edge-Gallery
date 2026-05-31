package com.google.ai.edge.gallery.video

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoFrameSamplerTest {
  @Test
  fun sampleTimesFor_shortVideoAtOneFps_returnsOneFramePerSecond() {
    val samples = VideoFrameSampler.sampleTimesUs(durationUs = 10_000_000L, fps = 1f)

    assertEquals(10, samples.size)
    assertEquals(0L, samples.first())
    assertEquals(9_000_000L, samples.last())
  }

  @Test
  fun sampleTimesFor_longVideoCapsAtThirtyFramesUniformly() {
    val samples = VideoFrameSampler.sampleTimesUs(durationUs = 40_000_000L, fps = 1f)

    assertEquals(30, samples.size)
    assertEquals(0L, samples.first())
    assertEquals(40_000_000L, samples.last())
    assertEquals(true, samples.zipWithNext().all { it.second > it.first })
  }

  @Test
  fun sampleTimesFor_halfFpsReturnsEveryTwoSeconds() {
    val samples = VideoFrameSampler.sampleTimesUs(durationUs = 6_000_000L, fps = 0.5f)

    assertEquals(listOf(0L, 2_000_000L, 4_000_000L), samples)
  }
}
