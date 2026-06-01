# Technical Spec — Enhanced Ask Image and Smart Surveillance

## Confirmed Product Decisions

- Enhanced Ask Image supports camera-recorded video and local video file selection.
- Any video input is extracted to image frames before model input.
- Extracted frames become previewable/deletable message attachments.
- User enters text and sends text + frames to Gemma.
- Smart Surveillance is a separate task/page with live camera, rule panel/event feedback, TTS action, and embedded chat.
- Smart Surveillance is not a simple chat interaction and must not redirect to Ask Image for its chat.
- Users describe rules in natural language; Gemma parses them into structured rules.
- Use Room for rule persistence.
- Use Android system TextToSpeech for current TTS behavior.
- No background/lock-screen monitoring is required now.
- Gemma must be used for surveillance analysis.

## Enhanced Ask Image Requirements

### Video Sources

1. Camera recording
   - User sees live preview.
   - User manually stops recording.
   - Recorded source video is a temporary artifact.
   - Delete the recorded source video after successful frame extraction.

2. Local file selection
   - User selects a video from local storage/system picker.
   - Do not delete or modify the original selected file.
   - Extract frames into app-controlled prompt/cache data.

### Frame Extraction

- Default fps: 1 fps.
- Supported fps choices: 0.5 / 1 / 2, unless implementation constraints require a smaller UI first.
- Maximum frames: 30.
- If source duration/fps would exceed 30 frames, sample uniformly or lower effective fps to stay within 30.
- Output bitmaps should be suitable for existing image prompt pipeline and avoid excessive memory use.

### Prompt Sending

- Reuse the existing multi-image attachment path where practical.
- Gemma input is image sequence + user text.
- Do not send raw video bytes to Gemma.

## Smart Surveillance Requirements

### Placement

- Integrate through the app's task/custom-task main path.
- Exact Home/category placement is flexible.
- Avoid standalone activity or bypass navigation.

### UI Responsibilities

- Show live camera preview.
- Show rule list/panel.
- Show recent event feedback/timeline.
- Provide embedded chat in the Surveillance page for rule management and ad-hoc questions.
- Portrait-first stacked layout is acceptable/preferred; responsive refinements are optional.

### Rules

Room-backed rule fields should cover at least:

- id
- name
- raw prompt
- trigger JSON / structured trigger condition
- action JSON / action content
- active flag
- created timestamp
- updated timestamp

Initial executable action scope:

- Implement `tts` execution.
- Do not execute `notification` or `agent` actions in current scope. If Gemma proposes unsupported actions, ask/clarify or constrain parsing to TTS.

### Analysis Loop

- Camera frames are sampled periodically.
- Default analysis interval: 3 seconds.
- Default frames per cycle: max 3.
- Target frame size: about 480p unless existing pipeline forces a different practical size.
- Only one Gemma inference should be in flight at a time.
- Embedded chat/user request has priority; pause/defer periodic surveillance analysis while handling user chat.
- Rule hits may trigger TTS.
- Event timeline can be in-memory for now; persistent audit log is not required now.

### Analysis Output Contract (rule feature/action separation)

- A stored rule separates a perception-only `detectionFeature` (what to look for) from its
  `actionContent` (the TTS utterance).
- The realtime analysis prompt sends Gemma only the per-cycle rule alias (`r1`, `r2`, …) plus each
  rule's `detectionFeature`. It MUST NOT send TTS/action content to the model.
- Gemma returns a single strict-JSON confidence map: `{}` for no event, `{"r1":0.82,"r3":0.6}` for
  events. The parser is recovery-first (tolerant regex), not strict-Gson-only: it recovers from a
  missing closing brace, single quotes, a prose wrapper, and code fences.
- The app resolves the action/TTS text locally from the stored rule after alias→UUID validation; the
  model never supplies speech text on the analysis path.
- A user-tunable confidence threshold (default 0.5) gates firing. A listed rule with a
  missing/malformed/truncated confidence is treated as fired at the threshold and logged.

### Rule Registration / Merge

- Rule creation derives `detectionFeature` and runs a local lexical similarity prefilter against
  existing rules. If a near-duplicate is found, the user is asked to Merge / Create new (default
  highlight) / Cancel before anything is persisted; merges never happen silently.
- Merging preserves the target rule's id and `createdAt`, updating feature/action/name/`updatedAt`.

## Testing and Validation

This environment cannot perform phone/device debugging. Implementation still requires build/test gates.

Required before claiming completion:

1. Establish or report baseline if needed.
2. Run Android build from `Android/src`:
   - `./gradlew assembleDebug`
3. Add meaningful unit tests for new pure logic where practical, especially:
   - frame sampling math / max-frame behavior;
   - rule JSON parsing or validation;
   - any repository/service pure logic that can run on JVM.
4. Run unit tests when added:
   - `./gradlew testDebugUnitTest` or the closest available Gradle unit-test task.
5. If connected/instrumented tests require device/emulator, document that they cannot be completed in this environment instead of pretending success.

## Reporting Requirements

OpenCode must keep `status/latest.progress.md` and `status/history.progress.md` current, and must write:

- `status/kickoff.md`
- `status/implementation-plan.md` or equivalent if project-workflow requires it
- `status/arch-self-review.md`
- `status/execution-report.md`
- `status/questions.md` if blocked or uncertain

## Scope Expectation

Eric explicitly requested that the upcoming implementation should complete the full requested feature set rather than splitting into a small first phase followed by later phases. If full implementation becomes unsafe/impossible in one pass, stop and report with concrete blockers rather than silently shrinking scope.
