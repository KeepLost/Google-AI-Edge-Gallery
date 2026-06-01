package com.google.ai.edge.gallery.customtasks.smartsurveillance

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.runtimeHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

data class SurveillanceUiState(
  val chatMessages: List<String> = emptyList(),
  val events: List<SurveillanceEvent> = emptyList(),
  val analysisDiagnostic: AnalysisDiagnostic? = null,
  val latestFrame: Bitmap? = null,
  val inferenceOwner: InferenceOwner = InferenceOwner.None,
  val monitoringState: MonitoringState = MonitoringState.IdleNoRules,
  val monitoringStatus: String = "No active rules. Add a rule, then start monitoring.",
  val ttsStatus: String = "TTS initializing",
  val promptGuidance: SurveillancePromptGuidance = SurveillancePromptGuidance.defaults(),
  val runtimeSettings: SurveillanceRuntimeSettings = SurveillanceRuntimeSettings.defaults(),
  val ruleMergeProposal: RuleMergeProposal? = null,
) {
  val inProgress: Boolean
    get() = inferenceOwner != InferenceOwner.None
}

/**
 * Pending decision shown after a new rule is parsed but found similar to an existing one. The new
 * rule is NOT persisted until the user picks Create-new or Merge; merging preserves the target
 * rule's id and createdAt.
 */
data class RuleMergeProposal(
  val parsedRule: ParsedSurveillanceRule,
  val targetRuleId: String,
  val targetRuleName: String,
  val targetDetectionFeature: String,
  val similarity: Float,
)

data class AnalysisDiagnostic(
  val message: String,
  val rawPreview: String?,
  val extractedJsonPreview: String?,
  val candidateSummary: String?,
  val frameCount: Int,
  val ruleCount: Int,
  val lookbackSeconds: Int,
)

private sealed interface InferenceResult {
  data class Success(val text: String) : InferenceResult
  data class Error(val message: String) : InferenceResult
}

@HiltViewModel
class SmartSurveillanceViewModel
@Inject
constructor(
  private val ruleDao: SurveillanceRuleDao,
  private val dataStoreRepository: DataStoreRepository,
) : ViewModel() {
  private val _uiState = MutableStateFlow(SurveillanceUiState())
  val uiState = _uiState.asStateFlow()
  val rules = ruleDao.observeRules().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
  private val frameBuffer = ArrayDeque<Bitmap>()
  private var monitoringJob: Job? = null
  private var monitoringGeneration = 0
  private var currentMonitoringModel: Model? = null
  private val lastSpokenAtByRuleId = mutableMapOf<String, Long>()
  private val askHistory = mutableListOf<AskTurn>()
  private val frameSampler = SurveillanceFrameSampler()
  private val frameWindow = SurveillanceFrameWindow()

  init {
    _uiState.value =
      _uiState.value.copy(
        promptGuidance = dataStoreRepository.readSurveillancePromptGuidance(),
        runtimeSettings = dataStoreRepository.readSurveillanceRuntimeSettings(),
      )
  }

  fun onFrame(bitmap: Bitmap) {
    if (!frameSampler.shouldAcceptFrame(nowMs = System.currentTimeMillis(), settings = _uiState.value.runtimeSettings)) return
    frameBuffer.addLast(bitmap)
    while (frameBuffer.size > _uiState.value.runtimeSettings.maxFramesPerRequest) frameBuffer.removeFirst()
    _uiState.value = _uiState.value.copy(latestFrame = bitmap)
  }

  fun clearFrameBuffer() {
    frameBuffer.clear()
    frameSampler.reset()
    frameWindow.reset(System.currentTimeMillis())
    _uiState.value = _uiState.value.copy(latestFrame = null)
  }

  fun setTtsState(state: TtsState) {
    _uiState.value = _uiState.value.copy(ttsStatus = state.toStatusText())
  }

  fun savePromptGuidance(rule: String, ask: String, analysis: String) {
    val guidance = SurveillancePromptGuidance.sanitize(rule = rule, ask = ask, analysis = analysis)
    dataStoreRepository.saveSurveillancePromptGuidance(guidance)
    _uiState.value = _uiState.value.copy(promptGuidance = guidance)
  }

  fun resetPromptGuidance() {
    val guidance = SurveillancePromptGuidance.defaults()
    dataStoreRepository.saveSurveillancePromptGuidance(guidance)
    _uiState.value = _uiState.value.copy(promptGuidance = guidance)
  }

  fun saveRuntimeSettings(frameSamplingFps: Float, lookbackSeconds: Int) {
    saveRuntimeSettings(frameSamplingFps = frameSamplingFps, lookbackSeconds = lookbackSeconds, minConfidence = _uiState.value.runtimeSettings.minConfidence)
  }

  fun saveRuntimeSettings(frameSamplingFps: Float, lookbackSeconds: Int, minConfidence: Float) {
    val settings =
      SurveillanceRuntimeSettings.sanitize(
        frameSamplingFps = frameSamplingFps,
        lookbackSeconds = lookbackSeconds,
        minConfidence = minConfidence,
      )
    dataStoreRepository.saveSurveillanceRuntimeSettings(settings)
    _uiState.value = _uiState.value.copy(runtimeSettings = settings)
    while (frameBuffer.size > settings.maxFramesPerRequest) frameBuffer.removeFirst()
  }

  fun buildAnalysisPromptPreview(activeRules: List<SurveillanceRuleEntity>): String =
    SurveillancePromptBuilder.buildAnalysisPromptPreview(
      rules = activeRules.filter { it.active },
      guidance = _uiState.value.promptGuidance.analysis,
      settings = _uiState.value.runtimeSettings,
    )

  fun sendUserMessage(model: Model, text: String, tts: SurveillanceTtsController?) {
    sendUserMessage(model = model, text = text, defaultMode = SurveillanceInputMode.CreateRule, tts = tts)
  }

  fun sendUserMessage(model: Model, text: String, defaultMode: SurveillanceInputMode, tts: SurveillanceTtsController?) {
    val resolved = SurveillanceInputMode.resolve(defaultMode = defaultMode, input = text)
    when (resolved.mode) {
      SurveillanceInputMode.Ask -> sendAskMessage(model = model, text = resolved.text)
      SurveillanceInputMode.CreateRule -> sendRuleMessage(model = model, text = resolved.text)
    }
  }

  private fun sendRuleMessage(model: Model, text: String) {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return
    prepareForUserRequest(model)
    viewModelScope.launch {
      runWithOwner(InferenceOwner.RuleCreation) {
        addChat("User: $trimmed")
        val prompt = SurveillancePromptBuilder.buildRulePrompt(trimmed, _uiState.value.promptGuidance.rule)
        when (val inference = runModel(model, prompt, emptyList(), InferenceOwner.RuleCreation)) {
          is InferenceResult.Error -> addChat("Gemma: ${inference.message}")
          is InferenceResult.Success -> handleRuleCreationResponse(trimmed, inference.text)
        }
      }
    }
  }

  private suspend fun handleRuleCreationResponse(rawPrompt: String, response: String) {
    when (val parsed = SurveillanceRuleParser.parseRuleCreationResponse(rawPrompt = rawPrompt, response = response)) {
      is RuleCreationParseResult.Rule -> {
        val candidate =
          RuleSimilarity.shortlist(
            newFeature = parsed.rule.detectionFeature,
            existing = rules.value.map { it.id to it.detectionFeature.ifBlank { it.triggerJson.ifBlank { it.rawPrompt } } },
            k = 1,
            floor = RULE_MERGE_SIMILARITY_FLOOR,
          ).firstOrNull()
        val target = candidate?.let { c -> rules.value.firstOrNull { it.id == c.id } }
        if (candidate != null && target != null) {
          logI("rule creation: similar existing rule found id=${target.id} score=${candidate.score}; awaiting user merge decision")
          _uiState.value =
            _uiState.value.copy(
              ruleMergeProposal =
                RuleMergeProposal(
                  parsedRule = parsed.rule,
                  targetRuleId = target.id,
                  targetRuleName = target.name,
                  targetDetectionFeature = target.detectionFeature.ifBlank { target.triggerJson },
                  similarity = candidate.score,
                ),
            )
          addChat("Gemma: This looks similar to existing rule '${target.name}'. Choose Merge or Create new.")
        } else {
          persistNewRule(parsed.rule)
        }
      }
      is RuleCreationParseResult.NotRule -> addChat("Gemma: ${parsed.message}")
      is RuleCreationParseResult.Invalid -> addChat("Gemma: ${parsed.message}")
    }
  }

  private suspend fun persistNewRule(rule: ParsedSurveillanceRule) {
    ruleDao.insertRule(rule.toEntity())
    updateMonitoringState(requestedMonitoring = false, status = "Rule saved. Tap Start monitoring to enable real-time detection.")
    addChat("Gemma: Rule saved - ${rule.name}. Tap Start monitoring when ready.")
  }

  /** Create the proposed rule as a brand-new rule with a fresh id (default, non-destructive choice). */
  fun confirmCreateNewRule() {
    val proposal = _uiState.value.ruleMergeProposal ?: return
    _uiState.value = _uiState.value.copy(ruleMergeProposal = null)
    viewModelScope.launch { persistNewRule(proposal.parsedRule) }
  }

  /**
   * Merge the proposed rule into the existing target: update feature/action/name/updatedAt while
   * preserving the target rule's id and createdAt (so cooldown/history stay coherent).
   */
  fun confirmMergeRule() {
    val proposal = _uiState.value.ruleMergeProposal ?: return
    _uiState.value = _uiState.value.copy(ruleMergeProposal = null)
    viewModelScope.launch {
      val target = rules.value.firstOrNull { it.id == proposal.targetRuleId }
      if (target == null) {
        // Target vanished (deleted while dialog was open); fall back to create-new.
        logW("merge target ${proposal.targetRuleId} no longer exists; creating new rule instead")
        persistNewRule(proposal.parsedRule)
        return@launch
      }
      val merged =
        target.copy(
          name = proposal.parsedRule.name,
          rawPrompt = proposal.parsedRule.rawPrompt,
          triggerJson = proposal.parsedRule.triggerJson,
          detectionFeature = proposal.parsedRule.detectionFeature,
          actionJson = proposal.parsedRule.actionJson,
          actionType = proposal.parsedRule.actionType,
          actionContent = proposal.parsedRule.actionContent,
          active = true,
          updatedAt = System.currentTimeMillis(),
          // id and createdAt intentionally preserved from target.
        )
      ruleDao.updateRule(merged)
      logI("rule merged into id=${merged.id} (createdAt preserved=${merged.createdAt})")
      updateMonitoringState(requestedMonitoring = monitoringJob != null, status = "Rule merged into '${merged.name}'.")
      addChat("Gemma: Merged into existing rule '${merged.name}'.")
    }
  }

  fun cancelRuleProposal() {
    if (_uiState.value.ruleMergeProposal == null) return
    _uiState.value = _uiState.value.copy(ruleMergeProposal = null)
    addChat("Gemma: Rule creation cancelled.")
  }

  private fun sendAskMessage(model: Model, text: String) {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return
    prepareForUserRequest(model)
    viewModelScope.launch {
      runWithOwner(InferenceOwner.Ask) {
        addChat("User: $trimmed")
        val frames = frameBuffer.toList().takeLast(_uiState.value.runtimeSettings.maxFramesPerRequest)
        val prompt =
          SurveillancePromptBuilder.buildAskPrompt(
            userText = trimmed,
            frameCount = frames.size,
            lookbackSeconds = _uiState.value.runtimeSettings.lookbackSeconds,
            guidance = _uiState.value.promptGuidance.ask,
            askHistory = askHistory,
          )
        when (val inference = runModel(model, prompt, frames, InferenceOwner.Ask)) {
          is InferenceResult.Error -> addChat("Gemma: ${inference.message}")
          is InferenceResult.Success -> {
            askHistory.add(AskTurn(user = trimmed, assistant = inference.text))
            addChat("Gemma: ${inference.text}")
          }
        }
      }
    }
  }

  fun deleteRule(rule: SurveillanceRuleEntity) {
    viewModelScope.launch {
      ruleDao.deleteRule(rule)
      stopMonitoringIfNoActiveRules()
    }
  }

  fun toggleRule(rule: SurveillanceRuleEntity) {
    viewModelScope.launch {
      ruleDao.updateRule(rule.copy(active = !rule.active, updatedAt = System.currentTimeMillis()))
      stopMonitoringIfNoActiveRules()
    }
  }

  fun refreshMonitoringState() {
    viewModelScope.launch { updateMonitoringState(requestedMonitoring = monitoringJob != null) }
  }

  fun startMonitoring(model: Model, tts: SurveillanceTtsController?) {
    viewModelScope.launch {
      val activeRules = ruleDao.getActiveRules()
      if (!SurveillanceMonitoringState.canStartMonitoring(activeRules.size, _uiState.value.inferenceOwner)) {
        if (activeRules.isEmpty()) {
          stopMonitoringNow(model = model, status = "Cannot start monitoring: add or enable a rule first.")
        } else {
          setMonitoringStatus("Cannot start monitoring: Gemma is busy.")
        }
        return@launch
      }
      stopMonitoringNow(model = model, status = "Restarting monitoring.")
      currentMonitoringModel = model
      val token = ++monitoringGeneration
      resetFrameWindow()
      updateMonitoringState(requestedMonitoring = true, status = "Monitoring started.")
      monitoringJob =
        viewModelScope.launch {
          while (monitoringGeneration == token) {
            val runtimeSettings = _uiState.value.runtimeSettings
            if (_uiState.value.inferenceOwner != InferenceOwner.None) {
              delay(COLLECTION_POLL_MS)
              continue
            }
            val currentActiveRules = ruleDao.getActiveRules()
            val frames = frameBuffer.toList().takeLast(runtimeSettings.maxFramesPerRequest)
            if (currentActiveRules.isEmpty()) {
              stopMonitoringNow(model = model, status = "Monitoring stopped: no active rules.")
              break
            }
            if (!frameWindow.shouldAnalyze(nowMs = System.currentTimeMillis(), settings = runtimeSettings, acceptedFrameCount = frames.size)) {
              setMonitoringStatus("Monitoring active. Collecting ${runtimeSettings.lookbackSeconds}s frame window (${frames.size}/${runtimeSettings.maxFramesPerRequest}).")
              delay(COLLECTION_POLL_MS)
              continue
            }
            if (frames.isEmpty()) {
              setMonitoringStatus("Monitoring active. Waiting for camera frames.")
              resetFrameWindow()
              delay(COLLECTION_POLL_MS)
              continue
            }
            runWithOwner(InferenceOwner.Monitoring) {
              logI(
                "monitoring analysis start: frames=${frames.size}, activeRules=${currentActiveRules.size}, ruleIds=${currentActiveRules.joinToString { it.id }}, ruleNames=${currentActiveRules.joinToString { it.name }}",
              )
              val response =
                runModel(
                  model,
                  SurveillancePromptBuilder.buildAnalysisPrompt(currentActiveRules, _uiState.value.promptGuidance.analysis, runtimeSettings),
                  frames,
                  InferenceOwner.Monitoring,
                )
              if (monitoringGeneration != token || _uiState.value.monitoringState != MonitoringState.Monitoring) return@runWithOwner
              when (response) {
                is InferenceResult.Error -> {
                  logE("monitoring analysis model error: ${response.message}")
                  setMonitoringStatus("Model error: ${response.message}")
                }
                is InferenceResult.Success -> {
                  logI("monitoring analysis end: rawLength=${response.text.length}, rawPreview=${response.text.logPreview()}")
                  handleAnalysisResponse(response.text, currentActiveRules, frames.size, tts)
                }
              }
            }
            resetFrameWindow()
          }
        }
    }
  }

  fun stopMonitoring(model: Model? = currentMonitoringModel, status: String = "Monitoring paused.") {
    viewModelScope.launch { stopMonitoringNow(model = model, status = status) }
  }

  private suspend fun stopMonitoringNow(model: Model? = currentMonitoringModel, status: String = "Monitoring paused.") {
    monitoringGeneration++
    monitoringJob?.cancel()
    monitoringJob = null
    resetFrameWindow()
    if (_uiState.value.inferenceOwner == InferenceOwner.Monitoring) {
      model?.runtimeHelper?.stopResponse(model)
      _uiState.value = _uiState.value.copy(inferenceOwner = InferenceOwner.None)
    }
    updateMonitoringState(requestedMonitoring = false, status = status)
  }

  fun stopAnalysis() {
    stopMonitoring(status = "Monitoring stopped.")
  }

  private suspend fun runModel(model: Model, prompt: String, images: List<Bitmap>, requestKind: InferenceOwner): InferenceResult =
    try {
      withTimeout(INFERENCE_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
          val builder = StringBuilder()
          try {
            resetConversationForRequest(model = model, requestKind = requestKind)
            model.runtimeHelper.runInference(
              model = model,
              input = prompt,
              images = images,
              resultListener = { partial, done, _ ->
                if (!done) builder.append(partial)
                if (done && continuation.isActive) continuation.resume(InferenceResult.Success(builder.toString()))
              },
              cleanUpListener = {
                if (continuation.isActive) continuation.resume(InferenceResult.Error("Model runtime cleaned up."))
              },
              onError = { message ->
                if (continuation.isActive) continuation.resume(InferenceResult.Error(message))
              },
              coroutineScope = viewModelScope,
            )
          } catch (e: Exception) {
            if (continuation.isActive) continuation.resume(InferenceResult.Error(e.message ?: "Model inference failed."))
          }
          continuation.invokeOnCancellation { model.runtimeHelper.stopResponse(model) }
        }
      }
    } catch (e: TimeoutCancellationException) {
      model.runtimeHelper.stopResponse(model)
      InferenceResult.Error("Gemma timed out. Please try again.")
    } catch (e: CancellationException) {
      model.runtimeHelper.stopResponse(model)
      InferenceResult.Error("Gemma request was cancelled.")
    } catch (e: Exception) {
      InferenceResult.Error(e.message ?: "Gemma request failed.")
    }

  private suspend fun runWithOwner(owner: InferenceOwner, block: suspend () -> Unit) {
    if (_uiState.value.inferenceOwner != InferenceOwner.None) {
      setMonitoringStatus("Gemma is busy: ${_uiState.value.inferenceOwner.label}.")
      return
    }
    _uiState.value = _uiState.value.copy(inferenceOwner = owner)
    try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (owner == InferenceOwner.Monitoring) setMonitoringStatus("Monitoring error: ${e.message ?: "unknown error"}")
      else addChat("Gemma: ${e.message ?: "Request failed."}")
    } finally {
      if (_uiState.value.inferenceOwner == owner) _uiState.value = _uiState.value.copy(inferenceOwner = InferenceOwner.None)
    }
  }

  private fun prepareForUserRequest(model: Model) {
    if (_uiState.value.inferenceOwner == InferenceOwner.Monitoring || monitoringJob != null) {
      stopMonitoring(model = model, status = "Monitoring paused for user request.")
    }
  }

  private suspend fun stopMonitoringIfNoActiveRules() {
    val activeRules = ruleDao.getActiveRules()
    if (activeRules.isEmpty()) stopMonitoringNow(status = "Monitoring stopped: no active rules.")
    else updateMonitoringState(requestedMonitoring = monitoringJob != null)
  }

  private suspend fun updateMonitoringState(requestedMonitoring: Boolean, status: String? = null) {
    val activeRuleCount = ruleDao.getActiveRules().size
    val state = SurveillanceMonitoringState.derive(activeRuleCount = activeRuleCount, requestedMonitoring = requestedMonitoring)
    val resolvedStatus = status ?: when (state) {
      MonitoringState.IdleNoRules -> "No active rules. Add a rule, then start monitoring."
      MonitoringState.ReadyPaused -> "Monitoring paused. Tap Start monitoring to run real-time analysis."
      MonitoringState.Monitoring -> "Monitoring active."
    }
    _uiState.value = _uiState.value.copy(monitoringState = state, monitoringStatus = resolvedStatus)
  }

  private fun setMonitoringStatus(status: String) {
    _uiState.value = _uiState.value.copy(monitoringStatus = status)
  }

  private fun addChat(message: String) {
    _uiState.value = _uiState.value.copy(chatMessages = _uiState.value.chatMessages + message)
  }

  private fun handleAnalysisResponse(response: String, activeRules: List<SurveillanceRuleEntity>, frameCount: Int, tts: SurveillanceTtsController?) {
    logD("parse analysis response: frameCount=$frameCount, activeRules=${activeRules.size}")
    val analysisRules =
      activeRules.mapIndexed { index, rule ->
        SurveillanceRuleParser.AnalysisRule(
          id = rule.id,
          alias = SurveillanceRuleParser.analysisAlias(index),
          name = rule.name,
          actionContent = rule.actionContent,
          detectionFeature = rule.detectionFeature.ifBlank { rule.triggerJson },
        )
      }
    when (val parsed = SurveillanceRuleParser.parseAnalysisConfidenceMap(response, rules = analysisRules, minConfidence = _uiState.value.runtimeSettings.minConfidence)) {
      is AnalysisParseResult.Events -> {
        logI("parse analysis result: events=${parsed.events.size}, ruleIds=${parsed.events.joinToString { it.ruleId }}")
        _uiState.value = _uiState.value.copy(events = (parsed.events + _uiState.value.events).take(20), analysisDiagnostic = null)
        setMonitoringStatus("Monitoring event: ${parsed.events.size} event(s) detected.")
        speakEvents(parsed.events, tts)
      }
      AnalysisParseResult.NoEvents -> {
        logI("parse analysis result: no events")
        _uiState.value = _uiState.value.copy(analysisDiagnostic = null)
        setMonitoringStatus("Monitoring active. Last analysis returned no events.")
      }
      is AnalysisParseResult.Invalid -> {
        logW(
          "parse analysis invalid: reason=${parsed.message}, rawPreview=${parsed.rawPreview?.logPreview()}, extractedPreview=${parsed.extractedJsonPreview?.logPreview()}, candidates=${parsed.candidateSummary}",
        )
        _uiState.value =
          _uiState.value.copy(
            analysisDiagnostic =
                AnalysisDiagnostic(
                  message = parsed.message,
                  rawPreview = parsed.rawPreview,
                  extractedJsonPreview = parsed.extractedJsonPreview,
                  candidateSummary = parsed.candidateSummary,
                  frameCount = frameCount,
                  ruleCount = activeRules.size,
                  lookbackSeconds = _uiState.value.runtimeSettings.lookbackSeconds,
              )
          )
        setMonitoringStatus("Analysis parse error: ${parsed.message}")
      }
    }
  }

  private fun resetFrameWindow() {
    frameBuffer.clear()
    frameSampler.reset()
    frameWindow.reset(System.currentTimeMillis())
  }

  private fun speakEvents(events: List<SurveillanceEvent>, tts: SurveillanceTtsController?) {
    val now = System.currentTimeMillis()
    events.forEach { event ->
      val lastSpokenAt = lastSpokenAtByRuleId[event.ruleId] ?: 0L
      if (now - lastSpokenAt < TTS_COOLDOWN_MS) {
        logI("TTS skipped for cooldown: ruleId=${event.ruleId}")
        setMonitoringStatus("Event shown. TTS skipped for cooldown.")
        return@forEach
      }
      lastSpokenAtByRuleId[event.ruleId] = now
      when (val result = tts?.speak(event.message) ?: TtsSpeakResult.NotReady("TTS controller is unavailable")) {
        is TtsSpeakResult.Queued -> setMonitoringStatus("Event shown. TTS queued.")
        TtsSpeakResult.BlankText -> setMonitoringStatus("Event shown. TTS skipped: blank text.")
        is TtsSpeakResult.NotReady -> setMonitoringStatus("Event shown. TTS unavailable: ${result.reason}")
        is TtsSpeakResult.SpeakFailed -> setMonitoringStatus("Event shown. TTS failed: ${result.code}")
      }
    }
  }

  private fun resetConversationForRequest(model: Model, requestKind: InferenceOwner) {
    model.runtimeHelper.resetConversation(
      model = model,
      supportImage = true,
      supportAudio = false,
    )
  }

  private fun TtsState.toStatusText(): String =
    when (this) {
      TtsState.Initializing -> "TTS initializing"
      is TtsState.Ready -> "TTS ready: $language"
      is TtsState.Unavailable -> "TTS unavailable: $reason"
      is TtsState.SpeakQueued -> "TTS queued"
      is TtsState.Speaking -> "TTS speaking"
      is TtsState.Done -> "TTS done"
      is TtsState.Error -> "TTS error: $reason"
    }

  private fun String.logPreview(maxChars: Int = 800): String = trim().replace(Regex("\\s+"), " ").take(maxChars)

  private fun logD(message: String) {
    runCatching { Log.d(TAG, message) }
  }

  private fun logI(message: String) {
    runCatching { Log.i(TAG, message) }
  }

  private fun logW(message: String) {
    runCatching { Log.w(TAG, message) }
  }

  private fun logE(message: String) {
    runCatching { Log.e(TAG, message) }
  }

  companion object {
    private const val TAG = "SmartSurveillance"
    private const val INFERENCE_TIMEOUT_MS = 60_000L
    private const val TTS_COOLDOWN_MS = 15_000L
    private const val COLLECTION_POLL_MS = 200L
    private const val RULE_MERGE_SIMILARITY_FLOOR = 0.6f
  }
}
