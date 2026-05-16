#!/bin/bash
# out_res.sh - 打包 res/index 并计算 res 目录下所有文件的 MD5
# 用法: bash out_res.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RES_DIR="$SCRIPT_DIR/res"
INDEX_DIR="$RES_DIR/index"
INDEX_ZIP="$RES_DIR/index.zip"
MD5_FILE="$RES_DIR/md5.txt"

echo "=== out_res.sh ==="
echo "资源目录: $RES_DIR"

# 1. 压缩 index 文件夹为 index.zip（仅存储模式，不压缩）
if [ -d "$INDEX_DIR" ]; then
    echo ">>> 正在打包 index/ -> index.zip (仅存储模式)..."
    rm -f "$INDEX_ZIP"
    cd "$RES_DIR"
    zip -0 -r -y "index.zip" "index"
    echo "    完成: $(ls -lh "$INDEX_ZIP" | awk '{print $5}')"
    cd "$SCRIPT_DIR"
else
    echo "!!! 警告: $INDEX_DIR 不存在，跳过打包"
fi

# 2. 计算 MD5
echo ">>> 正在计算 MD5..."
# 清空 md5.txt
> "$MD5_FILE"

# 遍历 res 目录下所有文件（不递归子目录，但 index 子目录要递归）
# 规则：res/* 下的文件 + res/index/* 下的文件，不包含其他子目录

# 2a. res 目录下的直接文件（不包括子目录）
find "$RES_DIR" -maxdepth 1 -type f | sort | while read -r f; do
    FILENAME=$(basename "$f")
    MD5=$(md5sum "$f" | cut -d' ' -f1)
    echo "$FILENAME:$MD5" >> "$MD5_FILE"
    echo "  $FILENAME -> $MD5"
done

# 2b. index 子目录下的所有文件（递归）
if [ -d "$INDEX_DIR" ]; then
    find "$INDEX_DIR" -type f | sort | while read -r f; do
        FILENAME=$(basename "$f")
        MD5=$(md5sum "$f" | cut -d' ' -f1)
        echo "$FILENAME:$MD5" >> "$MD5_FILE"
        echo "  $FILENAME -> $MD5"
    done
fi

echo "=== 完成 ==="
echo "输出文件:"
echo "  $INDEX_ZIP"
echo "  $MD5_FILE"
echo "MD5 条目数: $(wc -l < "$MD5_FILE")"
