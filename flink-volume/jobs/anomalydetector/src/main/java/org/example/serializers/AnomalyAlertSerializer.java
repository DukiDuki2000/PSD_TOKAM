package org.example.serializers;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.example.models.AnomalyAlert;

import java.nio.charset.StandardCharsets;

public class AnomalyAlertSerializer implements KafkaRecordSerializationSchema<AnomalyAlert> {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String topic;

    public AnomalyAlertSerializer(String topic) {
        this.topic = topic;
    }

    @Override
    public ProducerRecord<byte[], byte[]> serialize(AnomalyAlert alert, KafkaSinkContext context, Long timestamp) {
        try {
            byte[] value = objectMapper.writeValueAsBytes(alert);

            String key = alert.anomalyType;
            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);

            Headers headers = new RecordHeaders();
            String severityLevel = getSeverityLevel(alert.severity);
            headers.add("Severity: ", severityLevel.getBytes(StandardCharsets.UTF_8));

            return new ProducerRecord<>(topic, null, timestamp, keyBytes, value, headers);

        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize anomaly alert", e);
        }
    }

    private String getSeverityLevel(double severity) {
        if (severity >= 0.9) return "CRITICAL";
        if (severity >= 0.7) return "HIGH";
        if (severity >= 0.5) return "MEDIUM";
        return "LOW";
    }
}