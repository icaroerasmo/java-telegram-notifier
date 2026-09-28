package com.icaroerasmo.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.icaroerasmo.messaging.LogDocument;
import com.icaroerasmo.messaging.NotificationDocument;
import com.icaroerasmo.messaging.NotificationPage;
import com.icaroerasmo.messaging.NotificationSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Query;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationSearchServiceTest {

    private ElasticsearchOperations operations;
    private ElasticsearchClient client;
    private NotificationSearchService service;

    @BeforeEach
    void setUp() {
        operations = mock(ElasticsearchOperations.class);
        client = mock(ElasticsearchClient.class);
        service = new NotificationSearchService(operations, client, 10);
    }

    private NotificationSummary summary(String id, String mediaType) {
        return new NotificationSummary(id, "recorder", mediaType, "TEMPLATE", "summary " + id,
                "file" + id, "log.txt", null, 1000L, "2026-09-28", "10", 1024);
    }

    @Test
    void index_savesNotificationDocumentWithMappedFields() {
        service.index(summary("n1", "TEXT"));

        ArgumentCaptor<NotificationDocument> captor = ArgumentCaptor.forClass(NotificationDocument.class);
        verify(operations).save(captor.capture());
        NotificationDocument doc = captor.getValue();
        assertEquals("n1", doc.getId());
        assertEquals("TEXT", doc.getMediaType());
        assertEquals("recorder", doc.getSender());
        assertEquals(1024, doc.getSize());
        assertNull(doc.getStorageRef());
    }

    @Test
    void indexLog_savesDecodedLogContentForDocument() {
        byte[] payload = "linha de log\nteste".getBytes(StandardCharsets.UTF_8);

        service.indexLog(summary("n1", "DOCUMENT"), payload);

        ArgumentCaptor<LogDocument> captor = ArgumentCaptor.forClass(LogDocument.class);
        verify(operations).save(captor.capture());
        LogDocument doc = captor.getValue();
        assertEquals("n1", doc.getId());
        assertEquals("linha de log\nteste", doc.getLogContent());
        assertEquals("log.txt", doc.getFilename());
        assertTrue(doc.getContentStoredAt() > 0);
    }

    @Test
    void indexLog_skipsNonDocument() {
        service.indexLog(summary("n1", "PHOTO"), "x".getBytes(StandardCharsets.UTF_8));
        verify(operations, never()).save(any(LogDocument.class));
    }

    @Test
    void migrateFromJsonl_readsFileAndSavesEachLine(@TempDir Path tempDir) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path file = tempDir.resolve("notifications.jsonl");
        Files.writeString(file,
                mapper.writeValueAsString(summary("a", "TEXT")) + "\n"
                        + mapper.writeValueAsString(summary("b", "DOCUMENT")) + "\n");

        int count = service.migrateFromJsonl(file);

        assertEquals(2, count);
        verify(operations).bulkIndex(anyList(), eq(NotificationDocument.class));
    }

    @Test
    void list_mapsHitsAndReturnsCursor() {
        NotificationDocument doc = NotificationDocument.builder()
                .id("n1").sender("recorder").mediaType("TEXT").kind("T")
                .summary("s").fileId("f").filename(null).sentAt(null)
                .timestamp(1000L).date("2026-09-28").hour("10").size(1)
                .storageRef(null).build();
        SearchHit<NotificationDocument> hit = mock(SearchHit.class);
        when(hit.getContent()).thenReturn(doc);
        when(hit.getSortValues()).thenReturn(List.of(1000L, "n1"));
        SearchHits<NotificationDocument> hits = mock(SearchHits.class);
        when(hits.getSearchHits()).thenReturn(List.of(hit));
        doReturn(hits).when(operations).search(any(Query.class), eq(NotificationDocument.class));

        NotificationPage page = service.list(false, null, null, null, null, 100);

        assertEquals(1, page.items().size());
        assertEquals("n1", page.items().get(0).id());
        assertNotNull(page.nextCursor());
    }

    @Test
    void searchLogs_mapsHitsAndReturnsCursor() {
        LogDocument doc = LogDocument.builder()
                .id("n1").sender("recorder").mediaType("DOCUMENT").kind("T")
                .summary("s").fileId("f").filename("log.txt").sentAt(null)
                .timestamp(1000L).date("2026-09-28").hour("10").size(1)
                .logContent("sincronizacao finalizada").contentStoredAt(1000L).build();
        SearchHit<LogDocument> hit = mock(SearchHit.class);
        when(hit.getContent()).thenReturn(doc);
        when(hit.getSortValues()).thenReturn(List.of(1000L, "n1"));
        SearchHits<LogDocument> hits = mock(SearchHits.class);
        when(hits.getSearchHits()).thenReturn(List.of(hit));
        doReturn(hits).when(operations).search(any(Query.class), eq(LogDocument.class));

        NotificationPage page = service.searchLogs("sincronizacao", "T", null, null, null, 100);

        assertEquals(1, page.items().size());
        assertEquals("n1", page.items().get(0).id());
        assertNotNull(page.nextCursor());
    }
}
