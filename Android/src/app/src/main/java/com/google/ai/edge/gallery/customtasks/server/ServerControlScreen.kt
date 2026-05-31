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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.GalleryTopAppBar
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.AppBarAction
import com.google.ai.edge.gallery.data.AppBarActionType
import com.google.ai.edge.gallery.server.BIND_ALL
import com.google.ai.edge.gallery.server.ServerService
import com.google.ai.edge.gallery.server.ServerState
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

/**
 * Control screen for the OpenAI-compatible local server. It is an app-level destination reached from
 * the home drawer (NOT a per-task model-list flow), so it renders directly without the model-list
 * gate that previously bounced the user out with "0 Models".
 *
 * Model inventory comes from the shared activity-scoped [ModelManagerViewModel] via
 * [ModelManagerViewModel.getAllDownloadedModels] — the single source of truth for downloaded LLMs.
 * The screen distinguishes loading / error / no-downloaded-models / has-models so it never shows a
 * misleading empty state during the async allowlist load.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerControlScreen(
  modelManagerViewModel: ModelManagerViewModel,
  onNavigateUp: () -> Unit,
  onNavigateToModels: () -> Unit,
  viewModel: ServerControlViewModel = hiltViewModel(),
) {
  val context = LocalContext.current
  val status by viewModel.status.collectAsState()
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()

  // Source of truth: downloaded + usable LLMs from the shared model manager.
  val downloadedModels = remember(modelManagerUiState) { modelManagerViewModel.getAllDownloadedModels() }
  // Publish to the controller whenever the list changes; the controller re-validates the bound model.
  LaunchedEffect(downloadedModels) {
    viewModel.publishAvailableModels(downloadedModels)
    viewModel.ensureToken()
  }

  val running = status.state == ServerState.RUNNING || status.state == ServerState.STARTING
  val loading = modelManagerUiState.loadingModelAllowlist
  val allowlistError = modelManagerUiState.loadingModelAllowlistError

  Scaffold(
    topBar = {
      GalleryTopAppBar(
        title = stringResource(R.string.drawer_server_label),
        leftAction =
          AppBarAction(actionType = AppBarActionType.NAVIGATE_UP, actionFn = onNavigateUp),
      )
    }
  ) { innerPadding ->
    Column(
      modifier =
        Modifier.fillMaxSize()
          .padding(innerPadding)
          .verticalScroll(rememberScrollState())
          .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      // Status card.
      Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Server status", style = MaterialTheme.typography.titleMedium)
          Text(
            text =
              when (status.state) {
                ServerState.RUNNING -> "Running on ${status.config.bindHost}:${status.config.port}"
                ServerState.STARTING -> "Starting..."
                ServerState.ERROR -> "Error: ${status.lastError ?: "unknown"}"
                ServerState.STOPPED -> "Stopped"
              },
            style = MaterialTheme.typography.bodyMedium,
          )
          if (status.busy) {
            Text(
              "Busy: handling a request",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.primary,
            )
          }
          Text(
            "Requests served: ${status.requestCount}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
              onClick = {
                viewModel.ensureToken()
                ServerService.start(context)
              },
              // Start only with a resolvable bound model and not already running/loading.
              enabled = !running && !loading && viewModel.canStart(),
            ) {
              Text("Start")
            }
            OutlinedButton(onClick = { ServerService.stop(context) }, enabled = running) {
              Text("Stop")
            }
          }
        }
      }

      // Bound model card — four explicit states.
      Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Bound model", style = MaterialTheme.typography.titleMedium)
          when {
            // 1. Loading the allowlist/inventory.
            loading -> {
              Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                  strokeWidth = 3.dp,
                  modifier = Modifier.padding(end = 12.dp).size(20.dp),
                )
                Text("Loading models...", style = MaterialTheme.typography.bodyMedium)
              }
            }
            // 2. Error loading the allowlist.
            allowlistError.isNotEmpty() -> {
              Text(
                allowlistError,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
              )
              OutlinedButton(onClick = { modelManagerViewModel.loadModelAllowlist() }) {
                Text("Retry")
              }
            }
            // 3. Truly zero downloaded usable models.
            downloadedModels.isEmpty() -> {
              Text(
                "No downloaded models yet. Download a Gemma model to use the server.",
                style = MaterialTheme.typography.bodyMedium,
              )
              Button(onClick = onNavigateToModels) { Text("Download a model") }
            }
            // 4. Has downloaded models — list/select/bind.
            else -> {
              downloadedModels.forEach { model ->
                FilterChip(
                  selected = status.config.boundModelName == model.name,
                  onClick = { viewModel.setBoundModel(model.name) },
                  label = { Text(model.name) },
                  enabled = !running,
                )
              }
              if (running) {
                Text(
                  "Stop the server to change the bound model.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      }

      // Network + auth card.
      Card(modifier = Modifier.fillMaxWidth()) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Text("Network & auth", style = MaterialTheme.typography.titleMedium)

          var portText by
            remember(status.config.port) { mutableStateOf(status.config.port.toString()) }
          OutlinedTextField(
            value = portText,
            onValueChange = { newValue ->
              portText = newValue.filter { it.isDigit() }.take(5)
              portText.toIntOrNull()?.let { if (it in 1..65535) viewModel.setPort(it) }
            },
            label = { Text("Port") },
            enabled = !running,
            singleLine = true,
          )

          // Bearer token row.
          Text("Bearer token", style = MaterialTheme.typography.bodyMedium)
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              text = status.config.token.ifBlank { "(generating...)" },
              style = MaterialTheme.typography.bodySmall,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f),
            )
            IconButton(
              onClick = { copyToClipboard(context, "token", status.config.token) },
              enabled = status.config.token.isNotBlank(),
            ) {
              Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy token")
            }
            IconButton(onClick = { viewModel.regenerateToken() }, enabled = !running) {
              Icon(Icons.Rounded.Refresh, contentDescription = "Regenerate token")
            }
          }

          HorizontalDivider()

          // LAN toggle with explicit warning.
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text("Expose on LAN (${BIND_ALL})", style = MaterialTheme.typography.bodyMedium)
              Text(
                "Risky: any device on your network can reach the server (auth still required).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
              )
            }
            Switch(
              checked = status.config.lanEnabled,
              onCheckedChange = { viewModel.setLanEnabled(it) },
              enabled = !running,
            )
          }
        }
      }

      // Usage hint card.
      Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Usage", style = MaterialTheme.typography.titleMedium)
          val baseUrl = "http://${status.config.bindHost}:${status.config.port}/v1"
          Text("Base URL: $baseUrl", style = MaterialTheme.typography.bodySmall)
          Text(
            "Send header: Authorization: Bearer <token>",
            style = MaterialTheme.typography.bodySmall,
          )
          Text(
            "Endpoints: GET /v1/models, POST /v1/chat/completions (stream supported), POST /v1/completions",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(modifier = Modifier.height(4.dp))
          OutlinedButton(onClick = { copyToClipboard(context, "base_url", baseUrl) }) {
            Text("Copy base URL")
          }
        }
      }
    }
  }
}

private fun copyToClipboard(context: Context, label: String, value: String) {
  if (value.isBlank()) return
  val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
  Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}
