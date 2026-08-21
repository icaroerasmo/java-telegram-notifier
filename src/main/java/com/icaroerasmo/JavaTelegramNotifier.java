package com.icaroerasmo;

import com.icaroerasmo.properties.NotifierProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(NotifierProperties.class)
public class JavaTelegramNotifier {

    public static void main(String[] args) {
        SpringApplication.run(JavaTelegramNotifier.class, args);
    }
}
