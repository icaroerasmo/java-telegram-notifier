package com.icaroerasmo.controller;

import com.icaroerasmo.messaging.NotificationPage;
import com.icaroerasmo.messaging.NotificationSummary;
import com.icaroerasmo.services.NotificationSearchService;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.request.GetFile;
import com.pengrad.telegrambot.response.GetFileResponse;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Serves the notification history (from Elasticsearch) and proxies media stored
 * on Telegram. The bot token never leaves this service.
 */
@Log4j2
@RestController
@RequestMapping("/api")
public class NotificationController {

    private final NotificationSearchService searchService;
    private final TelegramBot telegramBot;

    public NotificationController(NotificationSearchService searchService, TelegramBot telegramBot) {
        this.searchService = searchService;
        this.telegramBot = telegramBot;
    }

    /**
     * Notification history with filters. {@code type=notifications|logs} selects
     * which set is returned (notifications returns ALL including DOCUMENT; logs
     * returns only DOCUMENT). {@code text=} switches to full-text search over log
     * content. {@code kind}/{@code date}/{@code hour} filter logs. Pagination is
     * cursor-based (opaque) via search_after.
     */
    @GetMapping("/notifications")
    public NotificationPage getNotifications(@RequestParam(defaultValue = "notifications") String type,
                                             @RequestParam(defaultValue = "100") int limit,
                                             @RequestParam(required = false) String cursor,
                                             @RequestParam(required = false) String text,
                                             @RequestParam(required = false) String kind,
                                             @RequestParam(required = false) String date,
                                             @RequestParam(required = false) String hour) {
        int capped = Math.max(1, Math.min(limit, 1000));
        if (text != null && !text.isBlank()) {
            return searchService.searchLogs(text, kind, date, hour, cursor, capped);
        }
        return searchService.list("logs".equalsIgnoreCase(type), kind, date, hour, cursor, capped);
    }

    /** Returns all distinct log kinds (template names) for the logs tab. */
    @GetMapping("/notifications/kinds")
    public List<String> getKinds() {
        return searchService.distinctKinds();
    }

    /** Returns a single notification by id (404 when absent). */
    @GetMapping("/notifications/{id}")
    public ResponseEntity<NotificationSummary> getNotification(@PathVariable String id) {
        return searchService.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Backfills the log content for every DOCUMENT notification whose content is
     * not already indexed, fetching each file from Telegram. Runs async and is
     * idempotent (skips already-indexed content, so it can be re-run).
     */
    @PostMapping("/notifications/backfill")
    public ResponseEntity<Map<String, Object>> backfill() {
        Thread thread = new Thread(this::runBackfill, "log-backfill");
        thread.setDaemon(true);
        thread.start();
        return ResponseEntity.accepted().body(Map.of("status", "started"));
    }

    private void runBackfill() {
        String cursor = null;
        int processed = 0;
        int indexed = 0;
        while (true) {
            NotificationPage page;
            try {
                page = searchService.list(true, null, null, null, cursor, 200);
            } catch (Exception e) {
                log.error("Backfill failed to fetch page: {}", e.getMessage());
                break;
            }
            if (page.items().isEmpty()) {
                break;
            }
            for (NotificationSummary s : page.items()) {
                processed++;
                if (s.fileId() == null || s.fileId().isBlank()) {
                    continue;
                }
                try {
                    if (searchService.hasLogContent(s.id())) {
                        continue;
                    }
                    GetFileResponse response = telegramBot.execute(new GetFile(s.fileId()));
                    if (response.isOk() && response.file() != null) {
                        byte[] bytes = telegramBot.getFileContent(response.file());
                        searchService.reindexLogContent(s, new String(bytes, StandardCharsets.UTF_8));
                        indexed++;
                    }
                } catch (Exception e) {
                    log.warn("Backfill failed for {} (fileId={}): {}", s.id(), s.fileId(), e.getMessage());
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.info("Backfill interrupted: processed={} indexed={}", processed, indexed);
                    return;
                }
            }
            log.info("Backfill progress: processed={} indexed={}", processed, indexed);
            if (!page.hasMore() || page.nextCursor() == null) {
                break;
            }
            cursor = page.nextCursor();
        }
        log.info("Backfill complete: processed={} indexed={}", processed, indexed);
    }

    @GetMapping("/media/{fileId}")
    public ResponseEntity<byte[]> getMedia(@PathVariable String fileId,
                                           @RequestParam(value = "filename", required = false) String filename) {
        try {
            GetFileResponse response = telegramBot.execute(new GetFile(fileId));
            if (!response.isOk() || response.file() == null) {
                return ResponseEntity.notFound().build();
            }
            byte[] bytes = telegramBot.getFileContent(response.file());
            triggerLogReindex(fileId, bytes);
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

    /**
     * Lazy reload: when a DOCUMENT (log) is viewed, re-index its content so it
     * becomes text-searchable again even after it expired from the "logs" index.
     * Runs async so the view response is not blocked.
     */
    private void triggerLogReindex(String fileId, byte[] bytes) {
        CompletableFuture.runAsync(() -> {
            try {
                searchService.findLogByFileId(fileId).ifPresent(summary ->
                        searchService.reindexLogContent(summary, new String(bytes, StandardCharsets.UTF_8)));
            } catch (Exception e) {
                log.warn("Failed to reindex log content for fileId={}: {}", fileId, e.getMessage());
            }
        });
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
            // object-detection envia MP4 (H264) como "animation"; o Telegram
            // nomeia com extensao .gif mas o conteudo real e MP4.
            return MediaType.valueOf("video/mp4");
        }
        if (lower.endsWith(".mp4")) {
            return MediaType.valueOf("video/mp4");
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
