#!/usr/bin/env bash
# 实验二 本地校验：不依赖 Hadoop，用系统 javac 编译“纯算法部分”并运行等价实现，
# 产出 results/expected-output.txt（集群跑完后的比对基准）与 results/sample-output.txt（报告用样例）。
# 用法：bash scripts/verify-local.sh
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$ROOT/verify/build"
OUT="$ROOT/results"
mkdir -p "$BUILD" "$OUT"

echo "### 1) 编译 src/IndexLogic.java + verify/LocalInvertedIndex.java（无需 Hadoop 依赖）"
set -x
javac -encoding UTF-8 -d "$BUILD" "$ROOT/src/IndexLogic.java" "$ROOT/verify/LocalInvertedIndex.java"
set +x

echo
echo "### 2) 运行本地等价实现（Map -> Combine -> Reduce 单进程模拟）"
java -cp "$BUILD" LocalInvertedIndex "$ROOT/data" "$ROOT/stopwords.txt" "$OUT/expected-output.txt"

echo
echo "### 3) 用校验器检查输出格式与自洽性"
python3 "$ROOT/verify/check-output.py" "$OUT/expected-output.txt" "$ROOT/stopwords.txt" "$ROOT/data"

echo
echo "### 4) 生成便于提交/写报告的样例文件 results/sample-output.txt"
TOTAL_TOKENS=$(awk -F'<total,' '{gsub(/>\.$/,"",$2); s+=$2} END{print s}' "$OUT/expected-output.txt")
{
  echo "# 实验二 倒排索引 输出样例（由 scripts/verify-local.sh 自动生成）"
  echo
  echo "## 1) 统计"
  echo "输入文档数：$(ls "$ROOT"/data/document*.txt | wc -l)"
  echo "停词表词数：$(grep -cvE '^[[:space:]]*(#|$)' "$ROOT/stopwords.txt")"
  echo "词条数（不同单词数，即输出行数）：$(wc -l < "$OUT/expected-output.txt")"
  echo "总词频（所有单词在所有文档中的出现次数之和）：$TOTAL_TOKENS"
  echo
  echo "## 2) 题目样例中的关键词"
  grep -E '^(data|hadoop|index|mapreduce|search) ' "$OUT/expected-output.txt" || true
  echo
  echo "## 3) 普通词条示例（字母开头的词条，取前 20 行）"
  grep -E '^[a-z]' "$OUT/expected-output.txt" | head -20
  echo
  echo "## 4) 停词表生效验证（下面这条 grep 应无输出）"
  if grep -E '^(the|and|of|is) ' "$OUT/expected-output.txt" >/dev/null; then
    echo "!! 停词表中的词出现在输出中，停词表未生效"
  else
    echo "（无输出 = 停词表已生效：the/and/of/is 均未进入倒排索引）"
  fi
} > "$OUT/sample-output.txt"

echo
echo "本地校验完成："
echo "  预期输出 : $OUT/expected-output.txt"
echo "  样例文件 : $OUT/sample-output.txt"
