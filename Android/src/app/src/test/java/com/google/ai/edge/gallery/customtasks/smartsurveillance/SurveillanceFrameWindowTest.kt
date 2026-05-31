package com.google.ai.edge.gallery.customtasks.smartsurveillance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveillanceFrameWindowTest {
  @Test
  fun shouldAnalyzeWhenWindowDurationElapsedOrTargetFrameCountReached() {
    val settings = SurveillanceRuntimeSettings.sanitize(frameSamplingFps = 1f, lookbackSeconds = 4)
    val window = SurveillanceFrameWindow()

    window.reset(windowStartMs = 1_000L)

    assertFalse(window.shouldAnalyze(nowMs = 3_000L, settings = settings, acceptedFrameCount = 2))
    assertTrue(window.shouldAnalyze(nowMs = 5_000L, settings = settings, acceptedFrameCount = 2))
    assertTrue(window.shouldAnalyze(nowMs = 3_500L, settings = settings, acceptedFrameCount = 4))
  }

  @Test
  fun resetStartsANewNonOverlappingWindow() {
    val settings = SurveillanceRuntimeSettings.sanitize(frameSamplingFps = 1f, lookbackSeconds = 4)
    val window = SurveillanceFrameWindow()

    window.reset(windowStartMs = 1_000L)
    assertTrue(window.shouldAnalyze(nowMs = 5_000L, settings = settings, acceptedFrameCount = 1))

    window.reset(windowStartMs = 5_500L)
    assertFalse(window.shouldAnalyze(nowMs = 8_000L, settings = settings, acceptedFrameCount = 1))
    assertEquals(5_500L, window.windowStartMs)
  }
}
