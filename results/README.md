# results/ 目录说明

| 文件 | 来源 | 说明 |
|---|---|---|
| `expected-output.txt` | `bash scripts/verify-local.sh`（本地等价实现） | 5368 行倒排索引结果，作为集群运行结果的比对基准（未提交到 git，体积约 330 KB） |
| `sample-output.txt` | `bash scripts/verify-local.sh`（自动生成） | 报告用样例：统计数字 + 题目样例关键词 + 普通词条示例 + 停词表生效验证（已提交） |
| `part-r-00000` | `bash scripts/run.sh`（集群运行后 `hdfs dfs -get`） | Hadoop 作业的真实输出（未提交到 git） |

集群跑完 `scripts/run.sh` 后，`verify/check-output.py` 会自动把 `part-r-00000` 与
`expected-output.txt` 逐行比对；两者一致即说明集群结果与本地等价实现完全吻合。

仓库中只提交 `sample-output.txt`（2 KB，便于查看输出样例）；`expected-output.txt` 与 `part-r-*`
已在 `.gitignore` 中忽略 —— 前者可由 `bash scripts/verify-local.sh` 随时再生，后者由集群运行产生。
