package com.icaroerasmo.services;

import com.icaroerasmo.messaging.NotificationMessage;
import com.icaroerasmo.messaging.NotificationMessage.MediaType;
import com.icaroerasmo.properties.NotifierProperties;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.request.ParseMode;
import com.pengrad.telegrambot.request.SendAnimation;
import com.pengrad.telegrambot.request.SendDocument;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.request.SendPhoto;
import com.pengrad.telegrambot.response.SendResponse;
import lombok.extern.log4j.Log4j2;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import java.text.MessageFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@Log4j2
@Service
public class NotificationListener {

    private static final int TEXT_MAX_LENGTH = 4096;
    private static final int CAPTION_MAX_LENGTH = 1024;
    private static final String ELLIPSIS = "…";
    private static final String NO_LOGS_SUFFIX = ". Nenhum registro encontrado.";
    private static final Set<String> ALLOWED_SENDERS =
            Set.of("live-transmission", "recorder", "face-recognition");

    private final TelegramBot telegramBot;
    private final TranslationService translationService;
    private final NotifierProperties properties;
    private final AtomicLong lastSentAt = new AtomicLong(0);

    public NotificationListener(TelegramBot telegramBot,
                                TranslationService translationService,
                                NotifierProperties properties) {
        this.telegramBot = telegramBot;
        this.translationService = translationService;
        this.properties = properties;
    }

    @RabbitListener(queues = "telegram.notifications")
    public void onNotification(NotificationMessage message) {
        log.info("Received telegram notification messageId={} sender={} mediaType={} template={} appendNoLogs={}",
                message.messageId(), message.sender(), message.mediaType(), message.template(), message.appendNoLogs());

        validate(message);
        String text = renderText(message);
        throttle();

        try {
            send(message, text);
            lastSentAt.set(System.currentTimeMillis());
        } catch (Exception e) {
            log.error("Failed to send telegram notification messageId={}", message.messageId(), e);
            throw new RuntimeException("Failed to send telegram notification: " + message.messageId(), e);
        }
    }

    private void validate(NotificationMessage message) {
        if (message.sender() == null || !ALLOWED_SENDERS.contains(message.sender())) {
            throw new AmqpRejectAndDontRequeueException("Invalid sender: " + message.sender());
        }
        if (message.mediaType() == null) {
            throw new AmqpRejectAndDontRequeueException("mediaType must not be null");
        }
        if (message.mediaType() != MediaType.TEXT && message.payload() == null) {
            throw new AmqpRejectAndDontRequeueException(
                    "payload must not be null for mediaType " + message.mediaType());
        }
        if (message.mediaType() == MediaType.DOCUMENT &&
                (message.filename() == null || message.filename().isBlank())) {
            throw new AmqpRejectAndDontRequeueException("filename must not be null for DOCUMENT mediaType");
        }

        boolean hasTemplate = message.template() != null && !message.template().isBlank();
        boolean hasRawHtml = message.rawHtml() != null;
        if (hasTemplate && hasRawHtml) {
            throw new AmqpRejectAndDontRequeueException("only one of template or rawHtml must be set");
        }
        if (!hasTemplate && !hasRawHtml && !message.appendNoLogs()) {
            throw new AmqpRejectAndDontRequeueException("template or rawHtml must be set");
        }
    }

    private String renderText(NotificationMessage message) {
        String prefix = modulePrefix(message.sender());

        String body;
        if (message.template() != null && !message.template().isBlank()) {
            String pattern;
            try {
                pattern = translationService.translate(message.template());
            } catch (Exception e) {
                throw new AmqpRejectAndDontRequeueException("Unknown template: " + message.template(), e);
            }
            Object[] args = message.args() != null ? message.args().toArray() : new Object[0];
            try {
                body = escapeHtml(MessageFormat.format(pattern, args));
            } catch (Exception e) {
                throw new AmqpRejectAndDontRequeueException("Failed to format template: " + message.template(), e);
            }
        } else {
            body = message.rawHtml() != null ? message.rawHtml() : "";
        }

        if (message.appendNoLogs()) {
            body = body + NO_LOGS_SUFFIX;
        }

        return prefix + body;
    }

    private String modulePrefix(String sender) {
        return switch (sender) {
            case "live-transmission" -> "[Live Transmission] ";
            case "recorder" -> "[Recorder] ";
            case "face-recognition" -> "[Face Recognition] ";
            default -> throw new AmqpRejectAndDontRequeueException("Unknown sender: " + sender);
        };
    }

    private static String escapeHtml(String input) {
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private void send(NotificationMessage message, String text) {
        String chatId = properties.telegram().chatId();
        SendResponse response;
        switch (message.mediaType()) {
            case TEXT -> {
                response = telegramBot.execute(
                        new SendMessage(chatId, truncate(text, TEXT_MAX_LENGTH)).parseMode(ParseMode.HTML));
            }
            case PHOTO -> {
                response = telegramBot.execute(
                        new SendPhoto(chatId, message.payload())
                                .caption(truncate(text, CAPTION_MAX_LENGTH))
                                .parseMode(ParseMode.HTML));
            }
            case ANIMATION -> {
                response = telegramBot.execute(
                        new SendAnimation(chatId, message.payload())
                                .caption(truncate(text, CAPTION_MAX_LENGTH))
                                .parseMode(ParseMode.HTML));
            }
            case DOCUMENT -> {
                response = telegramBot.execute(
                        new SendDocument(chatId, message.payload())
                                .fileName(message.filename())
                                .caption(truncate(text, CAPTION_MAX_LENGTH))
                                .parseMode(ParseMode.HTML));
            }
            default -> throw new IllegalStateException("Unexpected media type: " + message.mediaType());
        }
        if (!response.isOk()) {
            throw new RuntimeException("Telegram send failed: code=" + response.errorCode() +
                    " description=" + response.description());
        }
    }

    private static String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 1) + ELLIPSIS;
    }

    private void throttle() {
        long now = System.currentTimeMillis();
        long last = lastSentAt.get();
        long wait = properties.minSendIntervalMs() - (now - last);
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while throttling telegram sends", e);
            }
        }
    }
}
