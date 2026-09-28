package com.icaroerasmo.services;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icaroerasmo.messaging.LogDocument;
import com.icaroerasmo.messaging.NotificationDocument;
import com.icaroerasmo.messaging.NotificationPage;
import com.icaroerasmo.messaging.NotificationSummary;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.query.IndexQuery;
import org.springframework.data.elasticsearch.core.query.IndexQueryBuilder;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Elasticsearch-backed replacement for the old JSONL {@link NotificationStore}.
 * Holds notification metadata in the "notifications" index (no TTL) and searchable
 * log content in the "logs" index (10-day TTL on contentStoredAt).
 */
@Log4j2
@Service
public class NotificationSearchService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long LOG_TTL_DAYS = 10;
    private static final int MAX_LOG_CONTENT_BYTES = 1_000_000; // 1 MB cap

    private final ElasticsearchOperations operations;
    private final ElasticsearchClient client;

    public NotificationSearchService(ElasticsearchOperations operations, ElasticsearchClient client) {
        this.operations = operations;
        this.client = client;
    }

    // ------------------------------------------------------------------
    // Index bootstrap + one-time migration
    // ------------------------------------------------------------------

    public void ensureIndices() {
        createIndexWithRetry(NotificationDocument.class);
        createIndexWithRetry(LogDocument.class);
    }

    /**
     * One-time, idempotent migration of the legacy JSONL file into the
     * "notifications" index. Historical DOCUMENT rows carry metadata only — their
     * log content is indexed lazily on first view (Telegram holds the bytes).
     */
    public int migrateFromJsonl(Path file) {
        if (file == null || !Files.exists(file)) {
            return 0;
        }
        int count = 0;
        List<IndexQuery> batch = new ArrayList<>(500);
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    NotificationSummary summary = MAPPER.readValue(line, NotificationSummary.class);
                    batch.add(new IndexQueryBuilder()
                            .withId(summary.id())
                            .withObject(toDocument(summary))
                            .build());
                    count++;
                } catch (IOException e) {
                    log.warn("Skipping malformed notification line during migration: {}", e.getMessage());
                }
                if (batch.size() >= 500) {
                    flushBatch(batch);
                    batch.clear();
                }
            }
        } catch (IOException e) {
            log.warn("Failed to migrate notification history: {}", e.getMessage());
        }
        flushBatch(batch);
        log.info("Migrated {} notifications from {} into Elasticsearch", count, file);
        markMigrated(file);
        return count;
    }

    private void flushBatch(List<IndexQuery> batch) {
        if (batch.isEmpty()) {
            return;
        }
        try {
            operations.bulkIndex(batch, NotificationDocument.class);
        } catch (Exception e) {
            log.warn("Failed to bulk-index {} notifications: {}", batch.size(), e.getMessage());
        }
    }

    /** Renames the JSONL file once migrated so the (idempotent) migration runs only once. */
    private void markMigrated(Path file) {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".migrated"));
        } catch (IOException e) {
            log.warn("Failed to rename migrated JSONL file {}: {}", file, e.getMessage());
        }
    }

    private void createIndexWithRetry(Class<?> clazz) {
        IndexOperations indexOps = operations.indexOps(clazz);
        for (int attempt = 1; attempt <= 30; attempt++) {
            try {
                if (indexOps.exists()) {
                    return;
                }
                indexOps.createWithMapping();
                log.info("Created Elasticsearch index for {}", clazz.getSimpleName());
                return;
            } catch (Exception e) {
                log.warn("Elasticsearch index creation attempt {} failed for {}: {}",
                        attempt, clazz.getSimpleName(), e.getMessage());
                sleepQuietly(2000);
            }
        }
        log.error("Could not create Elasticsearch index for {} after 30 attempts", clazz.getSimpleName());
    }

    // ------------------------------------------------------------------
    // Ingest
    // ------------------------------------------------------------------

    public void index(NotificationSummary summary) {
        operations.save(toDocument(summary));
    }

    /** Indexes the decoded log content for a DOCUMENT notification (forward-going). */
    public void indexLog(NotificationSummary summary, byte[] payload) {
        if (!"DOCUMENT".equals(summary.mediaType()) || payload == null || payload.length == 0) {
            return;
        }
        saveLog(summary, decode(payload));
    }

    /** Re-indexes log content fetched from Telegram on demand (lazy reload). */
    public void reindexLogContent(NotificationSummary summary, String content) {
        saveLog(summary, content);
    }

    /** Returns true if the log content for the given id is already indexed. */
    public boolean hasLogContent(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        return operations.get(id, LogDocument.class) != null;
    }

    private void saveLog(NotificationSummary s, String content) {
        String safe = content == null ? "" : content;
        if (safe.length() > MAX_LOG_CONTENT_BYTES) {
            safe = safe.substring(0, MAX_LOG_CONTENT_BYTES);
        }
        operations.save(LogDocument.builder()
                .id(s.id())
                .logContent(safe)
                .filename(s.filename())
                .summary(s.summary())
                .sender(s.sender())
                .kind(s.kind())
                .mediaType(s.mediaType())
                .fileId(s.fileId())
                .sentAt(s.sentAt())
                .timestamp(s.timestamp())
                .date(s.date())
                .hour(s.hour())
                .size(s.size())
                .contentStoredAt(System.currentTimeMillis())
                .build());
    }

    // ------------------------------------------------------------------
    // Read (list + full-text search)
    // ------------------------------------------------------------------

    /**
     * Lists notifications. {@code logsOnly} selects DOCUMENT rows only (the "has
     * logs" filter); otherwise all notifications are returned (including DOCUMENT).
     */
    public NotificationPage list(boolean logsOnly, String kind, String date, String hour, String cursor, int limit) {
        NativeQueryBuilder builder = NativeQuery.builder();
        if (logsOnly) {
            builder.withQuery(q -> q.bool(b -> {
                b.must(m -> m.term(t -> t.field("mediaType").value("DOCUMENT")));
                applyFilters(b, kind, date, hour);
                return b;
            }));
        }
        return page(builder, cursor, limit, NotificationDocument.class, this::toSummaryDoc);
    }

    /** Full-text search over log content with optional filters. */
    public NotificationPage searchLogs(String text, String kind, String date, String hour, String cursor, int limit) {
        NativeQueryBuilder builder = NativeQuery.builder();
        builder.withQuery(q -> q.bool(b -> {
            b.must(m -> m.match(mm -> mm.field("logContent").query(text)));
            applyFilters(b, kind, date, hour);
            return b;
        }));
        return page(builder, cursor, limit, LogDocument.class, this::toSummaryLog);
    }

    private void applyFilters(BoolQuery.Builder b, String kind, String date, String hour) {
        if (kind != null && !kind.isBlank()) {
            b.filter(f -> f.term(t -> t.field("kind").value(kind)));
        }
        if (date != null && !date.isBlank()) {
            b.filter(f -> f.term(t -> t.field("date").value(date)));
        }
        if (hour != null && !hour.isBlank()) {
            b.filter(f -> f.term(t -> t.field("hour").value(hour)));
        }
    }

    /** Returns all distinct log kinds (template names) for DOCUMENT notifications. */
    public List<String> distinctKinds() {
        try {
            var response = client.search(s -> s
                    .index("notifications")
                    .size(0)
                    .query(q -> q.term(t -> t.field("mediaType").value("DOCUMENT")))
                    .aggregations("kinds", a -> a.terms(t -> t.field("kind").size(1000))),
                    NotificationDocument.class);
            var terms = response.aggregations().get("kinds");
            if (terms != null && terms.isSterms()) {
                return terms.sterms().buckets().array().stream()
                        .map(b -> b.key().stringValue())
                        .sorted()
                        .toList();
            }
            return List.of();
        } catch (Exception e) {
            log.warn("Failed to fetch distinct kinds: {}", e.getMessage());
            return List.of();
        }
    }

    /** Finds a DOCUMENT notification by its Telegram fileId (1:1 for logs). */
    public Optional<NotificationSummary> findLogByFileId(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            return Optional.empty();
        }
        Query query = NativeQuery.builder()
                .withQuery(q -> q.bool(b -> b
                        .must(m -> m.term(t -> t.field("fileId").value(fileId)))
                        .must(m -> m.term(t -> t.field("mediaType").value("DOCUMENT")))))
                .withPageable(PageRequest.of(0, 1))
                .build();
        SearchHits<NotificationDocument> hits = operations.search(query, NotificationDocument.class);
        return hits.getSearchHits().stream()
                .findFirst()
                .map(h -> toSummaryDoc(h.getContent()));
    }

    private <T> NotificationPage page(NativeQueryBuilder builder, String cursor, int limit, Class<T> clazz,
                                      Function<T, NotificationSummary> mapper) {
        int capped = Math.max(1, Math.min(limit, 1000));
        builder.withSort(Sort.by(Sort.Order.desc("timestamp"), Sort.Order.desc("id")));
        applyCursor(builder, cursor);
        builder.withPageable(PageRequest.of(0, capped));
        Query query = builder.build();

        SearchHits<T> hits = operations.search(query, clazz);
        List<SearchHit<T>> hitList = hits.getSearchHits();
        List<NotificationSummary> items = new ArrayList<>(hitList.size());
        for (SearchHit<T> hit : hitList) {
            items.add(mapper.apply(hit.getContent()));
        }
        String nextCursor = nextCursor(hitList);
        boolean hasMore = hitList.size() == capped;
        return new NotificationPage(items, nextCursor, hasMore);
    }

    // ------------------------------------------------------------------
    // Retention (10-day TTL on log content)
    // ------------------------------------------------------------------

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void deleteExpiredLogs() {
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(LOG_TTL_DAYS);
        Query query = NativeQuery.builder()
                .withQuery(q -> q.range(r -> r.field("contentStoredAt").lt(co.elastic.clients.json.JsonData.of(cutoff))))
                .build();
        try {
            operations.delete(query, LogDocument.class);
        } catch (Exception e) {
            log.warn("Failed to delete expired logs: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Cursor + mapping helpers
    // ------------------------------------------------------------------

    private void applyCursor(NativeQueryBuilder builder, String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int idx = decoded.indexOf(':');
            if (idx <= 0) {
                return;
            }
            long ts = Long.parseLong(decoded.substring(0, idx));
            String id = decoded.substring(idx + 1);
            builder.withSearchAfter(List.of(ts, id));
        } catch (Exception ignored) {
            // invalid cursor -> treat as first page
        }
    }

    private <T> String nextCursor(List<SearchHit<T>> hitList) {
        if (hitList.isEmpty()) {
            return null;
        }
        SearchHit<T> last = hitList.get(hitList.size() - 1);
        List<Object> sv = last.getSortValues();
        if (sv == null || sv.size() < 2) {
            return null;
        }
        long ts = ((Number) sv.get(0)).longValue();
        String id = String.valueOf(sv.get(1));
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((ts + ":" + id).getBytes(StandardCharsets.UTF_8));
    }

    private NotificationDocument toDocument(NotificationSummary s) {
        return NotificationDocument.builder()
                .id(s.id())
                .sender(s.sender())
                .mediaType(s.mediaType())
                .kind(s.kind())
                .summary(s.summary())
                .fileId(s.fileId())
                .filename(s.filename())
                .sentAt(s.sentAt())
                .timestamp(s.timestamp())
                .date(s.date())
                .hour(s.hour())
                .size(s.size())
                .storageRef(null)
                .build();
    }

    private NotificationSummary toSummaryDoc(NotificationDocument d) {
        return new NotificationSummary(d.getId(), d.getSender(), d.getMediaType(), d.getKind(),
                d.getSummary(), d.getFileId(), d.getFilename(), d.getSentAt(),
                d.getTimestamp(), d.getDate(), d.getHour(), d.getSize());
    }

    private NotificationSummary toSummaryLog(LogDocument d) {
        return new NotificationSummary(d.getId(), d.getSender(), d.getMediaType(), d.getKind(),
                d.getSummary(), d.getFileId(), d.getFilename(), d.getSentAt(),
                d.getTimestamp(), d.getDate(), d.getHour(), d.getSize());
    }

    private String decode(byte[] payload) {
        byte[] capped = payload.length > MAX_LOG_CONTENT_BYTES
                ? Arrays.copyOf(payload, MAX_LOG_CONTENT_BYTES)
                : payload;
        return new String(capped, StandardCharsets.UTF_8);
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
