# 上游 ASR WebSocket 协议

本文档描述主服务与独立上游 ASR 服务之间当前约定的 WebSocket 契约。

## 目标

这条协议只负责一件事：让主服务把实时音频和上下文稳定转发给上游 ASR，再把上游识别结果取回。

## 主服务发给上游的消息

### 1. `start_session`

文本帧 JSON：

```json
{
  "type": "start_session",
  "context": "这是一次普通的语音输入测试"
}
```

说明：

- 表示本次语音会话开始
- `context` 可为空
- 上游应把它当成当前会话的初始上下文

### 2. `context_update`

文本帧 JSON：

```json
{
  "type": "context_update",
  "selected_text": "帮我请个假，今天不想去了",
  "pre_text": "老板，",
  "post_text": "谢谢。"
}
```

说明：

- 用于“修改已有文本”场景
- 上游应更新当前会话中的上下文状态
- 这类消息可能在会话过程中多次出现

### 3. 音频二进制帧

二进制帧：

- 第 1 个字节是 `OpCode`
- 后续是裸 PCM 音频数据

当前约定：

- `0x01`：普通录音
- `0x02`：修改模式录音

### 4. `stop_recording`

文本帧 JSON：

```json
{
  "type": "stop_recording"
}
```

说明：

- 表示当前一句话结束
- 上游应尽快给出这一句的最终识别结果

## 上游回给主服务的消息

### 1. `session_ready`

文本帧 JSON：

```json
{
  "type": "session_ready",
  "content": {
    "session_id": "abc123",
    "utterance_id": 0,
    "sequence_id": 0,
    "created_at_ms": 1775401708252
  }
}
```

说明：

- 目前主服务会忽略它
- 但建议上游保留这条消息，方便后续扩展和排查

### 2. `asr_partial`

文本帧 JSON：

```json
{
  "type": "asr_partial",
  "content": {
    "text": "普通输入 识别中，第 3 段音频正在转写",
    "utterance_id": 1,
    "sequence_id": 3,
    "created_at_ms": 1775402341394
  }
}
```

说明：

- 表示草稿识别结果
- 主服务会立刻转成内部 ASR partial 事件，继续往下游推

### 3. `asr_final`

文本帧 JSON：

```json
{
  "type": "asr_final",
  "content": {
    "text": "第 1 句语音已完成，累计收到 40896 bytes 音频。",
    "utterance_id": 1,
    "sequence_id": 65,
    "created_at_ms": 1775402343332
  }
}
```

说明：

- 表示当前一句的最终识别结果
- 主服务收到后会继续交给重写器处理

## 当前兼容策略

1. 主服务当前只严格消费 `asr_partial` 和 `asr_final`
2. 其他服务端消息会被忽略
3. 非法 JSON 会被日志记录后忽略

## 当前实现文件

- 主服务远程 ASR provider：
  [app/services/asr/remote_ws.py](/Users/sam/Downloads/typeink/app/services/asr/remote_ws.py)
- 本地假上游：
  [tools/fake_asr_upstream.py](/Users/sam/Downloads/typeink/tools/fake_asr_upstream.py)

