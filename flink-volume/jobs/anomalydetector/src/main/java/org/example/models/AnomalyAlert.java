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

    @JsonProperty("description")
    public String description;

    @JsonProperty("severity")
    public double severity;

    @JsonProperty("timestamp")
    public long timestamp;

    @JsonProperty("location")
    public Location location;

    public AnomalyAlert() {}

    public AnomalyAlert(String alertId, String transactionId, String cardId, String userId,
                        String anomalyType, String description, double severity, long timestamp, Location location) {
        this.alertId = alertId;
        this.transactionId = transactionId;
        this.cardId = cardId;
        this.userId = userId;
        this.anomalyType = anomalyType;
        this.description = description;
        this.severity = severity;
        this.timestamp = timestamp;
        this.location = location;
    }

}