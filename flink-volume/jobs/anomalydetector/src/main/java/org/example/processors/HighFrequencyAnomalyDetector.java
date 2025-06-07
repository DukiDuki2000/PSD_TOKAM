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

/**
 * Detektor wysokiej częstotliwości transakcji.
 * Wykrywa sytuacje gdy karta jest używana 3+ razy w ciągu minuty.
 * Przechowuje dane w Redis zamiast w Flink State.
 */
public class HighFrequencyAnomalyDetector extends KeyedProcessFunction<String, Transaction, Transaction> {

    private final OutputTag<AnomalyAlert> highFrequencyAnomalyTag;
    private transient JedisPool jedisPool;
    private transient ObjectMapper objectMapper;

    // Konfiguracja detektora
    private static final int MIN_TRANSACTIONS_FOR_ALERT = 3; // Alert już przy 3 transakcjach
    private static final long TIME_WINDOW_MS = 60_000; // 1 minuta w milisekundach
    private static final int CRITICAL_THRESHOLD = 5; // Jeśli więcej niż 5 w minutę - krytyczne

    // Redis konfiguracja
    private static final String HIGH_FREQ_PREFIX = "high_freq:";
    private static final int HIGH_FREQ_TTL = 120; // 2 minuty TTL (dłużej niż okno)

    public HighFrequencyAnomalyDetector(OutputTag<AnomalyAlert> highFrequencyAnomalyTag) {
        this.highFrequencyAnomalyTag = highFrequencyAnomalyTag;
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);

        // Inicjalizacja Redis pool
        try {
            JedisPoolConfig config = new JedisPoolConfig();
            config.setMaxTotal(10);
            config.setMaxIdle(5);
            config.setMinIdle(1);
            config.setTestOnBorrow(true);

            jedisPool = new JedisPool(config, RedisConfig.REDIS_HOST, RedisConfig.REDIS_PORT,
                    2000, RedisConfig.REDIS_PASSWORD);

            // Test połączenia
            try (Jedis jedis = jedisPool.getResource()) {
                String pong = jedis.ping();
                System.out.println("✅ Redis connection successful for HighFrequencyDetector: " + pong);
            }

            objectMapper = new ObjectMapper();

        } catch (Exception e) {
            System.err.println("❌ Failed to initialize Redis for HighFrequencyDetector: " + e.getMessage());
            throw e;
        }
    }

    @Override
    public void processElement(Transaction transaction, Context context, Collector<Transaction> out) throws Exception {
        long currentTime = transaction.timestamp;
        String cardId = transaction.cardId;

        try {
            // Pobierz ostatnie transakcje z Redis
            List<TransactionTimestamp> recentTransactions = getRecentTransactions(cardId);

            // Dodaj bieżącą transakcję
            recentTransactions.add(new TransactionTimestamp(transaction.transactionId, currentTime));

            // Usuń transakcje starsze niż okno czasowe (1 minuta)
            long windowStart = currentTime - TIME_WINDOW_MS;
            Iterator<TransactionTimestamp> iterator = recentTransactions.iterator();
            while (iterator.hasNext()) {
                TransactionTimestamp txTime = iterator.next();
                if (txTime.timestamp < windowStart) {
                    iterator.remove();
                }
            }

            // Sprawdź czy liczba transakcji osiągnęła próg (3 lub więcej)
            int transactionCount = recentTransactions.size();

            if (transactionCount >= MIN_TRANSACTIONS_FOR_ALERT) {
                // Wykryto wysoką częstotliwość transakcji dla tej karty
                String description = createDescription(transactionCount, cardId);

                AnomalyAlert alert = new AnomalyAlert(
                        "high_freq_" + cardId + "_" + currentTime,
                        transaction.transactionId,
                        cardId,
                        transaction.userId,
                        "HIGH_FREQUENCY_ANOMALY",
                        description,
                        calculateSeverityScore(transactionCount),
                        currentTime
                );

                // Wyślij alert przez side output
                context.output(highFrequencyAnomalyTag, alert);

                System.out.println("🚨 HIGH FREQUENCY DETECTED: " + description);
            }

            // Zapisz zaktualizowaną listę do Redis
            saveRecentTransactions(cardId, recentTransactions);

        } catch (Exception e) {
            System.err.println("❌ Error processing high frequency detection for card " + cardId + ": " + e.getMessage());
            // Kontynuuj przetwarzanie nawet przy błędzie Redis
        }

        // Przekaż transakcję dalej
        out.collect(transaction);
    }

    /**
     * Pobiera ostatnie transakcje dla karty z Redis
     */
    private List<TransactionTimestamp> getRecentTransactions(String cardId) {
        if (jedisPool == null) {
            return new ArrayList<>();
        }

        try (Jedis jedis = jedisPool.getResource()) {
            String key = HIGH_FREQ_PREFIX + cardId;
            String json = jedis.get(key);

            if (json != null && !json.isEmpty()) {
                TypeReference<List<TransactionTimestamp>> typeRef = new TypeReference<List<TransactionTimestamp>>() {};
                return objectMapper.readValue(json, typeRef);
            }
        } catch (Exception e) {
            System.err.println("❌ Error reading recent transactions from Redis: " + e.getMessage());
        }

        return new ArrayList<>();
    }

    /**
     * Zapisuje ostatnie transakcje dla karty do Redis
     */
    private void saveRecentTransactions(String cardId, List<TransactionTimestamp> recentTransactions) {
        if (jedisPool == null) return;

        try (Jedis jedis = jedisPool.getResource()) {
            String key = HIGH_FREQ_PREFIX + cardId;

            if (recentTransactions.isEmpty()) {
                // Usuń klucz jeśli brak transakcji
                jedis.del(key);
            } else {
                // Zapisz listę transakcji z TTL
                String json = objectMapper.writeValueAsString(recentTransactions);
                jedis.setex(key, HIGH_FREQ_TTL, json);
            }
        } catch (Exception e) {
            System.err.println("❌ Error saving recent transactions to Redis: " + e.getMessage());
        }
    }

    private double calculateSeverityScore(int transactionCount) {
        // Normalizuj wynik do przedziału 0.0 - 1.0
        // 3 transakcje = 0.3, 4 = 0.5, 5+ = 0.8+
        if (transactionCount == 3) return 0.3;
        if (transactionCount == 4) return 0.5;
        if (transactionCount >= 5) return Math.min(0.2 * transactionCount, 1.0);
        return 0.1;
    }

    private String createDescription(int transactionCount, String cardId) {
        String severity;
        if (transactionCount >= CRITICAL_THRESHOLD) {
            severity = "KRYTYCZNA";
        } else if (transactionCount == 4) {
            severity = "WYSOKA";
        } else {
            severity = "ŚREDNIA";
        }

        return String.format(
                "⚠️ CZĘSTA AKTYWNOŚĆ [%s]: Karta %s została użyta %d razy w ciągu ostatniej minuty. " +
                        "Monitorowanie podejrzanej aktywności.",
                severity, cardId, transactionCount
        );
    }

    @Override
    public void close() throws Exception {
        if (jedisPool != null) {
            jedisPool.close();
            System.out.println("🔒 HighFrequencyDetector Redis connection closed");
        }
    }

    /**
     * Klasa pomocnicza do przechowywania ID transakcji i czasu w Redis.
     */
    public static class TransactionTimestamp {
        public String transactionId;
        public long timestamp;

        public TransactionTimestamp() {}

        public TransactionTimestamp(String transactionId, long timestamp) {
            this.transactionId = transactionId;
            this.timestamp = timestamp;
        }

        // Gettery i settery dla Jackson JSON
        public String getTransactionId() { return transactionId; }
        public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    }
}