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

/** Loopback bind address. Default and safest option. */
const val BIND_LOOPBACK = "127.0.0.1"

/** Wildcard bind address that exposes the server to the local network. Opt-in, risky. */
const val BIND_ALL = "0.0.0.0"

const val DEFAULT_SERVER_PORT = 8080

/** Immutable snapshot of the server configuration the UI controls. */
data class ServerConfig(
  val port: Int = DEFAULT_SERVER_PORT,
  /** When true, bind to 0.0.0.0 (LAN). When false, bind to 127.0.0.1 (loopback only). */
  val lanEnabled: Boolean = false,
  /** Mandatory bearer token. The server refuses to start without one. */
  val token: String = "",
  /** Name of the model the server will load/use. Empty until the user selects one. */
  val boundModelName: String = "",
) {
  val bindHost: String
    get() = if (lanEnabled) BIND_ALL else BIND_LOOPBACK

  fun hasToken(): Boolean = token.isNotBlank()
}
