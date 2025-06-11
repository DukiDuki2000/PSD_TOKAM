package org.example.processors.window;

import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;
import org.example.models.AnomalyAlert;
import org.example.models.Transaction;
import org.example.processors.base.BaseWindowAnomalyDetector;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class DuplicateTransactionWindowDetector extends BaseWindowAnomalyDetector<Transaction, Transaction, String> {

    private final OutputTag<AnomalyAlert> duplicateAnomalyTag;

    private static final int MIN_DUPLICATE_COUNT = 2;
    private static final double AMOUNT_TOLERANCE = 0.01;
    private static final String DUPLICATE_STATS_PREFIX = "dup_window_stats:";
    private static final int DUPLICATE_STATS_TTL = 300; // 5 minutes TTL

    public DuplicateTransactionWindowDetector(OutputTag<AnomalyAlert> duplicateAnomalyTag) {
        this.duplicateAnomalyTag = duplicateAnomalyTag;
    }

    @Override
    public void process(String cardId, Context context, Iterable<Transaction> elements, Collector<Transaction> out) throws Exception {

        List<Transaction> transactions = StreamSupport.stream(elements.spliterator(), false)
                .collect(Collectors.toList());

        elements.forEach(out::collect);

        if (transactions.size() < 2) {
            return;
        }

        if (shouldCreateDuplicateAlert(cardId, transactions, context)) {
            detectDuplicateTransactionAnomaly(cardId, transactions, context);
        }

        updateDuplicateWindowStats(cardId, transactions, context.window());
    }

    private boolean shouldCreateDuplicateAlert(String cardId, List<Transaction> transactions, Context context) {
        try {
            String alertKey = "recent_alert:" + cardId + ":DUPLICATE_TRANSACTIONS";
            String lastAlertTimeStr = getFromRedis(alertKey, String.class);

            if (lastAlertTimeStr != null) {
                long lastAlertTime = Long.parseLong(lastAlertTimeStr);
                long timeDiff = context.window().getEnd() - lastAlertTime;

                if (timeDiff < 45_000) {
                    return false;
                }
            }

            Map<DuplicateKey, List<Transaction>> groups = new HashMap<>();
            for (Transaction tx : transactions) {
                DuplicateKey key = new DuplicateKey(tx.amount, tx.location.city);
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(tx);
            }

            int maxDuplicatesInGroup = groups.values().stream()
                    .mapToInt(List::size)
                    .max()
                    .orElse(0);

            int adaptiveThreshold = calculateDuplicateAdaptiveThreshold(cardId, maxDuplicatesInGroup);

            if (maxDuplicatesInGroup >= adaptiveThreshold) {
                storeInRedis(alertKey, String.valueOf(context.window().getEnd()), 90);
                return true;
            }

            return false;

        } catch (Exception e) {
            System.err.println("Error checking duplicate alert conditions: " + e.getMessage());
            return false;
        }
    }

    private int calculateDuplicateAdaptiveThreshold(String cardId, int currentMaxDuplicates) {
        try {
            String statsKey = DUPLICATE_STATS_PREFIX + cardId;
            DuplicateWindowStats stats = getFromRedis(statsKey, DuplicateWindowStats.class);

            if (stats == null || stats.windowCount < 3) {
                return MIN_DUPLICATE_COUNT;
            }

            double avgDuplicatesPerWindow = stats.windowsWithDuplicates > 0 ?
                    stats.totalDuplicateTransactions / (double) stats.windowsWithDuplicates : 0.0;

            if (avgDuplicatesPerWindow < 1.0) {
                return 2;
            } else if (avgDuplicatesPerWindow < 3.0) {
                return Math.max(2, (int) Math.ceil(avgDuplicatesPerWindow * 2.0));
            } else {
                return Math.max(3, (int) Math.ceil(avgDuplicatesPerWindow * 1.5));
            }

        } catch (Exception e) {
            System.err.println("Error calculating duplicate adaptive threshold: " + e.getMessage());
            return MIN_DUPLICATE_COUNT;
        }
    }

    private void detectDuplicateTransactionAnomaly(String cardId, List<Transaction> transactions, Context context) {
        long windowStart = context.window().getStart();
        long windowEnd = context.window().getEnd();
        long windowDurationSeconds = (windowEnd - windowStart) / 1000;

        Map<DuplicateKey, List<Transaction>> groups = new HashMap<>();
        for (Transaction tx : transactions) {
            DuplicateKey key = new DuplicateKey(tx.amount, tx.location.city);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(tx);
        }

        List<List<Transaction>> duplicateGroups = groups.values().stream()
                .filter(group -> group.size() >= MIN_DUPLICATE_COUNT)
                .collect(Collectors.toList());

        if (!duplicateGroups.isEmpty()) {
            List<Transaction> largestGroup = duplicateGroups.stream()
                    .max(Comparator.comparing(List::size))
                    .get();

            double duplicateAmount = largestGroup.get(0).amount;
            String location = largestGroup.get(0).location.city;

            String transactionIds = largestGroup.stream()
                    .limit(3)
                    .map(t -> t.transactionId)
                    .collect(Collectors.joining(", "));

            Transaction lastTransaction = largestGroup.get(largestGroup.size() - 1);

            String description = String.format(
                    "DUPLICATE TRANSACTIONS: %d identical transactions of %.2f PLN in %s within %d-second window. " +
                            "Transaction IDs: %s. Adaptive threshold applied.",
                    largestGroup.size(), duplicateAmount, location, windowDurationSeconds, transactionIds
            );

            double severity = Math.min(0.9, 0.5 + (largestGroup.size() * 0.1));

            AnomalyAlert alert = new AnomalyAlert(
                    "dup_adaptive_" + UUID.randomUUID().toString().substring(0, 8),
                    lastTransaction.transactionId,
                    cardId,
                    lastTransaction.userId,
                    "DUPLICATE_TRANSACTIONS_PATTERN",
                    description,
                    severity,
                    windowEnd,
                    lastTransaction.location
            );

            context.output(duplicateAnomalyTag, alert);
        }
    }

    private void updateDuplicateWindowStats(String cardId, List<Transaction> transactions, TimeWindow window) {
        String statsKey = DUPLICATE_STATS_PREFIX + cardId;

        DuplicateWindowStats stats = getFromRedis(statsKey, DuplicateWindowStats.class);
        if (stats == null) {
            stats = new DuplicateWindowStats();
        }

        stats.windowCount++;
        stats.totalTransactions += transactions.size();
        stats.lastWindowStart = window.getStart();
        stats.lastWindowEnd = window.getEnd();

        // Calculate unique amounts and locations
        Set<Double> uniqueAmounts = transactions.stream()
                .map(t -> t.amount)
                .collect(Collectors.toSet());

        Set<String> uniqueLocations = transactions.stream()
                .map(t -> t.location.city)
                .collect(Collectors.toSet());

        stats.uniqueAmounts = uniqueAmounts.size();
        stats.uniqueLocations = uniqueLocations.size();

        // Count duplicate groups and duplicate transactions
        Map<DuplicateKey, Long> amountLocationGroups = transactions.stream()
                .collect(Collectors.groupingBy(
                        t -> new DuplicateKey(t.amount, t.location.city),
                        Collectors.counting()
                ));

        stats.duplicateGroups = (int) amountLocationGroups.values().stream()
                .filter(count -> count > 1)
                .count();

        int duplicateTransactionsInWindow = (int) amountLocationGroups.values().stream()
                .filter(aLong -> aLong > 1)
                .mapToLong(l -> l)
                .sum();

        stats.totalDuplicateTransactions += duplicateTransactionsInWindow;

        boolean foundDuplicates = amountLocationGroups.values().stream()
                .anyMatch(count -> count > 1);

        if (foundDuplicates) {
            stats.windowsWithDuplicates++;
        }

        storeInRedis(statsKey, stats, DUPLICATE_STATS_TTL);
    }

    private static class DuplicateKey {
        final double amount;
        final String locationCity;

        DuplicateKey(double amount, String locationCity) {
            this.amount = amount;
            this.locationCity = locationCity;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            DuplicateKey that = (DuplicateKey) o;
            return Math.abs(amount - that.amount) <= AMOUNT_TOLERANCE &&
                    Objects.equals(locationCity, that.locationCity);
        }

        @Override
        public int hashCode() {
            return Objects.hash(Math.round(amount / AMOUNT_TOLERANCE), locationCity);
        }
    }

    public static class DuplicateWindowStats {
        public int windowCount = 0;
        public int totalTransactions = 0;
        public int totalDuplicateTransactions = 0;
        public int uniqueAmounts = 0;
        public int uniqueLocations = 0;
        public int duplicateGroups = 0;
        public int windowsWithDuplicates = 0;
        public long lastWindowStart = 0;
        public long lastWindowEnd = 0;

        public DuplicateWindowStats() {}
    }
}