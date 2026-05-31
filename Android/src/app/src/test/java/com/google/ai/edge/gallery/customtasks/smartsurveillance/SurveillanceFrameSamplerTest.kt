package com.google.ai.edge.gallery.customtasks.smartsurveillance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveillanceFrameSamplerTest {
  @Test
  fun shouldAcceptFrame_acceptsFirstFrameAndThenHonorsSamplingInterval() {
    val sampler = SurveillanceFrameSampler()
    val settings = SurveillanceRuntimeSettings.sanitize(frameSamplingFps = 1f, lookbackSeconds = 4)

    assertTrue(sampler.shouldAcceptFrame(nowMs = 1_000L, settings = settings))
    assertFalse(sampler.shouldAcceptFrame(nowMs = 1_500L, settings = settings))
    assertTrue(sampler.shouldAcceptFrame(nowMs = 2_000L, settings = settings))
  }

  @Test
  fun shouldAcceptFrame_acceptsImmediatelyAfterReset() {
    val sampler = SurveillanceFrameSampler()
    val settings = SurveillanceRuntimeSettings.sanitize(frameSamplingFps = 1f, lookbackSeconds = 4)

    assertTrue(sampler.shouldAcceptFrame(nowMs = 1_000L, settings = settings))
    assertFalse(sampler.shouldAcceptFrame(nowMs = 1_500L, settings = settings))
    sampler.reset()
    assertTrue(sampler.shouldAcceptFrame(nowMs = 1_500L, settings = settings))
  }
}
