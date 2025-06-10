package org.example;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.windowing.assigners.SlidingEventTimeWindows;
import org.apache.flink.util.OutputTag;
import org.example.models.AnomalyAlert;
import org.example.models.Transaction;
import org.example.processors.*;
import org.example.processors.window.*;
import org.example.serializers.AnomalyAlertSerializer;
import org.example.serializers.TransactionDeserializer;
import org.example.watermarks.TransactionTimestampAssigner;
import org.example.config.KafkaConfig;

import java.time.Duration;

public class Main {

    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        env.enableCheckpointing(30000);
        env.getCheckpointConfig().setCheckpointingMode(CheckpointingMode.EXACTLY_ONCE);
        env.getCheckpointConfig().setMinPauseBetweenCheckpoints(15000);
        env.getCheckpointConfig().setCheckpointTimeout(90000);
        env.getCheckpointConfig().setMaxConcurrentCheckpoints(1);

        env.setParallelism(1);


        KafkaSource<Transaction> kafkaSource = KafkaSource.<Transaction>builder()
                .setBootstrapServers(KafkaConfig.KAFKA_BOOTSTRAP_SERVERS)
                .setTopics(KafkaConfig.KAFKA_TOPIC_TRANSACTIONS)
                .setGroupId(KafkaConfig.KAFKA_GROUP_ID)
                .setStartingOffsets(OffsetsInitializer.latest())
                .setDeserializer(new TransactionDeserializer())
                .build();


        WatermarkStrategy<Transaction> watermarkStrategy = WatermarkStrategy
                .<Transaction>forBoundedOutOfOrderness(Duration.ofSeconds(1))
                .withTimestampAssigner(new TransactionTimestampAssigner())
                .withIdleness(Duration.ofMinutes(1));


        DataStream<Transaction> transactions = env
                .fromSource(kafkaSource, watermarkStrategy, "kafka-transaction-source")
                .name("Transaction Stream from Kafka");


        OutputTag<AnomalyAlert> amountAnomalyTag = new OutputTag<AnomalyAlert>("amount-anomaly") {};
        OutputTag<AnomalyAlert> inactiveCardAnomalyTag = new OutputTag<AnomalyAlert>("inactive-card-anomaly") {};
        OutputTag<AnomalyAlert> limitAnomalyTag = new OutputTag<AnomalyAlert>("limit-anomaly") {};
        OutputTag<AnomalyAlert> microTransactionAnomalyTag = new OutputTag<AnomalyAlert>("micro-transaction-anomaly") {};
        OutputTag<AnomalyAlert> rapidGeoAnomalyTag = new OutputTag<AnomalyAlert>("rapid-geo-anomaly") {};
        OutputTag<AnomalyAlert> duplicateAnomalyTag = new OutputTag<AnomalyAlert>("duplicate-anomaly") {};
        OutputTag<AnomalyAlert> roundAmountAnomalyTag = new OutputTag<AnomalyAlert>("round-amount-anomaly") {};
        OutputTag<AnomalyAlert> atmPatternAnomalyTag = new OutputTag<AnomalyAlert>("atm-pattern-anomaly") {};
        OutputTag<AnomalyAlert> unusualMerchantAnomalyTag = new OutputTag<AnomalyAlert>("unusual-merchant-anomaly") {};



        // 1. Amount anomaly detection (independent, stateless - INSTANT)
        SingleOutputStreamOperator<Transaction> amountDetector = transactions
                .process(new AmountAnomalyDetector(amountAnomalyTag))
                .name("Amount Anomaly Detection");

        // 2. Inactive/Expired card detection (independent, stateless - INSTANT)
        SingleOutputStreamOperator<Transaction> cardStatusDetector = transactions
                .process(new InactiveCardAnomalyDetector(inactiveCardAnomalyTag))
                .name("Card Status Anomaly Detection");

        // 3. Limit anomaly detection (independent, stateless - INSTANT)
        SingleOutputStreamOperator<Transaction> limitDetector = transactions
                .process(new LimitAnomalyDetector(limitAnomalyTag))
                .name("Limit Anomaly Detection");

        // 4. Unusual merchant detection (independent, stateless - INSTANT)
        SingleOutputStreamOperator<Transaction> merchantDetector = transactions
                .process(new UnusualMerchantDetector(unusualMerchantAnomalyTag))
                .name("Unusual Merchant Detection");

        // 5. Micro transaction detection (OPTIMIZED WINDOW)
        SingleOutputStreamOperator<Transaction> microDetector = transactions
                .keyBy(transaction -> transaction.cardId)
                .window(SlidingEventTimeWindows.of(Duration.ofSeconds(40), Duration.ofSeconds(20)))
                .process(new MicroTransactionWindowDetector(microTransactionAnomalyTag))
                .name("Micro Transaction Window Detection");

        // 6. Rapid geo change detection (OPTIMIZED WINDOW)
        SingleOutputStreamOperator<Transaction> geoDetector = transactions
                .keyBy(transaction -> transaction.cardId)
                .window(SlidingEventTimeWindows.of(Duration.ofSeconds(20),Duration.ofSeconds(10)))
                .process(new RapidGeoChangeWindowDetector(rapidGeoAnomalyTag))
                .name("Rapid Geo Change Window Detection");


        // 7. Duplicate transaction detection (OPTIMIZED WINDOW)
        SingleOutputStreamOperator<Transaction> duplicateDetector=transactions
                .keyBy(transaction->transaction.cardId)
                .window(SlidingEventTimeWindows.of(Duration.ofSeconds(20),Duration.ofSeconds(10)))
                .process(new DuplicateTransactionWindowDetector(duplicateAnomalyTag))
                .name("Duplicate Transaction Window Detection");

        // 8. Round amount detection (OPTIMIZED WINDOW)
        SingleOutputStreamOperator<Transaction> roundDetector = transactions
                .keyBy(transaction -> transaction.cardId)
                .window(SlidingEventTimeWindows.of(Duration.ofSeconds(40), Duration.ofSeconds(20)))
                .process(new RoundAmountWindowDetector(roundAmountAnomalyTag))
                .name("Round Amount Window Detection");

        // 9. ATM pattern detection (OPTIMIZED WINDOW)
        SingleOutputStreamOperator<Transaction> atmDetector = transactions
                .keyBy(transaction -> transaction.cardId)
                .window(SlidingEventTimeWindows.of(Duration.ofSeconds(40), Duration.ofSeconds(20)))
                .process(new ATMPatternWindowDetector(atmPatternAnomalyTag))
                .name("ATM Pattern Window Detection");





        DataStream<AnomalyAlert> amountAnomalies = amountDetector.getSideOutput(amountAnomalyTag);
        DataStream<AnomalyAlert> inactiveCardAnomalies = cardStatusDetector.getSideOutput(inactiveCardAnomalyTag);
        DataStream<AnomalyAlert> limitAnomalies = limitDetector.getSideOutput(limitAnomalyTag);
        DataStream<AnomalyAlert> unusualMerchantAnomalies = merchantDetector.getSideOutput(unusualMerchantAnomalyTag);
        DataStream<AnomalyAlert> microTransactionAnomalies = microDetector.getSideOutput(microTransactionAnomalyTag);
        DataStream<AnomalyAlert> rapidGeoAnomalies = geoDetector.getSideOutput(rapidGeoAnomalyTag);
        DataStream<AnomalyAlert> duplicateAnomalies = duplicateDetector.getSideOutput(duplicateAnomalyTag);
        DataStream<AnomalyAlert> roundAmountAnomalies = roundDetector.getSideOutput(roundAmountAnomalyTag);
        DataStream<AnomalyAlert> atmPatternAnomalies = atmDetector.getSideOutput(atmPatternAnomalyTag);


        DataStream<AnomalyAlert> allAnomalies = amountAnomalies
                .union(inactiveCardAnomalies)
                .union(limitAnomalies)
                .union(unusualMerchantAnomalies)
                .union(microTransactionAnomalies)
                .union(rapidGeoAnomalies)
                .union(duplicateAnomalies)
                .union(roundAmountAnomalies)
                .union(atmPatternAnomalies);


        allAnomalies.print();


        KafkaSink<AnomalyAlert> alertSink = KafkaSink.<AnomalyAlert>builder()
                .setBootstrapServers(KafkaConfig.KAFKA_BOOTSTRAP_SERVERS)
                .setRecordSerializer(new AnomalyAlertSerializer(KafkaConfig.KAFKA_TOPIC_ANOMALY))
                .build();


        allAnomalies.sinkTo(alertSink).name("Alert Kafka Sink");



        env.execute("PSD Projekt");
    }
}