package com.icaroerasmo.messaging;

import java.util.List;

public record NotificationSummary(
        String id,
        String sender,
        String mediaType,
        String kind,
        String summary,
        String fileId,
        String filename,
        String sentAt,
        long timestamp,
        String date,
        String hour,
        long size,
        List<String> personNames,
        boolean browserNotify) {
}
