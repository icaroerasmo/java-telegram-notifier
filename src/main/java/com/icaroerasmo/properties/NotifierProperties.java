package com.icaroerasmo.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "telegram-notifier")
public record NotifierProperties(
        String locale,
        long minSendIntervalMs,
        Telegram telegram) {

    public NotifierProperties {
        if (locale == null || locale.isBlank()) {
            locale = "pt-BR";
        }
        if (minSendIntervalMs <= 0) {
            minSendIntervalMs = 2000;
        }
    }

    public record Telegram(String chatId, String botToken) {
    }
}
