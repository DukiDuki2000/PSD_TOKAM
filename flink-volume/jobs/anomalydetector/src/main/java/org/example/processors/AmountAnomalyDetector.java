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

    private static final String AMOUNT_STATS_PREFIX = "amount_stats:";
    private static final int AMOUNT_STATS_TTL = 300;
    private static final double MIN_SEVERITY_THRESHOLD = 0.5; // Minimum severity to create alert

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

        AmountAnomalyStats stats = getAmountStats(transaction.cardId);
        double adaptiveSeverity = calculateAdaptiveSeverity(multiplier, stats);
        String severityLevel = getSeverityLevel(adaptiveSeverity);

        if (adaptiveSeverity >= MIN_SEVERITY_THRESHOLD) {
            sendAdaptiveAmountAlert(transaction, cardProfile, multiplier, adaptiveSeverity, severityLevel, context);
            updateAmountStats(transaction.cardId, multiplier, adaptiveSeverity);
        }
    }

    private double calculateAdaptiveSeverity(double multiplier, AmountAnomalyStats stats) {
        double baseSeverity = getBaseSeverity(multiplier);

        if (stats == null || stats.anomalyCount < 3) {
            return baseSeverity;
        }
        double avgMultiplier = stats.totalMultiplier / stats.anomalyCount;

        if (avgMultiplier > 12.0) {
            return Math.max(0.0, baseSeverity - 0.2);
        } else if (avgMultiplier < 6.0) {
            return Math.min(1.0, baseSeverity + 0.15);
        }

        return baseSeverity;
    }

    private double getBaseSeverity(double multiplier) {
        if (multiplier >= 15.0) return 0.9;   // CRITICAL
        if (multiplier >= 8.0) return 0.7;    // HIGH
        if (multiplier >= 5.0) return 0.5;    // MEDIUM
        return 0.3; // LOW
    }

    private String getSeverityLevel(double severity) {
        if (severity >= 0.9) return "CRITICAL";
        if (severity >= 0.7) return "HIGH";
        if (severity >= 0.5) return "MEDIUM";
        return "LOW";
    }

    private void sendAdaptiveAmountAlert(Transaction transaction, CardProfile cardProfile,
                                         double multiplier, double severity, String severityLevel, Context context) {

        String description = String.format(
                "%s AMOUNT: %.2f PLN (%.1fx avg %.2f PLN) - Card %s. Adaptive threshold applied.",
                severityLevel, transaction.amount, multiplier, cardProfile.avgAmount, transaction.cardId
        );

        AnomalyAlert alert = new AnomalyAlert(
                "amount_adaptive_" + UUID.randomUUID().toString().substring(0, 8),
                transaction.transactionId,
                transaction.cardId,
                transaction.userId,
                "AMOUNT_ANOMALY",
                description,
                severity,
                transaction.timestamp,
                transaction.location
        );

        context.output(amountAnomalyTag, alert);
    }

    private AmountAnomalyStats getAmountStats(String cardId) {
        String statsKey = AMOUNT_STATS_PREFIX + cardId;
        return getFromRedis(statsKey, AmountAnomalyStats.class);
    }

    private void updateAmountStats(String cardId, double multiplier, double severity) {
        try {
            String statsKey = AMOUNT_STATS_PREFIX + cardId;
            AmountAnomalyStats stats = getFromRedis(statsKey, AmountAnomalyStats.class);

            if (stats == null) {
                stats = new AmountAnomalyStats();
            }

            stats.anomalyCount++;
            stats.totalMultiplier += multiplier;
            stats.totalSeverity += severity;
            stats.lastAnomalyTime = System.currentTimeMillis();

            if (multiplier > stats.maxMultiplier) {
                stats.maxMultiplier = multiplier;
            }

            storeInRedis(statsKey, stats, AMOUNT_STATS_TTL);

        } catch (Exception e) {
            System.err.println("Failed to update amount anomaly stats: " + e.getMessage());
        }
    }

    public static class AmountAnomalyStats {
        public int anomalyCount = 0;
        public double totalMultiplier = 0.0;
        public double totalSeverity = 0.0;
        public double maxMultiplier = 0.0;
        public long lastAnomalyTime = 0;

        public AmountAnomalyStats() {}
    }
}