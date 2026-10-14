#!/usr/bin/env bash
# 实验二 编译脚本：用 Hadoop 的类路径编译 MapReduce 作业并打包成 jar。
# 用法：bash scripts/build.sh
# 注意：必须在能读到 $HADOOP_HOME 的用户下运行（例如 hadoop 用户），否则 `hadoop classpath` 会失败。
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export HADOOP_HOME="${HADOOP_HOME:-/home/hadoop/hadoop-3.3.6}"
export PATH="$PATH:$HADOOP_HOME/bin"
export HADOOP_CONF_DIR="${HADOOP_CONF_DIR:-$HADOOP_HOME/etc/hadoop}"

echo "HADOOP_HOME = $HADOOP_HOME"
hadoop version | head -2

rm -rf "$ROOT/build"
mkdir -p "$ROOT/build"

CP="$(hadoop classpath)"
set -x
javac -encoding UTF-8 -classpath "$CP" -d "$ROOT/build" "$ROOT"/src/*.java
jar cf "$ROOT/lab2-inverted-index.jar" -C "$ROOT/build" .
set +x

echo
echo "编译完成：$ROOT/lab2-inverted-index.jar"
echo "jar 内含的 class："
jar tf "$ROOT/lab2-inverted-index.jar"
