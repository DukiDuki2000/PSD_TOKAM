package org.example.models;

import com.fasterxml.jackson.annotation.JsonProperty;

public class Transaction {
    @JsonProperty("transaction_id")
    public String transactionId;

    @JsonProperty("card_id")
    public String cardId;

    @JsonProperty("user_id")
    public String userId;

    public double amount;
    public long timestamp;

    public Transaction() {}

    public Transaction(String transactionId, String cardId, String userId, double amount, long timestamp) {
        this.transactionId = transactionId;
        this.cardId = cardId;
        this.userId = userId;
        this.amount = amount;
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return String.format("Transaction{id='%s', cardId='%s', userId='%s', amount=%.2f, timestamp=%d}",
                transactionId, cardId, userId, amount, timestamp);
    }
}