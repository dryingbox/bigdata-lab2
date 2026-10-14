#!/usr/bin/env bash
# 实验二 运行脚本：把语料与停词表上传到 HDFS -> 运行倒排索引作业 -> 打印关键结果 -> 取回完整结果。
# 用法：bash scripts/run.sh                              （HDFS 工作目录 /lab2）
#       HDFS_BASE=/user/hadoop/lab2 bash scripts/run.sh   （/lab2 无权限时用这个）
# 注意：必须在 hadoop 用户下、且已执行 start-dfs.sh / start-yarn.sh 之后运行。
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export HADOOP_HOME="${HADOOP_HOME:-/home/hadoop/hadoop-3.3.6}"
export PATH="$PATH:$HADOOP_HOME/bin"
export HADOOP_CONF_DIR="${HADOOP_CONF_DIR:-$HADOOP_HOME/etc/hadoop}"

HDFS_BASE="${HDFS_BASE:-/lab2}"
IN="$HDFS_BASE/data"
STOP="$HDFS_BASE/stopwords.txt"
OUT="$HDFS_BASE/out/inverted-index"
JAR="$ROOT/lab2-inverted-index.jar"
LOG=/tmp/lab2-out/run.log

mkdir -p /tmp/lab2-out "$ROOT/results"
exec > >(tee -a "$LOG") 2>&1

if [ ! -f "$JAR" ]; then
  echo "缺少 $JAR，请先运行：bash scripts/build.sh"
  exit 1
fi

echo "================ 0) 实验环境 ================"
hadoop version | head -3
echo "--- HDFS 概况 ---"
# 用 sed 而不是 head：head 提前退出会让管道左端收到 SIGPIPE，
# 例如 hdfs dfs -cat ... | head -30 会打印 "cat: Unable to write to output stream."（无害但像报错）
hdfs dfsadmin -report 2>/dev/null | sed -n '1,12p' || echo "(dfsadmin 不可用，跳过)"

echo
echo "================ 1) 上传语料与停词表到 $HDFS_BASE ================"
set -x
hdfs dfs -rm -r -f "$HDFS_BASE"
hdfs dfs -mkdir -p "$IN"
hdfs dfs -put -f "$ROOT"/data/document*.txt "$IN/"
hdfs dfs -put -f "$ROOT/stopwords.txt" "$STOP"
hdfs dfs -ls -R "$HDFS_BASE"
set +x
echo "--- HDFS 上的语料（应为 12 个 document*.txt，列表第一行是表头） ---"
hdfs dfs -ls "$IN" | wc -l

echo
echo "================ 2) 运行 MapReduce 倒排索引作业 ================"
set -x
hadoop jar "$JAR" InvertedIndex "$IN" "$OUT" "$STOP"
rc=$?
set +x
if [ "$rc" != "0" ]; then
  echo "!!!! 作业失败，退出码 $rc，请把上面的日志发回以便定位问题。"
  exit "$rc"
fi

echo
echo "================ 3) 输出文件 ================"
hdfs dfs -ls "$OUT"

echo
echo "================ 4) 词条总数（输出行数 = 不同单词个数） ================"
hdfs dfs -cat "$OUT/part-r-*" | wc -l

echo
echo "================ 5) 输出前 30 行 ================"
hdfs dfs -cat "$OUT/part-r-*" | sed -n '1,30p'

echo
echo "================ 6) 题目样例中的关键词 ================"
hdfs dfs -cat "$OUT/part-r-*" | grep -E '^(data|hadoop|index|mapreduce|search) ' || echo "(没有匹配到，检查语料)"

echo
echo "================ 7) 停词表生效验证（下面应无输出） ================"
if hdfs dfs -cat "$OUT/part-r-*" | grep -E '^(the|and|of|is) '; then
  echo "!!!! 停词表中的词出现在输出中，停词表未生效"
else
  echo "（无输出 = 停词表已生效：the/and/of/is 均未进入倒排索引）"
fi

echo
echo "================ 8) 取回完整结果到本机 results/ ================"
set -x
hdfs dfs -get -f "$OUT/part-r-*" "$ROOT/results/"
set +x
ls -l "$ROOT/results/"

echo
echo "================ 9) 与本地预期输出比对（可选） ================"
if [ -f "$ROOT/results/expected-output.txt" ] && command -v python3 >/dev/null 2>&1; then
  python3 "$ROOT/verify/check-output.py" "$ROOT/results/part-r-00000" \
      "$ROOT/stopwords.txt" "$ROOT/data" --expect "$ROOT/results/expected-output.txt" || true
else
  echo "(跳过：缺少 results/expected-output.txt 或 python3；可在本机用 scripts/verify-local.sh 生成预期输出)"
fi

echo
echo "================ 完成 ================"
echo "完整日志：$LOG"
echo "结果文件：$ROOT/results/part-r-00000"
echo "写报告时需要的截图：编译成功、上面的作业进度与计数器、第 3/5/6/7 节输出。"
