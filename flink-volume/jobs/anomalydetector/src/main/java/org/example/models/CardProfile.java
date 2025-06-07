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

    public CardProfile() {}

    public CardProfile(String cardId, String userId, double avgAmount, double stdAmount,
                       double dailyLimit, double monthlyLimit) {
        this.cardId = cardId;
        this.userId = userId;
        this.avgAmount = avgAmount;
        this.stdAmount = stdAmount;
        this.dailyLimit = dailyLimit;
        this.monthlyLimit = monthlyLimit;
    }

    @Override
    public String toString() {
        return String.format("CardProfile{cardId='%s', userId='%s', avgAmount=%.2f, stdAmount=%.2f, dailyLimit=%.2f, monthlyLimit=%.2f}",
                cardId, userId, avgAmount, stdAmount, dailyLimit, monthlyLimit);
    }
}
