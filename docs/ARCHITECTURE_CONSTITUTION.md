# Architecture Constitution — Google AI Edge Gallery Customization

## Purpose

Guide implementation of Enhanced Ask Image and Smart Surveillance without bypassing the existing Google AI Edge Gallery architecture.

## Top-Level Intent

- Extend Google AI Edge Gallery to support video-derived multi-image prompts and Gemma-based smart surveillance.
- Keep changes inside the app's existing task/model/runtime architecture.
- Do not create a separate app path, standalone activity, or decorative architecture that is not used by the main UI.

## Core Architectural Rules

1. **Main-path integration**
   - Enhanced Ask Image must enhance the existing Ask Image flow where practical.
   - Smart Surveillance must enter through the app's task/custom-task/navigation system, not through a disconnected side path.

2. **Video input contract**
   - Gemma receives images plus text, not video files.
   - Video from camera recording or local file selection must be converted into image frames first.
   - Frames must be previewable as message attachments before user sends the prompt.

3. **Smart Surveillance interaction model**
   - Smart Surveillance is not a simple chat screen.
   - Its primary interaction is surveillance: live camera view, rule panel, event feedback, TTS action, and embedded chat for rule management/ad-hoc questions.
   - The embedded chat must remain inside the surveillance page; do not route the user to Ask Image for surveillance chat.

4. **Gemma requirement**
   - Surveillance analysis must use Gemma because this is a confirmed company/leader requirement.
   - Non-LLM heuristics may support sampling/throttling/UI logic, but must not replace Gemma as the surveillance reasoning engine unless explicitly authorized later.

5. **Persistence**
   - Use Room for structured surveillance rules.
   - Proto DataStore or existing chat persistence may remain for chat/session history if appropriate.

6. **Privacy/storage**
   - Camera-recorded source videos are temporary intermediate artifacts and should be deleted after successful frame extraction.
   - Local files selected by the user must not be deleted or modified.
   - Keep only necessary frame/cache artifacts for the app's prompt/session flow.

7. **Lifecycle/performance**
   - Avoid concurrent Gemma inference loops.
   - User chat inside Surveillance has priority over periodic analysis.
   - Continuous camera/model usage must have clear start/stop lifecycle and cleanup.

8. **No background requirement**
   - Background/lock-screen monitoring is out of scope for the current implementation.

## Stop-the-Line Rules

Stop and report if:

- implementation requires changing external model/runtime contracts in a way not described by the spec;
- Smart Surveillance cannot be integrated into the task/custom-task main path without a major navigation decision;
- camera/video permissions or storage/privacy behavior becomes unclear;
- build/test gates cannot be run or fail for reasons not understood;
- a workaround would bypass the architecture above.

## Definition of Done

Implementation is not complete until:

- all requested features are implemented or explicitly blocked;
- production code is integrated into the main app path;
- required status reports are written;
- architecture self-review is written;
- Android build passes;
- necessary unit tests are added and pass where practical;
- limitations that require physical device validation are documented.
