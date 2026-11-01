package com.fourbytees.indexing.messaging;

import com.fourbytees.indexing.config.IndexingConfig;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.RedeliveryPolicy;

import javax.jms.Connection;
import javax.jms.JMSException;

public class JmsClientFactory {
    public static Connection createConnection(String brokerUrl) throws JMSException {
        return createConnection(brokerUrl, new IndexingConfig());
    }

    public static Connection createConnection(String brokerUrl, IndexingConfig config) throws JMSException {
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);

        RedeliveryPolicy policy = new RedeliveryPolicy();
        policy.setInitialRedeliveryDelay(config.redeliveryInitialDelayMs);
        policy.setRedeliveryDelay(config.redeliveryInitialDelayMs);
        policy.setUseExponentialBackOff(config.redeliveryBackoffMultiplier > 1.0);
        policy.setBackOffMultiplier(config.redeliveryBackoffMultiplier);
        policy.setMaximumRedeliveryDelay(config.redeliveryMaxDelayMs);
        policy.setMaximumRedeliveries(config.redeliveryMaximumRedeliveries);
        factory.setRedeliveryPolicy(policy);

        Connection conn = factory.createConnection();
        conn.start();
        return conn;
    }
}
