package com.icaroerasmo.services;

import com.icaroerasmo.messaging.NotificationMessage;
import com.icaroerasmo.messaging.NotificationMessage.MediaType;
import com.icaroerasmo.properties.NotifierProperties;
import com.pengrad.telegrambot.TelegramBot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class NotificationListenerTest {

    private TelegramBot telegramBot;
    private NotificationListener listener;

    @BeforeEach
    void setUp() {
        telegramBot = mock(TelegramBot.class);
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        NotifierProperties properties =
                new NotifierProperties("pt-BR", 2000, new NotifierProperties.Telegram("chat-id", "bot-token"));
        listener = new NotificationListener(telegramBot, new TranslationService(messageSource, properties), properties);
    }

    @Test
    void validateRejectsInvalidSender() {
        NotificationMessage message = textMessage("hacker", "MOVEMENT_DETECTED");
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> invoke("validate", message));
    }

    @Test
    void validateRejectsNullMediaType() {
        NotificationMessage message = new NotificationMessage(
                "m1", "object-detection", null, "MOVEMENT_DETECTED",
                List.of("garagem1"), null, null, null, null, false);
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> invoke("validate", message));
    }

    @Test
    void validateRejectsNullPayloadForPhoto() {
        NotificationMessage message = new NotificationMessage(
                "m1", "object-detection", MediaType.PHOTO, "MOVEMENT_DETECTED",
                List.of("garagem1"), null, null, null, null, false);
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> invoke("validate", message));
    }

    @Test
    void validateAcceptsValidTextMessage() {
        NotificationMessage message = textMessage("object-detection", "MOVEMENT_DETECTED");
        assertDoesNotThrow(() -> invoke("validate", message));
    }

    @Test
    void renderTextRendersMovementDetectedTemplate() {
        NotificationMessage message = textMessage("object-detection", "MOVEMENT_DETECTED");
        String rendered = invoke("renderText", message);
        assertEquals("[Object Detection] 🚶 Movimento detectado na câmera garagem1", rendered);
        assertTrue(rendered.startsWith("[Object Detection] "));
        assertTrue(rendered.contains("Movimento detectado na câmera garagem1"));
    }

    @Test
    void modulePrefixUsesObjectDetectionForObjectDetectionSender() {
        String prefix = invoke("modulePrefix", new Class<?>[]{String.class}, new Object[]{"object-detection"});
        assertEquals("[Object Detection] ", prefix);
    }

    @Test
    void renderTextThrowsOnUnknownTemplate() {
        NotificationMessage message = textMessage("object-detection", "NONEXISTENT_TEMPLATE");
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> invoke("renderText", message));
    }

    private static NotificationMessage textMessage(String sender, String template) {
        return new NotificationMessage(
                "m1", sender, MediaType.TEXT, template,
                List.of("garagem1"), null, null, null, null, false);
    }

    @SuppressWarnings("unchecked")
    private <T> T invoke(String methodName, NotificationMessage message) {
        return invoke(methodName, new Class<?>[]{NotificationMessage.class}, new Object[]{message});
    }

    @SuppressWarnings("unchecked")
    private <T> T invoke(String methodName, Class<?>[] parameterTypes, Object[] args) {
        try {
            Method method = NotificationListener.class.getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return (T) method.invoke(listener, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(cause);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
