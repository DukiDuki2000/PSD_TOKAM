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
    public String status ;

    @JsonProperty("merchant_category")
    public String merchantCategory = "general";

    public Location location;

    public Transaction() {}

    public Transaction(String transactionId, String cardId, String userId, double amount,
                       Location location, String status, String merchantCategory) {
        this.transactionId = transactionId;
        this.cardId = cardId;
        this.userId = userId;
        this.amount = amount;
        this.location = location;
        this.status = status;
        this.merchantCategory = merchantCategory;
    }

    @Override
    public String toString() {
        return String.format("Transaction{id='%s', cardId='%s', userId='%s', amount=%.2f, status='%s', category='%s', location='%s'}",
                transactionId, cardId, userId, amount, status, merchantCategory,
                location != null ? location.city : "unknown");
    }
}
