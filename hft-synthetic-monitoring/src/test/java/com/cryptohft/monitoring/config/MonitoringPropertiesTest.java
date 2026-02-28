package com.cryptohft.monitoring.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MonitoringPropertiesTest {

    @Autowired
    private MonitoringProperties properties;

    @Test
    void topLevelPropertiesBound() {
        assertThat(properties.getIntervalMs()).isEqualTo(30000);
        assertThat(properties.getTimeoutMs()).isEqualTo(3000);
    }

    @Test
    void targetPropertiesBound() {
        assertThat(properties.getTarget().getBaseUrl()).isEqualTo("http://localhost:8080");
        assertThat(properties.getTarget().getWsUrl()).isEqualTo("ws://localhost:8080/ws/trading");
    }

    @Test
    void orderPropertiesBound() {
        assertThat(properties.getOrder().getSymbol()).isEqualTo("BTCUSD");
        assertThat(properties.getOrder().getPrice()).isEqualTo(100.00);
        assertThat(properties.getOrder().getQuantity()).isEqualTo(0.001);
        assertThat(properties.getOrder().getAccount()).isEqualTo("SM_CHECK");
        assertThat(properties.getOrder().getClientIdPrefix()).isEqualTo("sm-");
    }

    @Test
    void alertPropertiesBound() {
        assertThat(properties.getAlert().getConsecutiveFailuresThreshold()).isEqualTo(3);
        assertThat(properties.getAlert().getWarnThreshold()).isEqualTo(5);
        assertThat(properties.getAlert().getWebhookUrl()).isEmpty();
    }
}
