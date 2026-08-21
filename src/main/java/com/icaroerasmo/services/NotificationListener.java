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
import java.util.Map;
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
        boolean hasCaption = message.caption() != null;
        boolean hasRawHtml = message.rawHtml() != null;
        int setCount = (hasTemplate ? 1 : 0) + (hasCaption ? 1 : 0) + (hasRawHtml ? 1 : 0);
        if (setCount > 1) {
            throw new AmqpRejectAndDontRequeueException("only one of template, caption, or rawHtml must be set");
        }
        if (setCount == 0 && !message.appendNoLogs()) {
            throw new AmqpRejectAndDontRequeueException("template, caption, or rawHtml must be set");
        }
        if (hasCaption && message.mediaType() != MediaType.PHOTO && message.mediaType() != MediaType.ANIMATION) {
            throw new AmqpRejectAndDontRequeueException("caption is only supported for PHOTO/ANIMATION media types");
        }
    }

    private String renderText(NotificationMessage message) {
        String prefix = modulePrefix(message.sender());

        String body;
        if (message.caption() != null) {
            body = switch (message.mediaType()) {
                case PHOTO -> buildDetectionCaption(message.caption());
                case ANIMATION -> buildGifCaption(message.caption());
                default -> throw new AmqpRejectAndDontRequeueException(
                        "caption is only supported for PHOTO/ANIMATION media types");
            };
        } else if (message.template() != null && !message.template().isBlank()) {
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

    private String render(String template, Object... args) {
        try {
            String pattern = translationService.translate(template);
            return MessageFormat.format(pattern, args);
        } catch (Exception e) {
            throw new AmqpRejectAndDontRequeueException("Failed to render template: " + template, e);
        }
    }

    private String buildDetectionCaption(NotificationMessage.CaptionSpec c) {
        StringBuilder sb = new StringBuilder();
        sb.append("<b>").append(render("DETECTION_HEADER", c.cameraName())).append("</b>\n");

        double lowestDistance = c.detectedPeople().entrySet().stream()
                .filter(e -> !"Unknown".equalsIgnoreCase(e.getKey()))
                .mapToDouble(Map.Entry::getValue)
                .min()
                .orElse(100.0);
        sb.append("<b>").append(render("DETECTION_BEST_MATCH", String.format("%.2f", lowestDistance))).append("</b>\n");
        sb.append("<b>").append(render("DETECTION_FRAMES_IDENTIFIED", c.identityFrameCount())).append("</b>\n");
        sb.append("<b>").append(render("DETECTION_FRAMES_TRACKED", c.totalTrackedFrames())).append("</b>\n\n");

        int unknownCount = 0;
        int knownCount = 0;
        StringBuilder knownNames = new StringBuilder();
        for (Map.Entry<String, Double> entry : c.detectedPeople().entrySet()) {
            if ("Unknown".equalsIgnoreCase(entry.getKey())) {
                unknownCount += (int) Math.round(entry.getValue());
            } else {
                knownCount++;
                if (knownNames.length() > 0) knownNames.append(", ");
                knownNames.append(entry.getKey());
            }
        }

        sb.append("<b>").append(render("DETECTION_LABEL")).append("</b>\n");
        if (knownCount > 0) {
            sb.append("✓ ").append(render("DETECTION_KNOWN", knownCount, knownNames.toString())).append("\n");
        }
        if (unknownCount > 0) {
            sb.append("🔍 ").append(render("DETECTION_UNKNOWN", unknownCount)).append("\n");
        }
        if (knownNames.length() == 0 && unknownCount == 0) {
            sb.append(render("DETECTION_NONE")).append("\n");
        }

        sb.append("\n").append(render("DETECTION_TIME",
                java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));
        return sb.toString();
    }

    private String buildGifCaption(NotificationMessage.CaptionSpec c) {
        return String.format("<b>%s</b>\n<b>%s</b>\n<b>%s</b>\n<b>%s</b>",
                render("GIF_HEADER"),
                render("GIF_CAMERA", c.cameraName()),
                render("GIF_FRAMES", c.frameCount()),
                render("GIF_DURATION", String.format("%.1f", c.duration())));
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
