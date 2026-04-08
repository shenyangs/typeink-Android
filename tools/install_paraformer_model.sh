#!/bin/bash
# 安装 sherpa-onnx Paraformer 单模型到已连接 Android 设备
# 用法：
#   ./tools/install_paraformer_model.sh
#   ./tools/install_paraformer_model.sh /path/to/model.zip
#   ./tools/install_paraformer_model.sh https://example.com/model.zip

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PACKAGE_NAME="${PACKAGE_NAME:-com.typeink.prototype}"
MODEL_URL_DEFAULT="https://github.com/BryceWG/BiBi-Keyboard/releases/download/models/sherpa-onnx-streaming-paraformer-bilingual-zh-en.zip"
MODEL_SOURCE="${1:-$MODEL_URL_DEFAULT}"
REMOTE_MODEL_DIR="/sdcard/Android/data/${PACKAGE_NAME}/files/local_asr/paraformer/bilingual"

if command -v adb >/dev/null 2>&1; then
  ADB_BIN="$(command -v adb)"
elif [ -x "/Users/sam/Library/Android/sdk/platform-tools/adb" ]; then
  ADB_BIN="/Users/sam/Library/Android/sdk/platform-tools/adb"
else
  echo "未找到 adb，请先安装 Android platform-tools 或把 adb 加入 PATH。"
  exit 1
fi

TMP_DIR="$(mktemp -d)"
cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

ZIP_PATH="$TMP_DIR/paraformer.zip"

echo "使用 adb: $ADB_BIN"
echo "目标包名: $PACKAGE_NAME"
echo "远端目录: $REMOTE_MODEL_DIR"

if [[ "$MODEL_SOURCE" =~ ^https?:// ]]; then
  echo "下载模型压缩包..."
  curl -L --fail --output "$ZIP_PATH" "$MODEL_SOURCE"
elif [ -f "$MODEL_SOURCE" ]; then
  echo "使用本地压缩包: $MODEL_SOURCE"
  cp "$MODEL_SOURCE" "$ZIP_PATH"
else
  echo "模型来源不存在：$MODEL_SOURCE"
  exit 1
fi

echo "解压模型..."
unzip -q "$ZIP_PATH" -d "$TMP_DIR/unpacked"

MODEL_DIR="$(find "$TMP_DIR/unpacked" -type f -name 'tokens.txt' -print | while read -r token_file; do
  dir="$(dirname "$token_file")"
  if [ -f "$dir/encoder.int8.onnx" ] || [ -f "$dir/encoder.onnx" ]; then
    if [ -f "$dir/decoder.int8.onnx" ] || [ -f "$dir/decoder.onnx" ]; then
      echo "$dir"
      break
    fi
  fi
done)"

if [ -z "$MODEL_DIR" ]; then
  echo "未在压缩包中找到 Paraformer 模型目录（需要 tokens.txt + encoder*.onnx + decoder*.onnx）。"
  exit 1
fi

echo "找到模型目录: $MODEL_DIR"

echo "检查设备连接..."
"$ADB_BIN" get-state >/dev/null

echo "创建远端目录..."
"$ADB_BIN" shell "mkdir -p '$REMOTE_MODEL_DIR'"

echo "推送模型到手机..."
"$ADB_BIN" push "$MODEL_DIR/." "$REMOTE_MODEL_DIR/"

echo "校验远端文件..."
"$ADB_BIN" shell "ls -lh '$REMOTE_MODEL_DIR'"

echo ""
echo "安装完成。下一步："
echo "1. 打开 Typeink 设置页，查看草稿识别状态是否变成 Sherpa。"
echo "2. 重新打开输入法并尝试语音输入。"
echo "3. 如需重新安装 APK，仍然使用 ./tools/build_apk.sh 维持自动 build 号输出。"
