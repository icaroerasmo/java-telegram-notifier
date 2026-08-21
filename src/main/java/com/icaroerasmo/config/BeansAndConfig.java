package com.icaroerasmo.config;

import com.icaroerasmo.properties.NotifierProperties;
import com.pengrad.telegrambot.TelegramBot;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;

@Configuration
public class BeansAndConfig {

    @Bean
    public TelegramBot telegramBot(NotifierProperties properties) {
        return new TelegramBot(properties.telegram().botToken());
    }

    @Bean
    public MessageSource messageSource() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        return messageSource;
    }
}
