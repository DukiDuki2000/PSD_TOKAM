package org.example.processors;

import org.apache.flink.configuration.Configuration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;
import org.example.models.AnomalyAlert;
import org.example.models.CardProfile;
import org.example.models.Transaction;
import org.example.config.RedisConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import java.util.UUID;


public class AmountAnomalyDetectionProcess extends ProcessFunction<Transaction, Transaction> {

    private final OutputTag<AnomalyAlert> amountAnomalyTag;
    private transient JedisPool jedisPool;
    private transient ObjectMapper objectMapper;


    private static final double SUSPICIOUS_MULTIPLIER_THRESHOLD = 4.0; // Alert when 4x+ average
    private static final double EXTREME_MULTIPLIER_THRESHOLD = 8.0;    // Higher severity when 8x+ average
    private static final double CRITICAL_MULTIPLIER_THRESHOLD = 15.0;  // Critical when 15x+ average

    private static final String AMOUNT_STATS_PREFIX = "amount_stats:";
    private static final int AMOUNT_STATS_TTL = 30; // 30 s  TTL
    private static final int MAX_RECENT_AMOUNTS = 10; // Keep only 10 recent amounts for performance

    public AmountAnomalyDetectionProcess(OutputTag<AnomalyAlert> amountAnomalyTag) {
        this.amountAnomalyTag = amountAnomalyTag;
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(50);
        config.setMaxIdle(20);
        config.setMinIdle(5);
        config.setTestOnBorrow(true);
        config.setMaxWaitMillis(500);

        jedisPool = new JedisPool(config, RedisConfig.REDIS_HOST, RedisConfig.REDIS_PORT,
                500, RedisConfig.REDIS_PASSWORD);
        objectMapper = new ObjectMapper();
    }

    @Override
    public void processElement(Transaction transaction, Context context, Collector<Transaction> out)
            throws Exception {

        try {
            CardProfile cardProfile = getCardProfile(transaction.cardId);

            if (cardProfile != null) {
                detectAmountAnomaly(transaction, cardProfile, context);
                updateAmountStatistics(transaction, cardProfile);
            }

        } catch (Exception e) {
            System.err.println("Amount anomaly detection error for card " + transaction.cardId + ": " + e.getMessage());
        }

        out.collect(transaction);
    }


    private void detectAmountAnomaly(Transaction transaction, CardProfile cardProfile, Context context) {
        double transactionAmount = transaction.amount;
        double userAvgAmount = cardProfile.avgAmount;

        if (userAvgAmount < 1.0) {
            return;
        }

        double multiplier = transactionAmount / userAvgAmount;


        if (multiplier >= SUSPICIOUS_MULTIPLIER_THRESHOLD) {

            String severityLevel;
            double severity;

            if (multiplier >= CRITICAL_MULTIPLIER_THRESHOLD) {
                severityLevel = "CRITICAL";
                severity = 1.0; // Maximum severity
            } else if (multiplier >= EXTREME_MULTIPLIER_THRESHOLD) {
                severityLevel = "EXTREME";
                severity = 0.8;
            } else {
                severityLevel = "HIGH";
                severity = 0.6;
            }

            String description = String.format(
                    "[%s] Large amount detected: %.2f PLN (%.1fx user's average %.2f PLN). " +
                            "Pattern matches Python amount_anomaly generator (5-20x multiplier).",
                    severityLevel, transactionAmount, multiplier, userAvgAmount
            );

            AnomalyAlert alert = new AnomalyAlert(
                    "amount_" + UUID.randomUUID().toString().substring(0, 8),
                    transaction.transactionId,
                    transaction.cardId,
                    transaction.userId,
                    "AMOUNT_ANOMALY",
                    description,
                    severity,
                    System.currentTimeMillis(),
                    transaction.location
            );

            context.output(amountAnomalyTag, alert);

            System.out.println(String.format(
                    "🚨 AMOUNT ANOMALY: Card %s, Amount %.2f PLN (%.1fx avg), Severity: %s",
                    transaction.cardId, transactionAmount, multiplier, severityLevel
            ));
        }
    }


    private CardProfile getCardProfile(String cardId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String profileKey = "card_profile:" + cardId;
            String profileJson = jedis.get(profileKey);

            if (profileJson != null) {
                return objectMapper.readValue(profileJson, CardProfile.class);
            } else {
                System.err.println("⚠️  No card profile found for: " + cardId);
                return null;
            }

        } catch (Exception e) {
            System.err.println("❌ Failed to get card profile for " + cardId + ": " + e.getMessage());
            return null;
        }
    }


    private void updateAmountStatistics(Transaction transaction, CardProfile cardProfile) {
        try (Jedis jedis = jedisPool.getResource()) {
            String statsKey = AMOUNT_STATS_PREFIX + transaction.cardId;

            AmountStatistics stats = getAmountStatistics(statsKey, jedis);

            stats.recentAmounts.add(transaction.amount);
            stats.totalTransactions++;

            if (stats.recentAmounts.size() > MAX_RECENT_AMOUNTS) {
                stats.recentAmounts.remove(0);
            }

            double currentAverage = stats.recentAmounts.stream()
                    .mapToDouble(Double::doubleValue)
                    .average()
                    .orElse(cardProfile.avgAmount);

            stats.currentAverage = currentAverage;
            stats.lastUpdateTime = System.currentTimeMillis();

            String statsJson = objectMapper.writeValueAsString(stats);
            jedis.setex(statsKey, AMOUNT_STATS_TTL, statsJson);

        } catch (Exception e) {
            System.err.println("Failed to update amount statistics: " + e.getMessage());
        }
    }


    private AmountStatistics getAmountStatistics(String statsKey, Jedis jedis) {
        try {
            String statsJson = jedis.get(statsKey);
            if (statsJson != null) {
                return objectMapper.readValue(statsJson, AmountStatistics.class);
            }
        } catch (Exception e) {
        }

        return new AmountStatistics();
    }

    @Override
    public void close() throws Exception {
        if (jedisPool != null) {
            jedisPool.close();
        }
        super.close();
    }

    public static class AmountStatistics {
        public java.util.List<Double> recentAmounts = new java.util.ArrayList<>();
        public double currentAverage = 0.0;
        public int totalTransactions = 0;
        public long lastUpdateTime = 0;

        public AmountStatistics() {}
    }
}