package com.fourbytees.indexing.messaging;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.core.HazelcastInstance;

public class HazelcastClientFactory {
    public static HazelcastInstance create(String address) {
        ClientConfig config = new ClientConfig();
        config.getNetworkConfig().addAddress(address);
        config.setClusterName("dev");
        return HazelcastClient.newHazelcastClient(config);
    }
}