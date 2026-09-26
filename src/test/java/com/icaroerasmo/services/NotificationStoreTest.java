package com.icaroerasmo.services;

import com.icaroerasmo.messaging.NotificationSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationStoreTest {

    @TempDir
    Path tempDir;

    private NotificationSummary summary(long ts, String id) {
        return new NotificationSummary(id, "recorder", "DOCUMENT", "log", "summary " + id,
                "file" + id, "log.txt", null, ts, "2026-09-25", "10", 1024);
    }

    @Test
    void getRecent_deveRetornarOsMaisRecentes() {
        NotificationStore store = new NotificationStore(tempDir.resolve("n.jsonl").toString());
        for (long i = 1; i <= 5; i++) {
            store.append(summary(i * 1000, "n" + i));
        }
        List<NotificationSummary> recent = store.getRecent(3);
        assertEquals(3, recent.size());
        assertEquals("n3", recent.get(0).id());
        assertEquals("n5", recent.get(2).id());
    }

    @Test
    void getBefore_deveRetornarItensMaisAntigosQueOTimestamp() {
        NotificationStore store = new NotificationStore(tempDir.resolve("n.jsonl").toString());
        for (long i = 1; i <= 5; i++) {
            store.append(summary(i * 1000, "n" + i));
        }
        List<NotificationSummary> before = store.getBefore(4000, 10);
        assertEquals(3, before.size());
        assertEquals("n3", before.get(0).id());
        assertEquals("n1", before.get(2).id());
    }

    @Test
    void getBefore_deveRespeitarOLimite() {
        NotificationStore store = new NotificationStore(tempDir.resolve("n.jsonl").toString());
        for (long i = 1; i <= 5; i++) {
            store.append(summary(i * 1000, "n" + i));
        }
        List<NotificationSummary> before = store.getBefore(5000, 2);
        assertEquals(2, before.size());
        assertEquals("n4", before.get(0).id());
        assertEquals("n3", before.get(1).id());
    }

    @Test
    void getBefore_semItensAntigos_deveRetornarVazio() {
        NotificationStore store = new NotificationStore(tempDir.resolve("n.jsonl").toString());
        store.append(summary(1000, "n1"));
        assertTrue(store.getBefore(1000, 10).isEmpty());
    }
}