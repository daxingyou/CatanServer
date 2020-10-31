package com.jokerbee.cache;

import io.vertx.core.json.JsonObject;

public enum CacheManager {
    INSTANCE;

    private RedisClient redisClient;

    public static CacheManager getInstance() {
        return INSTANCE;
    }

    public void init(JsonObject config) throws Exception {
        redisClient = new RedisClient(config.getBoolean("cluster"), config.getString("host"),
                config.getInteger("port"), config.getString("masterName"), config.getString("password"),
                config.getInteger("maxActive"), config.getLong("maxWait"), config.getInteger("timeout"));
        redisClient.startup();
    }

    public RedisClient redis() {
        return redisClient;
    }

    public void shutdown() {
        redisClient.shutdown();
    }
}
