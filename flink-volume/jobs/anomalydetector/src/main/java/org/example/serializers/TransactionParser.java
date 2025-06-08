package org.example.serializers;

import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.example.models.Transaction;
import org.example.models.Location;

public class TransactionParser implements MapFunction<String, Transaction> {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public Transaction map(String value) throws Exception {
        JsonNode node = mapper.readTree(value);

        Transaction transaction = new Transaction();
        transaction.transactionId = node.get("transaction_id").asText();
        transaction.cardId = node.get("card_id").asText();
        transaction.userId = node.get("user_id").asText();
        transaction.amount = node.get("amount").asDouble();
        transaction.status = node.get("status").asText();
        transaction.merchantCategory = node.get("merchant_category").asText();

        JsonNode locationNode = node.get("location");
        Location location = new Location();
        location.latitude = locationNode.get("latitude").asDouble();
        location.longitude = locationNode.get("longitude").asDouble();
        location.city = locationNode.get("city").asText();
        location.country = locationNode.get("country").asText();
        location.countryCode = locationNode.get("country_code").asText();
        transaction.location = location;

        return transaction;
    }
}