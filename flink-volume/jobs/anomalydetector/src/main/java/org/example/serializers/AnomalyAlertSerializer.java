package org.example.serializers;

import org.apache.flink.api.common.serialization.SerializationSchema;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.example.models.AnomalyAlert;

public class AnomalyAlertSerializer implements SerializationSchema<AnomalyAlert> {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public byte[] serialize(AnomalyAlert alert) {
        try {
            return mapper.writeValueAsBytes(alert);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize alert", e);
        }
    }
}