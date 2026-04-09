#!/bin/bash
# Typeink APK 构建脚本
# 用法: ./tools/build_apk.sh [版本号]

set -e

VERSION=${1:-"0.5.0"}
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_DIR="$PROJECT_DIR/android"
OUTPUT_DIR="$PROJECT_DIR/outputs"
RELEASE_DIR="$PROJECT_DIR/发布包"

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${YELLOW}开始构建 Typeink APK v${VERSION}...${NC}"

# 进入 Android 目录
cd "$ANDROID_DIR"

# 构建 Debug APK
if [ -n "$JAVA_HOME" ]; then
    echo "使用 JAVA_HOME: $JAVA_HOME"
else
    export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    echo "设置 JAVA_HOME: $JAVA_HOME"
fi

./gradlew :app:assembleDebug --no-daemon

# 创建输出目录
mkdir -p "$OUTPUT_DIR"
mkdir -p "$RELEASE_DIR"

# 生成 build 号（同一版本自动递增，不覆盖旧包）
LAST_BUILD=$(find "$OUTPUT_DIR" -maxdepth 1 -type f -name "typeink-v${VERSION}-build*.apk" \
    | sed -E "s#.*typeink-v${VERSION}-build([0-9]+)\\.apk#\\1#" \
    | sort -n \
    | tail -1)

if [ -z "$LAST_BUILD" ]; then
    BUILD_NUMBER=1
else
    BUILD_NUMBER=$((LAST_BUILD + 1))
fi

# 复制并命名 APK
SOURCE_APK="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
TARGET_APK="$OUTPUT_DIR/typeink-v${VERSION}-build${BUILD_NUMBER}.apk"
RELEASE_APK="$RELEASE_DIR/typeink-v${VERSION}-build${BUILD_NUMBER}.apk"

cp "$SOURCE_APK" "$TARGET_APK"
cp "$SOURCE_APK" "$RELEASE_APK"

# 计算文件大小
APK_SIZE=$(du -h "$TARGET_APK" | cut -f1)

echo -e "${GREEN}构建成功!${NC}"
echo -e "  版本: v${VERSION}"
echo -e "  Build: ${BUILD_NUMBER}"
echo -e "  大小: ${APK_SIZE}"
echo -e "  路径: ${TARGET_APK}"
echo -e "  GitHub 体验包: ${RELEASE_APK}"
echo ""
echo -e "${YELLOW}发布建议:${NC}"
echo "  1. 正常提交并推送代码"
echo "  2. 将 ${RELEASE_APK} 上传为 GitHub Release 附件"
