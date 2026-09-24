package com.icaroerasmo.services;

import com.icaroerasmo.messaging.NotificationSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/**
 * Publishes summarized notifications to the dashboard exchange so the web
 * dashboard can render a feed and trigger browser notifications.
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class NotificationSummaryPublisher {

    private static final String DASHBOARD_EXCHANGE = "dashboard.exchange";
    private static final String DASHBOARD_ROUTING_KEY = "dashboard.notifications";

    private final RabbitTemplate rabbitTemplate;

    public void publish(NotificationSummary summary) {
        try {
            rabbitTemplate.convertAndSend(DASHBOARD_EXCHANGE, DASHBOARD_ROUTING_KEY, summary);
            log.debug("Published notification summary to dashboard: id={}", summary.id());
        } catch (Exception e) {
            log.warn("Failed to publish notification summary to dashboard: {}", e.getMessage());
        }
    }
}
