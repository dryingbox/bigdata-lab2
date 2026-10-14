import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 实验二：倒排索引的“本地等价实现”（不依赖 Hadoop，用普通 javac 就能编译运行）。
 *
 * 目的：在没有 Hadoop 环境的机器上，用同一份分词/过滤/格式化逻辑（IndexLogic）把
 *       Map -> Combine -> Reduce 三个阶段在单进程里跑一遍，从而
 *       1) 验证算法正确、输出格式与题目要求完全一致；
 *       2) 先得到一份“预期输出” results/expected-output.txt，集群跑完后可以逐行比对。
 *
 * 用法: java LocalInvertedIndex <语料目录> <停词表> <输出文件>
 * 例如: java LocalInvertedIndex data stopwords.txt results/expected-output.txt
 */
public class LocalInvertedIndex {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("用法: java LocalInvertedIndex <语料目录> <停词表> <输出文件>");
            System.exit(2);
        }
        File dataDir = new File(args[0]);
        File stopwordFile = new File(args[1]);
        File outFile = new File(args[2]);

        Set<String> stopWords = IndexLogic.readStopWords(stopwordFile);
        File[] docs = dataDir.listFiles(new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.endsWith(".txt");
            }
        });
        if (docs == null || docs.length == 0) {
            throw new IOException("语料目录中没有 .txt 文档: " + dataDir);
        }
        Arrays.sort(docs, new Comparator<File>() {
            public int compare(File a, File b) {
                return a.getName().compareTo(b.getName());
            }
        });

        System.out.println("=== 实验二 倒排索引 本地等价实现（无 Hadoop 依赖）===");
        System.out.println("语料目录 : " + dataDir.getAbsolutePath());
        System.out.println("文档数量 : " + docs.length);
        System.out.println("停词表   : " + stopwordFile.getAbsolutePath() + "（" + stopWords.size() + " 个停词）");
        System.out.println();

        // ---------- Map + Combine 阶段：每个文档分词并统计 word -> (doc -> count) ----------
        long t0 = System.currentTimeMillis();
        TreeMap<String, TreeMap<String, Integer>> index = buildIndex(docs, stopWords);
        long t1 = System.currentTimeMillis();

        // ---------- Reduce 阶段：拼装输出行 ----------
        StringBuilder sb = new StringBuilder();
        int records = 0;
        for (Map.Entry<String, TreeMap<String, Integer>> e : index.entrySet()) {
            int total = 0;
            for (int n : e.getValue().values()) {
                total += n;
            }
            // 与集群上的 TextOutputFormat（separator=" "）一致：单词 + 空格 + 索引列表 + 换行
            sb.append(e.getKey()).append(' ').append(IndexLogic.formatLine(e.getValue(), total)).append('\n');
            records++;
        }
        long t2 = System.currentTimeMillis();

        Files.write(Paths.get(outFile.getAbsolutePath()), sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.printf("Map+Combine 耗时 %d ms，Reduce+写出耗时 %d ms%n", t1 - t0, t2 - t1);
        System.out.printf("输出文件 : %s%n", outFile.getAbsolutePath());
        System.out.printf("词条总数（输出行数）: %d，文件大小: %d 字节%n%n", records, outFile.length());

        // ---------- 输出样例 ----------
        System.out.println("---- 输出前 5 行 ----");
        printHead(outFile, 5);
        System.out.println();
        System.out.println("---- 题目样例中的关键词 ----");
        for (String w : new String[]{"data", "hadoop", "index", "mapreduce", "search"}) {
            String line = findLine(index, w);
            System.out.println(line == null ? (w + "  (该语料中未出现)") : line);
        }
        System.out.println();

        System.out.println("---- 词频最高的 10 个词 ----");
        List<Map.Entry<String, Integer>> tops = new ArrayList<Map.Entry<String, Integer>>();
        for (Map.Entry<String, TreeMap<String, Integer>> e : index.entrySet()) {
            int total = 0;
            for (int n : e.getValue().values()) {
                total += n;
            }
            tops.add(new java.util.AbstractMap.SimpleEntry<String, Integer>(e.getKey(), total));
        }
        Collections.sort(tops, new Comparator<Map.Entry<String, Integer>>() {
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return b.getValue() - a.getValue();
            }
        });
        for (int i = 0; i < Math.min(10, tops.size()); i++) {
            System.out.printf("  %2d. %-16s 总词频 %d，出现在 %d 个文档中%n", i + 1, tops.get(i).getKey(),
                    tops.get(i).getValue(), index.get(tops.get(i).getKey()).size());
        }
        System.out.println();

        // ---------- 停词表效果对照：同样语料，不用停词表 ----------
        TreeMap<String, TreeMap<String, Integer>> raw = buildIndex(docs, new HashSet<String>());
        int tokenWith = totalOccurrences(index);
        int tokenWithout = totalOccurrences(raw);
        System.out.println("---- 停词表效果对照 ----");
        System.out.printf("词条数（不同单词）: 启用停词表 %d，禁用停词表 %d（停词表去掉 %d 个词条，%.1f%%）%n",
                index.size(), raw.size(), raw.size() - index.size(),
                100.0 * (raw.size() - index.size()) / raw.size());
        System.out.printf("总词频（所有出现次数）: 启用停词表 %d，禁用停词表 %d（过滤掉 %d 次停词出现，%.1f%%）%n",
                tokenWith, tokenWithout, tokenWithout - tokenWith,
                100.0 * (tokenWithout - tokenWith) / tokenWithout);
        for (String w : new String[]{"the", "and", "of", "is"}) {
            System.out.printf("  %-5s 启用停词表: %s | 禁用停词表: %s%n", w,
                    index.containsKey(w) ? "出现(异常)" : "已过滤",
                    raw.containsKey(w) ? "出现" : "未出现");
        }
        System.out.println();

        // ---------- 自检：格式、total 自洽、单词唯一、文档名升序 ----------
        int checked = selfCheck(outFile, stopWords, docs);
        System.out.printf("自检通过：%d 行全部满足题目要求的输出格式，且 <total> 等于各文档词频之和。%n", checked);
    }

    /** Map + Combine：读每个文档，分词后统计 word -> (doc -> count)，相当于 Mapper + Combiner 的最终效果 */
    private static TreeMap<String, TreeMap<String, Integer>> buildIndex(File[] docs, Set<String> stopWords)
            throws IOException {
        TreeMap<String, TreeMap<String, Integer>> index = new TreeMap<String, TreeMap<String, Integer>>();
        for (File doc : docs) {
            String docName = doc.getName();
            List<String> lines = Files.readAllLines(doc.toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                for (String word : IndexLogic.tokenize(line, stopWords)) {
                    TreeMap<String, Integer> perDoc = index.get(word);
                    if (perDoc == null) {
                        perDoc = new TreeMap<String, Integer>();
                        index.put(word, perDoc);
                    }
                    Integer old = perDoc.get(docName);
                    perDoc.put(docName, old == null ? 1 : old + 1);
                }
            }
        }
        return index;
    }

    /** 整个索引的总词频（所有文档所有词的出现次数之和） */
    private static int totalOccurrences(TreeMap<String, TreeMap<String, Integer>> index) {
        int total = 0;
        for (TreeMap<String, Integer> perDoc : index.values()) {
            for (int n : perDoc.values()) {
                total += n;
            }
        }
        return total;
    }

    private static String findLine(TreeMap<String, TreeMap<String, Integer>> index, String word) {        TreeMap<String, Integer> perDoc = index.get(word);
        if (perDoc == null) {
            return null;
        }
        int total = 0;
        for (int n : perDoc.values()) {
            total += n;
        }
        return word + " " + IndexLogic.formatLine(perDoc, total);
    }

    private static void printHead(File f, int n) throws IOException {
        PrintStream out = System.out;
        List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        for (int i = 0; i < Math.min(n, lines.size()); i++) {
            out.println(lines.get(i));
        }
    }

    /** 自检：格式匹配、total 自洽、单词唯一、文档名去重且升序、无停词残留、文档名来自语料 */
    private static int selfCheck(File outFile, Set<String> stopWords, File[] docs) throws IOException {
        List<String> lines = Files.readAllLines(outFile.toPath(), StandardCharsets.UTF_8);
        Set<String> seen = new HashSet<String>();
        Set<String> docNames = new HashSet<String>();
        for (File d : docs) {
            docNames.add(d.getName());
        }
        Pattern item = Pattern.compile("<([^,>]+),(\\d+)>");
        int i = 0;
        for (String line : lines) {
            i++;
            if (!line.matches("^[a-z0-9]+ (<[^,>]+,[0-9]+>;)+<total,[0-9]+>\\.$")) {
                throw new IllegalStateException("第 " + i + " 行不符合题目要求的输出格式: " + line);
            }
            int sp = line.indexOf(' ');
            String word = line.substring(0, sp);
            if (!seen.add(word)) {
                throw new IllegalStateException("第 " + i + " 行单词重复: " + word);
            }
            if (stopWords.contains(word)) {
                throw new IllegalStateException("第 " + i + " 行出现停词: " + word);
            }
            int totalPos = line.lastIndexOf("<total,");
            String body = line.substring(sp + 1, totalPos);                       // "<doc,n>;<doc,n>;"
            String declared = line.substring(totalPos + 7, line.length() - 2);    // total 的数字部分
            int total = 0;
            String prevDoc = null;
            Matcher m = item.matcher(body);
            while (m.find()) {
                String doc = m.group(1);
                if (!docNames.contains(doc)) {
                    throw new IllegalStateException("第 " + i + " 行出现未知文档名: " + doc);
                }
                if (prevDoc != null && prevDoc.compareTo(doc) >= 0) {
                    throw new IllegalStateException("第 " + i + " 行文档名重复或未按升序排列: " + line);
                }
                prevDoc = doc;
                total += Integer.parseInt(m.group(2));
            }
            if (total != Integer.parseInt(declared)) {
                throw new IllegalStateException("第 " + i + " 行 <total> 与各文档词频之和不一致: " + line);
            }
        }
        return lines.size();
    }
}
