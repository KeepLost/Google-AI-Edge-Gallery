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

package com.google.ai.edge.gallery.customtasks.server

import androidx.lifecycle.ViewModel
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.server.ServerConfig
import com.google.ai.edge.gallery.server.ServerController
import com.google.ai.edge.gallery.server.ServerStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** Exposes the singleton [ServerController] to the Compose control screen. */
@HiltViewModel
class ServerControlViewModel @Inject constructor(private val controller: ServerController) :
  ViewModel() {

  val status: StateFlow<ServerStatus> = controller.status

  /**
   * Publishes the current downloaded models so the server can resolve `model` ids headlessly. The
   * controller re-validates the bound model against this list (auto-binding the first model if none
   * is bound, clearing/rebinding if the bound model disappeared).
   */
  fun publishAvailableModels(models: List<Model>) {
    controller.setAvailableModels(models)
  }

  /** Whether the server may be started: a usable model is bound and resolvable. */
  fun canStart(): Boolean = controller.canStart()

  fun ensureToken(): String = controller.ensureToken()

  fun regenerateToken() {
    controller.regenerateToken()
  }

  fun setPort(port: Int) = controller.setPort(port)

  fun setLanEnabled(enabled: Boolean) = controller.setLanEnabled(enabled)

  fun setBoundModel(name: String) = controller.setBoundModel(name)

  fun availableModelNames(): List<String> = controller.availableModelNames()
}
