package org.example.processors;

import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;
import org.example.models.AnomalyAlert;
import org.example.models.CardProfile;
import org.example.models.Transaction;
import org.example.processors.base.BaseAnomalyDetector;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public class InactiveCardAnomalyDetector extends BaseAnomalyDetector<Transaction, Transaction> {

    private final OutputTag<AnomalyAlert> inactiveCardAnomalyTag;

    private static final String CARD_USAGE_STATS_PREFIX = "card_usage_stats:";
    private static final int CARD_USAGE_STATS_TTL = 300;
    private static final long COOLDOWN_PERIOD_MS = 120_000;

    public InactiveCardAnomalyDetector(OutputTag<AnomalyAlert> inactiveCardAnomalyTag) {
        this.inactiveCardAnomalyTag = inactiveCardAnomalyTag;
    }

    @Override
    public void processElement(Transaction transaction, Context context, Collector<Transaction> out) throws Exception {
        try {
            CardProfile cardProfile = getFromRedis("card_profile:" + transaction.cardId, CardProfile.class);

            if (cardProfile != null) {
                ExpiryResult expiryResult = checkCardExpiry(cardProfile, transaction);

                if (expiryResult.isExpired) {
                    if (shouldCreateExpiryAlert(transaction.cardId)) {
                        detectExpiredCardAnomaly(transaction, expiryResult, context);
                        updateUsageStats(transaction.cardId, "EXPIRED");
                    }
                } else if (!cardProfile.isActive) {
                    if (shouldCreateInactiveAlert(transaction.cardId)) {
                        detectInactiveCardAnomaly(transaction, context);
                        updateUsageStats(transaction.cardId, "INACTIVE");
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Inactive card anomaly detection error for card " + transaction.cardId + ": " + e.getMessage());
        }

        out.collect(transaction);
    }

    private boolean shouldCreateExpiryAlert(String cardId) {
        CardUsageStats stats = getUsageStats(cardId);

        if (stats == null) {
            return true;
        }

        long timeSinceLastExpiredAlert = System.currentTimeMillis() - stats.lastExpiredAlertTime;
        if (timeSinceLastExpiredAlert < COOLDOWN_PERIOD_MS) {
            return false;
        }

        if (stats.expiredAttempts > 5) {
            long extendedCooldown = COOLDOWN_PERIOD_MS * 2; // 2 hours
            return timeSinceLastExpiredAlert > extendedCooldown;
        }

        return true;
    }

    private boolean shouldCreateInactiveAlert(String cardId) {
        CardUsageStats stats = getUsageStats(cardId);

        if (stats == null) {
            return true;
        }

        long timeSinceLastInactiveAlert = System.currentTimeMillis() - stats.lastInactiveAlertTime;
        if (timeSinceLastInactiveAlert < COOLDOWN_PERIOD_MS) {
            return false;
        }

        if (stats.inactiveAttempts > 3) {
            long extendedCooldown = COOLDOWN_PERIOD_MS * 3; // 3 hours
            return timeSinceLastInactiveAlert > extendedCooldown;
        }

        return true;
    }

    private ExpiryResult checkCardExpiry(CardProfile cardProfile, Transaction transaction) {
        try {
            LocalDateTime expiryDate = LocalDateTime.parse(cardProfile.expiryDate, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            LocalDateTime transactionTime = LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(transaction.timestamp),
                    java.time.ZoneOffset.UTC
            );

            boolean isExpired = expiryDate.isBefore(transactionTime);
            long daysExpired = isExpired ? java.time.Duration.between(expiryDate, transactionTime).toDays() : 0;

            return new ExpiryResult(isExpired, daysExpired, expiryDate, transactionTime);
        } catch (Exception e) {
            return new ExpiryResult(false, 0, null, null);
        }
    }

    private void detectInactiveCardAnomaly(Transaction transaction, Context context) {
        String description = String.format(
                "INACTIVE CARD: Transaction attempted on inactive card %s. " +
                        "Amount: %.2f PLN. Status: %s. Adaptive cooldown applied.",
                transaction.cardId, transaction.amount, transaction.status
        );

        AnomalyAlert alert = new AnomalyAlert(
                "inactive_adaptive_" + UUID.randomUUID().toString().substring(0, 8),
                transaction.transactionId,
                transaction.cardId,
                transaction.userId,
                "INACTIVE_CARD",
                description,
                0.85,
                transaction.timestamp,
                transaction.location
        );

        context.output(inactiveCardAnomalyTag, alert);
    }

    private void detectExpiredCardAnomaly(Transaction transaction, ExpiryResult expiryResult, Context context) {
        double severity = 0.55;
        if (expiryResult.daysExpired > 30) {
            severity = 0.95;
        } else if (expiryResult.daysExpired < 7) {
            severity = 0.70;
        }

        String description = String.format(
                "EXPIRED CARD: Transaction attempted on expired card %s " +
                        "(expired %d days before transaction). Amount: %.2f PLN. Status: %s. Adaptive severity applied.",
                transaction.cardId, expiryResult.daysExpired, transaction.amount, transaction.status
        );

        AnomalyAlert alert = new AnomalyAlert(
                "expired_adaptive_" + UUID.randomUUID().toString().substring(0, 8),
                transaction.transactionId,
                transaction.cardId,
                transaction.userId,
                "EXPIRED_CARD",
                description,
                severity,
                transaction.timestamp,
                transaction.location
        );

        context.output(inactiveCardAnomalyTag, alert);
    }

    private CardUsageStats getUsageStats(String cardId) {
        String statsKey = CARD_USAGE_STATS_PREFIX + cardId;
        return getFromRedis(statsKey, CardUsageStats.class);
    }

    private void updateUsageStats(String cardId, String anomalyType) {
        try {
            String statsKey = CARD_USAGE_STATS_PREFIX + cardId;
            CardUsageStats stats = getFromRedis(statsKey, CardUsageStats.class);

            if (stats == null) {
                stats = new CardUsageStats();
            }

            long currentTime = System.currentTimeMillis();

            if ("EXPIRED".equals(anomalyType)) {
                stats.expiredAttempts++;
                stats.lastExpiredAlertTime = currentTime;
            } else if ("INACTIVE".equals(anomalyType)) {
                stats.inactiveAttempts++;
                stats.lastInactiveAlertTime = currentTime;
            }

            stats.totalAnomalies++;
            stats.lastAnomalyTime = currentTime;

            storeInRedis(statsKey, stats, CARD_USAGE_STATS_TTL);

        } catch (Exception e) {
            System.err.println("Failed to update card usage stats: " + e.getMessage());
        }
    }

    private static class ExpiryResult {
        final boolean isExpired;
        final long daysExpired;
        final LocalDateTime expiryDate;
        final LocalDateTime transactionTime;

        ExpiryResult(boolean isExpired, long daysExpired, LocalDateTime expiryDate, LocalDateTime transactionTime) {
            this.isExpired = isExpired;
            this.daysExpired = daysExpired;
            this.expiryDate = expiryDate;
            this.transactionTime = transactionTime;
        }
    }

    public static class CardUsageStats {
        public int expiredAttempts = 0;
        public int inactiveAttempts = 0;
        public int totalAnomalies = 0;
        public long lastExpiredAlertTime = 0;
        public long lastInactiveAlertTime = 0;
        public long lastAnomalyTime = 0;

        public CardUsageStats() {}
    }
}