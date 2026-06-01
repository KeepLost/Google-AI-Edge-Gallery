package com.google.ai.edge.gallery.customtasks.smartsurveillance

import kotlin.math.ceil
import kotlin.math.roundToLong

data class SurveillanceRuntimeSettings(
  val frameSamplingFps: Float,
  val lookbackSeconds: Int,
  val minConfidence: Float = DEFAULT_MIN_CONFIDENCE,
) {
  val frameSamplingIntervalMs: Long
    get() = (1_000f / frameSamplingFps).roundToLong()

  val lookbackWindowMs: Long
    get() = lookbackSeconds * 1_000L

  val maxFramesPerRequest: Int
    get() = ceil(frameSamplingFps * lookbackSeconds).toInt().coerceIn(MIN_FRAME_CACHE, MAX_FRAME_CACHE)

  val analysisIntervalSeconds: Int
    get() = lookbackSeconds

  val analysisIntervalMs: Long
    get() = lookbackWindowMs

  val maxFrameCache: Int
    get() = maxFramesPerRequest

  companion object {
    const val DEFAULT_FRAME_SAMPLING_FPS = 1f
    const val DEFAULT_LOOKBACK_SECONDS = 4
    const val MIN_LOOKBACK_SECONDS = 3
    const val MAX_LOOKBACK_SECONDS = 60
    const val MIN_FRAME_CACHE = 1
    const val MAX_FRAME_CACHE = 30
    const val DEFAULT_MIN_CONFIDENCE = 0.5f
    val SUPPORTED_FRAME_SAMPLING_FPS = listOf(0.5f, 1f, 2f)
    val SUPPORTED_LOOKBACK_SECONDS = listOf(3, 4, 5, 10)
    val SUPPORTED_MIN_CONFIDENCE = listOf(0.3f, 0.5f, 0.7f, 0.9f)

    fun defaults(): SurveillanceRuntimeSettings =
      SurveillanceRuntimeSettings(
        frameSamplingFps = DEFAULT_FRAME_SAMPLING_FPS,
        lookbackSeconds = DEFAULT_LOOKBACK_SECONDS,
        minConfidence = DEFAULT_MIN_CONFIDENCE,
      )

    fun sanitize(
      frameSamplingFps: Float,
      lookbackSeconds: Int,
      minConfidence: Float = DEFAULT_MIN_CONFIDENCE,
    ): SurveillanceRuntimeSettings {
      val resolvedFrameSamplingFps =
        SUPPORTED_FRAME_SAMPLING_FPS.firstOrNull { it == frameSamplingFps } ?: DEFAULT_FRAME_SAMPLING_FPS
      val resolvedLookbackSeconds =
        if (lookbackSeconds <= 0) {
          DEFAULT_LOOKBACK_SECONDS
        } else {
          lookbackSeconds.coerceIn(MIN_LOOKBACK_SECONDS, MAX_LOOKBACK_SECONDS)
        }
      val resolvedMinConfidence =
        if (minConfidence.isNaN() || minConfidence <= 0f) DEFAULT_MIN_CONFIDENCE else minConfidence.coerceIn(0f, 1f)
      return SurveillanceRuntimeSettings(
        frameSamplingFps = resolvedFrameSamplingFps,
        lookbackSeconds = resolvedLookbackSeconds,
        minConfidence = resolvedMinConfidence,
      )
    }

    fun sanitize(
      frameSamplingFps: Float,
      analysisIntervalSeconds: Int,
      maxFrameCache: Int,
    ): SurveillanceRuntimeSettings = sanitize(frameSamplingFps = frameSamplingFps, lookbackSeconds = analysisIntervalSeconds)
  }
}

class SurveillanceFrameSampler {
  private var lastAcceptedAtMs: Long? = null

  fun shouldAcceptFrame(nowMs: Long, settings: SurveillanceRuntimeSettings): Boolean {
    val lastAcceptedAt = lastAcceptedAtMs
    if (lastAcceptedAt == null || nowMs - lastAcceptedAt >= settings.frameSamplingIntervalMs) {
      lastAcceptedAtMs = nowMs
      return true
    }
    return false
  }

  fun reset() {
    lastAcceptedAtMs = null
  }
}

class SurveillanceFrameWindow {
  var windowStartMs: Long = 0L
    private set

  fun reset(windowStartMs: Long) {
    this.windowStartMs = windowStartMs
  }

  fun shouldAnalyze(
    nowMs: Long,
    settings: SurveillanceRuntimeSettings,
    acceptedFrameCount: Int,
  ): Boolean =
    acceptedFrameCount >= settings.maxFramesPerRequest || nowMs - windowStartMs >= settings.lookbackWindowMs
}
