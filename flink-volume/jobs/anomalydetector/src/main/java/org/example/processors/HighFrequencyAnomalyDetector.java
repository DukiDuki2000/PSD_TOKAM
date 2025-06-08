package org.example.processors;

import org.apache.flink.configuration.Configuration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;
import org.example.models.AnomalyAlert;
import org.example.models.Transaction;
import org.example.config.RedisConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;


//public class HighFrequencyAnomalyDetector extends KeyedProcessFunction<String, Transaction, Transaction> {
//
//    private final OutputTag<AnomalyAlert> highFrequencyAnomalyTag;
//    private transient JedisPool jedisPool;
//    private transient ObjectMapper objectMapper;
//
//    // Konfiguracja detektora
//    private static final int MIN_TRANSACTIONS_FOR_ALERT = 3; // Alert już przy 3 transakcjach
//    private static final long TIME_WINDOW_MS = 60_000; // 1 minuta w milisekundach
//    private static final int CRITICAL_THRESHOLD = 5; // Jeśli więcej niż 5 w minutę - krytyczne
//
//    // Redis konfiguracja
//    private static final String HIGH_FREQ_PREFIX = "high_freq:";
//    private static final int HIGH_FREQ_TTL = 120; // 2 minuty TTL (dłużej niż okno)
//
//    public HighFrequencyAnomalyDetector(OutputTag<AnomalyAlert> highFrequencyAnomalyTag) {
//        this.highFrequencyAnomalyTag = highFrequencyAnomalyTag;
//    }
//
//    @Override
//    public void open(Configuration parameters) throws Exception {
//        super.open(parameters);
//
//        try {
//            JedisPoolConfig config = new JedisPoolConfig();
//            config.setMaxTotal(10);
//            config.setMaxIdle(5);
//            config.setMinIdle(1);
//            config.setTestOnBorrow(true);
//
//            jedisPool = new JedisPool(config, RedisConfig.REDIS_HOST, RedisConfig.REDIS_PORT,
//                    2000, RedisConfig.REDIS_PASSWORD);
//
//            objectMapper = new ObjectMapper();
//
//        } catch (Exception e) {
//            System.err.println("❌ Failed to initialize Redis for HighFrequencyDetector: " + e.getMessage());
//            throw e;
//        }
//    }
//
//    @Override
//    public void processElement(Transaction transaction, Context context, Collector<Transaction> out) throws Exception {
//        long currentTime = transaction.timestamp;
//        String cardId = transaction.cardId;
//
//        try {
//            List<TransactionTimestamp> recentTransactions = getRecentTransactions(cardId);
//
//            recentTransactions.add(new TransactionTimestamp(transaction.transactionId, currentTime));
//
//            long windowStart = currentTime - TIME_WINDOW_MS;
//            Iterator<TransactionTimestamp> iterator = recentTransactions.iterator();
//            while (iterator.hasNext()) {
//                TransactionTimestamp txTime = iterator.next();
//                if (txTime.timestamp < windowStart) {
//                    iterator.remove();
//                }
//            }
//
//            int transactionCount = recentTransactions.size();
//
//            if (transactionCount >= MIN_TRANSACTIONS_FOR_ALERT) {
//                String description = createDescription(transactionCount, cardId);
//
//                AnomalyAlert alert = new AnomalyAlert(
//                        "high_freq_" + cardId + "_" + currentTime,
//                        transaction.transactionId,
//                        cardId,
//                        transaction.userId,
//                        "HIGH_FREQUENCY_ANOMALY",
//                        description,
//                        calculateSeverityScore(transactionCount),
//                        currentTime
//                );
//                context.output(highFrequencyAnomalyTag, alert);
//            }
//            saveRecentTransactions(cardId, recentTransactions);
//
//        } catch (Exception e) {
//            System.err.println("❌ Error processing high frequency detection for card " + cardId + ": " + e.getMessage());
//        }
//
//        out.collect(transaction);
//    }
//
//
//    private List<TransactionTimestamp> getRecentTransactions(String cardId) {
//        if (jedisPool == null) {
//            return new ArrayList<>();
//        }
//
//        try (Jedis jedis = jedisPool.getResource()) {
//            String key = HIGH_FREQ_PREFIX + cardId;
//            String json = jedis.get(key);
//
//            if (json != null && !json.isEmpty()) {
//                TypeReference<List<TransactionTimestamp>> typeRef = new TypeReference<List<TransactionTimestamp>>() {};
//                return objectMapper.readValue(json, typeRef);
//            }
//        } catch (Exception e) {
//            System.err.println("❌ Error reading recent transactions from Redis: " + e.getMessage());
//        }
//
//        return new ArrayList<>();
//    }
//
//
//
//
//
//
//
//
//
//    private void saveRecentTransactions(String cardId, List<TransactionTimestamp> recentTransactions) {
//        if (jedisPool == null) return;
//
//        try (Jedis jedis = jedisPool.getResource()) {
//            String key = HIGH_FREQ_PREFIX + cardId;
//
//            if (recentTransactions.isEmpty()) {
//                jedis.del(key);
//            } else {
//                String json = objectMapper.writeValueAsString(recentTransactions);
//                jedis.setex(key, HIGH_FREQ_TTL, json);
//            }
//        } catch (Exception e) {
//            System.err.println("❌ Error saving recent transactions to Redis: " + e.getMessage());
//        }
//    }
//
//    private double calculateSeverityScore(int transactionCount) {
//        if (transactionCount == 3) return 0.3;
//        if (transactionCount == 4) return 0.5;
//        if (transactionCount >= 5) return Math.min(0.2 * transactionCount, 1.0);
//        return 0.1;
//    }
//
//    private String createDescription(int transactionCount, String cardId) {
//        String severity;
//        if (transactionCount >= CRITICAL_THRESHOLD) {
//            severity = "CRITICAL";
//        } else if (transactionCount == 4) {
//            severity = "HIGH";
//        } else {
//            severity = "MEDIUM";
//        }
//
//        return String.format(
//                "FREQUENT ACTIVITY [%s]: Card %s has been used %d times in the last minute. ",
//                severity, cardId, transactionCount
//        );
//    }
//
//
//
//
//    /**
//     * Klasa pomocnicza do przechowywania ID transakcji i czasu w Redis.
//     */
//    public static class TransactionTimestamp {
//        public String transactionId;
//        public long timestamp;
//
//        public TransactionTimestamp() {}
//
//        public TransactionTimestamp(String transactionId, long timestamp) {
//            this.transactionId = transactionId;
//            this.timestamp = timestamp;
//        }
//
//        // Gettery i settery dla Jackson JSON
//        public String getTransactionId() { return transactionId; }
//        public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
//        public long getTimestamp() { return timestamp; }
//        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
//    }
//}