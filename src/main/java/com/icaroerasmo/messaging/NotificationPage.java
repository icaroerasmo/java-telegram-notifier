package com.icaroerasmo.messaging;

import java.util.List;

/**
 * A page of notification summaries plus an opaque cursor for the next page
 * (search_after pagination) and a flag signalling whether more pages exist.
 */
public record NotificationPage(List<NotificationSummary> items, String nextCursor, boolean hasMore) {
}
