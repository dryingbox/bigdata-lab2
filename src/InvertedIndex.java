import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 实验二：文档倒排索引（Inverted Index）
 *
 * 功能：对输入目录下的多个英文文档建立倒排索引，输出每个单词出现在哪些文档中、出现多少次，
 *       以及该单词在所有文档中的总词频，格式为：
 *         单词 &lt;文档名,词频&gt;;&lt;文档名,词频&gt;;&lt;total,总词频&gt;.
 *       例如：data &lt;document01.txt,6&gt;;&lt;document02.txt,3&gt;;&lt;document05.txt,2&gt;;&lt;total,11&gt;.
 *
 * 设计要点：
 *   1) 一个 MapReduce 作业完成：Mapper 分词并输出 (单词, "文档名\t1")；Combiner 做本地聚合；
 *      Reducer 汇总所有文档的词频并拼装成题目要求的输出格式。
 *   2) Combiner 的输出格式与 Mapper 的 value 格式完全一致（都是 "文档名\t局部词频"），
 *      所以无论 Combiner 是否被触发，Reducer 都能正确解析，这是这类作业最容易踩的坑。
 *   3) 停词表通过分布式缓存（DistributedCache）分发给每个任务，任务在本地工作目录读取 stopwords.txt。
 *   4) 单词集用 TreeMap 聚合，文档名天然升序；单词本身由 MapReduce 按 key 排序，
 *      再配合 setNumReduceTasks(1)，输出就是一个整体有序的单个文件，便于比对与展示。
 *   5) 词条格式与 stopwords.txt 的读取由 IndexLogic 提供（该类不含任何 Hadoop 依赖，
 *      因此 verify/LocalInvertedIndex.java 能用同一份逻辑在本地校验算法与输出格式）。
 */
public class InvertedIndex {

    /** 停词表在分布式缓存中的本地文件名（Driver 用 "<HDFS路径>#stopwords.txt" 指定） */
    public static final String STOPWORD_FILE = "stopwords.txt";

    /** Mapper：<行号, 一行文本> -> <单词, "文档名\t1">，输入文件名即文档名 */
    public static class IndexMapper extends Mapper<LongWritable, Text, Text, Text> {
        private final Text outKey = new Text();
        private final Text outValue = new Text();
        private Set<String> stopWords;
        private String docName;

        @Override
        protected void setup(Context context) throws IOException {
            File file = new File(STOPWORD_FILE);
            if (!file.exists()) {
                throw new IOException("找不到停词表 " + STOPWORD_FILE
                        + "：请先把 stopwords.txt 上传到 HDFS，并作为第 3 个参数传入，"
                        + "Driver 会通过分布式缓存把它分发到每个任务的工作目录。");
            }
            stopWords = IndexLogic.readStopWords(file);
            // 一个输入分片对应一个文档文件，取文件名作为文档名
            docName = ((FileSplit) context.getInputSplit()).getPath().getName();
        }

        @Override
        protected void map(LongWritable key, Text value, Context context)
                throws IOException, InterruptedException {
            List<String> words = IndexLogic.tokenize(value.toString(), stopWords);
            for (String word : words) {
                outKey.set(word);
                outValue.set(docName + "\t1");
                context.write(outKey, outValue);
            }
        }
    }

    /** Combiner：<单词, ["文档名\t1", ...]> -> <单词, "文档名\t该文档内的局部词频">，减少 shuffle 数据量 */
    public static class IndexCombiner extends Reducer<Text, Text, Text, Text> {
        private final Text outValue = new Text();

        @Override
        protected void reduce(Text key, Iterable<Text> values, Context context)
                throws IOException, InterruptedException {
            Map<String, Integer> perDoc = new HashMap<String, Integer>();
            for (Text value : values) {
                String s = value.toString();          // 必须立即取值：Iterable 会复用同一个对象
                int tab = s.indexOf('\t');
                String doc = s.substring(0, tab);
                int n = Integer.parseInt(s.substring(tab + 1));
                Integer old = perDoc.get(doc);
                perDoc.put(doc, old == null ? n : old + n);
            }
            for (Map.Entry<String, Integer> e : perDoc.entrySet()) {
                outValue.set(e.getKey() + "\t" + e.getValue());
                context.write(key, outValue);
            }
        }
    }

    /** Reducer：<单词, ["文档名\t词频", ...]> -> <单词, "<文档名,词频>;...;<total,总词频>."> */
    public static class IndexReducer extends Reducer<Text, Text, Text, Text> {
        private final Text outValue = new Text();

        @Override
        protected void reduce(Text key, Iterable<Text> values, Context context)
                throws IOException, InterruptedException {
            TreeMap<String, Integer> perDoc = new TreeMap<String, Integer>();   // 文档名升序，结果可复现
            int total = 0;
            for (Text value : values) {
                String s = value.toString();
                int tab = s.indexOf('\t');
                String doc = s.substring(0, tab);
                int n = Integer.parseInt(s.substring(tab + 1));
                Integer old = perDoc.get(doc);
                perDoc.put(doc, old == null ? n : old + n);
                total += n;
            }
            outValue.set(IndexLogic.formatLine(perDoc, total));
            context.write(key, outValue);   // 输出由 TextOutputFormat 拼接为：单词 + 分隔符 + 索引列表
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("用法: InvertedIndex <输入目录> <输出目录> <HDFS上的停词表路径>");
            System.err.println("例如: InvertedIndex /lab2/data /lab2/out/inverted-index /lab2/stopwords.txt");
            System.exit(2);
        }

        Configuration conf = new Configuration();
        // 默认分隔符是制表符，题目要求“单词 空格 <文档,词频>;...”，因此改为空格
        conf.set("mapreduce.output.textoutputformat.separator", " ");

        Job job = Job.getInstance(conf, "inverted-index");
        job.setJarByClass(InvertedIndex.class);
        job.setMapperClass(IndexMapper.class);
        job.setCombinerClass(IndexCombiner.class);
        job.setReducerClass(IndexReducer.class);
        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(Text.class);
        job.setNumReduceTasks(1);              // 单个 Reducer：输出为一个整体有序的文件

        FileInputFormat.addInputPath(job, new Path(args[0]));
        FileOutputFormat.setOutputPath(job, new Path(args[1]));
        job.addCacheFile(new URI(args[2] + "#" + STOPWORD_FILE));   // 停词表分发给每个任务

        System.exit(job.waitForCompletion(true) ? 0 : 1);
    }
}
