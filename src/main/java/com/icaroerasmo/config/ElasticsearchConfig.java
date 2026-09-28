package com.icaroerasmo.config;

import com.icaroerasmo.services.NotificationSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.file.Path;

/**
 * Bootstraps Elasticsearch on startup: creates the indices (with retry) and runs
 * the one-time migration of the legacy JSONL notification history.
 */
@Configuration
@EnableScheduling
@RequiredArgsConstructor
public class ElasticsearchConfig {

    private final NotificationSearchService searchService;

    @Bean
    ApplicationRunner elasticsearchBootstrap(
            @Value("${notifications.file:/app/config/notifications.jsonl}") String jsonlFile) {
        return args -> {
            searchService.ensureIndices();
            searchService.migrateFromJsonl(Path.of(jsonlFile));
        };
    }
}
