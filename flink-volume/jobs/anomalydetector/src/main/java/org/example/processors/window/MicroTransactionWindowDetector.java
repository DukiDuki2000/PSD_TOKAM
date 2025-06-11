package org.example.processors.window;

import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;
import org.example.models.AnomalyAlert;
import org.example.models.Transaction;
import org.example.processors.base.BaseWindowAnomalyDetector;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class MicroTransactionWindowDetector extends BaseWindowAnomalyDetector<Transaction, Transaction, String> {

    private final OutputTag<AnomalyAlert> microTransactionAnomalyTag;

    private static final double MICRO_TRANSACTION_THRESHOLD = 1.0; // Less than 1 PLN
    private static final int MIN_MICRO_TRANSACTIONS = 3; // Minimum for anomaly (fallback)
    private static final String MICRO_STATS_PREFIX = "micro_window_stats:";
    private static final int MICRO_STATS_TTL = 300; // 5 minutes TTL

    public MicroTransactionWindowDetector(OutputTag<AnomalyAlert> microTransactionAnomalyTag) {
        this.microTransactionAnomalyTag = microTransactionAnomalyTag;
    }

    @Override
    public void process(String cardId, Context context, Iterable<Transaction> elements, Collector<Transaction> out) throws Exception {

        List<Transaction> microTransactions = StreamSupport.stream(elements.spliterator(), false)
                .filter(tx -> tx.amount <= MICRO_TRANSACTION_THRESHOLD)
                .collect(Collectors.toList());

        elements.forEach(out::collect);

        if (!microTransactions.isEmpty()) {
            if (shouldCreateMicroAlert(cardId, microTransactions, context)) {
                detectMicroTransactionAnomaly(cardId, microTransactions, context);
            }

            updateMicroWindowStats(cardId, microTransactions, context.window());
        }
    }

    private boolean shouldCreateMicroAlert(String cardId, List<Transaction> microTransactions, Context context) {
        try {
            String alertKey = "recent_alert:" + cardId + ":MICRO_TRANSACTIONS";
            String lastAlertTimeStr = getFromRedis(alertKey, String.class);

            if (lastAlertTimeStr != null) {
                long lastAlertTime = Long.parseLong(lastAlertTimeStr);
                long timeDiff = context.window().getEnd() - lastAlertTime;

                if (timeDiff < 30_000) {
                    return false;
                }
            }

            int adaptiveThreshold = calculateMicroAdaptiveThreshold(cardId, microTransactions.size());

            if (microTransactions.size() >= adaptiveThreshold) {
                storeInRedis(alertKey, String.valueOf(context.window().getEnd()), 60);
                return true;
            }

            return false;

        } catch (Exception e) {
            System.err.println("Error checking micro alert conditions: " + e.getMessage());
            return microTransactions.size() >= MIN_MICRO_TRANSACTIONS;
        }
    }

    private int calculateMicroAdaptiveThreshold(String cardId, int currentCount) {
        try {
            String statsKey = MICRO_STATS_PREFIX + cardId;
            MicroWindowStats stats = getFromRedis(statsKey, MicroWindowStats.class);

            if (stats == null || stats.windowCount < 5) {
                return MIN_MICRO_TRANSACTIONS;
            }

            double avgMicroPerWindow = stats.totalMicroTransactions / (double) stats.windowCount;

            if (avgMicroPerWindow < 1.0) {
                return Math.max(2, (int) Math.ceil(avgMicroPerWindow * 3.0));
            } else if (avgMicroPerWindow < 3.0) {
                return Math.max(3, (int) Math.ceil(avgMicroPerWindow * 2.5));
            } else {
                return Math.max(5, (int) Math.ceil(avgMicroPerWindow * 2.0));
            }

        } catch (Exception e) {
            System.err.println("Error calculating micro adaptive threshold: " + e.getMessage());
            return MIN_MICRO_TRANSACTIONS; // Fallback
        }
    }

    private void detectMicroTransactionAnomaly(String cardId, List<Transaction> microTransactions, Context context) {
        long windowStart = context.window().getStart();
        long windowEnd = context.window().getEnd();
        long windowDurationSeconds = (windowEnd - windowStart) / 1000;

        double totalAmount = microTransactions.stream()
                .mapToDouble(tx -> tx.amount)
                .sum();

        double averageAmount = totalAmount / microTransactions.size();

        Transaction lastTransaction = microTransactions.get(microTransactions.size() - 1);

        String description = String.format(
                "MICRO TRANSACTION PATTERN: %d transactions ≤%.2f PLN in %d-second window. " +
                        "Total: %.2f PLN, Avg: %.3f PLN. Location: %s. Adaptive threshold applied.",
                microTransactions.size(), MICRO_TRANSACTION_THRESHOLD, windowDurationSeconds,
                totalAmount, averageAmount, lastTransaction.location.city
        );

        double severity = Math.min(0.9, 0.4 + (microTransactions.size() * 0.08));

        AnomalyAlert alert = new AnomalyAlert(
                "micro_adaptive_" + UUID.randomUUID().toString().substring(0, 8),
                lastTransaction.transactionId,
                cardId,
                lastTransaction.userId,
                "MICRO_TRANSACTIONS_PATTERN",
                description,
                severity,
                windowEnd,
                lastTransaction.location
        );

        context.output(microTransactionAnomalyTag, alert);
    }

    private void updateMicroWindowStats(String cardId, List<Transaction> microTransactions, TimeWindow window) {
        String statsKey = MICRO_STATS_PREFIX + cardId;

        MicroWindowStats stats = getFromRedis(statsKey, MicroWindowStats.class);
        if (stats == null) {
            stats = new MicroWindowStats();
        }

        stats.windowCount++;
        stats.totalMicroTransactions += microTransactions.size();
        stats.lastWindowStart = window.getStart();
        stats.lastWindowEnd = window.getEnd();

        double totalAmount = microTransactions.stream()
                .mapToDouble(tx -> tx.amount)
                .sum();
        stats.totalMicroAmount += totalAmount;

        double smallestInWindow = microTransactions.stream()
                .mapToDouble(tx -> tx.amount)
                .min()
                .orElse(0.0);

        if (stats.smallestAmount == 0.0 || smallestInWindow < stats.smallestAmount) {
            stats.smallestAmount = smallestInWindow;
        }

        storeInRedis(statsKey, stats, MICRO_STATS_TTL);
    }

    public static class MicroWindowStats {
        public int windowCount = 0;
        public int totalMicroTransactions = 0;
        public double totalMicroAmount = 0.0;
        public double smallestAmount = 0.0;
        public long lastWindowStart = 0;
        public long lastWindowEnd = 0;

        public MicroWindowStats() {}
    }
}