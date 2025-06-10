package org.example.processors;

import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;
import org.example.models.AnomalyAlert;
import org.example.models.CardProfile;
import org.example.models.Transaction;
import org.example.processors.base.BaseAnomalyDetector;

import java.util.UUID;

public class AmountAnomalyDetector extends BaseAnomalyDetector<Transaction, Transaction> {

    private final OutputTag<AnomalyAlert> amountAnomalyTag;

    private enum SeverityLevel {
        HIGH(5.0, 0.5, "MEDIUM"),
        EXTREME(8.0, 0.7, "HIGH"),
        CRITICAL(15.0, 0.9, "CRITICAL");

        final double threshold;
        final double severity;
        final String name;

        SeverityLevel(double threshold, double severity, String name) {
            this.threshold = threshold;
            this.severity = severity;
            this.name = name;
        }

        static SeverityLevel fromMultiplier(double multiplier) {
            if (multiplier >= CRITICAL.threshold) return CRITICAL;
            if (multiplier >= EXTREME.threshold) return EXTREME;
            if (multiplier >= HIGH.threshold) return HIGH;
            return null; // No anomaly
        }
    }

    public AmountAnomalyDetector(OutputTag<AnomalyAlert> amountAnomalyTag) {
        this.amountAnomalyTag = amountAnomalyTag;
    }

    @Override
    public void processElement(Transaction transaction, Context context, Collector<Transaction> out) throws Exception {
        try {
            CardProfile cardProfile = getFromRedis("card_profile:" + transaction.cardId, CardProfile.class);

            if (cardProfile != null) {
                detectAmountAnomaly(transaction, cardProfile, context);
            }

        } catch (Exception e) {
            System.err.println("Amount anomaly detection error for card " + transaction.cardId + ": " + e.getMessage());
        }

        out.collect(transaction);
    }

    private void detectAmountAnomaly(Transaction transaction, CardProfile cardProfile, Context context) {
        double userAvgAmount = cardProfile.avgAmount;

        if (userAvgAmount < 1.0) {
            return;
        }

        double multiplier = transaction.amount / userAvgAmount;
        SeverityLevel level = SeverityLevel.fromMultiplier(multiplier);

        if (level != null) {
            sendAmountAnomalyAlert(transaction, cardProfile, multiplier, level, context);
        }
    }

    private void sendAmountAnomalyAlert(Transaction transaction, CardProfile cardProfile,
                                        double multiplier, SeverityLevel level, Context context) {

        String description = String.format(
                "%s AMOUNT: %.2f PLN (%.1fx avg %.2f PLN) - Card %s",
                level.name, transaction.amount, multiplier, cardProfile.avgAmount, transaction.cardId
        );

        AnomalyAlert alert = new AnomalyAlert(
                "amount_" + UUID.randomUUID().toString().substring(0, 8),
                transaction.transactionId,
                transaction.cardId,
                transaction.userId,
                "AMOUNT_ANOMALY",
                description,
                level.severity,
                transaction.timestamp,
                transaction.location
        );

        context.output(amountAnomalyTag, alert);

    }
}