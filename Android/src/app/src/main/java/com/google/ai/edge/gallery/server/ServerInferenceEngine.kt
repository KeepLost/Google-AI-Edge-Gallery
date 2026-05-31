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

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.litertlm.Contents
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Bridges the OpenAI server to the existing Gallery runtime ([Model.runtimeHelper] →
 * `LlmChatModelHelper`). It does NOT introduce a parallel inference engine; it drives the exact same
 * LiteRT-LM helper the in-app chat uses.
 *
 * Responsibilities:
 * - lazily load (`initialize`) the bound model into memory;
 * - apply per-request sampler params (`temperature`/`top_p`) via `resetConversation`;
 * - run inference, surfacing incremental token deltas to a callback;
 * - support cancellation (client disconnect) via `stopResponse`.
 *
 * Concurrency is enforced by the caller via [SingleFlightGuard]; this class assumes it holds the slot
 * for the duration of a call.
 */
class ServerInferenceEngine(private val appContext: Context) {

  /** Result of a non-streaming run. */
  data class RunResult(val text: String)

  /** Ensures [model] is initialized in memory. Throws [ServerException] on failure. */
  private suspend fun ensureLoaded(model: Model, supportImage: Boolean, systemInstruction: String) {
    if (model.instance != null) return
    val error =
      suspendCancellableCoroutine<String> { cont ->
        model.runtimeHelper.initialize(
          context = appContext,
          model = model,
          taskId = SERVER_TASK_ID,
          supportImage = supportImage,
          supportAudio = false,
          systemInstruction = if (systemInstruction.isBlank()) null else Contents.of(systemInstruction),
          onDone = { err -> if (cont.isActive) cont.resume(err) },
        )
      }
    if (error.isNotEmpty()) {
      throw ServerException(500, "Failed to load model '${model.name}': $error")
    }
  }

  /**
   * Applies per-request sampler params by mutating the model config and resetting the conversation.
   * Only does work when a value is actually provided and differs, because a reset clears context.
   */
  private fun applySamplerParamsIfNeeded(
    model: Model,
    temperature: Float?,
    topP: Float?,
    supportImage: Boolean,
    systemInstruction: String,
  ) {
    if (temperature == null && topP == null) return
    val newConfig = model.configValues.toMutableMap()
    var changed = false
    if (temperature != null && newConfig[ConfigKeys.TEMPERATURE.label] != temperature) {
      newConfig[ConfigKeys.TEMPERATURE.label] = temperature
      changed = true
    }
    if (topP != null && newConfig[ConfigKeys.TOPP.label] != topP) {
      newConfig[ConfigKeys.TOPP.label] = topP
      changed = true
    }
    if (!changed) return
    model.configValues = newConfig
    model.runtimeHelper.resetConversation(
      model = model,
      supportImage = supportImage,
      supportAudio = false,
      systemInstruction = if (systemInstruction.isBlank()) null else Contents.of(systemInstruction),
    )
  }

  /**
   * Runs inference and streams incremental token deltas to [onDelta]. Returns the full text.
   * Cancellation of the coroutine (e.g. client disconnect) triggers `stopResponse`.
   */
  suspend fun run(
    model: Model,
    prompt: String,
    systemInstruction: String,
    images: List<Bitmap>,
    temperature: Float?,
    topP: Float?,
    onDelta: (String) -> Unit,
  ): RunResult {
    val supportImage = images.isNotEmpty() && model.llmSupportImage
    if (images.isNotEmpty() && !model.llmSupportImage) {
      throw ServerException(400, "Model '${model.name}' does not support image input.")
    }
    ensureLoaded(model, supportImage, systemInstruction)
    applySamplerParamsIfNeeded(model, temperature, topP, supportImage, systemInstruction)

    val builder = StringBuilder()
    return suspendCancellableCoroutine { cont ->
      cont.invokeOnCancellation {
        try {
          model.runtimeHelper.stopResponse(model)
        } catch (e: Exception) {
          Log.w(TAG, "stopResponse on cancellation failed: ${e.message}")
        }
      }
      try {
        model.runtimeHelper.runInference(
          model = model,
          input = prompt,
          resultListener = { partial, done, _ ->
            if (partial.isNotEmpty()) {
              builder.append(partial)
              if (cont.isActive) onDelta(partial)
            }
            if (done && cont.isActive) {
              cont.resume(RunResult(builder.toString()))
            }
          },
          cleanUpListener = {},
          onError = { message ->
            if (cont.isActive) cont.cancel(ServerException(500, message))
          },
          images = images,
        )
      } catch (e: Exception) {
        if (cont.isActive) cont.cancel(ServerException(500, e.message ?: "Inference error"))
      }
    }
  }

  /** Unloads the model from memory, freeing the engine. */
  fun unload(model: Model) {
    try {
      if (model.instance != null) {
        model.runtimeHelper.cleanUp(model = model) {}
      }
    } catch (e: Exception) {
      Log.w(TAG, "cleanUp failed: ${e.message}")
    }
  }

  companion object {
    private const val TAG = "AGServerInferenceEngine"
    const val SERVER_TASK_ID = "openai_server"
  }
}

/** Carries an HTTP status code and message for OpenAI-style error responses. */
class ServerException(val statusCode: Int, override val message: String) : Exception(message)
