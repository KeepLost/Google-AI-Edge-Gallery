package com.google.ai.edge.gallery.customtasks.smartsurveillance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FlipCameraAndroid
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.LiveCameraView
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.launch

@Composable
fun SmartSurveillanceScreen(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  bottomPadding: Dp,
  setAppBarControlsDisabled: (Boolean) -> Unit,
  viewModel: SmartSurveillanceViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  val rules by viewModel.rules.collectAsState()
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val selectedModel = modelManagerUiState.selectedModel
  val activeRuleCount = rules.count { it.active }
  var input by remember { mutableStateOf("") }
  var viewTab by rememberSaveable { mutableStateOf(SurveillanceViewTab.Ask) }
  var showSettings by remember { mutableStateOf(false) }
  var cameraLensFacing by rememberSaveable { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
  var hasFrontCamera by remember { mutableStateOf(true) }
  var hasBackCamera by remember { mutableStateOf(true) }
  val bringIntoViewRequester = remember { BringIntoViewRequester() }
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  val tts = remember { SurveillanceTtsController(context) }

  LaunchedEffect(selectedModel.name) { viewModel.refreshMonitoringState() }
  DisposableEffect(tts) {
    tts.setStatusListener { viewModel.setTtsState(it) }
    onDispose { tts.setStatusListener(null) }
  }
  LaunchedEffect(Unit) {
    val cameraProvider = ProcessCameraProvider.awaitInstance(context)
    hasFrontCamera = runCatching { cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false)
    hasBackCamera = runCatching { cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false)
    if (!hasBackCamera && hasFrontCamera) cameraLensFacing = CameraSelector.LENS_FACING_FRONT
  }
  LaunchedEffect(uiState.inProgress) { setAppBarControlsDisabled(uiState.inProgress) }
  DisposableEffect(Unit) {
    onDispose {
      viewModel.stopAnalysis()
      tts.shutdown()
      setAppBarControlsDisabled(false)
    }
  }

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    bottomBar = {
      SmartSurveillanceInputBar(
        input = input,
        onInputChange = { input = it },
        enabled = !uiState.inProgress,
        viewTab = viewTab,
        bottomPadding = bottomPadding,
        bringIntoViewRequester = bringIntoViewRequester,
        onSend = {
          val mode = if (viewTab == SurveillanceViewTab.Ask) SurveillanceInputMode.Ask else SurveillanceInputMode.CreateRule
          viewModel.sendUserMessage(selectedModel, input, mode, tts)
          input = ""
        },
      )
    },
  ) { innerPadding ->
    Column(
      modifier =
        Modifier.fillMaxSize()
          .padding(bottom = innerPadding.calculateBottomPadding())
          .consumeWindowInsets(innerPadding)
          .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(task.label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = { showSettings = true }, enabled = !uiState.inProgress) {
          Icon(Icons.Rounded.Settings, contentDescription = null)
          Spacer(modifier = Modifier.width(4.dp))
          Text("Settings")
        }
      }

      CameraCard(
        cameraLensFacing = cameraLensFacing,
        canFlip = !uiState.inProgress && hasFrontCamera && hasBackCamera,
        viewTab = viewTab,
        monitoringState = uiState.monitoringState,
        activeRuleCount = activeRuleCount,
        inProgress = uiState.inProgress,
        onStartMonitoring = { viewModel.startMonitoring(selectedModel, tts) },
        onPauseMonitoring = { viewModel.stopMonitoring(selectedModel) },
        onFlipCamera = {
          viewModel.clearFrameBuffer()
          cameraLensFacing =
            if (cameraLensFacing == CameraSelector.LENS_FACING_BACK) {
              CameraSelector.LENS_FACING_FRONT
            } else {
              CameraSelector.LENS_FACING_BACK
            }
        },
        onFrame = viewModel::onFrame,
      )

      ViewTabs(
        selectedTab = viewTab,
        onTabSelected = { nextTab ->
          val previousTab = viewTab
          if (
            SurveillanceViewTab.shouldPauseMonitoringOnSwitch(
              from = previousTab,
              to = nextTab,
              monitoringState = uiState.monitoringState,
            )
          ) {
            viewModel.stopMonitoring(selectedModel, status = "Monitoring paused: switched to Ask.")
          }
          viewTab = nextTab
        },
        enabled = !uiState.inProgress,
      )

      Text(uiState.monitoringStatus, style = MaterialTheme.typography.bodySmall)
      Text(uiState.ttsStatus, style = MaterialTheme.typography.bodySmall)
      if (uiState.inProgress) {
        Text("Gemma: ${uiState.inferenceOwner.label}", style = MaterialTheme.typography.bodySmall)
      }

      when (viewTab) {
        SurveillanceViewTab.Ask -> AskContent(messages = uiState.chatMessages, modifier = Modifier.weight(1f))
        SurveillanceViewTab.Monitoring ->
          MonitoringContent(
            rules = rules,
            uiState = uiState,
            onToggleRule = viewModel::toggleRule,
            onDeleteRule = viewModel::deleteRule,
            modifier = Modifier.weight(1f),
          )
      }
    }
  }

  if (showSettings) {
    SmartSurveillanceSettingsDialog(
      guidance = uiState.promptGuidance,
      runtimeSettings = uiState.runtimeSettings,
      activeRules = rules.filter { it.active },
      analysisPromptPreview = viewModel.buildAnalysisPromptPreview(rules),
      onDismiss = { showSettings = false },
      onSave = { rule, ask, analysis ->
        viewModel.savePromptGuidance(rule = rule, ask = ask, analysis = analysis)
        showSettings = false
      },
      onSaveRuntimeSettings = viewModel::saveRuntimeSettings,
      onReset = { viewModel.resetPromptGuidance() },
    )
  }
}

@Composable
private fun CameraCard(
  cameraLensFacing: Int,
  canFlip: Boolean,
  viewTab: SurveillanceViewTab,
  monitoringState: MonitoringState,
  activeRuleCount: Int,
  inProgress: Boolean,
  onStartMonitoring: () -> Unit,
  onPauseMonitoring: () -> Unit,
  onFlipCamera: () -> Unit,
  onFrame: (android.graphics.Bitmap) -> Unit,
) {
  Card(modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 220.dp)) {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
      LiveCameraView(
        onBitmap = { bitmap, imageProxy ->
          onFrame(bitmap)
          imageProxy.close()
        },
        preferredSize = 480,
        lensFacing = cameraLensFacing,
        modifier = Modifier.fillMaxSize(),
      )
      IconButton(enabled = canFlip, onClick = onFlipCamera, modifier = Modifier.align(Alignment.TopEnd)) {
        Icon(Icons.Rounded.FlipCameraAndroid, contentDescription = "Switch camera")
      }
      MonitoringOverlayControls(
        viewTab = viewTab,
        monitoringState = monitoringState,
        activeRuleCount = activeRuleCount,
        inProgress = inProgress,
        onStartMonitoring = onStartMonitoring,
        onPauseMonitoring = onPauseMonitoring,
        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
      )
    }
  }
}

@Composable
private fun MonitoringOverlayControls(
  viewTab: SurveillanceViewTab,
  monitoringState: MonitoringState,
  activeRuleCount: Int,
  inProgress: Boolean,
  onStartMonitoring: () -> Unit,
  onPauseMonitoring: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val isMonitoringTab = viewTab == SurveillanceViewTab.Monitoring
  val isMonitoring = monitoringState == MonitoringState.Monitoring
  Surface(
    modifier = modifier,
    color =
      when {
        isMonitoring -> MaterialTheme.colorScheme.primaryContainer
        activeRuleCount == 0 -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
      },
    shape = MaterialTheme.shapes.medium,
    tonalElevation = 4.dp,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text =
          when {
            activeRuleCount == 0 -> "No active rules"
            isMonitoring -> "Live · $activeRuleCount active"
            else -> "Paused · $activeRuleCount active"
          },
        style = MaterialTheme.typography.labelMedium,
      )
      if (isMonitoringTab) {
        if (isMonitoring) {
          OutlinedButton(onClick = onPauseMonitoring) { Text("Pause") }
        } else {
          OutlinedButton(
            enabled = !inProgress && activeRuleCount > 0,
            onClick = onStartMonitoring,
          ) {
            Text("Start")
          }
        }
      }
    }
  }
}

@Composable
private fun ViewTabs(
  selectedTab: SurveillanceViewTab,
  onTabSelected: (SurveillanceViewTab) -> Unit,
  enabled: Boolean,
) {
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    SurveillanceViewTab.entries.forEach { tab ->
      FilterChip(
        selected = selectedTab == tab,
        onClick = { onTabSelected(tab) },
        label = { Text(tab.label) },
        enabled = enabled,
      )
    }
  }
}

@Composable
private fun AskContent(messages: List<String>, modifier: Modifier = Modifier) {
  LazyColumn(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (messages.isEmpty()) {
      item { Text("Ask a one-time question about the current camera view.", style = MaterialTheme.typography.bodyMedium) }
    }
    items(messages) { message -> Text(message, style = MaterialTheme.typography.bodySmall) }
  }
}

@Composable
private fun MonitoringContent(
  rules: List<SurveillanceRuleEntity>,
  uiState: SurveillanceUiState,
  onToggleRule: (SurveillanceRuleEntity) -> Unit,
  onDeleteRule: (SurveillanceRuleEntity) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Text("${uiState.monitoringState.label} · ${rules.count { it.active }} active rule(s)", style = MaterialTheme.typography.bodyMedium)
    Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      RulesColumn(
        rules = rules,
        onToggleRule = onToggleRule,
        onDeleteRule = onDeleteRule,
        modifier = Modifier.weight(1f).fillMaxHeight(),
      )
      EventsColumn(uiState = uiState, modifier = Modifier.weight(1f).fillMaxHeight())
    }
  }
}

@Composable
private fun RulesColumn(
  rules: List<SurveillanceRuleEntity>,
  onToggleRule: (SurveillanceRuleEntity) -> Unit,
  onDeleteRule: (SurveillanceRuleEntity) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text("Rules", style = MaterialTheme.typography.titleMedium)
    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      if (rules.isEmpty()) {
        item { Text("No rules yet. Describe a rule below to create one.", style = MaterialTheme.typography.bodySmall) }
      }
      items(rules) { rule -> RuleCard(rule, onToggle = { onToggleRule(rule) }, onDelete = { onDeleteRule(rule) }) }
    }
  }
}

@Composable
private fun EventsColumn(uiState: SurveillanceUiState, modifier: Modifier = Modifier) {
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text("Events", style = MaterialTheme.typography.titleMedium)
    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      uiState.analysisDiagnostic?.let { diagnostic ->
        item { AnalysisDiagnosticCard(diagnostic) }
      }
      if (uiState.events.isEmpty()) {
        item { Text(uiState.monitoringStatus, style = MaterialTheme.typography.bodySmall) }
      }
      items(uiState.events) { event -> EventCard(event) }
    }
  }
}

@Composable
private fun AnalysisDiagnosticCard(diagnostic: AnalysisDiagnostic) {
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
    Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Text("Analysis diagnostic", style = MaterialTheme.typography.titleSmall)
      Text(diagnostic.message, style = MaterialTheme.typography.bodySmall)
      Text(
        "Frames: ${diagnostic.frameCount} · Rules: ${diagnostic.ruleCount} · Lookback: ${diagnostic.lookbackSeconds}s",
        style = MaterialTheme.typography.labelSmall,
      )
      diagnostic.rawPreview?.let {
        Text("Raw: $it", style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
      }
      diagnostic.extractedJsonPreview?.let {
        Text("Extracted: $it", style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
      }
      diagnostic.candidateSummary?.let {
        Text("Candidates: $it", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
      }
    }
  }
}

@Composable
private fun RuntimeSettingsCard(
  settings: SurveillanceRuntimeSettings,
  onSaveRuntimeSettings: (Float, Int) -> Unit,
) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Monitoring settings", style = MaterialTheme.typography.titleSmall)
      Text("Frame sampling FPS and lookback seconds define each non-overlapping Gemma frame window.", style = MaterialTheme.typography.bodySmall)
      Text("Frames per request: ${settings.maxFramesPerRequest} (cap 30)", style = MaterialTheme.typography.bodySmall)
      Text("Frame sampling FPS", style = MaterialTheme.typography.labelMedium)
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SurveillanceRuntimeSettings.SUPPORTED_FRAME_SAMPLING_FPS.forEach { fps ->
          FilterChip(
            selected = settings.frameSamplingFps == fps,
            onClick = { onSaveRuntimeSettings(fps, settings.lookbackSeconds) },
            label = { Text("${fps}fps") },
          )
        }
      }
      Text("Lookback seconds", style = MaterialTheme.typography.labelMedium)
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SurveillanceRuntimeSettings.SUPPORTED_LOOKBACK_SECONDS.forEach { seconds ->
          FilterChip(
            selected = settings.lookbackSeconds == seconds,
            onClick = { onSaveRuntimeSettings(settings.frameSamplingFps, seconds) },
            label = { Text("${seconds}s") },
          )
        }
      }
    }
  }
}

@Composable
private fun SmartSurveillanceInputBar(
  input: String,
  onInputChange: (String) -> Unit,
  enabled: Boolean,
  viewTab: SurveillanceViewTab,
  bottomPadding: Dp,
  bringIntoViewRequester: BringIntoViewRequester,
  onSend: () -> Unit,
) {
  val scope = rememberCoroutineScope()
  Surface(tonalElevation = 3.dp) {
    Row(
      modifier =
        Modifier.fillMaxWidth()
          .navigationBarsPadding()
          .imePadding()
          .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = bottomPadding + 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      OutlinedTextField(
        value = input,
        onValueChange = {
          onInputChange(it)
          scope.launch { bringIntoViewRequester.bringIntoView() }
        },
        modifier =
          Modifier.weight(1f)
            .bringIntoViewRequester(bringIntoViewRequester)
            .onFocusEvent { if (it.isFocused) scope.launch { bringIntoViewRequester.bringIntoView() } },
        label = { Text(if (viewTab == SurveillanceViewTab.Ask) "Ask about current view" else "Create a monitoring rule") },
        enabled = enabled,
      )
      Button(enabled = input.isNotBlank() && enabled, onClick = onSend) { Text("Send") }
    }
  }
}

private enum class SettingsSection(val label: String) {
  Prompt("Prompt"),
  Preview("Preview"),
  Monitoring("Monitoring"),
}

@Composable
private fun SmartSurveillanceSettingsDialog(
  guidance: SurveillancePromptGuidance,
  runtimeSettings: SurveillanceRuntimeSettings,
  activeRules: List<SurveillanceRuleEntity>,
  analysisPromptPreview: String,
  onDismiss: () -> Unit,
  onSave: (String, String, String) -> Unit,
  onSaveRuntimeSettings: (Float, Int) -> Unit,
  onReset: () -> Unit,
) {
  var rule by remember(guidance.rule) { mutableStateOf(guidance.rule) }
  var ask by remember(guidance.ask) { mutableStateOf(guidance.ask) }
  var analysis by remember(guidance.analysis) { mutableStateOf(guidance.analysis) }
  var section by rememberSaveable { mutableStateOf(SettingsSection.Prompt) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Smart Surveillance settings") },
    text = {
      Column(
        modifier = Modifier.heightIn(max = 520.dp).imePadding().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          SettingsSection.entries.forEach { item ->
            FilterChip(selected = section == item, onClick = { section = item }, label = { Text(item.label) })
          }
        }
        when (section) {
          SettingsSection.Prompt -> {
            Text("这些内容会作为补充指令保存；JSON 格式、TTS-only 等硬约束不会被覆盖。")
            OutlinedTextField(value = rule, onValueChange = { rule = it }, label = { Text("新增规则") })
            OutlinedTextField(value = ask, onValueChange = { ask = it }, label = { Text("普通询问") })
            OutlinedTextField(value = analysis, onValueChange = { analysis = it }, label = { Text("监控分析") })
          }
          SettingsSection.Preview -> {
            Text("Realtime analysis prompt preview", style = MaterialTheme.typography.titleSmall)
            Text(
              "Active rules: ${activeRules.size}. Hard JSON/TTS-only constraints are visible here and are not directly editable.",
              style = MaterialTheme.typography.bodySmall,
            )
            Card(modifier = Modifier.fillMaxWidth()) {
              Text(
                text = analysisPromptPreview,
                modifier = Modifier.padding(10.dp),
                style = MaterialTheme.typography.bodySmall,
              )
            }
          }
          SettingsSection.Monitoring -> {
            RuntimeSettingsCard(settings = runtimeSettings, onSaveRuntimeSettings = onSaveRuntimeSettings)
          }
        }
      }
    },
    confirmButton = { TextButton(onClick = { onSave(rule, ask, analysis) }) { Text("Save") } },
    dismissButton = {
      Row {
        TextButton(
          onClick = {
            onReset()
            val defaults = SurveillancePromptGuidance.defaults()
            rule = defaults.rule
            ask = defaults.ask
            analysis = defaults.analysis
          }
        ) {
          Text("Reset")
        }
        TextButton(onClick = onDismiss) { Text("Cancel") }
      }
    },
  )
}

@Composable
private fun RuleCard(rule: SurveillanceRuleEntity, onToggle: () -> Unit, onDelete: () -> Unit) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(rule.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(rule.actionContent, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
      Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = rule.active, onCheckedChange = { onToggle() }, modifier = Modifier.weight(1f))
        IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
          Icon(Icons.Rounded.Delete, contentDescription = null)
        }
      }
    }
  }
}

@Composable
private fun EventCard(event: SurveillanceEvent) {
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
    Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Text("Triggered: ${event.ruleName ?: event.ruleId}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text("TTS: ${event.message}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
      Text("Reason: ${event.reason}", style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
      Text("Confidence: ${"%.2f".format(event.confidence)}", style = MaterialTheme.typography.labelSmall)
    }
  }
}
