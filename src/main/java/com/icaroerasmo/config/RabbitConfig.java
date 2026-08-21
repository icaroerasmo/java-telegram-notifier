package com.icaroerasmo.config;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;

@Configuration
@EnableRabbit
public class RabbitConfig {

    public static final String TELEGRAM_EXCHANGE = "telegram.exchange";
    public static final String TELEGRAM_NOTIFICATIONS_QUEUE = "telegram.notifications";
    public static final String TELEGRAM_NOTIFICATIONS_ROUTING_KEY = "telegram.notifications";
    public static final String TELEGRAM_DLX_EXCHANGE = "telegram.dlx";
    public static final String TELEGRAM_NOTIFICATIONS_DLQ = "telegram.notifications.dlq";

    @Bean
    public DirectExchange telegramExchange() {
        return new DirectExchange(TELEGRAM_EXCHANGE);
    }

    @Bean
    public Queue telegramNotificationsQueue() {
        return QueueBuilder.durable(TELEGRAM_NOTIFICATIONS_QUEUE)
                .withArgument("x-dead-letter-exchange", TELEGRAM_DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", TELEGRAM_NOTIFICATIONS_DLQ)
                .build();
    }

    @Bean
    public Binding telegramNotificationsBinding(Queue telegramNotificationsQueue, DirectExchange telegramExchange) {
        return BindingBuilder.bind(telegramNotificationsQueue).to(telegramExchange).with(TELEGRAM_NOTIFICATIONS_ROUTING_KEY);
    }

    @Bean
    public DirectExchange telegramDlxExchange() {
        return new DirectExchange(TELEGRAM_DLX_EXCHANGE);
    }

    @Bean
    public Queue telegramNotificationsDlq() {
        return new Queue(TELEGRAM_NOTIFICATIONS_DLQ, true);
    }

    @Bean
    public Binding telegramNotificationsDlqBinding(Queue telegramNotificationsDlq, DirectExchange telegramDlxExchange) {
        return BindingBuilder.bind(telegramNotificationsDlq).to(telegramDlxExchange).with(TELEGRAM_NOTIFICATIONS_DLQ);
    }

    @Bean
    public Jackson2JsonMessageConverter jackson2JsonMessageConverter() {
        return new Jackson2JsonMessageConverter("com.icaroerasmo");
    }

    @Bean
    public RetryOperationsInterceptor retryOperationsInterceptor() {
        return RetryInterceptorBuilder.stateless()
                .maxAttempts(3)
                .backOffOptions(1000, 2.0, 8000)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();
    }

    @Bean(name = "rabbitListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter messageConverter,
            RetryOperationsInterceptor retryOperationsInterceptor) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        factory.setAdviceChain(retryOperationsInterceptor);
        return factory;
    }
}
