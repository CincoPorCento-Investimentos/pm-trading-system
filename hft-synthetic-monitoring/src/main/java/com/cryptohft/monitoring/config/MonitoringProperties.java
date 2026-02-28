package com.cryptohft.monitoring.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "synthetic-monitoring")
public class MonitoringProperties {

    private long intervalMs = 30000;
    private long timeoutMs = 3000;

    private Target target = new Target();
    private Order order = new Order();
    private Alert alert = new Alert();

    @Data
    public static class Target {
        private String baseUrl = "http://localhost:8080";
        private String wsUrl = "ws://localhost:8080/ws/trading";
    }

    @Data
    public static class Order {
        private String symbol = "BTCUSD";
        private double price = 100.00;
        private double quantity = 0.001;
        private String account = "SM_CHECK";
        private String clientIdPrefix = "sm-";
    }

    @Data
    public static class Alert {
        private int consecutiveFailuresThreshold = 3;
        private int warnThreshold = 5;
        private String webhookUrl = "";
    }
}
