package com.google.ai.edge.gallery.customtasks.smartsurveillance

import org.junit.Assert.assertEquals
import org.junit.Test

class SurveillanceRuntimeSettingsTest {
  @Test
  fun sanitizeSettings_usesDefaultsForInvalidValues() {
    val settings =
      SurveillanceRuntimeSettings.sanitize(
        frameSamplingFps = 99f,
        lookbackSeconds = 0,
      )

    assertEquals(1f, settings.frameSamplingFps)
    assertEquals(4, settings.lookbackSeconds)
    assertEquals(4, settings.maxFramesPerRequest)
  }

  @Test
  fun maxFramesPerRequest_isDerivedFromFpsAndLookbackAndCappedAtThirty() {
    val settings =
      SurveillanceRuntimeSettings.sanitize(
        frameSamplingFps = 2f,
        lookbackSeconds = 20,
      )

    assertEquals(2f, settings.frameSamplingFps)
    assertEquals(20, settings.lookbackSeconds)
    assertEquals(30, settings.maxFramesPerRequest)
  }

  @Test
  fun maxFramesPerRequest_usesCeilForFractionalFps() {
    val settings =
      SurveillanceRuntimeSettings.sanitize(
        frameSamplingFps = 0.5f,
        lookbackSeconds = 5,
      )

    assertEquals(3, settings.maxFramesPerRequest)
  }

  @Test
  fun lookbackWindowDurationReplacesIndependentAnalysisInterval() {
    val settings =
      SurveillanceRuntimeSettings.sanitize(
        frameSamplingFps = 1f,
        lookbackSeconds = 2,
      )

    assertEquals(3, settings.lookbackSeconds)
    assertEquals(3_000L, settings.lookbackWindowMs)
    assertEquals(1_000L, settings.frameSamplingIntervalMs)
  }
}
