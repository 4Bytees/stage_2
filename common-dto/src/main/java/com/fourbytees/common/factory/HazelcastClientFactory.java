package com.fourbytees.common.factory;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.core.HazelcastInstance;

public class HazelcastClientFactory {

    public static HazelcastInstance create(String address, String clusterName) {
        ClientConfig config = new ClientConfig();
        if (address != null && !address.isBlank()) {
            config.getNetworkConfig().addAddress(address);
        } else {
            String envAddr = getEnvOrDefault("HZ_ADDRESS", getEnvOrDefault("HAZELCAST_ADDRESS", "localhost:5701"));
            config.getNetworkConfig().addAddress(envAddr);
        }

        if (clusterName != null && !clusterName.isBlank()) {
            config.setClusterName(clusterName);
        } else {
            String envCluster = getEnvOrDefault("HZ_CLUSTER_NAME", getEnvOrDefault("HAZELCAST_CLUSTER_NAME", "dev"));
            config.setClusterName(envCluster);
        }

        return HazelcastClient.newHazelcastClient(config);
    }

    public static HazelcastInstance create(String address) {
        return create(address, null);
    }

    public static HazelcastInstance create() {
        return create(null, null);
    }

    private static String getEnvOrDefault(String key, String defaultValue) {
        String prop = System.getProperty(key);
        if (prop != null && !prop.isBlank()) return prop;
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }
}

