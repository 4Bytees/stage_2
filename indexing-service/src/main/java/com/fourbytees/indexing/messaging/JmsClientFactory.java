package com.fourbytees.indexing.messaging;

import javax.jms.Connection;
import javax.jms.JMSException;

public class JmsClientFactory {
    public static Connection createConnection(String brokerUrl) throws JMSException {
        return com.fourbytees.common.factory.JmsClientFactory.createConnection(brokerUrl);
    }
}
