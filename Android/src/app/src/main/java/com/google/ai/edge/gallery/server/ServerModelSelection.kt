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

/**
 * Pure (UI-free, Android-free) logic for resolving which model the OpenAI server should bind to.
 *
 * This intentionally lives outside the ViewModel/Controller so it can be unit-tested on the JVM. It
 * encodes the rule whose absence let the "0 Models" bug ship: when the set of downloaded models
 * changes, the previously bound model must be re-validated and safely cleared or re-bound.
 */
object ServerModelSelection {

  /**
   * Decides the bound model name given the currently [available] model names (already filtered to
   * downloaded + usable) and the [current] bound model name (may be blank).
   *
   * Rules:
   * - No available models -> bind nothing (blank). A model-less server must never be startable.
   * - Current bound model still available -> keep it (stable; avoids churn).
   * - Current bound model missing/blank but models exist -> auto-bind the first available model.
   */
  fun resolveBoundModelName(available: List<String>, current: String): String {
    if (available.isEmpty()) return ""
    if (current.isNotBlank() && available.contains(current)) return current
    return available.first()
  }

  /**
   * Whether the server may be started: there must be at least one available model AND a non-blank
   * bound model that is present in the available set. Token/running checks are handled separately.
   */
  fun canStart(available: List<String>, boundModelName: String): Boolean {
    return boundModelName.isNotBlank() && available.contains(boundModelName)
  }
}
