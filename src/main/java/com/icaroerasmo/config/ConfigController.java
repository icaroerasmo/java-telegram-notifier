package com.icaroerasmo.config;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final ConfigService configService;

    public ConfigController(ConfigService configService) {
        this.configService = configService;
    }

    @GetMapping
    public Map<String, Object> getConfig() {
        return configService.maskSecrets(configService.readConfig());
    }

    @PutMapping
    public ResponseEntity<Void> updateConfig(@RequestBody Map<String, Object> config) {
        Map<String, Object> current = configService.readConfig();
        Map<String, Object> merged = configService.restoreSecrets(config, current);
        configService.writeConfig(merged);

        new Thread(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            System.exit(0);
        }).start();

        return ResponseEntity.ok().build();
    }
}
