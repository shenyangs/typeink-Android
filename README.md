# Typeink

Typeink 是一个面向高频输入场景的 Android AI 输入法项目。  
目标是把“说话 -> 文本 -> 润色 -> 回写”做成一条低打扰、可持续演进的输入链路。

## 当前发布状态

- 当前版本：`0.4.3`（`versionCode 14`）
- 当前主线：Android 输入法（IME）+ Android 宿主 App
- 技术栈：Kotlin、InputMethodService、Compose（逐步接管中）
- 代码状态：可构建、可真机安装、持续迭代中

## 目前已具备能力

- 输入法基础壳与语音输入主链路
- 语音转写后的文本回写
- 基础编辑与失败恢复流程
- 设置中心（模型配置、VAD、调试相关能力）
- APK 自动命名输出（支持 build 号递增）

## 快速开始

### 1) 环境准备

- Android Studio（内置 JBR）
- JDK 17（通常可直接使用 Android Studio 自带）
- Android SDK（`minSdk 29`，`targetSdk 34`）

### 2) 配置 API Key（本机）

在 `android/local.properties` 中追加：

```properties
typeink.dashscope.apiKey=你的DashScopeKey
```

读取优先级：

1. `android/local.properties` 的 `typeink.dashscope.apiKey`
2. 环境变量 `TYPEINK_DASHSCOPE_API_KEY`
3. 环境变量 `DASHSCOPE_API_KEY`

### 3) 构建 Debug APK

```bash
cd /Users/sam/Desktop/typeink-codex/android
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug
```

### 4) 运行单元测试

```bash
cd /Users/sam/Desktop/typeink-codex/android
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest
```

## APK 输出规范（发布必看）

唯一正确交付目录：

```text
/Users/sam/Desktop/typeink-codex/outputs/
```

不要把下面目录的临时产物当成交付包：

- `android/outputs/`
- `android/app/build/outputs/apk/debug/`

推荐使用仓库脚本生成可追踪版本包：

```bash
cd /Users/sam/Desktop/typeink-codex
./tools/build_apk.sh 0.4.3
```

产物命名规则：

```text
typeink-v{VERSION}-build{N}.apk
```

示例：`typeink-v0.4.3-build10.apk`

## 目录结构

```text
typeink-codex/
├─ android/                    # Android 主工程（唯一主代码）
├─ docs/                       # 架构、进度、方案、联调文档
├─ outputs/                    # APK 对外交付目录
├─ tools/build_apk.sh          # APK 打包脚本（自动递增 build 号）
└─ README.md
```

## 核心文档

- [系统架构与进度](docs/系统架构与进度.md)
- [安卓模块边界与 Typeless 对齐地图](docs/安卓模块边界与Typeless对齐地图.md)
- [安卓原生客户端接续说明](docs/安卓原生客户端接续说明.md)
- [安卓真机联调清单](docs/安卓真机联调清单.md)
- [构建与输出规范](docs/构建与输出规范.md)

## 路线图（简版）

- 持续收敛 IME 状态机与渲染入口
- 逐步推进 Compose 语音画布接管
- 升级本地 ASR + VAD 链路（降低云依赖）
- 为 iOS / 桌面端复用预留更多纯核心层能力

## 安全与协作提示

- `android/local.properties` 为本机私有配置，不要提交到 Git。
- 不要在代码、文档、截图中暴露真实 API Key。
- 贡献代码前建议先阅读 [系统架构与进度](docs/系统架构与进度.md)。
