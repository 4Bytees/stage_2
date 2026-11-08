package com.fourbytees.common.factory;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.RedeliveryPolicy;
import javax.jms.Connection;
import javax.jms.JMSException;

public class JmsClientFactory {
    public static Connection createConnection(String brokerUrl) throws JMSException {
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);
        Connection conn = factory.createConnection();
        conn.start();
        return conn;
    }

    public static Connection createConnection(
            String brokerUrl,
            long initialDelayMs,
            double backoffMultiplier,
            long maxDelayMs,
            int maximumRedeliveries) throws JMSException {
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);

        RedeliveryPolicy policy = new RedeliveryPolicy();
        policy.setInitialRedeliveryDelay(initialDelayMs);
        policy.setRedeliveryDelay(initialDelayMs);
        policy.setUseExponentialBackOff(backoffMultiplier > 1.0);
        policy.setBackOffMultiplier(backoffMultiplier);
        policy.setMaximumRedeliveryDelay(maxDelayMs);
        policy.setMaximumRedeliveries(maximumRedeliveries);
        factory.setRedeliveryPolicy(policy);

        Connection conn = factory.createConnection();
        conn.start();
        return conn;
    }
}

