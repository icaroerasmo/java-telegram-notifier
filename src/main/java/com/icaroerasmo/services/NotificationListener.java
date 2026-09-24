package com.icaroerasmo.services;

import com.icaroerasmo.messaging.NotificationMessage;
import com.icaroerasmo.messaging.NotificationMessage.MediaType;
import com.icaroerasmo.messaging.NotificationSummary;
import com.icaroerasmo.properties.TelegramProperties;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.PhotoSize;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
            Set.of("live-transmission", "recorder", "object-detection");

    private static final Set<String> INITIALIZATION_TEMPLATES = Set.of("COMPOSITOR_STARTED", "CAMERA_STARTED", "CAM_CONNECTED", "CAM_RECONNECTING", "CAM_HIBERNATE_COMPLETE");
    private final TelegramBot telegramBot;
    private final TranslationService translationService;
    private final TelegramProperties properties;
    private final NotificationStore notificationStore;
    private final NotificationSummaryPublisher summaryPublisher;
    private final AtomicLong lastSentAt = new AtomicLong(0);
    private final Object sendLock = new Object();

    public NotificationListener(TelegramBot telegramBot,
                                TranslationService translationService,
                                TelegramProperties properties,
                                NotificationStore notificationStore,
                                NotificationSummaryPublisher summaryPublisher) {
        this.telegramBot = telegramBot;
        this.translationService = translationService;
        this.properties = properties;
        this.notificationStore = notificationStore;
        this.summaryPublisher = summaryPublisher;
    }

    @RabbitListener(queues = "telegram.notifications")
    public void onNotification(NotificationMessage message) {
        log.info("Received telegram notification messageId={} sender={} mediaType={} template={} appendNoLogs={}",
                message.messageId(), message.sender(), message.mediaType(), message.template(), message.appendNoLogs());

        validate(message);
        String text = renderText(message);
        SendResponse response = sendWithThrottle(message, text);

        String fileId = extractFileId(message, response);
        String summary = buildSummary(message, text);
        long ts = parseTimestamp(message.sentAt());
        NotificationSummary notificationSummary = new NotificationSummary(
                message.messageId(),
                message.sender(),
                message.mediaType().name(),
                message.template(),
                summary,
                fileId,
                message.filename(),
                message.sentAt(),
                ts,
                formatDate(ts),
                formatHour(ts));

        notificationStore.append(notificationSummary);
        summaryPublisher.publish(notificationSummary);
    }

    /**
     * Serializes all Telegram sends and enforces the minimum send interval.
     * The slot is reserved BEFORE sending so failures do not bypass the throttle,
     * and a 429 retry_after wait blocks subsequent sends as well.
     */
    private SendResponse sendWithThrottle(NotificationMessage message, String text) {
        synchronized (sendLock) {
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
            lastSentAt.set(System.currentTimeMillis());

            try {
                return send(message, text);
            } catch (Exception e) {
                log.error("Failed to send telegram notification messageId={}", message.messageId(), e);
                throw new RuntimeException("Failed to send telegram notification: " + message.messageId(), e);
            }
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

        if (message.template() != null && INITIALIZATION_TEMPLATES.contains(message.template()) && message.sentAt() != null) {
            body = body + "\n🕐 " + message.sentAt();
        }

        if (message.delayed()) {
            body = body + "\n\n⚠️ Mensagem atrasada — reenviada automaticamente.";
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
            case "object-detection" -> "[Object Detection] ";
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

    private SendResponse send(NotificationMessage message, String text) {
        String chatId = properties.chatId();
        SendResponse response = execute(message, text, chatId);
        if (!response.isOk() && response.errorCode() == 429) {
            int retryAfter = response.parameters() != null && response.parameters().retryAfter() != null
                    ? response.parameters().retryAfter() : 5;
            log.warn("Telegram rate limited (429), retrying after {}s: messageId={}", retryAfter, message.messageId());
            try {
                Thread.sleep(retryAfter * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for Telegram rate limit", e);
            }
            response = execute(message, text, chatId);
        }
        if (!response.isOk()) {
            throw new RuntimeException("Telegram send failed: code=" + response.errorCode() +
                    " description=" + response.description());
        }
        return response;
    }

    private SendResponse execute(NotificationMessage message, String text, String chatId) {
        return switch (message.mediaType()) {
            case TEXT -> telegramBot.execute(
                    new SendMessage(chatId, truncate(text, TEXT_MAX_LENGTH)).parseMode(ParseMode.HTML));
            case PHOTO -> telegramBot.execute(
                    new SendPhoto(chatId, message.payload())
                            .caption(truncate(text, CAPTION_MAX_LENGTH))
                            .parseMode(ParseMode.HTML));
            case ANIMATION -> telegramBot.execute(
                    new SendAnimation(chatId, message.payload())
                            .caption(truncate(text, CAPTION_MAX_LENGTH))
                            .parseMode(ParseMode.HTML));
            case DOCUMENT -> telegramBot.execute(
                    new SendDocument(chatId, message.payload())
                            .fileName(message.filename())
                            .caption(truncate(text, CAPTION_MAX_LENGTH))
                            .parseMode(ParseMode.HTML));
        };
    }

    private static String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 1) + ELLIPSIS;
    }

    private String extractFileId(NotificationMessage message, SendResponse response) {
        if (response == null || response.message() == null) {
            log.warn("[extractFileId] null response/message: mediaType={} isOk={} errorCode={} description={}",
                    message.mediaType(),
                    response != null ? response.isOk() : "null",
                    response != null ? response.errorCode() : "null",
                    response != null ? response.description() : "null");
            return null;
        }
        Message msg = response.message();
        return switch (message.mediaType()) {
            case TEXT -> msg.messageId() != null ? String.valueOf(msg.messageId()) : null;
            case PHOTO -> {
                PhotoSize[] photos = msg.photo();
                yield (photos != null && photos.length > 0) ? photos[photos.length - 1].fileId() : null;
            }
            case ANIMATION -> {
                if (msg.animation() != null && msg.animation().fileId() != null) yield msg.animation().fileId();
                if (msg.video() != null && msg.video().fileId() != null) yield msg.video().fileId();
                if (msg.videoNote() != null && msg.videoNote().fileId() != null) yield msg.videoNote().fileId();
                if (msg.document() != null && msg.document().fileId() != null) yield msg.document().fileId();
                log.warn("[extractFileId] ANIMATION: no fileId found. animation={} video={} videoNote={} document={} errorCode={} description={}",
                        msg.animation() != null ? msg.animation().fileId() : "null",
                        msg.video() != null ? msg.video().fileId() : "null",
                        msg.videoNote() != null ? msg.videoNote().fileId() : "null",
                        msg.document() != null ? msg.document().fileId() : "null",
                        response.errorCode(), response.description());
                yield null;
            }
            case DOCUMENT -> msg.document() != null ? msg.document().fileId() : null;
        };
    }

    private String buildSummary(NotificationMessage message, String text) {
        return switch (message.mediaType()) {
            case TEXT, DOCUMENT -> text;
            case PHOTO -> buildDetectionSummary(message.caption());
            case ANIMATION -> buildGifSummary(message.caption());
        };
    }

    private String buildDetectionSummary(NotificationMessage.CaptionSpec c) {
        if (c == null || c.detectedPeople() == null) {
            return "👤 Detecção";
        }
        int known = 0;
        int unknown = 0;
        for (Map.Entry<String, Double> entry : c.detectedPeople().entrySet()) {
            if ("Unknown".equalsIgnoreCase(entry.getKey())) {
                unknown += (int) Math.round(entry.getValue());
            } else {
                known++;
            }
        }
        String cam = c.cameraName() != null ? c.cameraName() : "";
        if (known == 0 && unknown == 0) {
            return "👤 Detecção · " + cam;
        }
        return "👤 Detecção · " + cam + " · " + known + " conhecidos, " + unknown + " desconhecidos";
    }

    private String buildGifSummary(NotificationMessage.CaptionSpec c) {
        if (c == null) {
            return "🎬 Animação";
        }
        String cam = c.cameraName() != null ? c.cameraName() : "";
        double dur = c.duration() != null ? c.duration() : 0.0;
        return "🎬 Animação · " + cam + " · ~" + String.format("%.1f", dur) + "s";
    }

    private static long parseTimestamp(String sentAt) {
        if (sentAt == null || sentAt.isBlank()) {
            return System.currentTimeMillis();
        }
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
            return LocalDateTime.parse(sentAt, formatter)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    private static String formatDate(long ts) {
        return Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate().toString();
    }

    private static String formatHour(long ts) {
        return Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalTime()
                .format(DateTimeFormatter.ofPattern("HH"));
    }
}
