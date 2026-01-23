package com.cryptohft;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main entry point for the HFT Trading Platform.
 */
@Slf4j
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class HftApplication {
    
    public static void main(String[] args) {
        // Set system properties for optimal performance
        configureSystemProperties();
        
        SpringApplication app = new SpringApplication(HftApplication.class);
        app.run(args);
        
        log.info("HFT Trading Platform started successfully");
    }
    
    private static void configureSystemProperties() {
        // Disable JVM class verification for faster startup
        System.setProperty("java.security.egd", "file:/dev/./urandom");
        
        // Enable string deduplication
        // Note: Requires G1 GC: -XX:+UseG1GC -XX:+UseStringDeduplication
        
        // Agrona settings for off-heap memory
        System.setProperty("agrona.disable.bounds.checks", "true");
        
        // Aeron settings
        System.setProperty("aeron.term.buffer.sparse.file", "false");
        System.setProperty("aeron.pre.touch.mapped.memory", "true");
        
        log.info("System properties configured for optimal performance");
    }
}
