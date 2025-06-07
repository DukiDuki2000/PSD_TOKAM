package org.example;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.SlidingProcessingTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.util.OutputTag;
import org.example.models.AnomalyAlert;
import org.example.models.Transaction;
import org.example.processors.AmountAnomalyDetectionProcess;
import org.example.processors.FrequencyAnomalyWindowFunction;
import org.example.processors.HighFrequencyAnomalyDetector;
import org.example.serializers.AnomalyAlertSerializer;
import org.example.serializers.TransactionParser;
import org.example.config.KafkaConfig;

import java.time.Duration;

public class Main {


    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.enableCheckpointing(100);

        // Kafka source configuration
        KafkaSource<String> kafkaSource = KafkaSource.<String>builder()
                .setBootstrapServers(KafkaConfig.KAFKA_BOOTSTRAP_SERVERS)
                .setTopics(KafkaConfig.KAFKA_TOPIC_TRANSACTIONS)
                .setGroupId("anomaly-detection-group")
                .setStartingOffsets(OffsetsInitializer.earliest())
                .setValueOnlyDeserializer(new SimpleStringSchema())
                .build();

        // Parse transactions from Kafka
        DataStream<Transaction> transactions = env
                .fromSource(kafkaSource, WatermarkStrategy.<String>forBoundedOutOfOrderness(Duration.ofSeconds(5))
                        .withTimestampAssigner((event, timestamp) -> System.currentTimeMillis()), "kafka-source")
                .map(new TransactionParser())
                .name("Parse Transactions");

        // Output tags for different anomaly types
        OutputTag<AnomalyAlert> amountAnomalyTag = new OutputTag<AnomalyAlert>("amount-anomaly") {};
        OutputTag<AnomalyAlert> frequencyAnomalyTag = new OutputTag<AnomalyAlert>("frequency-anomaly") {};
        OutputTag<AnomalyAlert> highFrequencyAnomalyTag = new OutputTag<AnomalyAlert>("high-frequency-anomaly") {};

        // Amount anomaly detection
        SingleOutputStreamOperator<Transaction> amountProcessed = transactions
                .process(new AmountAnomalyDetectionProcess(amountAnomalyTag))
                .name("Amount Anomaly Detection");

        // High frequency detection (3+ transactions per minute)
        SingleOutputStreamOperator<Transaction> highFrequencyProcessed = transactions
                .keyBy(transaction -> transaction.cardId)
                .process(new HighFrequencyAnomalyDetector(highFrequencyAnomalyTag))
                .name("High Frequency Detection (3+ per minute)");

        // Regular frequency anomaly detection using sliding windows
        DataStream<AnomalyAlert> frequencyAnomalies = transactions
                .keyBy(transaction -> transaction.cardId)
                .window(SlidingProcessingTimeWindows.of(Time.minutes(5), Time.minutes(1)))
                .process(new FrequencyAnomalyWindowFunction(frequencyAnomalyTag))
                .name("Frequency Anomaly Detection (5min window)");

        // Collect all anomaly alerts from side outputs
        DataStream<AnomalyAlert> amountAnomalies = amountProcessed.getSideOutput(amountAnomalyTag);
        DataStream<AnomalyAlert> highFrequencyAnomalies = highFrequencyProcessed.getSideOutput(highFrequencyAnomalyTag);

        // Union all anomaly streams
        DataStream<AnomalyAlert> allAnomalies = amountAnomalies
                .union(frequencyAnomalies)
                .union(highFrequencyAnomalies);

        // Print alerts to console for monitoring
        allAnomalies.print("🚨 ANOMALY DETECTED");

        // Kafka sink for alerts
        KafkaSink<AnomalyAlert> alertSink = KafkaSink.<AnomalyAlert>builder()
                .setBootstrapServers(KafkaConfig.KAFKA_BOOTSTRAP_SERVERS)
                .setRecordSerializer(KafkaRecordSerializationSchema.builder()
                        .setTopic(KafkaConfig.KAFKA_TOPIC_ANOMALY)
                        .setValueSerializationSchema(new AnomalyAlertSerializer())
                        .build())
                .build();

        // Send all alerts to Kafka
        allAnomalies.sinkTo(alertSink).name("Alert Kafka Sink");

        System.out.println("🚀 Starting Anomaly Detection Pipeline...");
        System.out.println("📊 Monitoring:");
        System.out.println("   • Amount anomalies (z-score & extreme amounts)");
        System.out.println("   • High frequency (3+ transactions per minute)");
        System.out.println("   • General frequency anomalies (5-minute windows)");

        env.execute("Complete Anomaly Detection Pipeline");
    }
}