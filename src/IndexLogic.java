import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 实验二 倒排索引的“纯算法”部分：分词/过滤、停词表读取、输出行格式化。
 *
 * 这个类刻意不 import 任何 Hadoop 类，这样同一份逻辑可以：
 *   1) 被 MapReduce 作业 src/InvertedIndex.java 调用（集群上运行）；
 *   2) 被 verify/LocalInvertedIndex.java 调用（本地用普通 javac 编译运行，验证算法与输出格式）。
 * 两者共用同一套分词规则与格式化代码，保证“本地校验结果 = 集群输出”在逻辑上等价。
 */
public class IndexLogic {

    /**
     * 把一行文本切成合法词条：
     * 1) 转小写（Locale.ROOT，避免某些语言环境下 I/i 转换异常）；
     * 2) 以“非字母数字”为分隔符切分；
     * 3) 丢弃：空串、长度小于 2 的词、纯数字（不含任何字母）、停词表中的词。
     */
    public static List<String> tokenize(String line, Set<String> stopWords) {
        List<String> words = new ArrayList<String>();
        for (String t : line.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (t.length() < 2) {
                continue;
            }
            if (!t.matches(".*[a-z].*")) {   // 纯数字不是词
                continue;
            }
            if (stopWords.contains(t)) {
                continue;
            }
            words.add(t);
        }
        return words;
    }

    /** 读取停词表：一行一个词，全小写；跳过空行与以 # 开头的注释行。 */
    public static Set<String> readStopWords(File file) throws IOException {
        Set<String> stopWords = new HashSet<String>();
        BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim().toLowerCase(Locale.ROOT);
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                stopWords.add(line);
            }
        } finally {
            br.close();
        }
        return stopWords;
    }

    /**
     * 格式化一个单词的倒排索引，格式为：
     *   &lt;document01.txt,6&gt;;&lt;document02.txt,3&gt;;&lt;total,11&gt;.
     * perDoc 用 TreeMap 时文档名天然升序，输出可复现。
     */
    public static String formatLine(Map<String, Integer> perDoc, int total) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : perDoc.entrySet()) {
            sb.append('<').append(e.getKey()).append(',').append(e.getValue()).append(">;");
        }
        sb.append("<total,").append(total).append(">.");
        return sb.toString();
    }
}
