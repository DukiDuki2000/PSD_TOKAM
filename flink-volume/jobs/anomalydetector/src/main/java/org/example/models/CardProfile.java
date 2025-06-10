package org.example.models;

import com.fasterxml.jackson.annotation.JsonProperty;

public class CardProfile {
    @JsonProperty("card_id")
    public String cardId;

    @JsonProperty("user_id")
    public String userId;

    @JsonProperty("avg_amount")
    public double avgAmount;

    @JsonProperty("std_amount")
    public double stdAmount;

    @JsonProperty("daily_limit")
    public double dailyLimit;

    @JsonProperty("monthly_limit")
    public double monthlyLimit;

    @JsonProperty("transaction_limit")
    public double transactionLimit;

    @JsonProperty("is_active")
    public boolean isActive;

    @JsonProperty("current_balance")
    public double currentBalance;

    @JsonProperty("expiry_date")
    public String expiryDate;

    public CardProfile() {}

    public CardProfile(String cardId, String userId, double avgAmount, double stdAmount,
                       double dailyLimit, double monthlyLimit, double transactionLimit,
                       boolean isActive, double currentBalance, String expiryDate) {
        this.cardId = cardId;
        this.userId = userId;
        this.avgAmount = avgAmount;
        this.stdAmount = stdAmount;
        this.dailyLimit = dailyLimit;
        this.monthlyLimit = monthlyLimit;
        this.transactionLimit = transactionLimit;
        this.isActive = isActive;
        this.currentBalance = currentBalance;
        this.expiryDate = expiryDate;
    }


}