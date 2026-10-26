package com.fourbytes.search.config;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;

import java.util.HashMap;
import java.util.Map;

public class HazelcastConfig {
    private HazelcastInstance hazelcastClient;

    public HazelcastConfig() {
        try {
            ClientConfig clientConfig = new ClientConfig();
            clientConfig.setClusterName("dev");
            this.hazelcastClient = HazelcastClient.newHazelcastClient(clientConfig);
        } catch (Exception e) {
            System.err.println("[HazelcastConfig] Warning: Standalone mode or Hazelcast server not found. " + e.getMessage());
            this.hazelcastClient = null;
        }
    }

    public String getPublishedIndexVersion() {
        if (hazelcastClient == null) return "v1";
        IMap<String, String> metaMap = hazelcastClient.getMap("index_metadata");
        return metaMap.getOrDefault("published_version", "v1");
    }

    public Map<Integer, Double> getPostingsForTerm(String term) {
        Map<Integer, Double> postings = new HashMap<>();
        if (hazelcastClient == null) return postings;

        String currentVersion = getPublishedIndexVersion();
        IMap<String, Map<Integer, Double>> indexMap = hazelcastClient.getMap("inverted_index_" + currentVersion);
        
        Map<Integer, Double> result = indexMap.get(term.toLowerCase());
        if (result != null) {
            postings.putAll(result);
        }
        return postings;
    }

    public boolean isConnected() {
        return hazelcastClient != null && hazelcastClient.getLifecycleService().isRunning();
    }
}