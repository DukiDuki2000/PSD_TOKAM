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
                detectUnusualMerchantAnomaly(transaction, risk, context);
            }

        } catch (Exception e) {
            System.err.println("Unusual merchant detection error for card " + transaction.cardId + ": " + e.getMessage());
        }

        out.collect(transaction);
    }

    private void detectUnusualMerchantAnomaly(Transaction transaction, MerchantRisk risk, Context context) {
        CardProfile cardProfile = getFromRedis("card_profile:" + transaction.cardId, CardProfile.class);
        double baseAmount = (cardProfile != null) ? cardProfile.avgAmount : 0.0;

        double multiplier = baseAmount > 0 ? transaction.amount / baseAmount : 1.0;

        String description = String.format(
                "UNUSUAL MERCHANT: %s (Risk: %s) - %.2f PLN%s at %s, %s",
                transaction.merchantCategory.toUpperCase(),
                risk.level,
                transaction.amount,
                multiplier > 1 ? String.format(" (%.1fx avg)", multiplier) : "",
                transaction.location.city,
                transaction.location.country
        );

        double severity = Math.min(1.0, risk.baseSeverity + Math.min(0.3, (multiplier - 1) * 0.1));

        AnomalyAlert alert = new AnomalyAlert(
                "merchant_" + UUID.randomUUID().toString().substring(0, 8),
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

    private static class MerchantRisk {
        final String level;
        final double baseSeverity;

        MerchantRisk(String level, double baseSeverity) {
            this.level = level;
            this.baseSeverity = baseSeverity;
        }
    }
}