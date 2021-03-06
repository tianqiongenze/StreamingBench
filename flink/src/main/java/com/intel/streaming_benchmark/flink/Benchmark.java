package com.intel.streaming_benchmark.flink;

import com.alibaba.fastjson.JSON;
import com.intel.streaming_benchmark.common.*;
import com.intel.streaming_benchmark.utils.FlinkBenchConfig;
import org.apache.flink.api.common.JobExecutionResult;
import org.apache.flink.api.common.accumulators.IntCounter;
import org.apache.flink.api.common.functions.RichFlatMapFunction;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.api.java.tuple.*;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.AssignerWithPeriodicWatermarks;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer010;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.TableConfig;
import org.apache.flink.table.api.java.StreamTableEnvironment;
import org.apache.flink.types.Row;
import org.apache.flink.util.Collector;
import com.alibaba.fastjson.JSONObject;
import javax.annotation.Nullable;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Properties;

public class Benchmark {
    public static void main(String[] args) throws Exception {
        if (args.length < 2)
            BenchLogUtil.handleError("Usage: RunBench <ConfigFile> <QueryName>");
        //root Config
        ConfigLoader cl = new ConfigLoader(args[0]);
        String benchmarkConfDir = new File(args[0]).getParent();

        //flink config
        String flinkConf = benchmarkConfDir + "/../flink/conf/benchmarkConf.yaml";
        cl.merge(flinkConf);

        // Prepare configuration
        FlinkBenchConfig conf = new FlinkBenchConfig();
        conf.brokerList = cl.getProperty(StreamBenchConfig.KAFKA_BROKER_LIST);
        conf.zkHost = cl.getProperty(StreamBenchConfig.ZK_HOST);
        conf.consumerGroup = cl.getProperty(StreamBenchConfig.CONSUMER_GROUP);
        conf.checkpointDuration = Long.parseLong(cl.getProperty(StreamBenchConfig.FLINK_CHECKPOINTDURATION));
        conf.timeType = cl.getProperty(StreamBenchConfig.FLINK_TIMETYPE);
        conf.topic = QueryConfig.getTables(args[1]);
        conf.sqlLocation = benchmarkConfDir + "/../flink/query";
        conf.resultLocation = benchmarkConfDir + "/../flink/result";
        conf.sqlName = args[1];
        runQuery(conf);
    }

    public static void runQuery(FlinkBenchConfig config) throws Exception{
        long start = System.currentTimeMillis();
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.enableCheckpointing(config.checkpointDuration);
        if(config.timeType.equals("EventTime")){
            env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);
        }else{
            env.setStreamTimeCharacteristic(TimeCharacteristic.ProcessingTime);
        }

        TableConfig tc = new TableConfig();
        EnvironmentSettings builder = EnvironmentSettings.newInstance().useBlinkPlanner().inStreamingMode().build();
        StreamTableEnvironment tableEnv = StreamTableEnvironment.create(env,builder);

        Properties properties = new Properties();
        properties.setProperty("zookeeper.connect", config.zkHost);
        properties.setProperty("group.id", config.consumerGroup);
        properties.setProperty("bootstrap.servers", config.brokerList);

        String[] topics =  config.topic.split(",");

        //generate table
        for(int i = 0; i < topics.length; i++){
            // source stream
            FlinkKafkaConsumer010<String> consumer = new FlinkKafkaConsumer010<String>(topics[i], new SimpleStringSchema(),properties);
            consumer.setStartFromLatest();
//            consumer.setStartFromEarliest();
            //add stream source for flink
            DataStream<String> stream = env.addSource(consumer);
            // stream parse  need table schema
            String[] fieldNames = TableSchemaProvider.getSchema(topics[i]).getFieldNames();
            //  TypeInformation returnType = TypeExtractor.createTypeInfo();
            DataStream streamParsed;

            if(config.timeType.equals("EventTime")){
                if(topics[i].equals("shopping")){
                    streamParsed = stream.flatMap(new DeserializeShopping()).assignTimestampsAndWatermarks(new ShoppingWatermarks());
                }else if(topics[i].equals("click")){
                    streamParsed = stream.flatMap(new DeserializeClick()).assignTimestampsAndWatermarks(new ClickWatermarks());
                }else if(topics[i].equals("imp")){
                    streamParsed = stream.flatMap(new DeserializeImp()).assignTimestampsAndWatermarks(new ImpWatermarks());
                }else if(topics[i].equals("dau")){
                    streamParsed = stream.flatMap(new DeserializeDau()).assignTimestampsAndWatermarks(new DauWatermarks());
                }else if(topics[i].equals("userVisit")){
                    streamParsed = stream.flatMap(new DeserializeUserVisit()).assignTimestampsAndWatermarks(new UserVisitWatermarks());
                }else{
                    System.out.println("No such topic, please check your benchmarkConf.yaml");
                    return;
                }

            }else{
                if(topics[i].equals("shopping")){
                    streamParsed = stream.flatMap(new DeserializeShopping());
                }else if(topics[i].equals("click")){
                    streamParsed = stream.flatMap(new DeserializeClick());
                }else if(topics[i].equals("imp")){
                    streamParsed = stream.flatMap(new DeserializeImp());
                }else if(topics[i].equals("dau")){
                    streamParsed = stream.flatMap(new DeserializeDau());
                }else if(topics[i].equals("userVisit")){
                    streamParsed = stream.flatMap(new DeserializeUserVisit());
                }else{
                    System.out.println("No such topic, please check your benchmarkConf.yaml");
                    return;
                }
            }

            tableEnv.registerTable(topics[i], tableEnv.fromDataStream(streamParsed, FieldString(fieldNames, config.timeType)));
        }

        //runQuery
        File file = new File(config.sqlLocation + "/" + config.sqlName);
        if (!file.exists()) {
            return;
        }
        try {
            String queryString = DateUtils.fileToString(file);
            Table table = tableEnv.sqlQuery(queryString);
            table.printSchema();
            DataStream<Tuple2<Boolean, Row>> tuple2DataStream = tableEnv.toRetractStream(table, Row.class);
            tuple2DataStream.print();
        } catch (Exception e) {
            e.printStackTrace();
        }

        System.out.println("----------------runtime---------------- :");
            JobExecutionResult execute = env.execute(config.sqlName);
            System.out.println("----------------runtime---------------- :");
            JobExecutionResult jobExecutionResult = execute.getJobExecutionResult();
            long netRuntime = jobExecutionResult.getNetRuntime();
            System.out.println("----------------runtime---------------- :" + netRuntime);
            long count = 0;
            for(int i = 0; i < topics.length; i++){
                Integer tmp =  (Integer)jobExecutionResult.getAccumulatorResult(topics[i]);
                count = count + tmp.longValue();
            }
            File resultFile = new File(config.resultLocation + "/result.log" );
            if (!resultFile.exists()) {
                resultFile.createNewFile();
            }
            FileWriter fileWriter = new FileWriter(config.resultLocation + "/result.log", true);
            BufferedWriter bufferWriter = new BufferedWriter(fileWriter);
            bufferWriter.write("Finished time: "+ DateUtils.parseLong2String(System.currentTimeMillis()) + "; " + config.sqlName + "  Runtime: " + netRuntime/1000 + " TPS:" + count/(netRuntime/1000) + "\r\n");
            bufferWriter.close();





    }

    private static String FieldString(String[] fieldNames, String timeType){
        String fileds = "";
        for(int i =0; i< fieldNames.length; i++){
            fileds = fileds + fieldNames[i] + ",";
        }
        if(timeType.equals("EventTime")){
            fileds = fileds + "rowtime.rowtime";
        }else{
            fileds = fileds + "rowtime.proctime";
        }
        return fileds;
    }

    public static class ShoppingWatermarks implements AssignerWithPeriodicWatermarks<Tuple3<String, String,Long>> {
        Long currentMaxTimestamp = 0L;
        final Long maxOutOfOrderness = 2000L;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

        @Nullable
        @Override
        public Watermark getCurrentWatermark() {
            Watermark watermark = new Watermark(currentMaxTimestamp - maxOutOfOrderness);
            return watermark;
        }

        @Override
        public long extractTimestamp(Tuple3<String, String, Long> element, long previousElementTimestamp) {
            Long timestamp = Long.valueOf(element.f2);
            currentMaxTimestamp = Math.max(timestamp, currentMaxTimestamp);
            return timestamp;
        }
    }


    public static class ClickWatermarks implements AssignerWithPeriodicWatermarks<Tuple7<Long,String, String,String, String, String,String>> {
        Long currentMaxTimestamp = 0L;
        final Long maxOutOfOrderness = 2000L;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

        @Nullable
        @Override
        public Watermark getCurrentWatermark() {
            Watermark watermark = new Watermark(currentMaxTimestamp - maxOutOfOrderness);
            return watermark;
        }

        @Override
        public long extractTimestamp(Tuple7<Long, String, String, String, String, String, String> element, long previousElementTimestamp) {
            Long timestamp = Long.valueOf(element.f0);
            currentMaxTimestamp = Math.max(timestamp, currentMaxTimestamp);
            return timestamp;
        }
    }


    public static class ImpWatermarks implements AssignerWithPeriodicWatermarks<Tuple8<Long, String, String, String, String, Double, String, String>> {
        Long currentMaxTimestamp = 0L;
        final Long maxOutOfOrderness = 2000L;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

        @Nullable
        @Override
        public Watermark getCurrentWatermark() {
            Watermark watermark = new Watermark(currentMaxTimestamp - maxOutOfOrderness);
            return watermark;
        }

        @Override
        public long extractTimestamp(Tuple8<Long, String, String, String, String, Double, String, String> element, long previousElementTimestamp) {
            Long timestamp = Long.valueOf(element.f0);
            currentMaxTimestamp = Math.max(timestamp, currentMaxTimestamp);
            return timestamp;
        }
    }


    public static class DauWatermarks implements AssignerWithPeriodicWatermarks<Tuple3<Long,String, String>> {
        Long currentMaxTimestamp = 0L;
        final Long maxOutOfOrderness = 2000L;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

        @Nullable
        @Override
        public Watermark getCurrentWatermark() {
            Watermark watermark = new Watermark(currentMaxTimestamp - maxOutOfOrderness);
            return watermark;
        }

        @Override
        public long extractTimestamp(Tuple3<Long, String, String> element, long previousElementTimestamp) {
            Long timestamp = Long.valueOf(element.f0);
            currentMaxTimestamp = Math.max(timestamp, currentMaxTimestamp);
            return timestamp;
        }
    }


    public static class UserVisitWatermarks implements AssignerWithPeriodicWatermarks<Tuple13<String, Long, String, Long, Long, String, String, String, String, String, String, String, Integer>> {
        Long currentMaxTimestamp = 0L;
        final Long maxOutOfOrderness = 2000L;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

        @Nullable
        @Override
        public Watermark getCurrentWatermark() {
            Watermark watermark = new Watermark(currentMaxTimestamp - maxOutOfOrderness);
            return watermark;
        }

        @Override
        public long extractTimestamp(Tuple13<String, Long, String, Long, Long, String, String, String, String, String, String, String, Integer> element, long previousElementTimestamp) {
            Long timestamp = Long.valueOf(element.f4);
            currentMaxTimestamp = Math.max(timestamp, currentMaxTimestamp);
            return timestamp;
        }
    }



    public static class DeserializeShopping extends RichFlatMapFunction<String, Tuple3<String, String, Long>> {

        // Counter numLines;
        private IntCounter shopping = new IntCounter();
        @Override
        public void open(Configuration parameters) throws Exception {
            //numLines = getRuntimeContext().getMetricGroup().addGroup("flink_test_metric").counter("numLines");
            getRuntimeContext().addAccumulator("shopping", this.shopping);
            super.open(parameters);
        }

        @Override
        public void flatMap(String s, Collector<Tuple3<String, String, Long>> collector) throws Exception {
            this.shopping.add(1);
            String[] split = s.split(",");
            collector.collect(new Tuple3<String, String, Long>(split[0], split[1], Long.valueOf(split[2])));
        }
    }

    public static class DeserializeClick extends RichFlatMapFunction<String, Tuple7<Long, String, String, String, String, String,String>> {

        private IntCounter click = new IntCounter();
        @Override
        public void open(Configuration parameters) throws Exception {
            //numLines = getRuntimeContext().getMetricGroup().addGroup("flink_test_metric").counter("numLines");
            getRuntimeContext().addAccumulator("click", this.click);
            super.open(parameters);
        }

        @Override
        public void flatMap(String input, Collector<Tuple7<Long, String, String, String, String, String,String>> collector) throws Exception {
            this.click.add(1);
            JSONObject obj = JSON.parseObject(input);
//            JSONObject obj = new JSONObject(input);
            Tuple7<Long, String, String, String, String, String,String> tuple = new Tuple7<>(
                    obj.getLong("click_time"),
                    obj.getString("strategy"),
                    obj.getString("site"),
                    obj.getString("pos_id"),
                    obj.getString("poi_id"),
                    obj.getString("device_id"),
                    obj.getString("sessionId")
            );
            collector.collect(tuple);
        }
    }

    public static class DeserializeImp extends RichFlatMapFunction<String, Tuple8<Long, String, String, String, String, Double, String, String>> {

        private IntCounter imp = new IntCounter();
        @Override
        public void open(Configuration parameters) throws Exception {
            //numLines = getRuntimeContext().getMetricGroup().addGroup("flink_test_metric").counter("numLines");
            getRuntimeContext().addAccumulator("imp", this.imp);
            super.open(parameters);
        }

        @Override
        public void flatMap(String input, Collector<Tuple8<Long, String, String, String, String, Double, String, String>> collector) throws Exception {
            this.imp.add(1);
            JSONObject obj = JSON.parseObject(input);
//            JSONObject obj = new JSONObject(input);
            Tuple8<Long, String, String, String, String, Double, String,String> tuple = new Tuple8<>(
                    obj.getLong("imp_time"),
                    obj.getString("strategy"),
                    obj.getString("site"),
                    obj.getString("pos_id"),
                    obj.getString("poi_id"),
                    obj.getDouble("cost"),
                    obj.getString("device_id"),
                    obj.getString("sessionId")
            );
            collector.collect(tuple);
        }
    }

    public static class DeserializeDau extends RichFlatMapFunction<String, Tuple3<Long, String, String>> {

        private IntCounter dau = new IntCounter();
        @Override
        public void open(Configuration parameters) throws Exception {
            //numLines = getRuntimeContext().getMetricGroup().addGroup("flink_test_metric").counter("numLines");
            getRuntimeContext().addAccumulator("dau", this.dau);
            super.open(parameters);
        }

        @Override
        public void flatMap(String input, Collector<Tuple3<Long, String, String>> collector) throws Exception {
            this.dau.add(1);
            JSONObject obj = JSON.parseObject(input);
//            JSONObject obj = new JSONObject(input);
            Tuple3<Long, String, String> tuple = new Tuple3<>(
                    obj.getLong("dau_time"),
                    obj.getString("device_id"),
                    obj.getString("sessionId")
            );
            collector.collect(tuple);
        }
    }


    public static class DeserializeUserVisit extends RichFlatMapFunction<String, Tuple13<String, Long, String, Long, Long, String, String, String, String, String, String, String, Integer>> {

        private IntCounter userVisit = new IntCounter();
        @Override
        public void open(Configuration parameters) throws Exception {
            //numLines = getRuntimeContext().getMetricGroup().addGroup("flink_test_metric").counter("numLines");
            getRuntimeContext().addAccumulator("userVisit", this.userVisit);
            super.open(parameters);
        }

        @Override
        public void flatMap(String s, Collector<Tuple13<String, Long, String, Long, Long, String, String, String, String, String, String, String, Integer>> collector) throws Exception {
            this.userVisit.add(1);
            String[] split = s.split(",");
            Tuple13<String, Long, String, Long, Long, String, String, String, String, String, String, String, Integer> tuple = new Tuple13<>(
                    split[0],
                    Long.valueOf(split[1]),
                    split[2],
                    Long.valueOf(split[3]),
                    Long.valueOf(split[4]),
                    split[5],
                    split[6],
                    split[7],
                    split[8],
                    split[9],
                    split[10],
                    split[11],
                    Integer.valueOf(split[12])
            );
            collector.collect(tuple);
        }
    }

}
