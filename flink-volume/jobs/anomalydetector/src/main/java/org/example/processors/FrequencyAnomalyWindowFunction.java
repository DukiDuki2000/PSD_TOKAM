package org.example.processors;

import org.apache.flink.configuration.Configuration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;
import org.example.models.AnomalyAlert;
import org.example.models.Transaction;
import org.example.config.RedisConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.ArrayList;
import java.util.List;

public class FrequencyAnomalyWindowFunction extends ProcessWindowFunction<Transaction, AnomalyAlert, String, TimeWindow> {

    private final OutputTag<AnomalyAlert> frequencyAnomalyTag;
    private transient JedisPool jedisPool;
    private static final double FREQUENCY_THRESHOLD = 2.0;
    private static final int MIN_TRANSACTIONS_FOR_ANALYSIS = 5;

    public FrequencyAnomalyWindowFunction(OutputTag<AnomalyAlert> frequencyAnomalyTag) {
        this.frequencyAnomalyTag = frequencyAnomalyTag;
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(10);
        config.setMaxIdle(5);
        config.setMinIdle(1);
        jedisPool = new JedisPool(config, RedisConfig.REDIS_HOST, RedisConfig.REDIS_PORT,
                2000, RedisConfig.REDIS_PASSWORD);
    }

    @Override
    public void process(String cardId, Context context, Iterable<Transaction> elements, Collector<AnomalyAlert> out) throws Exception {
        List<Transaction> transactions = new ArrayList<>();
        elements.forEach(transactions::add);

        if (transactions.size() < MIN_TRANSACTIONS_FOR_ANALYSIS) {
            return;
        }

        int currentCount = transactions.size();
        FrequencyProfile profile = getFrequencyProfile(cardId);

        if (profile != null && profile.windowCount > 2) {
            double zScore = Math.abs(currentCount - profile.avgCount) / Math.max(profile.stdCount, 0.5);

            if (zScore > FREQUENCY_THRESHOLD) {
                Transaction firstTx = transactions.get(0);
                AnomalyAlert alert = new AnomalyAlert(
                        "frequency_" + cardId + "_" + context.window().getStart(),
                        firstTx.transactionId,
                        cardId,
                        firstTx.userId,
                        "FREQUENCY_ANOMALY",
                        String.format("High frequency: %d transactions (avg: %.1f, z-score: %.2f)",
                                currentCount, profile.avgCount, zScore),
                        Math.min(zScore / 3.0, 1.0),
                        firstTx.timestamp
                );
                out.collect(alert);
            }
        }

        updateFrequencyProfile(cardId, currentCount);
    }

    private FrequencyProfile getFrequencyProfile(String cardId) {
        if (jedisPool == null) return null;

        try (Jedis jedis = jedisPool.getResource()) {
            String json = jedis.get(RedisConfig.FREQUENCY_PROFILE_PREFIX + cardId);
            if (json != null) {
                ObjectMapper mapper = new ObjectMapper();
                return mapper.readValue(json, FrequencyProfile.class);
            }
        } catch (Exception e) {
            // Continue processing
        }
        return null;
    }

    private void updateFrequencyProfile(String cardId, int currentCount) {
        if (jedisPool == null) return;

        try (Jedis jedis = jedisPool.getResource()) {
            FrequencyProfile profile = getFrequencyProfile(cardId);

            if (profile == null) {
                profile = new FrequencyProfile();
                profile.avgCount = currentCount;
                profile.stdCount = 1.0;
                profile.windowCount = 1;
            } else {
                profile.windowCount++;
                double alpha = 0.1;
                double oldAvg = profile.avgCount;
                profile.avgCount = alpha * currentCount + (1 - alpha) * oldAvg;

                double variance = alpha * Math.pow(currentCount - profile.avgCount, 2) +
                        (1 - alpha) * Math.pow(profile.stdCount, 2);
                profile.stdCount = Math.sqrt(variance);
            }

            ObjectMapper mapper = new ObjectMapper();
            String json = mapper.writeValueAsString(profile);
            jedis.setex(RedisConfig.FREQUENCY_PROFILE_PREFIX + cardId, RedisConfig.FREQUENCY_PROFILE_TTL, json);
        } catch (Exception e) {
            // Continue processing
        }
    }

    @Override
    public void close() throws Exception {
        if (jedisPool != null) {
            jedisPool.close();
        }
    }

    public static class FrequencyProfile {
        public double avgCount;
        public double stdCount;
        public int windowCount;

        public FrequencyProfile() {}
    }
}