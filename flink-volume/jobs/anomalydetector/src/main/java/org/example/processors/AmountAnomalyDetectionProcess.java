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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Set;

public class AmountAnomalyDetectionProcess extends ProcessFunction<Transaction, Transaction> {

    private final OutputTag<AnomalyAlert> amountAnomalyTag;
    private transient JedisPool jedisPool;
    private static final double ANOMALY_THRESHOLD = 2.5; // Z-score threshold
    private static final double EXTREME_MULTIPLIER_THRESHOLD = 5.0; // Multiplier threshold (matches Python generator)

    public AmountAnomalyDetectionProcess(OutputTag<AnomalyAlert> amountAnomalyTag) {
        this.amountAnomalyTag = amountAnomalyTag;
    }

    @Override
    public void open(Configuration parameters) throws Exception {

        try {
            JedisPoolConfig config = new JedisPoolConfig();
            config.setMaxTotal(10);
            config.setMaxIdle(5);
            config.setMinIdle(1);
            config.setTestOnBorrow(true);

            jedisPool = new JedisPool(config, RedisConfig.REDIS_HOST, RedisConfig.REDIS_PORT,
                    2000, RedisConfig.REDIS_PASSWORD);

            try (Jedis jedis = jedisPool.getResource()) {
                String pong = jedis.ping();
            }
        } catch (Exception e) {
            throw e;
        }
    }

    @Override
    public void processElement(Transaction transaction, Context context, Collector<Transaction> out) throws Exception {
        try {
            CardProfile cardProfile = getCardProfile(transaction.cardId);

            if (cardProfile != null) {
                checkAmountAnomaly(transaction, cardProfile, context);
            } else {
                System.out.println("⚠️ No card profile found for card: " + transaction.cardId);
            }
        } catch (Exception e) {
        }

        out.collect(transaction);
    }

    private CardProfile getCardProfile(String cardId) {
        if (jedisPool == null) {
            return null;
        }

        try (Jedis jedis = jedisPool.getResource()) {
            String profileKey = "card_profile:" + cardId;
            String profileJson = jedis.get(profileKey);

            if (profileJson != null) {
                CardProfile profile = parseCardProfileManually(profileJson);

                if (profile != null) {

                    return profile;
                } else {

                    return null;
                }
            } else {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private CardProfile parseCardProfileManually(String json) {
        try {
            json = json.trim();
            if (json.startsWith("{")) json = json.substring(1);
            if (json.endsWith("}")) json = json.substring(0, json.length() - 1);

            CardProfile profile = new CardProfile();

            String[] pairs = json.split(",");

            for (String pair : pairs) {
                String[] keyValue = pair.split(":", 2);
                if (keyValue.length == 2) {
                    String key = keyValue[0].trim().replace("\"", "");
                    String value = keyValue[1].trim().replace("\"", "");

                    switch (key) {
                        case "card_id":
                            profile.cardId = value;
                            break;
                        case "user_id":
                            profile.userId = value;
                            break;
                        case "avg_amount":
                            profile.avgAmount = Double.parseDouble(value);
                            break;
                        case "std_amount":
                            profile.stdAmount = Double.parseDouble(value);
                            break;
                        case "daily_limit":
                            profile.dailyLimit = Double.parseDouble(value);
                            break;
                        case "monthly_limit":
                            profile.monthlyLimit = Double.parseDouble(value);
                            break;
                    }
                }
            }

            return profile;
        } catch (Exception e) {
            return parseWithObjectMapper(json);
        }
    }

    private CardProfile parseWithObjectMapper(String json) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(json, CardProfile.class);
        } catch (Exception e) {
            return null;
        }
    }



    private void checkAmountAnomaly(Transaction transaction, CardProfile cardProfile, Context context) throws Exception {
        double transactionAmount = transaction.amount;
        double avgAmount = cardProfile.avgAmount;
        double stdAmount = Math.max(cardProfile.stdAmount, 1.0);

        double zScore = Math.abs(transactionAmount - avgAmount) / stdAmount;

        double multiplier = transactionAmount / avgAmount;

        boolean isZScoreAnomaly = zScore > ANOMALY_THRESHOLD;
        boolean isExtremeAmount = multiplier >= EXTREME_MULTIPLIER_THRESHOLD;

        if (isZScoreAnomaly || isExtremeAmount) {
            String description;
            double severity;

            if (isExtremeAmount) {
                description = String.format("🚨 EXTREME AMOUNT: %.2f (%.1fx average %.2f) - matches Python generator pattern!",
                        transactionAmount, multiplier, avgAmount);
                severity = Math.min(multiplier / 20.0, 1.0);

            } else {
                description = String.format("📈 HIGH Z-SCORE: Amount %.2f deviates %.2f standard deviations from average %.2f",
                        transactionAmount, zScore, avgAmount);
                severity = Math.min(zScore / 5.0, 1.0);
            }

            // Stwórz alert
            AnomalyAlert alert = new AnomalyAlert(
                    "amount_" + transaction.transactionId,
                    transaction.transactionId,
                    transaction.cardId,
                    transaction.userId,
                    "AMOUNT_ANOMALY",
                    description,
                    severity,
                    transaction.timestamp
            );

            context.output(amountAnomalyTag, alert);

        }
    }

    @Override
    public void close() throws Exception {
        if (jedisPool != null) {
            jedisPool.close();

        }
    }
}