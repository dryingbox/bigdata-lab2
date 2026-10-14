#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""实验二 倒排索引输出校验器（只用标准库）。

用法:
    python3 verify/check-output.py <输出文件> <停词表> [语料目录] [--expect <期望输出文件>]

校验内容:
    1) 每行严格匹配题目要求的格式:  单词 <文档名,词频>;<文档名,词频>;<total,总词频>.
    2) 单词不重复、单词不是停词、单词不含大写/标点。
    3) 文档名去重且按升序排列；每个文档名都来自语料目录（若提供了语料目录）。
    4) <total,N> 等于该行各文档词频之和。
    5) 若给了 --expect，则与“本地等价实现”的预期输出逐行比对（最强的一致性检查）。
退出码 0 表示全部通过，非 0 表示存在问题。
"""
import os
import re
import sys

LINE_RE = re.compile(r"^([a-z0-9]+) ((?:<[^,>]+,[0-9]+>;)+)<total,([0-9]+)>\.$")
ITEM_RE = re.compile(r"<([^,>]+),([0-9]+)>")


def read_text(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read().splitlines()


def main(argv):
    args = [a for a in argv[1:] if not a.startswith("--")]
    expect = None
    if "--expect" in argv:
        expect = argv[argv.index("--expect") + 1]
        args = [a for a in args if a != expect]

    if len(args) < 2:
        print(__doc__)
        return 2
    out_path, stop_path = args[0], args[1]
    data_dir = args[2] if len(args) > 2 else None

    stop = set()
    for line in read_text(stop_path):
        line = line.strip().lower()
        if line and not line.startswith("#"):
            stop.add(line)

    docs_in_corpus = set()
    if data_dir and os.path.isdir(data_dir):
        docs_in_corpus = {n for n in os.listdir(data_dir) if n.endswith(".txt")}

    lines = read_text(out_path)
    errors = []
    words = set()
    doc_count = set()
    total_occurrences = 0

    for i, line in enumerate(lines, 1):
        m = LINE_RE.match(line)
        if not m:
            errors.append("第 %d 行格式不符合要求: %r" % (i, line))
            continue
        word, body, declared = m.group(1), m.group(2), int(m.group(3))
        if word in words:
            errors.append("第 %d 行单词重复: %s" % (i, word))
        words.add(word)
        if word in stop:
            errors.append("第 %d 行出现停词: %s" % (i, word))
        prev = None
        s = 0
        for doc, n in ITEM_RE.findall(body):
            doc_count.add(doc)
            if docs_in_corpus and doc not in docs_in_corpus:
                errors.append("第 %d 行出现语料中不存在的文档名: %s" % (i, doc))
            if prev is not None and prev >= doc:
                errors.append("第 %d 行文档名重复或未升序: %s -> %s" % (i, prev, doc))
            prev = doc
            s += int(n)
        if s != declared:
            errors.append("第 %d 行 <total,%d> 与各文档词频之和 %d 不一致" % (i, declared, s))
        total_occurrences += declared

    print("校验对象 : %s" % out_path)
    print("词条数   : %d" % len(lines))
    print("文档数   : %d %s" % (len(doc_count), sorted(doc_count) if doc_count else ""))
    print("总词频   : %d" % total_occurrences)

    exit_code = 0
    if errors:
        print("\n[FAIL] 发现 %d 个问题（最多显示 10 条）：" % len(errors))
        for e in errors[:10]:
            print("  - " + e)
        exit_code = 1
    else:
        print("\n[PASS] 格式、单词唯一性、停词过滤、文档名升序、<total> 自洽性检查全部通过。")

    if expect:
        exp = read_text(expect)
        if exp == lines:
            print("[PASS] 与预期输出 %s 逐行完全一致（共 %d 行）。" % (expect, len(lines)))
        else:
            exit_code = 1
            print("[FAIL] 与预期输出 %s 不一致：期望 %d 行，实际 %d 行。" % (expect, len(exp), len(lines)))
            shown = 0
            for i in range(max(len(exp), len(lines))):
                a = exp[i] if i < len(exp) else "<无此行>"
                b = lines[i] if i < len(lines) else "<无此行>"
                if a != b:
                    print("  首个差异在第 %d 行:" % (i + 1))
                    print("    期望: " + a)
                    print("    实际: " + b)
                    shown += 1
                    if shown >= 5:
                        break
    return exit_code


if __name__ == "__main__":
    sys.exit(main(sys.argv))
