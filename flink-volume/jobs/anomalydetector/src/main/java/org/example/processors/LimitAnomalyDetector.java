package org.example.processors;

import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;
import org.example.models.AnomalyAlert;
import org.example.models.CardProfile;
import org.example.models.Transaction;
import org.example.processors.base.BaseAnomalyDetector;
import java.util.UUID;

public class LimitAnomalyDetector extends BaseAnomalyDetector<Transaction, Transaction> {

    private final OutputTag<AnomalyAlert> limitAnomalyTag;

    private static final String LIMIT_STATS_PREFIX = "limit_stats:";
    private static final int LIMIT_STATS_TTL = 300;
    private static final long COOLDOWN_PERIOD_MS = 120_000;

    public LimitAnomalyDetector(OutputTag<AnomalyAlert> limitAnomalyTag) {
        this.limitAnomalyTag = limitAnomalyTag;
    }

    @Override
    public void processElement(Transaction transaction, Context context, Collector<Transaction> out) throws Exception {

        try {
            CardProfile cardProfile = getFromRedis("card_profile:" + transaction.cardId, CardProfile.class);

            if (cardProfile != null && "declined".equals(transaction.status)) {
                detectInsufficientFundsAnomaly(transaction, cardProfile, context);
                detectTransactionLimitAnomaly(transaction, cardProfile, context);
            }

        } catch (Exception e) {
            System.err.println("Limit anomaly detection error for card " + transaction.cardId + ": " + e.getMessage());
        }

        out.collect(transaction);
    }

    private void detectInsufficientFundsAnomaly(Transaction transaction, CardProfile cardProfile, Context context) {
        if (transaction.amount > cardProfile.currentBalance) {
            if (shouldCreateFundsAlert(transaction.cardId, transaction.amount, cardProfile.currentBalance)) {
                double excess = transaction.amount - cardProfile.currentBalance;
                double severity = calculateFundsSeverity(transaction.cardId, excess, cardProfile.currentBalance);

                String description = String.format(
                        "INSUFFICIENT FUNDS: Transaction %.2f PLN exceeds balance %.2f PLN by %.2f PLN. Status: %s. Adaptive threshold applied.",
                        transaction.amount, cardProfile.currentBalance, excess, transaction.status
                );

                AnomalyAlert alert = new AnomalyAlert(
                        "funds_adaptive_" + UUID.randomUUID().toString().substring(0, 8),
                        transaction.transactionId,
                        transaction.cardId,
                        transaction.userId,
                        "INSUFFICIENT_FUNDS",
                        description,
                        severity,
                        transaction.timestamp,
                        transaction.location
                );

                context.output(limitAnomalyTag, alert);
                updateLimitStats(transaction.cardId, "FUNDS", excess);
            }
        }
    }

    private void detectTransactionLimitAnomaly(Transaction transaction, CardProfile cardProfile, Context context) {
        if (transaction.amount > cardProfile.transactionLimit) {
            if (shouldCreateLimitAlert(transaction.cardId, transaction.amount, cardProfile.transactionLimit)) {
                double excess = transaction.amount - cardProfile.transactionLimit;
                double severity = calculateLimitSeverity(transaction.cardId, excess, cardProfile.transactionLimit);

                String description = String.format(
                        "TRANSACTION LIMIT EXCEEDED: Transaction %.2f PLN exceeds limit %.2f PLN by %.2f PLN. Status: %s. Adaptive threshold applied.",
                        transaction.amount, cardProfile.transactionLimit, excess, transaction.status
                );

                AnomalyAlert alert = new AnomalyAlert(
                        "limit_adaptive_" + UUID.randomUUID().toString().substring(0, 8),
                        transaction.transactionId,
                        transaction.cardId,
                        transaction.userId,
                        "TRANSACTION_LIMIT_EXCEEDED",
                        description,
                        severity,
                        transaction.timestamp,
                        transaction.location
                );

                context.output(limitAnomalyTag, alert);
                updateLimitStats(transaction.cardId, "LIMIT", excess);
            }
        }
    }

    private boolean shouldCreateFundsAlert(String cardId, double transactionAmount, double balance) {
        LimitAnomalyStats stats = getLimitStats(cardId);

        if (stats == null) {
            return true;
        }

        long timeSinceLastFundsAlert = System.currentTimeMillis() - stats.lastFundsAlertTime;
        if (timeSinceLastFundsAlert < COOLDOWN_PERIOD_MS) {
            return false;
        }

        if (stats.fundsViolations > 10) {
            double avgExcess = stats.totalFundsExcess / stats.fundsViolations;
            double currentExcess = transactionAmount - balance;
            return currentExcess > avgExcess * 2.0;
        }

        return true;
    }

    private boolean shouldCreateLimitAlert(String cardId, double transactionAmount, double limit) {
        LimitAnomalyStats stats = getLimitStats(cardId);

        if (stats == null) {
            return true;
        }

        long timeSinceLastLimitAlert = System.currentTimeMillis() - stats.lastLimitAlertTime;
        if (timeSinceLastLimitAlert < COOLDOWN_PERIOD_MS) {
            return false;
        }

        if (stats.limitViolations > 5) {
            double avgExcess = stats.totalLimitExcess / stats.limitViolations;
            double currentExcess = transactionAmount - limit;
            return currentExcess > avgExcess * 1.5; // 1.5x higher than usual
        }

        return true;
    }

    private double calculateFundsSeverity(String cardId, double excess, double balance) {
        LimitAnomalyStats stats = getLimitStats(cardId);
        double baseSeverity = 0.7;

        if (stats != null && stats.fundsViolations > 0) {
            double avgExcess = stats.totalFundsExcess / stats.fundsViolations;

            if (excess > avgExcess * 3.0) {
                baseSeverity = 0.85;
            } else if (excess < avgExcess * 0.5) {
                baseSeverity = 0.6;
            }
        }

        double excessRatio = excess / Math.max(balance, 1.0);
        if (excessRatio > 2.0) {
            baseSeverity = Math.min(0.9, baseSeverity + 0.1);
        }

        return baseSeverity;
    }

    private double calculateLimitSeverity(String cardId, double excess, double limit) {
        LimitAnomalyStats stats = getLimitStats(cardId);
        double baseSeverity = 0.8;

        if (stats != null && stats.limitViolations > 0) {
            double avgExcess = stats.totalLimitExcess / stats.limitViolations;

            if (excess > avgExcess * 2.0) {
                baseSeverity = 0.9;
            } else if (excess < avgExcess * 0.5) {
                baseSeverity = 0.7;
            }
        }

        double excessRatio = excess / limit;
        if (excessRatio > 1.0) {
            baseSeverity = Math.min(0.95, baseSeverity + 0.1);
        }

        return baseSeverity;
    }

    private LimitAnomalyStats getLimitStats(String cardId) {
        String statsKey = LIMIT_STATS_PREFIX + cardId;
        return getFromRedis(statsKey, LimitAnomalyStats.class);
    }

    private void updateLimitStats(String cardId, String violationType, double excess) {
        try {
            String statsKey = LIMIT_STATS_PREFIX + cardId;
            LimitAnomalyStats stats = getFromRedis(statsKey, LimitAnomalyStats.class);

            if (stats == null) {
                stats = new LimitAnomalyStats();
            }

            long currentTime = System.currentTimeMillis();

            if ("FUNDS".equals(violationType)) {
                stats.fundsViolations++;
                stats.totalFundsExcess += excess;
                stats.lastFundsAlertTime = currentTime;
            } else if ("LIMIT".equals(violationType)) {
                stats.limitViolations++;
                stats.totalLimitExcess += excess;
                stats.lastLimitAlertTime = currentTime;
            }

            stats.totalViolations++;
            stats.lastViolationTime = currentTime;

            storeInRedis(statsKey, stats, LIMIT_STATS_TTL);

        } catch (Exception e) {
            System.err.println("Failed to update limit anomaly stats: " + e.getMessage());
        }
    }

    public static class LimitAnomalyStats {
        public int fundsViolations = 0;
        public int limitViolations = 0;
        public int totalViolations = 0;
        public double totalFundsExcess = 0.0;
        public double totalLimitExcess = 0.0;
        public long lastFundsAlertTime = 0;
        public long lastLimitAlertTime = 0;
        public long lastViolationTime = 0;

        public LimitAnomalyStats() {}
    }
}