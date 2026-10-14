#!/usr/bin/env bash
# 实验二 语料获取脚本：从公开网页下载不少于 10 个英文文档，清洗为纯文本，并生成文档描述表。
# 用法：bash scripts/fetch-corpus.sh
# 产物：data/document01.txt ... data/document12.txt、data/SOURCES.md
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DATA="$ROOT/data"
mkdir -p "$DATA"

python3 - "$DATA" <<'PY'
# -*- coding: utf-8 -*-
"""下载 + 清洗 12 篇英文技术文档。只用标准库（环境无 bs4/lxml）。"""
import os, re, sys, time, datetime
import urllib.request, urllib.error
from html.parser import HTMLParser

DATA = sys.argv[1]
MAX_BYTES = 120000            # 每篇截断上限，保证 MapReduce 作业秒级完成
TIMEOUT = 25
UA = {"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120 Safari/537.36"}

# (文件名, 标题, [主 URL, 备选 URL...])  —— 主源均已实测可达
SOURCES = [
    ("document01.txt", "Apache Hadoop — MapReduce Tutorial（MapReduce 教程）",
     ["https://hadoop.apache.org/docs/stable/hadoop-mapreduce-client/hadoop-mapreduce-client-core/MapReduceTutorial.html"]),
    ("document02.txt", "Apache Hadoop — HDFS Architecture（HDFS 架构设计）",
     ["https://hadoop.apache.org/docs/stable/hadoop-project-dist/hadoop-hdfs/HdfsDesign.html"]),
    ("document03.txt", "Apache Hadoop — Single Node Cluster Setup（单节点/伪分布式部署）",
     ["https://hadoop.apache.org/docs/stable/hadoop-project-dist/hadoop-common/SingleCluster.html"]),
    ("document04.txt", "Apache Spark — Quick Start（快速入门）",
     ["https://spark.apache.org/docs/latest/quick-start.html"]),
    ("document05.txt", "Apache Spark — RDD Programming Guide（RDD 编程指南）",
     ["https://spark.apache.org/docs/latest/rdd-programming-guide.html"]),
    ("document06.txt", "Python 3 Tutorial — An Informal Introduction（入门）",
     ["https://docs.python.org/3/tutorial/introduction.html"]),
    ("document07.txt", "Python 3 Tutorial — Data Structures（数据结构）",
     ["https://docs.python.org/3/tutorial/datastructures.html"]),
    ("document08.txt", "Linux Kernel — HOWTO do Linux kernel development（内核开发入门）",
     ["https://www.kernel.org/doc/html/latest/process/howto.html"]),
    ("document09.txt", "Linux Kernel — Submitting patches（提交内核补丁）",
     ["https://www.kernel.org/doc/html/latest/process/submitting-patches.html"]),
    ("document10.txt", "IETF RFC 1122 — Requirements for Internet Hosts（互联网主机要求）",
     ["https://www.rfc-editor.org/rfc/rfc1122.txt"]),
    ("document11.txt", "IETF RFC 2616 — Hypertext Transfer Protocol HTTP/1.1（HTTP 协议）",
     ["https://www.rfc-editor.org/rfc/rfc2616.txt"]),
    ("document12.txt", "Oracle Java SE — Collections Framework Overview（Java 集合框架综述）",
     ["https://docs.oracle.com/javase/8/docs/technotes/guides/collections/overview.html",
      "https://www.kernel.org/doc/html/latest/process/coding-style.html"]),
]
# 兜底备选：任一主源失败时依次尝试
EXTRA_FALLBACK = ["https://www.rfc-editor.org/rfc/rfc793.txt",
                  "https://hadoop.apache.org/docs/stable/hadoop-yarn/hadoop-yarn-site/YARN.html",
                  "https://en.wikipedia.org/wiki/Inverted_index"]


class TextExtractor(HTMLParser):
    """把 HTML 正文抽成纯文本：丢弃脚本/样式/导航等非正文区块。"""
    SKIP = {"script", "style", "noscript", "svg", "form", "nav", "header", "footer", "aside"}
    BLOCK = {"p", "div", "br", "li", "tr", "td", "th", "h1", "h2", "h3", "h4", "h5", "h6",
             "section", "article", "pre", "table", "ul", "ol", "blockquote", "dl", "dt", "dd"}

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts, self.skip = [], 0

    def handle_starttag(self, tag, attrs):
        if tag in self.SKIP:
            self.skip += 1
        elif tag in self.BLOCK:
            self.parts.append("\n")

    def handle_endtag(self, tag):
        if tag in self.SKIP:
            if self.skip > 0:
                self.skip -= 1
        elif tag in self.BLOCK:
            self.parts.append("\n")

    def handle_data(self, data):
        if self.skip == 0:
            self.parts.append(data)


TRANS = {0x00a0: " ", 0x2018: "'", 0x2019: "'", 0x201c: '"', 0x201d: '"',
         0x2013: "-", 0x2014: "-", 0x2026: "...", 0x00b7: " ", 0x00a9: "(c)"}


def to_plain(raw, is_html):
    text = raw
    if is_html:
        p = TextExtractor()
        try:
            p.feed(raw)
        except Exception:
            pass
        text = "".join(p.parts)
    text = text.translate(TRANS)
    text = text.replace("\r\n", "\n").replace("\r", "\n").replace("\f", "\n")
    text = "".join(ch if (ch == "\n" or ch == "\t" or 32 <= ord(ch) < 127) else " " for ch in text)
    text = re.sub(r"[ \t]+", " ", text)
    lines = [ln.strip() for ln in text.split("\n")]
    out, blank = [], 0
    for ln in lines:
        if ln:
            out.append(ln); blank = 0
        else:
            blank += 1
            if blank <= 1:
                out.append("")
    text = "\n".join(out).strip() + "\n"
    if len(text.encode("utf-8")) > MAX_BYTES:
        cut = text.encode("utf-8")[:MAX_BYTES].decode("utf-8", "ignore")
        text = cut.rsplit("\n", 1)[0] + "\n"
    return text


def fetch(url):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
        raw = r.read(3 * 1024 * 1024)
        ctype = r.headers.get("Content-Type", "")
        charset = "utf-8"
        m = re.search(r"charset=([\w-]+)", ctype)
        if m:
            charset = m.group(1)
        return raw.decode(charset, "replace"), ("html" in ctype.lower())


def main():
    today = datetime.date.today().isoformat()
    rows, fail = [], []
    for fname, title, urls in SOURCES:
        got = None
        for url in urls + EXTRA_FALLBACK:
            try:
                raw, is_html = fetch(url)
                text = to_plain(raw, is_html)
                if len(text) < 2000:           # 清洗后太短，视为无效页
                    print(f"  WARN 内容过短({len(text)}B)，换下一个源: {url}")
                    continue
                got = (url, text)
                break
            except Exception as e:
                print(f"  FAIL {url} -> {type(e).__name__}: {e}")
            time.sleep(1)
        if not got:
            fail.append(fname)
            print(f"[X] {fname} 所有源均失败")
            continue
        url, text = got
        with open(os.path.join(DATA, fname), "w", encoding="utf-8") as f:
            f.write(text)
        nwords = len(re.findall(r"[A-Za-z0-9']+", text))
        rows.append((fname, title, url, len(text), nwords, today))
        print(f"[OK] {fname}  {len(text):>7d} 字符  {nwords:>6d} 词  <- {url}")

    with open(os.path.join(DATA, "SOURCES.md"), "w", encoding="utf-8") as f:
        f.write("# 实验二 语料文档描述表\n\n")
        f.write(f"共 {len(rows)} 个英文文档，均从公开网页下载，清洗为纯文本后每篇截断到不超过 "
                f"{MAX_BYTES//1000} KB。下载日期：{today}。\n\n")
        f.write("| 序号 | 文件名 | 文档标题 | 来源 URL | 字符数 | 词数 |\n")
        f.write("|---|---|---|---|---|---|\n")
        for i, (fn, ti, u, c, w, d) in enumerate(rows, 1):
            f.write(f"| {i} | {fn} | {ti} | <{u}> | {c} | {w} |\n")

    print(f"\n完成：{len(rows)}/12 篇写入 {DATA}")
    if fail:
        raise SystemExit(f"以下文件下载失败：{fail}")

main()
PY
