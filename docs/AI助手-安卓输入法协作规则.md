# AI 助手-安卓输入法协作规则

本文档是后续所有 AI 编程助手在接手 Android / 输入法主线时必须遵守的工作规则。

## 1. 进入任务前必须先读什么

按这个顺序读：

1. [系统架构与进度.md](/Users/sam/Downloads/typeink/docs/系统架构与进度.md)
2. [安卓原生客户端接续说明.md](/Users/sam/Downloads/typeink/docs/安卓原生客户端接续说明.md)
3. [安卓输入法完整实现方案与测试计划.md](/Users/sam/Downloads/typeink/docs/安卓输入法完整实现方案与测试计划.md)
4. [安卓真机联调清单.md](/Users/sam/Downloads/typeink/docs/安卓真机联调清单.md)
5. [安卓输入法接入计划.md](/Users/sam/Downloads/typeink/docs/安卓输入法接入计划.md)

如果没读完上述文档，不要直接改代码。

## 2. 动手前必须先回写总进度

每一轮 Android / 输入法相关改动开始前，必须先在：

- [系统架构与进度.md](/Users/sam/Downloads/typeink/docs/系统架构与进度.md)

补一条“启动记录”。

启动记录至少要写清：

1. 本轮目标
2. 当前判断
3. 当前约束
4. 本轮计划

时间格式必须是：

- `YYYY-MM-DD HH:mm:ss`

并且必须倒序写在对应章节最前面。

## 3. Android 主线的几条铁规则

### 3.1 不要再把 InputConnection 操作分散到 View 里

所有宿主输入框操作都要优先通过：

- [InputConnectionHelper.kt](/Users/sam/Downloads/typeink/android/app/src/main/java/com/typeink/inputmethod/InputConnectionHelper.kt)

包括：

- `commitText`
- `setComposingText`
- `finishComposingText`
- `deleteSurroundingText`
- `sendBackspace`
- `sendEnter`
- 光标移动

原因：

- 宿主 App 差异大，直接裸调很难排查。
- 统一 helper 才能集中打日志和做兼容处理。

### 3.2 不要让 MainActivity 和输入法面板走两套完全不同的状态机

以下交互要尽量保持一致：

1. 风格切换
2. 录音开始 / 停止
3. 本地草稿
4. 云端 ASR
5. 智能改写
6. 修最近一句

如果你只改了输入法，不改主 App，或反过来，必须在文档里明确说明“为何故意不对齐”。

### 3.3 不要重新把密钥写回源码

严禁再次把真实 API Key 写进：

- Kotlin 源码
- XML
- README
- 文档截图

当前 Android 构建通过 `build.gradle` 从 `android/local.properties` / 环境变量读取 `DASHSCOPE_API_KEY`。

如果后续需要换方案，允许改，但不允许再回退到“源码硬编码明文 Key”。

### 3.4 不要只看 BUILD SUCCESSFUL 就宣称功能完成

至少要同时完成：

1. `assembleDebug`
2. `testDebugUnitTest`
3. 必要的真机操作清单

如果真机没测，要明确写“未做真机验证”。

## 4. 修改语音链路时必须关注的点

### 4.1 ASR 收尾

如果改动了：

- `DashScopeService.stopAsr()`
- WebSocket 收尾时机
- `task-finished` 处理

必须重点检查：

1. 会不会再次出现“finish-task 发出后立刻关 socket”；
2. 会不会导致最终句丢失；
3. 改写有没有真正开始；
4. 失败时有没有 fallback。

### 4.2 智能改写

如果改动了提示词、SSE 解析或模型请求，必须至少保住：

1. 没命中规则时能走模型；
2. 模型失败时仍能回退到可用文本；
3. 不要无限卡在“正在润色”；
4. 单测要补上。

### 4.3 修最近一句

如果改动了这部分，必须同时检查：

1. 最近一次提交范围是否仍会被记录；
2. 按钮是否能选中那段文本；
3. 开始录音后是否真的以那段文本为改写对象；
4. 成功写回后最近范围是否会更新。

## 5. UI 改动规则

如果改了输入法 UI，需要同时满足：

1. 深色背景下主按钮必须一眼能看到；
2. 输入法面板高度要克制，不要遮住宿主内容太多；
3. 基础按键触控面积不能太小；
4. 错误信息必须是用户能懂的话，不能直接暴露错误码；
5. 录音中、整理中、失败态要有明显视觉区分。

## 6. 测试最低要求

每次改完 Android 主线，最低要跑：

```bash
cd /Users/sam/Downloads/typeink/android
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug
```

如果改了纯 Kotlin 规则、提示词、解析逻辑，也必须补或更新单测。

## 7. 文档更新最低要求

完成任务后，至少同步更新：

1. [系统架构与进度.md](/Users/sam/Downloads/typeink/docs/系统架构与进度.md)
2. [安卓输入法完整实现方案与测试计划.md](/Users/sam/Downloads/typeink/docs/安卓输入法完整实现方案与测试计划.md)

如果改了真机验证流程，再补：

3. [安卓真机联调清单.md](/Users/sam/Downloads/typeink/docs/安卓真机联调清单.md)

如果改了路线或输入法边界，再补：

4. [安卓原生客户端接续说明.md](/Users/sam/Downloads/typeink/docs/安卓原生客户端接续说明.md)
5. [安卓输入法接入计划.md](/Users/sam/Downloads/typeink/docs/安卓输入法接入计划.md)

## 8. 一句话原则

后续任何 AI 助手都不要再把 Android 输入法当“临时演示壳”去改。

要把它当成一个真实产品入口来维护：

- 可编辑
- 可解释
- 可测试
- 可交接
