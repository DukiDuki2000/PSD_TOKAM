package org.example.processors;

import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;
import org.example.models.AnomalyAlert;
import org.example.models.CardProfile;
import org.example.models.Transaction;
import org.example.processors.base.BaseAnomalyDetector;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class UnusualMerchantDetector extends BaseAnomalyDetector<Transaction, Transaction> {

    private final OutputTag<AnomalyAlert> unusualMerchantAnomalyTag;

    private static final String MERCHANT_STATS_PREFIX = "merchant_stats:";
    private static final int MERCHANT_STATS_TTL = 300;
    private static final long COOLDOWN_PERIOD_MS = 120_000;

    private static final Map<String, MerchantRisk> SUSPICIOUS_CATEGORIES;
    static {
        Map<String, MerchantRisk> categories = new HashMap<>();
        categories.put("gambling", new MerchantRisk("VERY HIGH", 0.9));
        categories.put("crypto_exchange", new MerchantRisk("VERY HIGH", 0.85));
        categories.put("adult_services", new MerchantRisk("HIGH", 0.8));
        categories.put("high_risk_merchant", new MerchantRisk("HIGH", 0.75));
        categories.put("luxury_goods", new MerchantRisk("MEDIUM", 0.6));
        SUSPICIOUS_CATEGORIES = Collections.unmodifiableMap(categories);
    }

    public UnusualMerchantDetector(OutputTag<AnomalyAlert> unusualMerchantAnomalyTag) {
        this.unusualMerchantAnomalyTag = unusualMerchantAnomalyTag;
    }

    @Override
    public void processElement(Transaction transaction, Context context, Collector<Transaction> out)
            throws Exception {

        try {
            MerchantRisk risk = SUSPICIOUS_CATEGORIES.get(transaction.merchantCategory);
            if (risk != null) {
                if (shouldCreateMerchantAlert(transaction.cardId, transaction.merchantCategory)) {
                    double adaptiveSeverity = calculateAdaptiveSeverity(transaction, risk);
                    detectUnusualMerchantAnomaly(transaction, risk, adaptiveSeverity, context);
                    updateMerchantStats(transaction.cardId, transaction.merchantCategory, transaction.amount, adaptiveSeverity);
                }
            }

        } catch (Exception e) {
            System.err.println("Unusual merchant detection error for card " + transaction.cardId + ": " + e.getMessage());
        }

        out.collect(transaction);
    }

    private boolean shouldCreateMerchantAlert(String cardId, String merchantCategory) {
        MerchantStats stats = getMerchantStats(cardId);

        if (stats == null) {
            return true;
        }

        String lastAlertKey = "last_" + merchantCategory + "_alert";
        Long lastAlertTime = stats.categoryLastAlert.get(lastAlertKey);

        if (lastAlertTime != null) {
            long timeSinceLastAlert = System.currentTimeMillis() - lastAlertTime;

            Integer categoryFrequency = stats.categoryFrequency.get(merchantCategory);
            if (categoryFrequency != null && categoryFrequency > 5) {
                long extendedCooldown = COOLDOWN_PERIOD_MS * 3;
                return timeSinceLastAlert > extendedCooldown;
            } else if (categoryFrequency != null && categoryFrequency > 2) {
                return timeSinceLastAlert > COOLDOWN_PERIOD_MS;
            }
            return timeSinceLastAlert > (COOLDOWN_PERIOD_MS / 2);
        }

        return true;
    }

    private double calculateAdaptiveSeverity(Transaction transaction, MerchantRisk risk) {
        CardProfile cardProfile = getFromRedis("card_profile:" + transaction.cardId, CardProfile.class);
        MerchantStats stats = getMerchantStats(transaction.cardId);

        double baseSeverity = risk.baseSeverity;
        double baseAmount = (cardProfile != null) ? cardProfile.avgAmount : 0.0;
        double multiplier = baseAmount > 0 ? transaction.amount / baseAmount : 1.0;
        double severityFromAmount = Math.min(0.3, (multiplier - 1) * 0.1);

        if (stats != null) {
            Integer categoryFrequency = stats.categoryFrequency.get(transaction.merchantCategory);
            if (categoryFrequency != null) {
                if (categoryFrequency > 10) {
                    baseSeverity *= 0.8;
                } else if (categoryFrequency < 2) {
                    baseSeverity *= 1.2;
                }
            }
            Double avgCategoryAmount = stats.categoryAvgAmount.get(transaction.merchantCategory);
            if (avgCategoryAmount != null && avgCategoryAmount > 0) {
                double categoryMultiplier = transaction.amount / avgCategoryAmount;
                if (categoryMultiplier > 3.0) {
                    baseSeverity *= 1.15;
                }
            }
        }

        return Math.min(1.0, baseSeverity + severityFromAmount);
    }

    private void detectUnusualMerchantAnomaly(Transaction transaction, MerchantRisk risk, double severity, Context context) {
        CardProfile cardProfile = getFromRedis("card_profile:" + transaction.cardId, CardProfile.class);
        double baseAmount = (cardProfile != null) ? cardProfile.avgAmount : 0.0;
        double multiplier = baseAmount > 0 ? transaction.amount / baseAmount : 1.0;

        String description = String.format(
                "UNUSUAL MERCHANT: %s (Risk: %s) - %.2f PLN%s at %s, %s. Adaptive severity applied.",
                transaction.merchantCategory.toUpperCase(),
                risk.level,
                transaction.amount,
                multiplier > 1 ? String.format(" (%.1fx avg)", multiplier) : "",
                transaction.location.city,
                transaction.location.country
        );

        AnomalyAlert alert = new AnomalyAlert(
                "merchant_adaptive_" + UUID.randomUUID().toString().substring(0, 8),
                transaction.transactionId,
                transaction.cardId,
                transaction.userId,
                "UNUSUAL_MERCHANT",
                description,
                severity,
                transaction.timestamp,
                transaction.location
        );

        context.output(unusualMerchantAnomalyTag, alert);
    }

    private MerchantStats getMerchantStats(String cardId) {
        String statsKey = MERCHANT_STATS_PREFIX + cardId;
        return getFromRedis(statsKey, MerchantStats.class);
    }

    private void updateMerchantStats(String cardId, String merchantCategory, double amount, double severity) {
        try {
            String statsKey = MERCHANT_STATS_PREFIX + cardId;
            MerchantStats stats = getFromRedis(statsKey, MerchantStats.class);

            if (stats == null) {
                stats = new MerchantStats();
            }

            long currentTime = System.currentTimeMillis();

            stats.categoryFrequency.put(merchantCategory,
                    stats.categoryFrequency.getOrDefault(merchantCategory, 0) + 1);

            Double currentAvg = stats.categoryAvgAmount.get(merchantCategory);
            Integer currentCount = stats.categoryFrequency.get(merchantCategory);

            if (currentAvg == null) {
                stats.categoryAvgAmount.put(merchantCategory, amount);
            } else {
                double newAvg = ((currentAvg * (currentCount - 1)) + amount) / currentCount;
                stats.categoryAvgAmount.put(merchantCategory, newAvg);
            }

            String lastAlertKey = "last_" + merchantCategory + "_alert";
            stats.categoryLastAlert.put(lastAlertKey, currentTime);
            stats.totalRiskyTransactions++;
            stats.totalRiskyAmount += amount;
            stats.lastRiskyTransactionTime = currentTime;

            storeInRedis(statsKey, stats, MERCHANT_STATS_TTL);

        } catch (Exception e) {
            System.err.println("Failed to update merchant anomaly stats: " + e.getMessage());
        }
    }

    private static class MerchantRisk {
        final String level;
        final double baseSeverity;

        MerchantRisk(String level, double baseSeverity) {
            this.level = level;
            this.baseSeverity = baseSeverity;
        }
    }

    public static class MerchantStats {
        public Map<String, Integer> categoryFrequency = new HashMap<>();
        public Map<String, Double> categoryAvgAmount = new HashMap<>();
        public Map<String, Long> categoryLastAlert = new HashMap<>();
        public int totalRiskyTransactions = 0;
        public double totalRiskyAmount = 0.0;
        public long lastRiskyTransactionTime = 0;

        public MerchantStats() {}
    }
}