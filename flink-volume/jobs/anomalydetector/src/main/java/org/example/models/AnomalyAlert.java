package org.example.models;

import com.fasterxml.jackson.annotation.JsonProperty;

public class AnomalyAlert {
    @JsonProperty("alert_id")
    public String alertId;

    @JsonProperty("transaction_id")
    public String transactionId;

    @JsonProperty("card_id")
    public String cardId;

    @JsonProperty("user_id")
    public String userId;

    @JsonProperty("anomaly_type")
    public String anomalyType;

    public String description;
    public double severity;
    public long timestamp;

    public AnomalyAlert() {}

    public AnomalyAlert(String alertId, String transactionId, String cardId, String userId,
                        String anomalyType, String description, double severity, long timestamp) {
        this.alertId = alertId;
        this.transactionId = transactionId;
        this.cardId = cardId;
        this.userId = userId;
        this.anomalyType = anomalyType;
        this.description = description;
        this.severity = severity;
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return String.format("AnomalyAlert{alertId='%s', type='%s', cardId='%s', severity=%.2f, description='%s'}",
                alertId, anomalyType, cardId, severity, description);
    }
}