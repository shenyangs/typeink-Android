# 安卓模块边界与 Typeless 对齐地图

> 目的：这份文档不是为了“改名字好看”，而是为了让后续重构始终围绕同一个北极星推进：**对标 Typeless 的隐形输入体验**，而不是继续修修补补旧控制面板。

## 1. 北极星约束

所有后续 Android 重构，都必须同时满足下面 4 条：

1. 单画布
   用户看到的应该是一个会呼吸、会形变的输入画布，而不是多个面板拼接。
2. 结果优先
   不再把“草稿框”和“润色框”并排展示给用户，文本应在同一区域原地流式替换。
3. 隐形 UI
   默认只保留最必要的视觉元素，传统键盘和显式控制按钮按需出现。
4. 可降级
   当用户要手动修字时，可以顺滑降级到传统键盘，但传统键盘不能反过来主导整体结构。

5. 可迁移
   未来要支持 iOS、Windows、macOS，所以从现在开始就要把“输入会话脑子”和“平台宿主壳子”拆开。能抽成纯 Kotlin 的状态、规则、会话编排，优先不要粘在 Android View、Service、InputConnection 上。

这也是 Compose 重构的前提判断标准。如果做完模块拆分后，仍然不能满足这 4 条，就说明还没到开始大规模 Compose 重写的时候。

说明：

- 这里的“可迁移”不是说现在立刻全面上 Kotlin Multiplatform；
- 现在更现实的做法是先坚持“portable core, thin host adapter”，也就是：
  - 核心层保存纯 Kotlin 的状态、规则、文本会话编排；
  - Android / iOS / Windows / macOS 各自只保留宿主接入、系统权限、输入框写回、音频采集等平台适配代码；
- 这样后面无论走 KMP、Kotlin Desktop，还是其他平台重写，迁移难点都会明显下降。

## 2. 当前结构的真实问题

当前代码的问题不是“目录不好看”，而是“职责交叉”：

- `prototype/` 里同时放了：
  - Activity 页面入口
  - 录音
  - DashScope 会话
  - ViewModel
  - UI 文案和状态模型
- `inputmethod/` 里同时放了：
  - `InputMethodService`
  - 输入法主视图
  - 会话协调器
  - 状态机
  - 输入连接工具
  - Insets / 震动 / 密码检测
  - 编辑面板与沉浸页
- `settings/`、`vad/`、`syncclipboard/` 是相对独立的，但还没有被明确地挂到“平台配置层”或“附加能力层”。

更关键的结构冲突有两个：

1. 状态模型双轨
   - App 页：`TypeinkUiPhase + TypeinkViewModel`
   - IME 页：`KeyboardState + KeyboardActionHandler` 以及 `TypeinkInputView` 自己的布尔值
2. 渲染出口双轨
   - `MainActivity` 走 ViewModel 驱动
   - `TypeinkInputView` 走本地 `isRecording / isEditMode / currentMode / draftText / finalText`

这就是为什么现在不能直接上 Compose。因为我们还没统一“谁说了算”。

## 3. 当前包的职责盘点

### 3.1 `com.typeink.prototype`

当前实际承担了 5 类职责：

- App 宿主页面：`MainActivity`
- 会话链路：`DashScopeService`、`TypeinkRewriteSupport`
- 音频采集：`PcmRecorder`、`LocalDraftRecognizer`
- 状态模型：`TypeinkModels`、`TypeinkUiText`、`TypeinkViewModel`
- 历史遗留联调入口：`TypeinkSessionClient`、`BackendPreferences`

结论：

- 这个包不该继续叫 `prototype`；
- 它已经不是原型临时区，而是当前 Android 主线的一部分；
- 后续必须拆开，不然所有新能力都会继续往这里堆。

### 3.2 `com.typeink.inputmethod`

当前实际承担了 6 类职责：

- 平台入口：`TypeinkInputMethodService`
- IME 主视图：`TypeinkInputView`
- 会话协调：`AsrSessionManager`
- 状态机：`KeyboardState`、`KeyboardActionHandler`
- 宿主写回能力：`InputConnectionHelper`
- 各种辅助能力：`ImeInsetsResolver`、`ImeLayoutController`、`HapticFeedbackManager`、`UndoManager`、`CandidateManager`

结论：

- 这是当前最需要拆的包；
- 它把“系统集成”“会话控制”“UI 渲染”“编辑能力”全部揉在了一起；
- 如果不先拆，后续 Compose 壳很容易直接依赖一堆旧 helper，最后只是把旧逻辑整体搬过去。

### 3.3 `com.typeink.settings`

当前相对清晰，但还不够收口：

- `SettingsActivity`
- `ProviderManager`
- `ProviderConfig`
- `ProviderListActivity`

结论：

- 这部分可以作为“配置与能力接入层”的起点；
- 后续不应再把输入法运行态逻辑塞到这里；
- 可以保留为相对稳定的外圈模块。

### 3.4 `com.typeink.asr` / `com.typeink.vad` / `com.typeink.syncclipboard`

这三块属于“可被挂载的能力层”：

- `asr/`：ASR 接口、录音采集、未来本地模型入口
- `vad/`：静音检测与配置
- `syncclipboard/`：剪贴板同步

结论：

- 这三块不应该直接主导 UI；
- 它们更适合作为会话层或扩展能力层的依赖；
- 后续 Compose 壳不该直接知道这些模块的实现细节。

## 4. 目标结构

建议的目标包结构如下：

```text
com.typeink.core.input
com.typeink.core.session
com.typeink.ime.platform
com.typeink.ime.session
com.typeink.ime.pipeline
com.typeink.ime.state
com.typeink.ime.ui
com.typeink.ime.fallback
com.typeink.app.home
com.typeink.settings
com.typeink.extensions.syncclipboard
```

### 4.0 `com.typeink.core.*`

职责：

- 平台无关的输入状态
- 会话规则
- 可迁移的纯 Kotlin 逻辑

约束：

- 不依赖 Android SDK
- 不直接引用 View、Service、InputConnection、Activity
- 优先只承载未来可能被 iOS / Windows / macOS 复用的脑力逻辑

候选归属：

- `TypeinkInputState`
- `TypeinkInputStateMapper`
- 未来的 `InputSessionReducer`
- 未来的 `RewritePolicy`

### 4.1 `com.typeink.ime.platform`

职责：

- 系统集成
- `InputMethodService`
- `InputConnection`
- Insets / 震动 / 安全输入检测

候选归属：

- `TypeinkInputMethodService`
- `InputConnectionHelper`
- `ImeInsetsResolver`
- `ImeLayoutController`
- `HapticFeedbackManager`
- `PasswordDetector`
- `ImeIntegration`

### 4.2 `com.typeink.ime.session`

职责：

- 一次输入会话从“准备 -> 收音 -> 识别 -> 润色 -> 写回”的编排
- 会话快照
- 最近提交内容
- 修最近一句上下文

候选归属：

- `AsrSessionManager`
- `SessionInputSnapshot`
- `RecentCommitSnapshot`
- 未来的 `InputSessionCoordinator`

### 4.3 `com.typeink.ime.pipeline`

职责：

- 音频采集
- ASR / LLM / 改写能力接入
- 未来本地模型与在线模型切换

候选归属：

- `DashScopeService`
- `TypeinkRewriteSupport`
- `PcmRecorder`
- `LocalDraftRecognizer`
- `AsrEngine`
- `LocalAsrEngine`
- `AudioCaptureManager`
- `VadProcessor`
- `VadConfig`

### 4.4 `com.typeink.ime.state`

职责：

- 单一状态源
- 统一事件模型
- 渲染态数据

候选归属：

- `KeyboardState`
- `KeyboardActionHandler`
- `TypeinkUiPhase`
- `TypeinkViewModel`
- 未来统一的 `InputState`

说明：

- 这里不会长期保留两套状态模型；
- 过渡期可以先让 `KeyboardState` 和 `TypeinkUiPhase` 在这里并存；
- 目标是最终收敛成一套输入状态对象。

### 4.5 `com.typeink.ime.ui`

职责：

- 输入法主画布
- 文本展示
- 语音光球
- 状态反馈

候选归属：

- 未来的 `TypeinkImeRoot`
- 未来的 `SystemAwareGlassCanvas`
- 未来的 `ThoughtTextRegion`
- 未来的 `VoiceOrb`

说明：

- 这是 Compose 的主要落点；
- 但在进入 Compose 前，不要把当前 `TypeinkInputView` 直接整体挪进来当终局结构。

### 4.6 `com.typeink.ime.fallback`

职责：

- 传统键盘降级层
- 候选词
- Undo
- 插入点

候选归属：

- `TypeinkInputView` 里现有的 `KeyboardView` 降级能力
- `CandidateManager`
- `UndoManager`
- `InsertionPointManager`

说明：

- Typeless 不等于不要传统键盘；
- 但传统键盘必须成为“可唤起的 fallback”，而不是整个产品的主结构。

### 4.7 `com.typeink.app.home`

职责：

- 宿主 App 首页和设置入口
- 设备权限引导
- 输入法启用 / 切换引导

候选归属：

- `MainActivity`
- `AudioWaveView`
- 宿主页相关文案与轻量状态展示

说明：

- 宿主 App 是“设置与引导壳”，不应承载最终的主交互心智。

## 5. 过渡遗产清单

下面这些类可以继续存在一段时间，但必须被明确视为过渡遗产：

- `MainActivity`
- `TypeinkInputView`
- `EditPanelActivity`
- `ImmersiveEditorActivity`
- `TypeinkSessionClient`
- `BackendPreferences`

它们的问题不是“现在完全不能用”，而是：

- 结构上不适合成为 Typeless 终局；
- 很容易把旧控制面板逻辑继续扩散到新架构里；
- 应该被包一层适配，而不是继续作为北极星模板。

## 6. Compose 何时开始

Compose 开工条件建议明确成 3 条：

1. `TypeinkInputView` 不再自己维护录音 / 编辑 / 处理中的本地布尔状态；
2. App 宿主页和 IME 至少共享同一套“输入状态定义”，不再分别用两套心智；
3. `InputConnection`、音频链路、ASR/LLM 链路已经有清晰的外层接口，不需要 Compose 组件直接调用一堆底层类。

在这 3 条满足之前，不建议全量上 Compose。

满足之后，Compose 的顺序应当是：

1. 先重做 IME 外壳画布
2. 再接文本原地流式替换区
3. 再接语音光球与轻反馈
4. 最后再逐步收掉旧 XML 键盘视图

## 7. 最安全的迁移顺序

### 第一批：只建立边界，不改行为

- 产出模块地图
- 明确目标包
- 给过渡遗产挂牌

### 第二批：先收紧依赖方向

- 平台层只能向上暴露接口
- 会话层不直接依赖具体 UI 类
- UI 层不直接操纵录音和网络实现细节

### 第三批：统一状态源

- 先统一输入状态定义
- 再让旧 View 层改为读同一状态对象
- 直到草稿/润色双轨被收敛

### 第四批：Compose 进入

- 先做新 IME 壳
- 旧键盘 fallback 先保留
- 等回归稳定后再逐步清退旧 XML 主视图

## 8. 对标 Typeless 的落地提醒

后续任何实现都要反复自查这 3 个问题：

1. 这次改动是在让界面更“隐形”，还是又新增一块控制面板？
2. 这次状态设计是在逼近“单路径输出”，还是继续保留草稿/结果双轨？
3. 这次技术拆分是否让 Compose 更容易落地成“单画布”，还是只是把旧结构搬到新包名？

如果这 3 个问题的答案不对，就说明已经偏离 Typeless 方向。
