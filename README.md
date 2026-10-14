# 大数据管理与分析 实验二：文档倒排索引算法实现（Inverted Index）

山东大学《大数据管理与分析》课程实验二。本仓库从公开网页下载 12 篇英文技术文档，建立停词表，
用 Hadoop MapReduce 编写一个作业，为所有单词建立倒排索引（单词 → 包含它的文档及各文档中的词频）。

输出格式：

```
单词 <文档名,该文档中的词频>;<文档名,词频>;<total,所有文档中的总词频>.
```

例如：

```
data <document01.txt,16>;<document02.txt,75>;<document03.txt,2>;<document04.txt,7>;<document05.txt,62>;<document06.txt,2>;<document07.txt,19>;<document10.txt,33>;<document11.txt,32>;<document12.txt,2>;<total,250>.
hadoop <document01.txt,91>;<document02.txt,36>;<document03.txt,58>;<document04.txt,5>;<document05.txt,38>;<total,228>.
```

> 本仓库只包含**实验代码、语料与脚本**；实验报告（docx/pdf）与报告截图不纳入版本管理。

## 一、目录结构

```
lab2/
├── data/                     12 篇英文技术文档（document01.txt … document12.txt）+ SOURCES.md（来源与字数描述表）
├── stopwords.txt             停词表，276 个英文停用词，一行一个
├── src/
│   ├── IndexLogic.java       纯算法部分：分词/过滤、读停词表、输出行格式化（不依赖 Hadoop）
│   └── InvertedIndex.java    MapReduce 作业：Mapper + Combiner + Reducer + Driver
├── verify/
│   ├── LocalInvertedIndex.java  本地等价实现（单进程模拟 Map->Combine->Reduce，用普通 javac 编译运行）
│   └── check-output.py          输出校验器（格式、单词唯一、停词过滤、文档升序、<total> 自洽、与预期输出比对）
├── scripts/
│   ├── fetch-corpus.sh        下载并清洗 12 篇文档，生成 data/SOURCES.md
│   ├── verify-local.sh        本地校验（不需要 Hadoop），产出 results/expected-output.txt
│   ├── build.sh               用 hadoop classpath 编译并打包 lab2-inverted-index.jar
│   └── run.sh                 上传语料到 HDFS -> 运行作业 -> 打印关键结果 -> 取回完整结果
├── results/                   sample-output.txt（输出样例，随仓库提交）
│                              expected-output.txt / part-r-* 由脚本生成，不提交
└── docs/实验二-操作指导.md     逐步操作指导 + 真实踩坑记录（权限、启动方式、报错表）
```

## 二、实验环境

- 操作系统：Ubuntu 22.04.5 LTS 64 位（VirtualBox 虚拟机，6 核 CPU / 3.8 GiB 内存 / 63 GB 磁盘）
- JDK：OpenJDK 1.8.0_504（`javac` / `java`）；Python 3（仅用标准库）
- 大数据平台：Apache Hadoop 3.3.6 伪分布式单节点（NameNode、DataNode、SecondaryNameNode、ResourceManager、NodeManager 各 1 个，`fs.defaultFS = hdfs://localhost:9000`）

## 三、算法与设计

1. **分词与过滤规则**（`IndexLogic.tokenize`）：整行转小写（`Locale.ROOT`）→ 按 `[^a-z0-9]+` 切分 →
   丢弃空串、长度小于 2 的词、纯数字，以及停词表中的词。
2. **Mapper**：`((FileSplit) context.getInputSplit()).getPath().getName()` 取输入文件名作为文档名，
   对每个合法词输出 `<单词, "文档名\t1">`。
3. **Combiner**：对同一单词，先把“同一文档内的词频”在本地加起来，输出格式与 Mapper 的 value 格式**完全一致**
   （都是 `文档名\t词频`），因此无论 Combiner 是否被触发，Reducer 都能正确解析 —— 这是这类作业最常见的坑。
4. **Reducer**：用 `TreeMap` 汇总单词在各文档的词频（文档名升序、结果可复现）并计算 `total`，
   拼装成题目要求的输出格式。
5. **单 Reducer**（`setNumReduceTasks(1)`）+ 单词由 MapReduce 按 key 排序，使输出为一个整体有序的文件；
   `mapreduce.output.textoutputformat.separator=" "` 把默认的制表符分隔改为空格，与题目样例一致。
6. **停词表通过分布式缓存分发**：`job.addCacheFile(new URI(hdfsStopwordsPath + "#stopwords.txt"))`，
   每个任务在本地工作目录读取 `stopwords.txt`；读不到会立刻报错，不会静默降级。

## 四、怎么运行

### 1) 本地校验（不需要 Hadoop，先确认算法与输出格式正确）

```bash
git clone https://github.com/dryingbox/bigdata-lab2.git
cd bigdata-lab2
bash scripts/verify-local.sh
```

它会编译 `src/IndexLogic.java` + `verify/LocalInvertedIndex.java`，跑出 `results/expected-output.txt`，
用 `verify/check-output.py` 校验格式与自洽性，最后生成便于写报告的 `results/sample-output.txt`。

### 2) 在 Hadoop 集群上运行（需在 hadoop 用户下执行，且 HDFS/YARN 已启动）

```bash
cd /home/hadoop/lab2
bash scripts/build.sh                                 # 编译打包 lab2-inverted-index.jar
bash scripts/run.sh 2>&1 | tee /tmp/lab2-out/run.log  # 上传语料 -> 运行 -> 打印结果 -> 取回输出
```

若 HDFS 根目录写权限被拒，可换工作目录：`HDFS_BASE=/user/hadoop/lab2 bash scripts/run.sh`

HDFS 布局：`/lab2/data/document*.txt`、`/lab2/stopwords.txt`，输出在 `/lab2/out/inverted-index/`。

## 五、运行结果（真实数据）

本地等价实现（`bash scripts/verify-local.sh`）：

| 指标 | 数值 |
|---|---|
| 输入文档数 | 12 |
| 停词表词数 | 276 |
| 词条数（不同单词数，= 输出行数） | 5368 |
| 总词频（出现次数之和） | 46844 |
| 禁用停词表时的词条数 | 5594（多 226 个，4.0%） |
| 禁用停词表时的总词频 | 78527（停词表过滤掉 31683 次出现，占 40.3%） |

集群实测（Hadoop 3.3.6 伪分布式，作业 `job_1791982752452_0001` 约 35 秒运行完成）：

| 指标 | 数值 |
|---|---|
| 作业状态 | `completed successfully`（map 100% / reduce 100%，Counters: 54） |
| 任务数 | 12 个 Map（全部 Data-local）+ 1 个 Reduce |
| Map input records | 14008 |
| Map output records | 46844 |
| Combine output records | 11659（shuffle 数据量降到材料化前的 27.6%） |
| Reduce input groups / output records | 5368 / 5368 |
| 输出文件 | `/lab2/out/inverted-index/part-r-00000`，5368 行 / 328958 字节 |
| 与本地基准比对 | `[PASS] 与预期输出逐行完全一致（共 5368 行）` |

`verify/check-output.py` 对输出文件逐行检查：格式、单词唯一、无停词、文档名升序、`<total>` 与各文档词频之和一致
—— 全部通过。

MapReduce 源码另外做过一次编译预演：用 Hadoop 3.3.6 官方 API jar
（`hadoop-client-api-3.3.6.jar`，Maven Central）执行 `javac -Xlint:all`，退出码 0、无警告，
产物为 `IndexLogic.class`、`InvertedIndex.class` 及其三个内部类 —— 与集群上 `scripts/build.sh` 的结果同构。

## 六、来源与说明

- 12 篇文档均从公开网页下载并清洗为纯文本，来源 URL、标题、字符数、词数见 `data/SOURCES.md`；
  文档版权归各自出版方（Apache Software Foundation、Python Software Foundation、Linux Kernel 社区、
  IETF、Oracle 等），此处仅用于课程实验的语料与索引实验。可用 `bash scripts/fetch-corpus.sh` 复现获取与清洗过程。
- 停词表为通用英文停用词表，可按需扩充（例如 RFC 文本的页眉页脚词 `page`、`rfc`）。
- `docs/实验二-操作指导.md` 记录了本次实验真实遇到的坑（两个用户家目录互不可读时的工程复制方案、
  `dfs.permissions.enabled`、用 `hdfs --daemon start` 绕开未配置的 SSH、`hdfs dfs -cat | head` 触发 SIGPIPE 等）与报错处理表。
- 实验报告按课程要求单独提交（不超过 8 页，宋体小四，1.25 倍行距）。

代码仓库（公开）：**https://github.com/dryingbox/bigdata-lab2**
