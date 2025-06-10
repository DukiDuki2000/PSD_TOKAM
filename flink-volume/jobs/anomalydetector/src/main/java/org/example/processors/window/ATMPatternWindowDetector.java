package org.example.processors.window;

import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;
import org.example.models.AnomalyAlert;
import org.example.models.Transaction;
import org.example.processors.base.BaseWindowAnomalyDetector;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class ATMPatternWindowDetector extends BaseWindowAnomalyDetector<Transaction, Transaction, String> {

    private final OutputTag<AnomalyAlert> atmPatternAnomalyTag;

    private static final int MIN_ATM_TRANSACTIONS = 3;
    private static final List<Double> ATM_STANDARD_AMOUNTS = Arrays.asList(20.00, 50.00, 100.00, 200.00, 300.00, 500.00, 1000.00);
    private static final String ATM_STATS_PREFIX = "atm_window_stats:";
    private static final int ATM_STATS_TTL = 300;

    public ATMPatternWindowDetector(OutputTag<AnomalyAlert> atmPatternAnomalyTag) {
        this.atmPatternAnomalyTag = atmPatternAnomalyTag;
    }

    @Override
    public void process(String cardId, Context context, Iterable<Transaction> elements, Collector<Transaction> out) throws Exception {

        List<Transaction> atmTransactions = StreamSupport.stream(elements.spliterator(), false)
                .filter(this::isATMTransaction)
                .collect(Collectors.toList());

        elements.forEach(out::collect);


        if (atmTransactions.size() >= MIN_ATM_TRANSACTIONS) {
            detectATMPatternAnomaly(cardId, atmTransactions, context);
        }

        if (!atmTransactions.isEmpty()) {
            updateATMWindowStats(cardId, atmTransactions, context.window());
        }
    }

    private boolean isATMTransaction(Transaction transaction) {
        boolean isATMCategory = "atm_withdrawal".equals(transaction.merchantCategory);
        boolean isStandardATMAmount = ATM_STANDARD_AMOUNTS.contains(transaction.amount);
        return isATMCategory || isStandardATMAmount;
    }

    private void detectATMPatternAnomaly(String cardId, List<Transaction> atmTransactions, Context context) {
        long windowStart = context.window().getStart();
        long windowEnd = context.window().getEnd();
        long windowDurationSeconds = (windowEnd - windowStart) / 1000;

        double totalAmount = atmTransactions.stream()
                .mapToDouble(tx -> tx.amount)
                .sum();

        long standardAmountCount = atmTransactions.stream()
                .mapToDouble(tx -> tx.amount)
                .filter(ATM_STANDARD_AMOUNTS::contains)
                .count();

        String amountsStr = atmTransactions.stream()
                .mapToDouble(tx -> tx.amount)
                .boxed()
                .map(amount -> String.format("%.0f", amount))
                .reduce((a, b) -> a + ", " + b)
                .orElse("");

        Transaction lastTransaction = atmTransactions.get(atmTransactions.size() - 1);

        String description = String.format(
                "ATM PATTERN DETECTED: %d ATM transactions in %d-second window. " +
                        "Amounts: %s PLN (Total: %.2f PLN). %d/%d are standard ATM denominations. Location: %s.",
                atmTransactions.size(), windowDurationSeconds,
                amountsStr, totalAmount, standardAmountCount, atmTransactions.size(),
                lastTransaction.location.city
        );

        double severity = Math.min(0.85, 0.5 + (standardAmountCount * 0.08) + (atmTransactions.size() * 0.05));

        AnomalyAlert alert = new AnomalyAlert(
                "atm_window_" + UUID.randomUUID().toString().substring(0, 8),
                lastTransaction.transactionId,
                cardId,
                lastTransaction.userId,
                "ATM_PATTERN",
                description,
                severity,
                windowEnd,
                lastTransaction.location
        );

        context.output(atmPatternAnomalyTag, alert);

    }

    private void updateATMWindowStats(String cardId, List<Transaction> atmTransactions, TimeWindow window) {
        String statsKey = ATM_STATS_PREFIX + cardId;
        long windowStart = window.getStart();

        ATMWindowStats stats = getFromRedis(statsKey, ATMWindowStats.class);
        if (stats == null) {
            stats = new ATMWindowStats();
        }

        stats.windowCount++;
        stats.totalATMTransactions += atmTransactions.size();
        stats.lastWindowStart = windowStart;
        stats.lastWindowEnd = window.getEnd();

        long standardAmounts = atmTransactions.stream()
                .mapToDouble(tx -> tx.amount)
                .filter(ATM_STANDARD_AMOUNTS::contains)
                .count();

        stats.totalStandardAmounts += (int) standardAmounts;

        storeInRedis(statsKey, stats, ATM_STATS_TTL);
    }

    public static class ATMWindowStats {
        public int windowCount = 0;
        public int totalATMTransactions = 0;
        public int totalStandardAmounts = 0;
        public long lastWindowStart = 0;
        public long lastWindowEnd = 0;

        public ATMWindowStats() {}
    }
}