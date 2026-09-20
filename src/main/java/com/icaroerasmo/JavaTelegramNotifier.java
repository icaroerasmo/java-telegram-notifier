package com.icaroerasmo;

import com.icaroerasmo.properties.TelegramProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(TelegramProperties.class)
public class JavaTelegramNotifier {

    public static void main(String[] args) {
        SpringApplication.run(JavaTelegramNotifier.class, args);
    }
}
