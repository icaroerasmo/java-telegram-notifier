package com.icaroerasmo.services;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslationServiceTest {

    private final ResourceBundleMessageSource messageSource = messageSource();
    private final TranslationService translationService = new TranslationService(messageSource, "pt-BR");

    @Test
    void translateMovementDetectedInPortuguese() {
        String rendered = translationService.translate("MOVEMENT_DETECTED", "garagem1");
        assertEquals("🚶 Movimento detectado na câmera garagem1", rendered);
    }

    @Test
    void translateMovementDetectedInEnglish() {
        TranslationService enService = new TranslationService(messageSource, "en-US");
        String rendered = enService.translate("MOVEMENT_DETECTED", "garagem1");
        assertEquals("🚶 Movement detected on camera garagem1", rendered);
    }

    @Test
    void translateFallsBackToPortugueseWhenLocaleIsBlank() {
        TranslationService defaultService = new TranslationService(messageSource, null);
        String rendered = defaultService.translate("MOVEMENT_DETECTED", "garagem1");
        assertTrue(rendered.contains("Movimento detectado na câmera garagem1"));
    }

    @Test
    void translatePetDetectedInPortuguese() {
        String rendered = translationService.translate("PET_DETECTED", "garagem1");
        assertTrue(rendered.contains("Animal de estimação detectado na câmera garagem1"));
    }

    private static ResourceBundleMessageSource messageSource() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        return messageSource;
    }
}
