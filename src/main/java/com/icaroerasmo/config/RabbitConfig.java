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
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
    public static final String DASHBOARD_EXCHANGE = "dashboard.exchange";
    public static final String DASHBOARD_NOTIFICATIONS_QUEUE = "dashboard.notifications";
    public static final String DASHBOARD_NOTIFICATIONS_ROUTING_KEY = "dashboard.notifications";

    @Bean
    public DirectExchange telegramExchange() {
        return new DirectExchange(TELEGRAM_EXCHANGE);
    }

    @Bean
    public DirectExchange dashboardExchange() {
        return new DirectExchange(DASHBOARD_EXCHANGE);
    }

    @Bean
    public Queue dashboardNotificationsQueue() {
        return QueueBuilder.durable(DASHBOARD_NOTIFICATIONS_QUEUE).build();
    }

    @Bean
    public Binding dashboardNotificationsBinding(Queue dashboardNotificationsQueue, DirectExchange dashboardExchange) {
        return BindingBuilder.bind(dashboardNotificationsQueue).to(dashboardExchange).with(DASHBOARD_NOTIFICATIONS_ROUTING_KEY);
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
        return QueueBuilder.durable(TELEGRAM_NOTIFICATIONS_DLQ)
                .withArgument("x-message-ttl", 259200000)
                .build();
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
        factory.setConcurrentConsumers(4);
        factory.setMaxConcurrentConsumers(8);
        factory.setPrefetchCount(1);
        factory.setAdviceChain(retryOperationsInterceptor);
        return factory;
    }

    @Bean(name = "dlqRabbitListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory dlqRabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter messageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(2);
        return factory;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         Jackson2JsonMessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        template.setMandatory(true);
        return template;
    }
}
