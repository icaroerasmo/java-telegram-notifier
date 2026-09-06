package com.icaroerasmo.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RabbitMqPropertyMapperTest {

    @Test
    void mapsRabbitMqPrefixToSpringRabbitmq() {
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test-config", Map.of(
                "rabbitMq.host", "myhost",
                "rabbitMq.port", "5673",
                "rabbitMq.username", "myuser",
                "rabbitMq.publisher-confirm-type", "correlated")));

        new RabbitMqPropertyMapper().postProcessEnvironment(environment, null);

        assertEquals("myhost", environment.getProperty("spring.rabbitmq.host"));
        assertEquals("5673", environment.getProperty("spring.rabbitmq.port"));
        assertEquals("myuser", environment.getProperty("spring.rabbitmq.username"));
        assertEquals("correlated", environment.getProperty("spring.rabbitmq.publisher-confirm-type"));
    }

    @Test
    void doesNothingWhenRabbitMqPrefixAbsent() {
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test-config", Map.of(
                "spring.rabbitmq.host", "existing")));

        new RabbitMqPropertyMapper().postProcessEnvironment(environment, null);

        assertEquals("existing", environment.getProperty("spring.rabbitmq.host"));
        assertNull(environment.getProperty("spring.rabbitmq.port"));
    }
}