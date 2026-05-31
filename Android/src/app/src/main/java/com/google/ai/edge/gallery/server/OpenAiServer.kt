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
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * Configures the Ktor application with the OpenAI-compatible routes. All request handling reuses the
 * shared [controller] (config/token/models) and the [engine] (runtime bridge). Auth is enforced on
 * every `/v1` route; localhost vs LAN binding is decided at engine start (see [ServerService]).
 */
fun Application.configureOpenAiServer(controller: ServerController, engine: ServerInferenceEngine) {
  install(ContentNegotiation) { json(ServerJson) }

  // CORS is disabled by default. We do NOT install a permissive CORS policy; cross-origin browser
  // calls are intentionally blocked. (Native clients / curl / OpenAI SDKs are unaffected.)
  install(CORS) {
    // Intentionally empty allowlist => browser cross-origin requests are rejected.
    allowHeader(HttpHeaders.Authorization)
    allowHeader(HttpHeaders.ContentType)
  }

  install(StatusPages) {
    exception<ServerException> { call, cause ->
      call.respondText(
        OpenAiPayloads.errorJson(cause.message),
        ContentType.Application.Json,
        HttpStatusCode.fromValue(cause.statusCode),
      )
    }
    exception<Throwable> { call, cause ->
      Log.e(TAG, "Unhandled server error", cause)
      call.respondText(
        OpenAiPayloads.errorJson(cause.message ?: "Internal server error", type = "server_error"),
        ContentType.Application.Json,
        HttpStatusCode.InternalServerError,
      )
    }
  }

  routing {
    get("/health") { call.respond(buildHealth(controller)) }
    get("/v1/health") { call.respond(buildHealth(controller)) }

    get("/v1/models") {
      if (!authorized(controller)) return@get respondUnauthorized()
      val created = System.currentTimeMillis() / 1000
      val models = controller.availableModelNames().map { ModelObject(id = it, created = created) }
      call.respond(ModelsResponse(data = models))
    }

    post("/v1/chat/completions") {
      if (!authorized(controller)) return@post respondUnauthorized()
      val request =
        parseBody(call.receiveText()) { ServerJson.decodeFromString(ChatCompletionRequest.serializer(), it) }
      handleChatCompletion(controller, engine, request)
    }

    post("/v1/completions") {
      if (!authorized(controller)) return@post respondUnauthorized()
      val request =
        parseBody(call.receiveText()) { ServerJson.decodeFromString(CompletionRequest.serializer(), it) }
      // Reuse the chat path by wrapping the prompt as a single user message.
      val chat =
        ChatCompletionRequest(
          model = request.model,
          messages = listOf(ChatMessage(role = "user", content = JsonPrimitive(request.prompt))),
          temperature = request.temperature,
          topP = request.topP,
          maxTokens = request.maxTokens,
          stream = request.stream,
        )
      handleChatCompletion(controller, engine, chat, legacyCompletion = true)
    }
  }
}

private const val TAG = "AGOpenAiServer"

private fun buildHealth(controller: ServerController): HealthResponse {
  val status = controller.status.value
  return HealthResponse(
    status = if (status.state == ServerState.RUNNING) "ok" else status.state.name.lowercase(),
    running = status.state == ServerState.RUNNING,
    model = status.config.boundModelName.ifBlank { null },
    busy = controller.singleFlight.isBusy,
  )
}

private fun RoutingContext.authorized(
  controller: ServerController
): Boolean {
  val header = call.request.headers[HttpHeaders.Authorization]
  return ServerAuth.isAuthorized(header, controller.status.value.config.token)
}

private suspend fun RoutingContext.respondUnauthorized() {
  call.respondText(
    OpenAiPayloads.errorJson("Missing or invalid bearer token.", type = "authentication_error"),
    ContentType.Application.Json,
    HttpStatusCode.Unauthorized,
  )
}

private inline fun <T> parseBody(body: String, decode: (String) -> T): T {
  return try {
    decode(body)
  } catch (e: Exception) {
    throw ServerException(400, "Invalid request body: ${e.message}")
  }
}

/** Resolves the model + decodes images, shared by chat and legacy completion handlers. */
private fun resolveRequest(
  controller: ServerController,
  request: ChatCompletionRequest,
): Triple<com.google.ai.edge.gallery.data.Model, OpenAiMessages.FlattenedPrompt, List<Bitmap>> {
  if (request.messages.isEmpty()) throw ServerException(400, "messages must not be empty.")
  val modelName = request.model.ifBlank { controller.status.value.config.boundModelName }
  if (modelName.isBlank()) throw ServerException(400, "No model specified and no bound model configured.")
  val model =
    controller.resolveModel(modelName)
      ?: throw ServerException(404, "Model '$modelName' not found or not downloaded.")
  // Only the bound model may be used (single bound model policy).
  val bound = controller.status.value.config.boundModelName
  if (bound.isNotBlank() && model.name != bound) {
    throw ServerException(
      400,
      "Server is bound to model '$bound'. Requested '$modelName' is not loaded.",
    )
  }
  val flattened = OpenAiMessages.flatten(request.messages)
  val imageRefs = OpenAiMessages.allImages(request.messages)
  val bitmaps =
    imageRefs.map { ref ->
      try {
        ImageDataUri.decodeBitmap(ref.url)
      } catch (e: ImageDataUri.UnsupportedImageException) {
        throw ServerException(400, e.message ?: "Unsupported image_url.")
      }
    }
  return Triple(model, flattened, bitmaps)
}

private suspend fun RoutingContext.handleChatCompletion(
  controller: ServerController,
  engine: ServerInferenceEngine,
  request: ChatCompletionRequest,
  legacyCompletion: Boolean = false,
) {
  val (model, flattened, bitmaps) = resolveRequest(controller, request)

  // Single-flight: refuse overlapping inference with 429.
  if (!controller.singleFlight.tryAcquire()) {
    call.respondText(
      OpenAiPayloads.errorJson("The model is busy with another request.", type = "rate_limit_error"),
      ContentType.Application.Json,
      HttpStatusCode.TooManyRequests,
    )
    return
  }

  controller.setBusy(true)
  controller.incrementRequests()
  try {
    val created = System.currentTimeMillis() / 1000
    val modelName = model.name
    if (request.stream) {
      val id = OpenAiPayloads.chatCompletionId()
      // Marshal token deltas from the runtime's native callback thread onto the writer coroutine via
      // a channel, so all socket writes happen on a single coroutine.
      val deltas = Channel<String>(capacity = Channel.UNLIMITED)
      call.respondTextWriter(ContentType.parse("text/event-stream"), HttpStatusCode.OK) {
        write(OpenAiPayloads.roleChunkLine(id, created, modelName))
        flush()
        coroutineScope {
          val producer =
            launch {
              try {
                engine.run(
                  model = model,
                  prompt = flattened.prompt,
                  systemInstruction = flattened.systemInstruction,
                  images = bitmaps,
                  temperature = request.temperature,
                  topP = request.topP,
                ) { delta -> deltas.trySend(delta) }
              } finally {
                deltas.close()
              }
            }
          for (delta in deltas) {
            write(OpenAiPayloads.contentChunkLine(id, created, modelName, delta))
            flush()
          }
          producer.join()
        }
        write(OpenAiPayloads.finishChunkLine(id, created, modelName))
        write(OpenAiPayloads.DONE_LINE)
        flush()
      }
    } else {
      val result =
        engine.run(
          model = model,
          prompt = flattened.prompt,
          systemInstruction = flattened.systemInstruction,
          images = bitmaps,
          temperature = request.temperature,
          topP = request.topP,
        ) {}
      if (legacyCompletion) {
        call.respond(
          CompletionResponse(
            id = OpenAiPayloads.completionId(),
            created = created,
            model = modelName,
            choices = listOf(TextChoice(index = 0, text = result.text)),
            usage = OpenAiPayloads.nonStreamingChat("x", created, modelName, result.text, flattened.prompt).usage,
          )
        )
      } else {
        call.respond(
          OpenAiPayloads.nonStreamingChat(
            id = OpenAiPayloads.chatCompletionId(),
            created = created,
            model = modelName,
            content = result.text,
            promptText = flattened.prompt,
          )
        )
      }
    }
  } finally {
    controller.setBusy(false)
    controller.singleFlight.release()
  }
}
