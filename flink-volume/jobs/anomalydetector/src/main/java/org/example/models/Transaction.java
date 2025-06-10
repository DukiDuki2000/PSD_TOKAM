package org.example.models;

import com.fasterxml.jackson.annotation.JsonProperty;

public class Transaction {
    @JsonProperty("transaction_id")
    public String transactionId;

    @JsonProperty("card_id")
    public String cardId;

    @JsonProperty("user_id")
    public String userId;

    @JsonProperty("amount")
    public double amount;

    @JsonProperty("status")
    public String status;

    @JsonProperty("merchant_category")
    public String merchantCategory;

    @JsonProperty("location")
    public Location location;

    @JsonProperty("timestamp")
    public long timestamp;

    public Transaction() {}


    public Transaction(String transactionId, String cardId, String userId, double amount,
                       Location location, String status, String merchantCategory, long timestamp) {
        this.transactionId = transactionId;
        this.cardId = cardId;
        this.userId = userId;
        this.amount = amount;
        this.location = location;
        this.status = status;
        this.merchantCategory = merchantCategory;
        this.timestamp = timestamp;
    }


}