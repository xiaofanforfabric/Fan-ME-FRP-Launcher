#!/bin/bash
# ============================================================
# 构建 frpc_jna 多平台动态库/二进制文件
# 输出到项目根目录的 res/ 文件夹
#
# 用法:
#   ./build_frpc_jna.sh          # 构建全部
#   ./build_frpc_jna.sh 1        # 仅构建 Windows DLL
#   ./build_frpc_jna.sh 2        # 仅构建 Linux SO
#   ./build_frpc_jna.sh 3        # 仅构建 Android 二进制
#   ./build_frpc_jna.sh 1 2 3    # 构建指定组合
#
# 依赖:
#   - Go (>=1.21)
#   - zig (用于交叉编译 C 代码, zig cc 自带所有平台工具链)
#     安装: https://ziglang.org/download/
#     MSYS2: pacman -S mingw-w64-clang-x86_64-zig
# ============================================================

set -e

# 项目根目录
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
# JNA 源码目录
JNA_DIR="$PROJECT_DIR/implementation/frp/jna"
# FRP 源码目录
FRP_DIR="$PROJECT_DIR/implementation/frp"
# 输出目录
OUTPUT_DIR="$PROJECT_DIR/res"

# 创建输出目录
mkdir -p "$OUTPUT_DIR"

echo "========================================"
echo "  Fan-ME-FRP-Launcher frpc_jna 多平台构建"
echo "========================================"
echo "项目目录: $PROJECT_DIR"
echo "输出目录: $OUTPUT_DIR"
echo ""

# 检查 Go 是否安装
if ! command -v go &> /dev/null; then
    echo "错误: 未找到 Go 编译器，请先安装 Go"
    exit 1
fi

# 检查 zig 是否安装
if ! command -v zig &> /dev/null; then
    echo "错误: 未找到 zig，请先安装 zig (https://ziglang.org/download/)"
    echo "  MSYS2: pacman -S mingw-w64-clang-x86_64-zig"
    exit 1
fi

GO_VERSION=$(go version)
ZIG_VERSION=$(zig version)
echo "Go 版本:  $GO_VERSION"
echo "Zig 版本: $ZIG_VERSION"
echo ""

# 解析参数: 如果没有参数则构建全部
BUILD_ALL=true
BUILD_DLL=false
BUILD_SO=false
BUILD_AEFL=false

if [ $# -gt 0 ]; then
    BUILD_ALL=false
    for arg in "$@"; do
        case $arg in
            1) BUILD_DLL=true ;;
            2) BUILD_SO=true  ;;
            3) BUILD_AEFL=true ;;
            *) echo "未知参数: $arg (可用: 1=DLL, 2=SO, 3=Android)" ;;
        esac
    done
fi

if [ "$BUILD_ALL" = true ]; then
    BUILD_DLL=true
    BUILD_SO=true
    BUILD_AEFL=true
fi

# ============================================================
# 1. Windows 构建 (DLL) - 使用 zig cc 交叉编译
# ============================================================
if [ "$BUILD_DLL" = true ]; then
    echo "----------------------------------------"
    echo "  1. 构建 Windows DLL"
    echo "----------------------------------------"

    cd "$JNA_DIR"

    echo ">>> 构建 windows_x64.dll (amd64)..."
    GOOS=windows GOARCH=amd64 CGO_ENABLED=1 \
      CC="zig cc -target x86_64-windows-gnu" \
      go build -buildmode=c-shared -o "$OUTPUT_DIR/windows_x64.dll" .
    echo "    完成: windows_x64.dll"

    echo ">>> 构建 windows_x86.dll (386)..."
    GOOS=windows GOARCH=386 CGO_ENABLED=1 \
      CC="zig cc -target x86-windows-gnu" \
      go build -buildmode=c-shared -o "$OUTPUT_DIR/windows_x86.dll" .
    echo "    完成: windows_x86.dll"

    echo ">>> 构建 windows_arm64.dll (arm64)..."
    GOOS=windows GOARCH=arm64 CGO_ENABLED=1 \
      CC="zig cc -target aarch64-windows-gnu" \
      go build -buildmode=c-shared -o "$OUTPUT_DIR/windows_arm64.dll" .
    echo "    完成: windows_arm64.dll"

    echo ""
fi

# ============================================================
# 2. Linux 构建 (SO) - 使用 zig cc 交叉编译
# ============================================================
if [ "$BUILD_SO" = true ]; then
    echo "----------------------------------------"
    echo "  2. 构建 Linux SO"
    echo "----------------------------------------"

    cd "$JNA_DIR"

    echo ">>> 构建 linux_amd64.so..."
    GOOS=linux GOARCH=amd64 CGO_ENABLED=1 \
      CC="zig cc -target x86_64-linux-gnu" \
      go build -buildmode=c-shared -o "$OUTPUT_DIR/linux_amd64.so" .
    echo "    完成: linux_amd64.so"

    echo ">>> 构建 linux_386.so..."
    GOOS=linux GOARCH=386 CGO_ENABLED=1 \
      CC="zig cc -target x86-linux-gnu" \
      go build -buildmode=c-shared -o "$OUTPUT_DIR/linux_386.so" .
    echo "    完成: linux_386.so"

    echo ">>> 构建 linux_arm64.so..."
    GOOS=linux GOARCH=arm64 CGO_ENABLED=1 \
      CC="zig cc -target aarch64-linux-gnu" \
      go build -buildmode=c-shared -o "$OUTPUT_DIR/linux_arm64.so" .
    echo "    完成: linux_arm64.so"

    echo ">>> 构建 linux_arm.so (armv7)..."
    GOOS=linux GOARCH=arm GOARM=7 CGO_ENABLED=1 \
      CC="zig cc -target arm-linux-gnueabihf" \
      go build -buildmode=c-shared -o "$OUTPUT_DIR/linux_arm.so" .
    echo "    完成: linux_arm.so"

    echo ""
fi

# ============================================================
# 3. Android 构建 (二进制文件, CGO_ENABLED=0)
#    Android 使用 exec 模式启动原始 frpc 二进制，不依赖 JNA
#    使用 -tags "frpc noweb" 跳过 web 前端资源嵌入
# ============================================================
if [ "$BUILD_AEFL" = true ]; then
    echo "----------------------------------------"
    echo "  3. 构建 Android 二进制文件"
    echo "----------------------------------------"

    cd "$FRP_DIR"

    echo ">>> 构建 frpc_Android_arm64-v8a (linux/arm64)..."
    GOOS=linux GOARCH=arm64 CGO_ENABLED=0 \
      go build -trimpath -ldflags="-s -w" -tags "frpc noweb" \
      -o "$OUTPUT_DIR/frpc_Android_arm64-v8a" ./cmd/frpc
    echo "    完成: frpc_Android_arm64-v8a"

    echo ">>> 构建 frpc_Android_armeabi-v7a (linux/arm/7)..."
    GOOS=linux GOARCH=arm GOARM=7 CGO_ENABLED=0 \
      go build -trimpath -ldflags="-s -w" -tags "frpc noweb" \
      -o "$OUTPUT_DIR/frpc_Android_armeabi-v7a" ./cmd/frpc
    echo "    完成: frpc_Android_armeabi-v7a"

    echo ""
fi

# ============================================================
# 完成
# ============================================================
echo "========================================"
echo "  构建完成!"
echo "========================================"
echo ""
echo "输出文件列表:"
ls -lh "$OUTPUT_DIR" | grep -E "(windows_|linux_|frpc_Android)"
echo ""

# 移动头文件到输出目录（如果生成在源码目录）
HEADER_FILE="$OUTPUT_DIR/frpc_jna.h"
if [ -f "$JNA_DIR/frpc_jna.h" ]; then
    mv "$JNA_DIR/frpc_jna.h" "$HEADER_FILE" 2>/dev/null || true
fi

echo "头文件: $HEADER_FILE"
echo ""
echo "所有文件已输出到: $OUTPUT_DIR"
