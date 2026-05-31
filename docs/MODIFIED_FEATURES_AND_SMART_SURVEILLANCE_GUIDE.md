# 修改版 Google AI Edge Gallery 功能说明与 Smart Surveillance 维护指南

## 1. 这版相比原 App 新增了什么

这套代码在原 Google AI Edge Gallery 的基础上，主要新增了两组能力：

1. Enhanced Ask Image 视频输入增强。
2. Smart Surveillance 智能监控任务。

从架构上看，这次改动没有另起一个独立 Android Activity，也没有绕开原来的模型管理和任务系统。新增能力都尽量接在 Gallery 原本的主路径上：

- Ask Image 还是原来的 `llm_ask_image` 任务，只是在消息输入区多了视频来源，并把视频转成图片帧。
- Smart Surveillance 是一个新的 `CustomTask`，任务 id 是 `llm_smart_surveillance`，通过原 app 的任务/模型/自定义任务机制进入页面。
- Gemma 推理仍然走 app 已有的 `Model.runtimeHelper.runInference(...)` 这类运行时入口。
- 规则持久化新增了 Room 数据库，因为需求明确要求 Smart Surveillance 的规则用 Room 保存。

一句话概括：这版把“图片问答”扩展成“视频抽帧后多图问答”，同时新增了一个“摄像头画面 + 自然语言规则 + Gemma 分析 + TTS 播报”的智能监控页面。

## 2. 整体功能列表

### 2.1 Enhanced Ask Image 视频输入

新增用户可见能力：

- 在 Ask Image 输入框的加号菜单里，新增 `Record video`。
- 在 Ask Image 输入框的加号菜单里，新增 `Pick video file`。
- 用户可以选择抽帧 fps：`0.5fps`、`1fps`、`2fps`。
- 视频会被抽成最多 30 张图片帧。
- 抽出来的帧会进入原来的图片附件预览栏。
- 每张帧图都可以像普通图片附件一样点右上角删除。
- 发送时，Gemma 收到的是“多张图片 + 文本”，不是视频文件。

主要实现文件：

- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/chat/MessageInputText.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/video/VideoFrameSampler.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/video/VideoFrameExtractor.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/Consts.kt`
- `Android/src/app/src/main/res/xml/file_paths.xml`

### 2.2 Smart Surveillance 智能监控

新增用户可见能力：

- App 中新增一个 `Smart Surveillance` 任务。
- 页面顶部显示任务标题。
- 页面上方有实时摄像头画面。
- 摄像头画面右上角有暂停/恢复分析按钮。
- 中间区域分成规则列表和事件列表。
- 底部有内嵌聊天输入框，用来让用户描述规则或向 Gemma 提问。
- 用户输入自然语言规则后，Gemma 尝试转成结构化 JSON 规则。
- 规则保存到 Room 数据库，重新进入页面后规则仍可从数据库读取。
- 页面持续从摄像头取最近帧，定时送给 Gemma 分析是否命中规则。
- 命中规则时生成事件，并调用 Android 系统 TextToSpeech 播报。
- 当前只执行 TTS 动作，不执行 notification 或 agent 动作。

主要实现文件：

- `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/smartsurveillance/SmartSurveillanceTask.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/smartsurveillance/SmartSurveillanceTaskModule.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/smartsurveillance/SmartSurveillanceScreen.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/smartsurveillance/SmartSurveillanceViewModel.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/smartsurveillance/SurveillanceRuleParser.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/smartsurveillance/SurveillanceRuleEntity.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/smartsurveillance/SurveillanceRuleDao.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/smartsurveillance/SurveillanceDatabase.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/smartsurveillance/SurveillanceDataModule.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/smartsurveillance/SurveillanceTtsController.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/LiveCameraView.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/Tasks.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/ModelAllowlist.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/modelmanager/ModelManagerViewModel.kt`

## 3. Enhanced Ask Image 视频输入怎么工作

### 3.1 入口在哪里

入口在 `MessageInputText.kt`。这个组件是 Gallery 通用聊天输入框的一部分，Ask Image 页面会以 `showImagePicker = true` 的方式使用它。

原来这个加号菜单已经有：

- 拍照：`Take a picture`
- 从相册选图：`Pick from album`

现在在同一组图片相关菜单里增加：

- 录制视频：`Record video`
- 选择视频文件：`Pick video file`

这样做的好处是：用户仍然停留在 Ask Image 任务，不需要切到别的页面；抽出来的帧也能直接复用原有图片附件和发送逻辑。

### 3.2 摄像头录制视频流程

源码位置：`MessageInputText.kt`

用户点击 `Record video` 后，流程是：

1. 检查 `CAMERA` 权限。
2. 在 app 的 external cache 目录下创建临时视频文件。
3. 通过 `FileProvider` 把这个临时文件变成可给系统相机 App 写入的 URI。
4. 发起 `MediaStore.ACTION_VIDEO_CAPTURE` 系统录制 Intent。
5. 用户在系统相机录制界面里看到实时预览，并手动停止录制。
6. 录制成功后，代码拿到临时视频 URI，调用视频抽帧逻辑。
7. 抽帧成功后，把帧加入 Ask Image 的图片附件列表。
8. 抽帧成功后删除临时视频源文件。
9. 如果用户取消录制，也删除已创建的临时文件。

这里需要注意：当前实现用的是 Android 系统视频录制界面，而不是自己在 Compose 里做一套完整 CameraX 录制 UI。因此“实时预览/手动停止”由系统相机录制界面提供。

相关文件：

- 临时文件创建：`MessageInputText.kt` 的 `createRecordedVideoFile(...)`
- FileProvider 配置：`file_paths.xml` 里的 `external-cache-path name="cache_videos" path="videos/"`
- 录制发起：`Intent(MediaStore.ACTION_VIDEO_CAPTURE)`
- 成功后抽帧：`handleVideoSelected(uri, true)`
- 删除录制源文件：`pendingRecordedVideoFile?.delete()`

### 3.3 本地视频选择流程

源码位置：`MessageInputText.kt`

用户点击 `Pick video file` 后，流程是：

1. 发起 `Intent.ACTION_GET_CONTENT`。
2. MIME type 设置为 `video/*`。
3. 只允许单选：`Intent.EXTRA_ALLOW_MULTIPLE = false`。
4. 用户选择视频后，拿到系统返回的 content URI。
5. 调用同一套抽帧逻辑。
6. 抽出的帧加入图片附件列表。
7. 本地视频源文件不删除、不修改。

录制视频和本地视频的核心差别是：

- 录制视频是 app 创建的临时中间产物，成功抽帧后应删除。
- 本地选择的视频属于用户，app 只读取，不做删除或修改。

### 3.4 抽帧策略

抽帧由两个文件配合完成：

- `VideoFrameSampler.kt`：只负责算“应该取哪些时间点”。
- `VideoFrameExtractor.kt`：负责真的从视频 URI 里取 Bitmap 帧。

`VideoFrameSampler` 的关键参数：

- 最大帧数：`MAX_FRAMES = 30`
- 默认 fps：`DEFAULT_FPS = 1f`
- 可选 fps：`0.5f, 1f, 2f`

抽帧时间点的规则：

- 如果视频较短，按 fps 固定间隔取帧。
- 如果按 fps 会超过 30 张，就改成在整个视频时长内均匀抽 30 张。
- 如果视频时长无效、fps 无效、最大帧数无效，则返回空列表。

举例：

- 10 秒视频 + 1fps：大约取 10 张。
- 60 秒视频 + 1fps：理论 60 张，超过 30，于是均匀取 30 张。
- 20 秒视频 + 2fps：理论 40 张，超过 30，于是均匀取 30 张。

`VideoFrameExtractor` 的职责：

- 用 `MediaMetadataRetriever` 打开视频 URI。
- 读取视频时长。
- 调 `VideoFrameSampler.sampleTimesUs(...)` 得到时间点。
- 对每个时间点调用 `getFrameAtTime(...)` 取帧。
- 把过大的帧缩放到最长边不超过 1024。
- 返回 `List<Bitmap>`。

### 3.5 附件预览和删除

抽出来的帧不会被做成新类型的“视频消息”，而是加入 `MessageInputText.kt` 里的 `pickedImages`。

这意味着它们复用原来的图片附件体验：

- 在输入框上方横向预览。
- 每张图右上角有关闭按钮。
- 用户可以删除不想发的帧。
- 最终通过 `createMessagesToSend(...)` 打包成 `ChatMessageImage`。

代码里 `ChatMessageImage` 本身支持多张 Bitmap，所以视频帧天然可以作为“多图附件”进入原 Ask Image 流程。

### 3.6 最终怎么送给 Gemma

发送时不是传视频，而是传图片列表：

1. `pickedImages` 被封装为 `ChatMessageImage(bitmaps = curPickedImages, ...)`。
2. 文本被封装为 `ChatMessageText`。
3. 原有聊天 ViewModel 会把图片和文本整理成模型输入。
4. LiteRT-LM/Gemma 收到的是多张图片和文字 prompt。

这里的架构选择很重要：没有新增“视频推理 runtime contract”，因此不会影响底层模型接口。视频只是 Ask Image 的输入便利能力，模型看到的仍然是图片序列。

### 3.7 当前限制和注意点

已确认事实：

- Ask Image 图片上限改成了 `MAX_IMAGE_COUNT = 30`。
- AI Core 的图片上限仍然是 `MAX_IMAGE_COUNT_AI_CORE = 1`，代码里仍有 AI Core 限制判断。
- 发送按钮当前仍要求文本非空或音频非空，只有图片帧但不输入文本时不能发送。这符合“用户输入文字 + 帧”的需求，但如果以后要支持“只发视频帧让模型描述”，需要改发送按钮启用条件。

需要真机验证：

- 系统视频录制在目标手机上的行为。
- 不同文件管理器/相册返回的视频 URI 是否都能被 `MediaMetadataRetriever` 正常读取。
- 大视频抽帧耗时和内存表现。
- 横竖屏视频帧方向是否符合预期。

## 4. Smart Surveillance 架构和工作流

这是本次改动最重要、最需要后续维护者理解的部分。

### 4.1 它不是 Ask Image 的变体

Smart Surveillance 不是把用户重定向到 Ask Image，也不是简单聊天页面。它是一个新的监控任务页面，核心组合是：

- 实时摄像头画面。
- 规则列表。
- 事件列表。
- 内嵌聊天输入。
- 定时摄像头帧分析。
- 命中规则后的 TTS 播报。

用户在这个页面里既可以管理规则，也可以看到监控事件。聊天只是页面的一部分，主要用于自然语言创建规则和临时提问。

### 4.2 它从哪里进入 app

入口由三个层次组成。

第一层：任务 id。

- 文件：`data/Tasks.kt`
- 新增：`BuiltInTaskId.LLM_SMART_SURVEILLANCE = "llm_smart_surveillance"`

第二层：CustomTask。

- 文件：`customtasks/smartsurveillance/SmartSurveillanceTask.kt`
- 类：`SmartSurveillanceTask : CustomTask`
- 它定义了用户看到的任务 label、分类、图标、描述、模型初始化方式和主屏幕。

第三层：Hilt 多绑定注册。

- 文件：`SmartSurveillanceTaskModule.kt`
- 通过 `@Provides` + `@IntoSet` 把 `SmartSurveillanceTask` 放进 app 的 custom task 集合。

简化入口链路：

```text
Hilt 收集 CustomTask
  -> ModelManagerViewModel.getActiveCustomTasks()
  -> Home/任务列表展示 Smart Surveillance
  -> 用户选择模型
  -> CustomTaskScreen 调用 SmartSurveillanceTask.MainScreen(...)
  -> SmartSurveillanceScreen 显示页面
```

这里有一个实际实现细节：`SmartSurveillanceTask` 的 category 是 `Category.LLM`，所以它不是单独新建一个完全独立 tab，而是进入现有 LLM 任务体系。这个符合后来的确认项：“具体入口不重要，但必须是监控页面 + 规则/事件/TTS + 内嵌聊天”。

### 4.3 模型如何分配给 Smart Surveillance

Smart Surveillance 必须用支持图片输入的 Gemma，因为监控分析需要看摄像头帧。

相关文件：

- `ModelAllowlist.kt`
- `ModelManagerViewModel.kt`
- `SmartSurveillanceTask.kt`

模型进入 Smart Surveillance 的方式：

- `ModelAllowlist.kt` 把 `llm_smart_surveillance` 视为 LLM 任务类型之一。
- `ModelManagerViewModel.loadModelAllowlist()` 在处理 allowlist 时，如果某模型属于 Ask Image 且 `llmSupportImage == true`，会把它也加入 Smart Surveillance 任务模型列表。
- `ModelManagerViewModel.addImportedLlmModel(...)` 对用户导入模型也做类似处理：只有 `llmSupportImage` 为 true 的导入模型会加到 Smart Surveillance。
- `SmartSurveillanceTask.initializeModelFn(...)` 调用 `LlmChatModelHelper.initialize(...)`，并明确 `supportImage = true`、`supportAudio = false`。

所以 Smart Surveillance 不是随便拿一个文本模型跑，而是复用 Ask Image 的“视觉模型”选择逻辑。

### 4.4 页面由哪些区域组成

源码位置：`SmartSurveillanceScreen.kt`

当前页面是 Compose 写的，结构大致如下：

```text
SmartSurveillanceScreen
  -> 标题: Smart Surveillance
  -> 摄像头卡片: LiveCameraView + 暂停/恢复按钮
  -> 中间区域: 左侧 Rules / 右侧 Events
  -> 聊天历史: 简短文本列表
  -> 底部输入: OutlinedTextField + Send 按钮
```

从 Android/Compose 角度理解：

- `Composable` 可以理解成声明式 UI 函数。
- `collectAsState()` 会把 ViewModel 里的数据流变成 UI 状态。
- 当规则列表、事件列表、进度状态变化时，Compose 自动刷新相关区域。

关键状态来源：

- `uiState` 来自 `SmartSurveillanceViewModel.uiState`。
- `rules` 来自 `SmartSurveillanceViewModel.rules`，这个底层连接 Room DAO 的 `Flow<List<SurveillanceRuleEntity>>`。
- `selectedModel` 来自 `ModelManagerViewModel.uiState.selectedModel`。

页面生命周期：

- `LaunchedEffect(selectedModel.name)`：选中的模型变了，就调用 `viewModel.startAnalysis(selectedModel, tts)`。
- `DisposableEffect(Unit)`：页面销毁时调用 `viewModel.stopAnalysis()`、`tts.shutdown()`，并恢复 app bar 控件状态。

### 4.5 摄像头预览和帧采集

摄像头组件是已有的 `LiveCameraView.kt`，Smart Surveillance 复用它。

`LiveCameraView` 的工作方式：

1. 检查 `CAMERA` 权限。
2. 通过 CameraX 获取 `ProcessCameraProvider`。
3. 创建 `ImageAnalysis` use case，而不是传统只显示预览的 `Preview` use case。
4. 使用 `STRATEGY_KEEP_ONLY_LATEST`，如果处理不过来，只保留最新帧，避免堆积。
5. 每来一帧，把 `ImageProxy` 转成 `Bitmap`。
6. 根据旋转角度修正 Bitmap。
7. 如果是前置摄像头，则镜像处理。
8. 调用外部传入的 `onBitmap(bitmap, imageProxy)`。
9. `LiveCameraView` 自己用 Canvas 把最新 Bitmap 画到屏幕上，形成实时预览。

Smart Surveillance 页面传入的回调是：

```text
onBitmap(bitmap, imageProxy)
  -> viewModel.onFrame(bitmap)
  -> imageProxy.close()
```

重点：`imageProxy.close()` 很重要。CameraX 的分析帧如果不关闭，后续帧会被阻塞。当前实现由调用者也就是 `SmartSurveillanceScreen` 负责关闭。

Smart Surveillance 给 `LiveCameraView` 的 `preferredSize` 是 `480`，也就是尽量拿接近 480 的帧，目的是降低推理开销。

### 4.6 ViewModel 如何保存最近帧

源码位置：`SmartSurveillanceViewModel.kt`

核心字段：

- `frameBuffer = ArrayDeque<Bitmap>()`
- `uiState.latestFrame`

每次摄像头回调 `onFrame(bitmap)`：

1. 把新 Bitmap 加到 `frameBuffer` 尾部。
2. 如果超过 3 张，就从头部移除旧帧。
3. 更新 UI 状态里的 `latestFrame`。

这相当于一个很小的最近帧环形缓冲区：

```text
摄像头连续帧
  -> onFrame(...)
  -> frameBuffer 最多保留最近 3 张
  -> 分析循环每轮取最近 3 张送 Gemma
```

为什么只留 3 张：

- 技术规格要求每轮默认最多 3 帧。
- 手机端 Gemma 视觉推理成本高，帧太多会慢、热、耗电。

### 4.7 自然语言规则管理

用户在底部输入框输入内容，点击 Send 后，调用：

- `SmartSurveillanceViewModel.sendUserMessage(selectedModel, input, tts)`

当前实现把用户输入优先当成“可能的规则描述”处理。流程是：

```text
用户输入自然语言
  -> ViewModel 标记 inProgress = true
  -> chatMessages 追加 User 文本
  -> buildRulePrompt(userText)
  -> runModel(model, prompt, emptyList())
  -> Gemma 返回文本
  -> SurveillanceRuleParser.parseRuleJson(rawPrompt, response)
  -> 如果能解析成 TTS 规则: 写入 Room，并在聊天里提示 Rule saved
  -> 如果不能解析: 把 Gemma 原始回复作为聊天回复显示
  -> inProgress = false
```

`buildRulePrompt(...)` 会要求模型返回这种形态的 JSON：

- `name`
- `triggerCondition`
- `action.type`
- `action.content`

并明确只允许 `action.type = "tts"`。

### 4.8 Rule Parser 做了什么保护

源码位置：`SurveillanceRuleParser.kt`

它主要解决两个问题：

1. Gemma 输出是自然语言文本，里面可能夹着 JSON，需要把 JSON 提取出来。
2. 即使模型输出了 JSON，也必须检查是不是当前支持的动作。

规则解析逻辑：

- 支持从 markdown fenced code block 中提取 JSON。
- 如果没有 fenced code block，就从文本中找第一个 `{` 到最后一个 `}`。
- 解析失败返回 `null`。
- 要求存在 `action` 对象。
- 要求 `action.type` 是 `tts`。
- 如果 action 不是 `tts`，直接拒绝，不保存规则。
- 要求存在 `action.content`。
- 支持 `triggerCondition` 或 `trigger` 字段。
- 如果 `triggerCondition` 是纯字符串，会包装成 `{"condition":"..."}` 形式保存。
- 如果没有 id，就用 UUID 生成。

这层保护很重要，因为产品确认当前只执行 TTS。不管 Gemma 是否输出 `notification` 或 `agent`，parser 都不会接受为可执行规则。

### 4.9 Room 规则持久化设计

Room 可以理解成 Android 上对 SQLite 的类型安全封装。这里它只用于保存 Smart Surveillance 规则。

相关文件：

- `SurveillanceRuleEntity.kt`
- `SurveillanceRuleDao.kt`
- `SurveillanceDatabase.kt`
- `SurveillanceDataModule.kt`

表名：`rules`

字段：

- `id`：主键。
- `name`：规则名。
- `raw_prompt`：用户原始自然语言输入。
- `trigger_json`：Gemma 解析出来的触发条件 JSON。
- `action_json`：Gemma 解析出来的动作 JSON。
- `action_type`：当前只接受 `tts`。
- `action_content`：TTS 要播报的文字。
- `active`：是否启用。
- `created_at`：创建时间。
- `updated_at`：更新时间。

DAO 提供的操作：

- `observeRules()`：按更新时间倒序观察全部规则，UI 规则列表使用它。
- `getActiveRules()`：取启用中的规则，分析循环使用它。
- `insertRule(...)`：插入或替换规则。
- `updateRule(...)`：更新规则，例如开关 active。
- `deleteRule(...)`：删除规则。

数据库创建：

- `SurveillanceDatabase` 版本是 1。
- `SurveillanceDataModule` 用 `Room.databaseBuilder(context, SurveillanceDatabase::class.java, "surveillance.db")` 创建数据库。
- DAO 通过 Hilt 注入到 `SmartSurveillanceViewModel`。

数据流：

```text
Gemma 解析出的 ParsedSurveillanceRule
  -> toEntity()
  -> SurveillanceRuleDao.insertRule(...)
  -> Room 的 rules 表
  -> observeRules() Flow 发出新列表
  -> SmartSurveillanceScreen 自动刷新 Rules 列表
```

### 4.10 规则列表 UI 如何工作

源码位置：`SmartSurveillanceScreen.kt`

规则列表来自：

- `val rules by viewModel.rules.collectAsState()`

每条规则用 `RuleCard(...)` 展示：

- 显示 `rule.name`。
- 显示 `rule.actionContent`。
- `Switch` 控制 `rule.active`。
- 删除按钮调用 `viewModel.deleteRule(rule)`。

开关规则时：

```text
用户点 Switch
  -> viewModel.toggleRule(rule)
  -> ruleDao.updateRule(rule.copy(active = !rule.active, updatedAt = now))
  -> Room 更新
  -> observeRules() 推出新列表
  -> UI 刷新
```

### 4.11 定时分析循环怎么跑

源码位置：`SmartSurveillanceViewModel.kt`

`startAnalysis(model, tts)` 启动一个 coroutine job。核心循环：

```text
while true:
  等待 3 秒
  如果 analysisRunning == false: 跳过本轮
  如果 inProgress == true: 跳过本轮
  从 Room 取 active rules
  从 frameBuffer 取最近 3 张帧
  如果没有规则或没有帧: 跳过本轮
  标记 inProgress = true
  buildAnalysisPrompt(activeRules)
  runModel(model, prompt, frames)
  parseAnalysisJson(response)
  如果有事件: TTS 播报 + 加入事件列表
  标记 inProgress = false
```

关键默认参数：

- 分析间隔：3 秒。
- 每轮最多帧数：3。
- 摄像头目标尺寸：480。
- 事件列表最多保留 20 条。

### 4.12 Gemma 推理如何调用

Smart Surveillance 没有新建另一套底层模型运行时，而是直接复用已有模型 runtime helper：

- 文件：`SmartSurveillanceViewModel.kt`
- 函数：`runModel(model, prompt, images)`
- 底层调用：`model.runtimeHelper.runInference(...)`

规则解析时：

- `images = emptyList()`
- 输入是用户自然语言规则 + JSON 输出要求。

监控分析时：

- `images = 最近 3 张摄像头 Bitmap`
- 输入是当前启用规则列表 + JSON 输出要求。

分析 prompt 要求模型返回：

- `events` 数组。
- 每个事件包含 `ruleId`、`confidence`、`action.type`、`action.content`。
- 当前 parser 只接受 action type 为 `tts` 的事件。

### 4.13 并发和优先级行为

这里有一个很关键的状态：`uiState.inProgress`。

它的作用：避免同一时间多个 Gemma 推理并发。

用户聊天时：

- `sendUserMessage(...)` 一开始检查 `inProgress`。
- 如果已有推理在跑，直接返回，不启动新的用户请求。
- 如果可以处理，则设置 `inProgress = true`。

定时分析时：

- 每轮分析前检查 `inProgress`。
- 如果用户聊天或上一轮推理还没结束，则跳过本轮分析。
- 这实现了“用户请求优先于周期性分析”的基本策略。

暂停按钮：

- 页面上的暂停/恢复按钮调用 `viewModel.setAnalysisRunning(...)`。
- 如果 `analysisRunning = false`，定时循环还在，但每轮都会跳过分析。

需要注意的限制：

- 目前 `inProgress` 同时控制用户聊天和周期分析，所以分析中用户输入框会被禁用。
- 如果 Gemma 推理很慢，页面会短时间不可输入。
- 如果将来要求“分析不中断但用户仍可排队发消息”，这里需要引入更细的队列或优先级调度。

### 4.14 事件时间线

事件类型定义在 `SurveillanceRuleParser.kt`：`SurveillanceEvent`

字段：

- `ruleId`
- `message`
- `confidence`
- `timestampMs`

事件来源：

- Gemma 分析摄像头帧后返回 JSON。
- `parseAnalysisJson(...)` 提取 `events`。
- 只保留 action type 为 `tts` 的事件。

事件存储位置：

- 当前只放在 `SmartSurveillanceViewModel.uiState.events`。
- 最多保留 20 条。
- 没有写入 Room 或日志文件。

页面展示：

- `SmartSurveillanceScreen.kt` 的 `Events` 列表。
- 每条显示 `ruleId`、播报内容和 confidence。

这意味着：

- 规则是持久化的。
- 事件不是持久化的。
- App 进程重启或页面状态丢失后，事件历史会消失。

这是已确认的当前范围，不是 bug。如果领导以后要求“事件审计日志”或“历史报警查询”，需要新增事件表或其他持久化。

### 4.15 TextToSpeech 动作执行

源码位置：`SurveillanceTtsController.kt`

它是对 Android 系统 `TextToSpeech` 的薄封装：

- 构造时创建 `TextToSpeech(context.applicationContext)`。
- 初始化成功后设置语言为 `Locale.getDefault()`。
- `speak(text)` 使用 `TextToSpeech.QUEUE_ADD` 排队播报。
- 页面销毁时调用 `shutdown()`，内部会 `stop()` 和 `shutdown()`。

触发链路：

```text
Gemma 分析返回事件 JSON
  -> SurveillanceRuleParser.parseAnalysisJson(...)
  -> 得到 SurveillanceEvent(message = action.content)
  -> SmartSurveillanceViewModel.startAnalysis 中 events.forEach { tts?.speak(it.message) }
  -> Android TextToSpeech 播报
```

当前只执行 TTS：

- 规则 parser 拒绝非 `tts` action。
- 分析事件 parser 也拒绝非 `tts` action。
- 没有实际执行 notification。
- 没有实际执行 agent 工具链。

### 4.16 Smart Surveillance 总数据流

创建规则数据流：

```text
用户底部输入自然语言
  -> SmartSurveillanceScreen input
  -> SmartSurveillanceViewModel.sendUserMessage(...)
  -> buildRulePrompt(...)
  -> Gemma 文本推理
  -> SurveillanceRuleParser.parseRuleJson(...)
  -> ParsedSurveillanceRule.toEntity()
  -> SurveillanceRuleDao.insertRule(...)
  -> Room: surveillance.db / rules
  -> observeRules() Flow
  -> Rules UI 自动刷新
```

监控分析数据流：

```text
CameraX ImageAnalysis
  -> LiveCameraView 转 Bitmap
  -> SmartSurveillanceScreen onBitmap
  -> SmartSurveillanceViewModel.onFrame(...)
  -> frameBuffer 最近 3 帧
  -> startAnalysis 每 3 秒触发
  -> getActiveRules() 从 Room 取启用规则
  -> buildAnalysisPrompt(activeRules)
  -> Gemma 图像+文本推理
  -> SurveillanceRuleParser.parseAnalysisJson(...)
  -> uiState.events 更新
  -> SurveillanceTtsController.speak(...)
  -> Events UI 自动刷新
```

生命周期清理数据流：

```text
用户离开 Smart Surveillance 页面
  -> SmartSurveillanceScreen DisposableEffect.onDispose
  -> viewModel.stopAnalysis()
  -> analysisJob.cancel()
  -> tts.shutdown()
  -> LiveCameraView DisposableEffect 解绑 CameraX
```

## 5. 主要文件 / 组件地图

### 5.1 Enhanced Ask Image 文件地图

| 文件 | 作用 | 常见修改点 |
|---|---|---|
| `MessageInputText.kt` | 聊天输入框、加号菜单、图片/音频/视频选择、附件预览、发送打包 | 改视频入口文案、菜单位置、fps UI、发送条件、附件删除体验 |
| `VideoFrameSampler.kt` | 纯逻辑：按时长/fps/上限算抽帧时间点 | 改最大帧数、默认 fps、均匀采样策略 |
| `VideoFrameExtractor.kt` | 用 Android `MediaMetadataRetriever` 从视频 URI 抽 Bitmap | 改目标尺寸、取关键帧策略、错误处理、异步进度 |
| `Consts.kt` | 全局图片数量上限 | 改 Ask Image 最多图片数、AI Core 限制 |
| `file_paths.xml` | FileProvider 可暴露的 cache 路径 | 改录制视频临时目录或新增共享路径 |

### 5.2 Smart Surveillance 文件地图

| 文件 | 作用 | 常见修改点 |
|---|---|---|
| `SmartSurveillanceTask.kt` | 定义任务、初始化/清理模型、进入主屏幕 | 改任务名称、描述、系统 prompt、是否支持音频、模型初始化策略 |
| `SmartSurveillanceTaskModule.kt` | Hilt 注册 CustomTask | 一般不改，除非任务注册方式变化 |
| `SmartSurveillanceScreen.kt` | Compose 页面：摄像头、规则、事件、聊天输入 | 改 UI 布局、按钮、规则卡片、事件卡片、交互流程 |
| `SmartSurveillanceViewModel.kt` | 核心业务编排：帧缓存、规则创建、定时分析、Gemma 调用、事件更新 | 改分析间隔、并发策略、prompt、事件保留数、规则处理逻辑 |
| `SurveillanceRuleParser.kt` | 解析 Gemma JSON，过滤非 TTS 动作 | 改 JSON schema、支持 notification/agent、容错策略 |
| `SurveillanceRuleEntity.kt` | Room 表结构实体 | 增加字段、修改规则存储结构 |
| `SurveillanceRuleDao.kt` | Room 数据访问接口 | 增加查询、批量更新、按条件过滤 |
| `SurveillanceDatabase.kt` | Room 数据库定义 | 增加表、升级版本、加 migration |
| `SurveillanceDataModule.kt` | Hilt 提供 Room database/DAO | 改数据库名、迁移策略、依赖提供方式 |
| `SurveillanceTtsController.kt` | Android TTS 封装 | 改语言、队列策略、播报节流、静音模式 |
| `LiveCameraView.kt` | CameraX 帧分析和预览 | 改摄像头选择、分辨率、帧格式、预览渲染、权限体验 |
| `Tasks.kt` | 任务 id 定义 | 改任务 id 或新增相关任务 |
| `ModelAllowlist.kt` | allowlist 模型转换时识别 Smart Surveillance 为 LLM 任务 | 改模型能力判断 |
| `ModelManagerViewModel.kt` | 任务/模型列表、导入模型、allowlist 加载 | 改 Smart Surveillance 可用模型来源、任务排序 |

### 5.3 测试文件地图

| 文件 | 验证内容 |
|---|---|
| `Android/src/app/src/test/java/com/google/ai/edge/gallery/video/VideoFrameSamplerTest.kt` | 抽帧时间点、最大 30 帧、fps 行为 |
| `Android/src/app/src/test/java/com/google/ai/edge/gallery/customtasks/smartsurveillance/SurveillanceRuleParserTest.kt` | 规则 JSON 解析、TTS-only 限制、分析事件解析 |

## 6. 未来需求变化应该改哪里

### 6.1 如果领导要改视频抽帧数量上限

优先看：

- `VideoFrameSampler.kt` 的 `MAX_FRAMES`
- `Consts.kt` 的 `MAX_IMAGE_COUNT`
- `MessageInputText.kt` 的附件上限逻辑

注意：只改 `MAX_FRAMES` 不一定够。如果 Ask Image 附件总数仍限制较低，抽出来的帧会被截断。当前实现两边都是 30。

### 6.2 如果要改 fps 选项或默认 fps

优先看：

- `VideoFrameSampler.DEFAULT_FPS`
- `VideoFrameSampler.SUPPORTED_FPS`
- `MessageInputText.kt` 中显示 fps `AssistChip` 的地方

如果要把 fps 做成模型配置或设置页选项，需要接入 Gallery 原有 `Config` 体系，而不是只改本地 `remember` 状态。

### 6.3 如果要支持“只发视频/图片，不输入文字也能发送”

优先看：

- `MessageInputText.kt` 发送按钮 `enabled` 条件。
- `createMessagesToSend(...)` 文本为空时的行为。
- 下游 ViewModel 是否接受只有图片没有文本的消息。

当前发送按钮要求 `curMessage.isNotEmpty() || pickedAudioClips.isNotEmpty()`，图片本身不会启用发送按钮。

### 6.4 如果要把视频录制改成 App 内自定义录制界面

优先看：

- `MessageInputText.kt` 当前 `MediaStore.ACTION_VIDEO_CAPTURE` 流程。
- CameraX `VideoCapture` 相关 API。
- 现有拍照 bottom sheet 的 CameraX `PreviewView` 结构。

当前实现依赖系统相机录制。要做 App 内录制，需要新增 CameraX VideoCapture use case、录制开始/停止按钮、文件输出、权限/生命周期处理。

### 6.5 如果 Smart Surveillance 要换入口或排序

优先看：

- `SmartSurveillanceTask.kt` 的 `category`、`label`、`description`。
- `ModelManagerViewModel.kt` 的 `PREDEFINED_LLM_TASK_ORDER`。
- Home/任务列表相关代码，如果要做真正单独 tab。

当前实现是 LLM category 下的 CustomTask，不是独立 Activity。

### 6.6 如果要改 Smart Surveillance 页面布局

优先看：

- `SmartSurveillanceScreen.kt`

典型改动：

- 摄像头区域高度：当前是 `220.dp`。
- Rules 和 Events 左右并排：当前是一个 `Row` 里两个 `Column`。
- 聊天历史高度：当前是 `120.dp`。
- 输入框 label：`Describe a rule or ask Gemma`。

如果要适配大屏/横屏，可以在这里根据窗口宽度选择左右分栏或上下堆叠。

### 6.7 如果要改分析间隔、每轮帧数、事件数量

优先看：

- `SmartSurveillanceViewModel.kt`

当前硬编码点：

- `delay(3_000)`：每 3 秒分析一次。
- `while (frameBuffer.size > 3)`：最多缓存 3 帧。
- `frameBuffer.toList().takeLast(3)`：每轮最多送 3 帧。
- `(events + _uiState.value.events).take(20)`：事件列表最多 20 条。

如果这些要变成用户设置，建议抽成常量或配置数据类，不要散落在 ViewModel 里。

### 6.8 如果要支持 notification 动作

优先看：

- `SurveillanceRuleParser.kt`
- `SmartSurveillanceViewModel.kt`
- Android 通知权限和通知渠道代码

当前 parser 明确拒绝非 `tts`。要支持 notification，至少要：

- 允许 `action.type = "notification"`。
- 定义 notification 的 JSON schema。
- 增加通知执行器。
- Android 13+ 需要处理 `POST_NOTIFICATIONS` 权限。
- 决定分析命中时是否同时 TTS 和通知。

### 6.9 如果要支持 agent 动作

优先看：

- `SurveillanceRuleParser.kt`
- `SmartSurveillanceViewModel.kt`
- 现有 Agent Chat / skills / MCP 相关模块

这是比 notification 更大的改动，因为 agent 动作可能涉及工具调用、安全边界、权限、执行确认、失败重试。不要只让 parser 接受 `agent` 就算完成，必须明确 agent 能做什么、谁授权、怎么审计。

### 6.10 如果要持久化事件历史

优先看：

- `SurveillanceRuleEntity.kt`
- `SurveillanceDatabase.kt`
- `SurveillanceRuleDao.kt`
- `SmartSurveillanceViewModel.kt`
- `SmartSurveillanceScreen.kt`

建议新增独立表，例如 `events`，不要混在 `rules` 表里。需要字段可能包括：

- event id
- rule id
- message
- confidence
- timestamp
- frame snapshot path，可选
- action execution result，可选

同时要考虑数据库 migration。当前 DB version 是 1，新增表后需要升级 version 并提供迁移策略，不能随便破坏用户已有规则。

### 6.11 如果要改 Gemma prompt 或 JSON schema

优先看：

- `SmartSurveillanceViewModel.buildRulePrompt(...)`
- `SmartSurveillanceViewModel.buildAnalysisPrompt(...)`
- `SurveillanceRuleParser.parseRuleJson(...)`
- `SurveillanceRuleParser.parseAnalysisJson(...)`
- 对应单元测试 `SurveillanceRuleParserTest.kt`

原则：prompt 和 parser 必须一起改。只改 prompt 不改 parser，Gemma 输出可能解析失败；只改 parser 不改 prompt，模型可能不会按新格式输出。

### 6.12 如果要减少误报或重复播报

优先看：

- `SmartSurveillanceViewModel.startAnalysis(...)`
- `SurveillanceEvent` 数据结构
- 未来事件持久化或内存去重逻辑

当前实现只要 Gemma 每轮返回事件，就会 TTS 播报。没有冷却时间、同一规则去重、置信度阈值过滤。常见增强包括：

- 同一 ruleId N 秒内只播报一次。
- confidence 低于阈值不播报。
- 连续两轮命中才播报。
- 分析中加入“不要重复报告刚刚报告过的事件”的上下文。

### 6.13 如果要换摄像头、改分辨率或后置摄像头

优先看：

- `LiveCameraView.kt`
- `SmartSurveillanceScreen.kt` 调用 `LiveCameraView` 的参数

当前 `LiveCameraView` 默认是 `CameraSelector.DEFAULT_FRONT_CAMERA`。Smart Surveillance 没有显式覆盖，所以默认前置摄像头。若要默认后置，传 `cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA` 或修改默认值。

分辨率通过 `preferredSize` 控制，Smart Surveillance 当前传 480。

### 6.14 如果要让 Smart Surveillance 后台运行或锁屏运行

当前明确不支持。要做这个不是小改 UI，而是新架构任务，至少涉及：

- 前台服务 Foreground Service。
- 后台摄像头限制。
- 通知常驻。
- 电池优化。
- 权限和隐私提示。
- 模型生命周期和资源回收。

这会明显改变架构边界，需要先重新确认需求。

## 7. 已验证事实、未验证点和风险

### 7.1 已从源码确认的事实

- Smart Surveillance 已作为 `CustomTask` 注册。
- Smart Surveillance 使用 task id `llm_smart_surveillance`。
- Smart Surveillance 初始化模型时设置 `supportImage = true`。
- Smart Surveillance 规则使用 Room 保存到 `surveillance.db` 的 `rules` 表。
- 摄像头帧来自 `LiveCameraView` 的 CameraX `ImageAnalysis`。
- 分析循环每 3 秒尝试运行一次。
- 每轮最多使用最近 3 张帧。
- 用户聊天和周期分析共用 `inProgress` 防并发。
- parser 只接受 TTS action。
- TTS 使用 Android 系统 `TextToSpeech`。
- Ask Image 视频输入最终变成 `ChatMessageImage` 多图附件。
- 录制视频成功抽帧后删除临时源文件。
- 本地选择视频不会被删除或修改。

### 7.2 构建和单元测试状态

上一轮实现任务记录的验证结果：

- `./gradlew testDebugUnitTest` 成功。
- `./gradlew assembleDebug` 成功。
- 针对 `VideoFrameSampler` 和 `SurveillanceRuleParser` 的单元测试成功。

本说明文档任务没有修改生产源码，所以不需要重新跑 Android 构建。

### 7.3 需要真机或模拟器验证的点

这些不能只靠源码和 JVM 单元测试确认：

- 系统视频录制在目标手机上的实际 UX。
- 不同 Android 版本的视频 picker URI 兼容性。
- `MediaMetadataRetriever` 对不同编码视频的抽帧成功率。
- Smart Surveillance 摄像头权限弹窗和预览实际效果。
- 480 尺寸帧在目标设备上的性能、发热和耗电。
- Gemma 在真实设备上每 3 秒视觉推理是否可接受。
- TTS 语言、音量、队列和打断行为。
- 前置/后置摄像头是否符合实际监控场景。

### 7.4 当前实现的产品限制

- Smart Surveillance 事件不持久化。
- Smart Surveillance 没有后台/锁屏运行。
- Smart Surveillance 当前没有通知动作和 agent 动作。
- Smart Surveillance 没有复杂规则编辑页，只能通过列表开关/删除，创建主要靠聊天输入。
- Smart Surveillance 没有误报抑制或冷却时间。
- Smart Surveillance 的聊天历史是内存状态，不是完整复用原 Chat session 持久化。
- Ask Image 视频抽帧没有进度条，长视频可能等待明显。
- Ask Image 视频发送仍需要用户输入文本。

## 8. Eric 快速定位表

| 领导改需求 | 先看哪里 |
|---|---|
| “视频最多不要 30 张，要 10 张/60 张” | `VideoFrameSampler.kt`、`Consts.kt`、`MessageInputText.kt` |
| “抽帧 fps 要更多选项” | `VideoFrameSampler.kt`、`MessageInputText.kt` |
| “视频录制不要跳系统相机，要 app 内录制” | `MessageInputText.kt`、CameraX VideoCapture、新录制 UI |
| “Smart Surveillance 默认用后置摄像头” | `LiveCameraView.kt`、`SmartSurveillanceScreen.kt` |
| “监控每秒分析一次/10 秒分析一次” | `SmartSurveillanceViewModel.startAnalysis(...)` |
| “每轮送更多帧给 Gemma” | `SmartSurveillanceViewModel.frameBuffer` 和 `takeLast(3)` |
| “规则要支持通知” | `SurveillanceRuleParser.kt`、新增 notification executor、Android 权限 |
| “规则要支持调用 agent” | `SurveillanceRuleParser.kt`、Agent/MCP/skills 执行链、安全确认 |
| “事件要保留历史记录” | 新增 Room event entity/DAO，改 `SurveillanceDatabase.kt` 和 ViewModel |
| “页面布局不好看” | `SmartSurveillanceScreen.kt` |
| “误报太多/一直播报” | `SmartSurveillanceViewModel.kt` 增加冷却、阈值、去重 |
| “Gemma 输出解析不稳定” | `buildRulePrompt`、`buildAnalysisPrompt`、`SurveillanceRuleParser.kt`、单元测试 |
| “想换模型列表/只允许某些模型” | `ModelAllowlist.kt`、`ModelManagerViewModel.kt`、allowlist JSON |
| “要后台监控” | 需要新设计：Foreground Service、后台相机、通知、资源管理 |

## 9. 给后续维护的建议

1. 改 Smart Surveillance 时优先从 `SmartSurveillanceViewModel.kt` 看业务流程，从 `SmartSurveillanceScreen.kt` 看 UI。
2. 改规则格式时，一定同时改 prompt、parser、Room 字段和单元测试。
3. 不要绕过 CustomTask 体系新增 Activity，否则会破坏模型管理和原 app 导航架构。
4. 不要让 Gemma 并发跑多个视觉推理，手机端很容易卡顿、发热或资源冲突。
5. 要新增 notification/agent 动作时，先明确权限、安全、失败处理和审计，不要只在 JSON 里加一个 action type。
6. 要做后台监控时，先单独立项，因为这不是当前前台页面的小扩展。
7. 每次调整抽帧或规则解析，优先补充/更新 JVM 单元测试，因为这两块是纯逻辑，最容易自动验证。

## 10. 总结

这版改动的核心思路是复用原 app 的主架构：

- 视频不直接喂给 Gemma，而是先抽成图片帧。
- 图片帧复用 Ask Image 已有的多图输入链路。
- Smart Surveillance 作为 CustomTask 进入任务系统。
- 模型复用支持图片输入的 Gemma。
- 规则用 Room 持久化。
- 摄像头帧用 CameraX `ImageAnalysis` 采集。
- 定时分析由 ViewModel 协调。
- 动作执行当前只做 Android TTS。

如果后续需求变化，最重要的是先判断它属于哪一层：输入层、UI 层、规则层、存储层、推理层、动作层、任务入口层，之后按上面的文件地图定位修改点。这样可以避免把需求改成临时补丁，也能避免破坏 Gallery 原有的任务/模型/runtime 主路径。
