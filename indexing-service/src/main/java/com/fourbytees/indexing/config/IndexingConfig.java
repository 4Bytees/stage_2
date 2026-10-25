package com.fourbytees.indexing.config;

import java.nio.file.Path;
import java.nio.file.Paths;

public class IndexingConfig {
    public final int port;
    public final Path datalakePath;
    public final String sqliteDbPath;
    public final String activeMqUrl;
    public final String hazelcastAddress;
    public final int workerThreads;
    public final int jmsConsumers;
    public final int maxRetries;

    public IndexingConfig() {
        this.port = Integer.parseInt(getPropOrEnv("PORT", "7002"));
        this.datalakePath = Paths.get(getPropOrEnv("DATALAKE_PATH", "./datalake"));
        this.sqliteDbPath = getPropOrEnv("SQLITE_DB_PATH", "./datamarts/metadata.db");
        this.activeMqUrl = getPropOrEnv("ACTIVEMQ_URL", "tcp://localhost:61616");
        this.hazelcastAddress = getPropOrEnv("HAZELCAST_ADDRESS", "localhost:5701");
        this.workerThreads = Integer.parseInt(getPropOrEnv("INDEX_WORKER_THREADS", "4"));
        this.jmsConsumers = Integer.parseInt(getPropOrEnv("JMS_CONSUMERS", "2"));
        this.maxRetries = Integer.parseInt(getPropOrEnv("MAX_RETRIES", "3"));
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