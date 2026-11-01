package com.fourbytees.indexing.config;

import java.nio.file.Path;
import java.nio.file.Paths;

public class IndexingConfig {
    public final int port;
    public final Path datalakePath;
    public final String sqliteDbPath;
    public final String activeMqUrl;
    public final String hazelcastAddress;
    public final String hazelcastClusterName;
    public final int workerThreads;
    public final int jmsConsumers;
    public final int maxRetries;
    public final long indexingTimeoutMs;
    public final int bookIndexedPublishAttempts;
    public final long bookIndexedRetryDelayMs;
    public final long outboxFlushIntervalMs;
    public final long outboxMinAgeMs;

    public IndexingConfig() {
        this.port = Integer.parseInt(getPropOrEnv("PORT", "7002"));
        this.datalakePath = Paths.get(getPropOrEnv("DATALAKE_PATH", "/data/datalake"));
        this.sqliteDbPath = getPropOrEnv("SQLITE_DB_PATH", getPropOrEnv("METADATA_DB_PATH", "/data/datamarts/metadata.db"));
        this.activeMqUrl = getPropOrEnv("ACTIVEMQ_URL", "tcp://activemq:61616");
        this.hazelcastAddress = getPropOrEnv("HZ_ADDRESS", getPropOrEnv("HAZELCAST_ADDRESS", "hazelcast:5701"));
        this.hazelcastClusterName = getPropOrEnv("HZ_CLUSTER_NAME", getPropOrEnv("HAZELCAST_CLUSTER_NAME", "dev"));
        this.workerThreads = Integer.parseInt(getPropOrEnv("INDEX_WORKER_THREADS", "4"));
        this.jmsConsumers = Integer.parseInt(getPropOrEnv("JMS_CONSUMERS", "2"));
        this.maxRetries = Integer.parseInt(getPropOrEnv("MAX_RETRIES", "3"));
        this.indexingTimeoutMs = Long.parseLong(getPropOrEnv("INDEXING_TIMEOUT_MS", "300000"));
        this.bookIndexedPublishAttempts = Integer.parseInt(getPropOrEnv("BOOK_INDEXED_PUBLISH_ATTEMPTS", "3"));
        this.bookIndexedRetryDelayMs = Long.parseLong(getPropOrEnv("BOOK_INDEXED_RETRY_DELAY_MS", "200"));
        this.outboxFlushIntervalMs = Long.parseLong(getPropOrEnv("OUTBOX_FLUSH_INTERVAL_MS", "10000"));
        this.outboxMinAgeMs = Long.parseLong(getPropOrEnv("OUTBOX_MIN_AGE_MS", "5000"));
    }

    private static String getPropOrEnv(String key, String defaultValue) {
        String prop = System.getProperty(key);
        if (prop != null && !prop.isBlank()) {
            return prop;
        }
        String env = System.getenv(key);
        return (env != null && !env.isBlank()) ? env : defaultValue;
    }
}
