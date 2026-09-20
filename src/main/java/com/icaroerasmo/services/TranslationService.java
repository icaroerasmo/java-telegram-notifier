package com.icaroerasmo.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class TranslationService {

    private static final Locale DEFAULT_LOCALE = new Locale("pt", "BR");

    private final MessageSource messageSource;
    private final Locale locale;

    public TranslationService(MessageSource messageSource, @Value("${locale:pt-BR}") String locale) {
        this.messageSource = messageSource;
        this.locale = resolveLocale(locale);
    }

    public String translate(String key, Object... args) {
        return messageSource.getMessage(key, args, locale);
    }

    private static Locale resolveLocale(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_LOCALE;
        }
        String[] parts = value.split("-", 2);
        if (parts.length == 2) {
            return new Locale(parts[0], parts[1]);
        }
        return new Locale(value);
    }
}
