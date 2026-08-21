package com.icaroerasmo.config;

import com.icaroerasmo.properties.NotifierProperties;
import com.pengrad.telegrambot.TelegramBot;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BeansAndConfig {

    @Bean
    public TelegramBot telegramBot(NotifierProperties properties) {
        return new TelegramBot(properties.telegram().botToken());
    }
}
