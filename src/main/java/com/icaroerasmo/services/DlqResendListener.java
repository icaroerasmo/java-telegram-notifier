package com.icaroerasmo.services;

import com.icaroerasmo.config.RabbitConfig;
import com.icaroerasmo.messaging.NotificationMessage;
import lombok.extern.log4j.Log4j2;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Log4j2
@Service
public class DlqResendListener {

    private static final long RESEND_DELAY_MS = 5 * 60 * 1000;

    private final RabbitTemplate rabbitTemplate;

    public DlqResendListener(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @RabbitListener(queues = RabbitConfig.TELEGRAM_NOTIFICATIONS_DLQ,
            containerFactory = "dlqRabbitListenerContainerFactory")
    public void resendDelayed(NotificationMessage message) throws InterruptedException {
        log.info("DLQ message received, resending in 5 minutes: messageId={}", message.messageId());
        Thread.sleep(RESEND_DELAY_MS);

        NotificationMessage delayed = new NotificationMessage(
                message.messageId(), message.sender(), message.mediaType(),
                message.template(), message.args(), message.rawHtml(),
                message.caption(), message.filename(), message.payload(),
                message.appendNoLogs(), message.sentAt(), true);

        rabbitTemplate.convertAndSend(RabbitConfig.TELEGRAM_EXCHANGE,
                RabbitConfig.TELEGRAM_NOTIFICATIONS_ROUTING_KEY, delayed);
        log.info("DLQ message resent: messageId={}", message.messageId());
    }
}