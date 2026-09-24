package com.icaroerasmo.controller;

import com.icaroerasmo.messaging.NotificationSummary;
import com.icaroerasmo.services.NotificationStore;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.request.GetFile;
import com.pengrad.telegrambot.response.GetFileResponse;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Serves the notification history and proxies media stored on Telegram.
 * The bot token never leaves this service.
 */
@Log4j2
@RestController
@RequestMapping("/api")
public class NotificationController {

    private final NotificationStore store;
    private final TelegramBot telegramBot;

    public NotificationController(NotificationStore store, TelegramBot telegramBot) {
        this.store = store;
        this.telegramBot = telegramBot;
    }

    @GetMapping("/notifications")
    public List<NotificationSummary> getNotifications(@RequestParam(defaultValue = "100") int limit) {
        return store.getRecent(Math.max(1, Math.min(limit, 1000)));
    }

    @GetMapping("/media/{fileId}")
    public ResponseEntity<byte[]> getMedia(@PathVariable String fileId,
                                           @RequestParam(value = "filename", required = false) String filename) {
        try {
            GetFileResponse response = telegramBot.execute(new GetFile(fileId));
            log.warn("[getMedia] fileId={} isOk={} errorCode={} description={} filePath={}",
                    fileId, response.isOk(), response.errorCode(), response.description(),
                    response.file() != null ? response.file().filePath() : "null");
            if (!response.isOk() || response.file() == null) {
                return ResponseEntity.notFound().build();
            }
            byte[] bytes = telegramBot.getFileContent(response.file());
            ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, inferMediaType(response.file().filePath()).toString())
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600");
            if (filename != null && !filename.isBlank()) {
                builder.header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename.replace("\"", "") + "\"");
            }
            return builder.body(bytes);
        } catch (Exception e) {
            log.warn("Failed to fetch media fileId={}: {}", fileId, e.getMessage());
            return ResponseEntity.status(500).build();
        }
    }

    private MediaType inferMediaType(String filePath) {
        if (filePath == null) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        String lower = filePath.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        if (lower.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (lower.endsWith(".gif")) {
            return MediaType.IMAGE_GIF;
        }
        if (lower.endsWith(".mp4")) {
            return MediaType.valueOf("video/mp4");
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
