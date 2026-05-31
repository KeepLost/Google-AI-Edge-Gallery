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

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single-flight guard ensuring only one inference runs at a time across the whole runtime (server
 * requests AND, when wired, the in-app chat/surveillance). Non-blocking: callers that cannot acquire
 * the slot get a busy signal and should return HTTP 429 rather than queueing indefinitely.
 *
 * This is intentionally a tiny, dependency-free state machine so it can be unit-tested on the JVM.
 */
class SingleFlightGuard {
  private val inFlight = AtomicBoolean(false)

  val isBusy: Boolean
    get() = inFlight.get()

  /** Attempts to acquire the slot. Returns true on success, false if already busy. */
  fun tryAcquire(): Boolean = inFlight.compareAndSet(false, true)

  /** Releases the slot. Safe to call only by the acquirer. */
  fun release() {
    inFlight.set(false)
  }

  /**
   * Runs [block] only if the slot is free, releasing it afterwards. Returns the block's result, or
   * null if the guard was busy (caller should surface a busy/429 response).
   */
  inline fun <T> withSlotOrNull(block: () -> T): T? {
    if (!tryAcquire()) return null
    try {
      return block()
    } finally {
      release()
    }
  }
}
