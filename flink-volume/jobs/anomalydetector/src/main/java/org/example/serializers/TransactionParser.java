package org.example.serializers;

import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.example.models.Transaction;

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
        transaction.timestamp = System.currentTimeMillis();
        return transaction;
    }
}