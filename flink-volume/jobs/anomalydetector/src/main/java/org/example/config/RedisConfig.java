package org.example.config;

public class RedisConfig {
    public static final String REDIS_HOST = "redis";
    public static final int REDIS_PORT = 6379;
    public static final String REDIS_PASSWORD = "admin";

    public static final String CARD_PROFILE_PREFIX = "card_profile:";
    public static final String FREQUENCY_PROFILE_PREFIX = "frequency_profile:";

    public static final int FREQUENCY_PROFILE_TTL = 60; // 1 min
    public static final int CARD_PROFILE_TTL = 3600;  // 1 hour
}