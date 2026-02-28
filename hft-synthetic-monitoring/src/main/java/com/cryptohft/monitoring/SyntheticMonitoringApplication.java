package com.cryptohft.monitoring;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

@Slf4j
@SpringBootApplication
@EnableScheduling
public class SyntheticMonitoringApplication {

    public static void main(String[] args) {
        Environment env = SpringApplication.run(SyntheticMonitoringApplication.class, args)
                .getEnvironment();
        log.info("HFT Synthetic Monitoring started on port {}", env.getProperty("server.port", "8081"));
    }
}
