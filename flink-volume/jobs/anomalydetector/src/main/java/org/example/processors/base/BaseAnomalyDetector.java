package org.example.processors.base;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.example.config.RedisConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.time.Duration;

public abstract class BaseAnomalyDetector<I, O> extends ProcessFunction<I, O> {

    protected transient JedisPool jedisPool;
    protected transient ObjectMapper objectMapper;

    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
        initializeRedisAndMapper();
        onOpen(parameters);
    }

    private void initializeRedisAndMapper() {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(50);
        config.setMaxIdle(20);
        config.setMinIdle(5);
        config.setTestOnBorrow(true);
        config.setMaxWait(Duration.ofMillis(500));

        jedisPool = new JedisPool(config, RedisConfig.REDIS_HOST, RedisConfig.REDIS_PORT,
                500, RedisConfig.REDIS_PASSWORD);
        objectMapper = new ObjectMapper();
    }


    protected void onOpen(Configuration parameters) throws Exception {

    }


    protected <T> T getFromRedis(String key, Class<T> clazz) {
        try (Jedis jedis = jedisPool.getResource()) {
            String json = jedis.get(key);
            if (json != null) {
                return objectMapper.readValue(json, clazz);
            }
            return null;
        } catch (Exception e) {
            System.err.println("Failed to get data from Redis for key " + key + ": " + e.getMessage());
            return null;
        }
    }


    protected void storeInRedis(String key, Object data, int ttlSeconds) {
        try (Jedis jedis = jedisPool.getResource()) {
            String json = objectMapper.writeValueAsString(data);
            if (ttlSeconds > 0) {
                jedis.setex(key, ttlSeconds, json);
            } else {
                jedis.set(key, json);
            }
        } catch (Exception e) {
            System.err.println("Failed to store data in Redis for key " + key + ": " + e.getMessage());
        }
    }

    @Override
    public void close() throws Exception {
        if (jedisPool != null) {
            jedisPool.close();
        }
        super.close();
    }
}