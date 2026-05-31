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

import com.google.ai.edge.gallery.data.Model
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** High-level lifecycle state of the server, surfaced to the control UI. */
enum class ServerState {
  STOPPED,
  STARTING,
  RUNNING,
  ERROR,
}

/** Observable status snapshot for the control UI. */
data class ServerStatus(
  val state: ServerState = ServerState.STOPPED,
  val config: ServerConfig = ServerConfig(),
  val busy: Boolean = false,
  val lastError: String? = null,
  val requestCount: Long = 0L,
)

/**
 * Singleton bridge between the in-app control screen (which has access to [ModelManagerViewModel] and
 * the live [Model] objects) and the headless foreground [ServerService] (which cannot inject a
 * ViewModel). The control screen publishes the desired config and the set of available downloaded
 * models here; the service reads them.
 *
 * This is NOT a parallel inference engine — actual inference always goes through [Model.runtimeHelper]
 * via [ServerInferenceEngine].
 */
@Singleton
class ServerController @Inject constructor() {

  private val _status = MutableStateFlow(ServerStatus())
  val status: StateFlow<ServerStatus> = _status.asStateFlow()

  /** Live downloaded models published by the control screen (used to resolve `model` ids). */
  @Volatile private var availableModels: List<Model> = emptyList()

  /** Shared single-flight guard across server requests (and future UI coordination). */
  val singleFlight = SingleFlightGuard()

  /**
   * Publishes the latest downloaded/usable models and re-validates the bound model against them.
   *
   * If the previously bound model is no longer available it is cleared or re-bound to the first
   * available model (see [ServerModelSelection.resolveBoundModelName]). This prevents a stale bound
   * model name from lingering after the user deletes a model elsewhere.
   */
  fun setAvailableModels(models: List<Model>) {
    availableModels = models
    val resolved =
      ServerModelSelection.resolveBoundModelName(
        available = models.map { it.name },
        current = _status.value.config.boundModelName,
      )
    if (resolved != _status.value.config.boundModelName) {
      setBoundModel(resolved)
    }
  }

  fun availableModelNames(): List<String> = availableModels.map { it.name }

  /** Whether the server currently has a resolvable bound model and may be started. */
  fun canStart(): Boolean =
    ServerModelSelection.canStart(
      available = availableModelNames(),
      boundModelName = _status.value.config.boundModelName,
    )

  fun resolveModel(name: String): Model? =
    availableModels.firstOrNull { it.name == name }
      ?: availableModels.firstOrNull { it.normalizedName == name }

  /** The currently bound model object, or null if none/unavailable. */
  fun boundModel(): Model? {
    val name = _status.value.config.boundModelName
    if (name.isBlank()) return null
    return resolveModel(name)
  }

  // ---- Config mutations (from the control screen) ----

  fun updateConfig(transform: (ServerConfig) -> ServerConfig) {
    _status.value = _status.value.copy(config = transform(_status.value.config))
  }

  fun setPort(port: Int) = updateConfig { it.copy(port = port) }

  fun setLanEnabled(enabled: Boolean) = updateConfig { it.copy(lanEnabled = enabled) }

  fun setToken(token: String) = updateConfig { it.copy(token = token) }

  fun setBoundModel(name: String) = updateConfig { it.copy(boundModelName = name) }

  fun ensureToken(): String {
    val current = _status.value.config.token
    if (current.isNotBlank()) return current
    val token = ServerAuth.generateToken()
    setToken(token)
    return token
  }

  fun regenerateToken(): String {
    val token = ServerAuth.generateToken()
    setToken(token)
    return token
  }

  // ---- State transitions (from the service) ----

  fun onStarting() {
    _status.value = _status.value.copy(state = ServerState.STARTING, lastError = null)
  }

  fun onRunning() {
    _status.value = _status.value.copy(state = ServerState.RUNNING, lastError = null)
  }

  fun onStopped() {
    _status.value = _status.value.copy(state = ServerState.STOPPED, busy = false)
  }

  fun onError(message: String) {
    _status.value = _status.value.copy(state = ServerState.ERROR, lastError = message)
  }

  fun setBusy(busy: Boolean) {
    _status.value = _status.value.copy(busy = busy)
  }

  fun incrementRequests() {
    _status.value = _status.value.copy(requestCount = _status.value.requestCount + 1)
  }

  val isRunning: Boolean
    get() = _status.value.state == ServerState.RUNNING || _status.value.state == ServerState.STARTING
}
