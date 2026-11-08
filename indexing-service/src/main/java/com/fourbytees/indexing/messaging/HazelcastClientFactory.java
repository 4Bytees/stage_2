package com.fourbytees.indexing.messaging;

import com.hazelcast.core.HazelcastInstance;

public class HazelcastClientFactory {
    public static HazelcastInstance create(String address) {
        return com.fourbytees.common.factory.HazelcastClientFactory.create(address);
    }

    public static HazelcastInstance create(String address, String clusterName) {
        return com.fourbytees.common.factory.HazelcastClientFactory.create(address, clusterName);
    }
}
