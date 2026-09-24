package com.icaroerasmo.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icaroerasmo.messaging.NotificationSummary;
import jakarta.annotation.PostConstruct;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Persists notification summaries (references only, no media bytes) so the
 * dashboard can show a scrollable history. Media stays on Telegram; only the
 * file_id + short summary are kept locally.
 */
@Log4j2
@Service
public class NotificationStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_RECENT = 5000;

    private final Deque<NotificationSummary> recent = new ArrayDeque<>();
    private final Path dataFile;

    public NotificationStore(@Value("${notifications.file:/app/config/notifications.jsonl}") String file) {
        this.dataFile = Path.of(file);
    }

    @PostConstruct
    public void load() {
        if (!Files.exists(dataFile)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(dataFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    recent.addLast(MAPPER.readValue(line, NotificationSummary.class));
                } catch (IOException e) {
                    log.warn("Skipping malformed notification line: {}", e.getMessage());
                }
                while (recent.size() > MAX_RECENT) {
                    recent.removeFirst();
                }
            }
        } catch (IOException e) {
            log.warn("Failed to load notification history: {}", e.getMessage());
        }
        log.info("Loaded {} notifications from {}", recent.size(), dataFile);
    }

    public synchronized void append(NotificationSummary summary) {
        recent.addLast(summary);
        while (recent.size() > MAX_RECENT) {
            recent.removeFirst();
        }
        try {
            Files.createDirectories(dataFile.getParent());
            Files.writeString(dataFile, MAPPER.writeValueAsString(summary) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Failed to persist notification summary: {}", e.getMessage());
        }
    }

    public List<NotificationSummary> getRecent(int limit) {
        List<NotificationSummary> list = new ArrayList<>(recent);
        int size = list.size();
        int from = Math.max(0, size - limit);
        return list.subList(from, size);
    }
}
