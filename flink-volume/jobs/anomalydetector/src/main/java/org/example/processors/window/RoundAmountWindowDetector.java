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

public class RoundAmountWindowDetector extends BaseWindowAnomalyDetector<Transaction, Transaction, String> {

    private final OutputTag<AnomalyAlert> roundAmountAnomalyTag;

    private static final int MIN_ROUND_TRANSACTIONS = 3; // Minimum for anomaly
    private static final String ROUND_STATS_PREFIX = "round_window_stats:";
    private static final int ROUND_STATS_TTL = 300; // 5 minutes TTL

    public RoundAmountWindowDetector(OutputTag<AnomalyAlert> roundAmountAnomalyTag) {
        this.roundAmountAnomalyTag = roundAmountAnomalyTag;
    }

    @Override
    public void process(String cardId, Context context, Iterable<Transaction> elements, Collector<Transaction> out) throws Exception {

        List<Transaction> roundTransactions = StreamSupport.stream(elements.spliterator(), false)
                .filter(tx -> isRoundAmount(tx.amount))
                .collect(Collectors.toList());


        elements.forEach(out::collect);

        if (roundTransactions.size() >= MIN_ROUND_TRANSACTIONS) {
            detectRoundAmountAnomaly(cardId, roundTransactions, context);
        }

        if (!roundTransactions.isEmpty()) {
            updateRoundWindowStats(cardId, roundTransactions, context.window());
        }
    }

    private boolean isRoundAmount(double amount) {
        if (amount % 1.0 != 0.0) {
            return false;
        }

        int intAmount = (int) amount;
        return intAmount > 0 && intAmount % 10 == 0;
    }

    private String getRoundType(double amount) {
        int intAmount = (int) amount;

        if (intAmount % 100 == 0) {
            return "hundreds";
        } else if (intAmount % 50 == 0) {
            return "fifties";
        } else if (intAmount % 10 == 0) {
            return "tens";
        }
        return "unknown";
    }

    private void detectRoundAmountAnomaly(String cardId, List<Transaction> roundTransactions, Context context) {
        long windowStart = context.window().getStart();
        long windowEnd = context.window().getEnd();
        long windowDurationSeconds = (windowEnd - windowStart) / 1000;

        double totalAmount = roundTransactions.stream()
                .mapToDouble(tx -> tx.amount)
                .sum();

        long tensCount = roundTransactions.stream()
                .mapToDouble(tx -> tx.amount)
                .mapToInt(amount -> (int) amount)
                .filter(amount -> amount % 10 == 0 && amount % 50 != 0 && amount % 100 != 0)
                .count();

        long fiftiesCount = roundTransactions.stream()
                .mapToDouble(tx -> tx.amount)
                .mapToInt(amount -> (int) amount)
                .filter(amount -> amount % 50 == 0 && amount % 100 != 0)
                .count();

        long hundredsCount = roundTransactions.stream()
                .mapToDouble(tx -> tx.amount)
                .mapToInt(amount -> (int) amount)
                .filter(amount -> amount % 100 == 0)
                .count();

        String roundPattern = String.format("tens: %d, fifties: %d, hundreds: %d",
                tensCount, fiftiesCount, hundredsCount);

        Transaction lastTransaction = roundTransactions.get(roundTransactions.size() - 1);

        String description = String.format(
                "ROUND AMOUNT PATTERN: %d round amount transactions in %d-second window. " +
                        "Total: %.2f PLN, Pattern: %s. Location: %s. ",
                roundTransactions.size(), windowDurationSeconds,
                totalAmount, roundPattern, lastTransaction.location.city
        );

        double severity = Math.min(0.8, 0.4 + (roundTransactions.size() * 0.08));

        AnomalyAlert alert = new AnomalyAlert(
                "round_window_" + UUID.randomUUID().toString().substring(0, 8),
                lastTransaction.transactionId,
                cardId,
                lastTransaction.userId,
                "ROUND_AMOUNTS_PATTERN",
                description,
                severity,
                windowEnd,
                lastTransaction.location
        );

        context.output(roundAmountAnomalyTag, alert);

    }

    private void updateRoundWindowStats(String cardId, List<Transaction> roundTransactions, TimeWindow window) {
        String statsKey = ROUND_STATS_PREFIX + cardId;

        RoundWindowStats stats = getFromRedis(statsKey, RoundWindowStats.class);
        if (stats == null) {
            stats = new RoundWindowStats();
        }

        stats.windowCount++;
        stats.totalRoundTransactions += roundTransactions.size();
        stats.lastWindowStart = window.getStart();
        stats.lastWindowEnd = window.getEnd();

        double totalAmount = roundTransactions.stream()
                .mapToDouble(tx -> tx.amount)
                .sum();
        stats.totalRoundAmount += totalAmount;

        for (Transaction tx : roundTransactions) {
            String roundType = getRoundType(tx.amount);
            switch (roundType) {
                case "tens": stats.tensCount++; break;
                case "fifties": stats.fiftiesCount++; break;
                case "hundreds": stats.hundredsCount++; break;
            }
        }

        storeInRedis(statsKey, stats, ROUND_STATS_TTL);
    }

    public static class RoundWindowStats {
        public int windowCount = 0;
        public int totalRoundTransactions = 0;
        public double totalRoundAmount = 0.0;
        public int tensCount = 0;
        public int fiftiesCount = 0;
        public int hundredsCount = 0;
        public long lastWindowStart = 0;
        public long lastWindowEnd = 0;

        public RoundWindowStats() {}
    }
}