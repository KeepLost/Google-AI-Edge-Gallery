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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.ai.edge.gallery.MainActivity
import com.google.ai.edge.gallery.R
import dagger.hilt.android.AndroidEntryPoint
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the embedded Ktor HTTP listener for the OpenAI-compatible server.
 *
 * The service holds the listener for the server's lifetime and shows a persistent notification while
 * running. Inference is delegated to [ServerInferenceEngine], which drives the existing
 * [com.google.ai.edge.gallery.data.Model] runtime — no parallel engine.
 */
@AndroidEntryPoint
class ServerService : Service() {

  @Inject lateinit var controller: ServerController

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private var server: EmbeddedServer<*, *>? = null
  private lateinit var engine: ServerInferenceEngine

  override fun onCreate() {
    super.onCreate()
    engine = ServerInferenceEngine(applicationContext)
    createNotificationChannel()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> {
        stopServerAndSelf()
        return START_NOT_STICKY
      }
      else -> startServer()
    }
    return START_STICKY
  }

  private fun startServer() {
    val config = controller.status.value.config
    if (!config.hasToken()) {
      controller.onError("Cannot start: missing bearer token.")
      stopSelf()
      return
    }
    // Guard: never start a model-less server. The bound model must resolve to a published model so a
    // race (e.g. model deleted just before Start) cannot leave the server unable to serve requests.
    if (controller.boundModel() == null) {
      controller.onError("Cannot start: no usable model is bound. Download and select a model first.")
      stopSelf()
      return
    }
    if (server != null) {
      // Already running; just (re)post the notification.
      startForegroundWithNotification(config)
      return
    }
    controller.onStarting()
    startForegroundWithNotification(config)
    serviceScope.launch {
      try {
        val embedded =
          embeddedServer(CIO, host = config.bindHost, port = config.port) {
            configureOpenAiServer(controller, this@ServerService.engine)
          }
        embedded.start(wait = false)
        server = embedded
        controller.onRunning()
        Log.i(TAG, "OpenAI server listening on ${config.bindHost}:${config.port}")
      } catch (e: Exception) {
        Log.e(TAG, "Failed to start server", e)
        controller.onError(e.message ?: "Failed to start server")
        stopServerAndSelf()
      }
    }
  }

  private fun stopServerAndSelf() {
    serviceScope.launch {
      try {
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 2000)
      } catch (e: Exception) {
        Log.w(TAG, "Error stopping server: ${e.message}")
      } finally {
        server = null
        // Release the bound model so memory is freed when the server stops.
        controller.boundModel()?.let { engine.unload(it) }
        controller.onStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
      }
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    try {
      server?.stop(gracePeriodMillis = 200, timeoutMillis = 1000)
    } catch (_: Exception) {}
    server = null
    controller.onStopped()
    serviceScope.cancel()
  }

  private fun startForegroundWithNotification(config: ServerConfig) {
    val notification = buildNotification(config)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  private fun buildNotification(config: ServerConfig): android.app.Notification {
    val openIntent =
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    val stopIntent =
      PendingIntent.getService(
        this,
        1,
        Intent(this, ServerService::class.java).apply { action = ACTION_STOP },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    val exposure = if (config.lanEnabled) "LAN" else "localhost"
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle(getString(R.string.server_notification_title))
      .setContentText(
        getString(R.string.server_notification_text, config.bindHost, config.port, exposure)
      )
      .setSmallIcon(android.R.drawable.ic_dialog_info)
      .setOngoing(true)
      .setContentIntent(openIntent)
      .addAction(0, getString(R.string.server_notification_stop), stopIntent)
      .build()
  }

  private fun createNotificationChannel() {
    val channel =
      NotificationChannel(CHANNEL_ID, "OpenAI Server", NotificationManager.IMPORTANCE_LOW).apply {
        description = "Notification shown while the local OpenAI-compatible server is running."
      }
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(channel)
  }

  companion object {
    private const val TAG = "AGServerService"
    private const val CHANNEL_ID = "openai_server_channel"
    private const val NOTIFICATION_ID = 73219
    const val ACTION_START = "com.google.ai.edge.gallery.server.START"
    const val ACTION_STOP = "com.google.ai.edge.gallery.server.STOP"

    fun start(context: Context) {
      val intent = Intent(context, ServerService::class.java).apply { action = ACTION_START }
      context.startForegroundService(intent)
    }

    fun stop(context: Context) {
      val intent = Intent(context, ServerService::class.java).apply { action = ACTION_STOP }
      context.startService(intent)
    }
  }
}
