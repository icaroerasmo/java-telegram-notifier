package com.icaroerasmo.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "telegram")
public record TelegramProperties(
        long minSendIntervalMs,
        String chatId,
        String botToken) {

    public TelegramProperties {
        if (minSendIntervalMs <= 0) {
            minSendIntervalMs = 2000;
        }
    }
}