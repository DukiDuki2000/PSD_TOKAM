package org.example.serializers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.connector.kafka.source.reader.deserializer.KafkaRecordDeserializationSchema;
import org.apache.flink.util.Collector;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.example.models.Location;
import org.example.models.Transaction;

import java.io.IOException;

public class TransactionDeserializer implements KafkaRecordDeserializationSchema<Transaction> {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void deserialize(ConsumerRecord<byte[], byte[]> record, Collector<Transaction> out) throws IOException {
        try {
            JsonNode node = objectMapper.readTree(record.value());

            JsonNode locationNode = node.get("location");
            Location location = new Location(
                    locationNode.get("latitude").asDouble(),
                    locationNode.get("longitude").asDouble(),
                    locationNode.get("city").asText(),
                    locationNode.get("country").asText(),
                    locationNode.get("country_code").asText()
            );

            long kafkaTimestamp = record.timestamp();

            Transaction transaction = new Transaction(
                    node.get("transaction_id").asText(),
                    node.get("card_id").asText(),
                    node.get("user_id").asText(),
                    node.get("amount").asDouble(),
                    location,
                    node.get("status").asText(),
                    node.get("merchant_category").asText(),
                    kafkaTimestamp
            );

            out.collect(transaction);
        } catch (Exception e) {
            System.err.println("Failed to deserialize transaction: " + e.getMessage());
        }
    }

    @Override
    public TypeInformation<Transaction> getProducedType() {
        return TypeInformation.of(Transaction.class);
    }
}