package com.fourbytees.search.config;

import com.fourbytees.common.constants.SystemConstants;
import com.fourbytees.common.factory.HazelcastClientFactory;
import com.hazelcast.core.HazelcastInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HazelcastConfig {
    private static final Logger log = LoggerFactory.getLogger(HazelcastConfig.class);

    private HazelcastInstance hazelcastClient;

    public HazelcastConfig() {
        String hzAddress = getEnvOrDefault("HZ_ADDRESS", getEnvOrDefault("HAZELCAST_ADDRESS", "hazelcast:5701"));
        String hzCluster = getEnvOrDefault("HZ_CLUSTER_NAME", getEnvOrDefault("HAZELCAST_CLUSTER_NAME", "dev"));

        try {
            log.info("Connecting Search Service to Hazelcast at {} (cluster: {})...", hzAddress, hzCluster);
            this.hazelcastClient = HazelcastClientFactory.create(hzAddress, hzCluster);
            log.info("Search Service connected to Hazelcast.");
        } catch (Exception e) {
            log.warn("[HazelcastConfig] WARNING: Could not connect to Hazelcast at {}. Service will operate in degraded mode (direct SQLite queries): {}",
                    hzAddress, e.getMessage());
            this.hazelcastClient = null;
        }
    }

    public HazelcastConfig(HazelcastInstance client) {
        this.hazelcastClient = client;
    }

    public String getPublishedIndexVersion() {
        if (hazelcastClient == null) {
            return "unknown";
        }
        try {
            if (!hazelcastClient.getLifecycleService().isRunning()) {
                return "unknown";
            }
            long version = hazelcastClient.getCPSubsystem()
                    .getAtomicLong(SystemConstants.ATOMIC_INDEX_VERSION)
                    .get();
            return String.valueOf(version);
        } catch (Exception e) {
            log.warn("Failed to read published index version from Hazelcast: {}", e.getMessage());
            return "unknown";
        }
    }

    public boolean isConnected() {
        return hazelcastClient != null && hazelcastClient.getLifecycleService().isRunning();
    }

    private static String getEnvOrDefault(String key, String defaultValue) {
        String prop = System.getProperty(key);
        if (prop != null && !prop.isBlank()) return prop;
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }
}
