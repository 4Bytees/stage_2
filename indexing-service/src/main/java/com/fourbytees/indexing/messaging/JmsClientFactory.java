package com.fourbytees.indexing.messaging;

import org.apache.activemq.ActiveMQConnectionFactory;
import javax.jms.Connection;
import javax.jms.JMSException;

public class JmsClientFactory {
    public static Connection createConnection(String brokerUrl) throws JMSException {
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);
        Connection conn = factory.createConnection();
        conn.start();
        return conn;
    }
}