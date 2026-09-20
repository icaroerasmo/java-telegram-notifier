package com.icaroerasmo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ConfigService {

    private static final Pattern URL_CREDENTIAL_PATTERN = Pattern.compile("^(\\w+://[^:/]+:)([^@]+)(@.*)$");

    @Value("${app.config-file-path:/app/config/config.yaml}")
    private String configFilePath;

    public Map<String, Object> readConfig() {
        try (FileReader reader = new FileReader(configFilePath)) {
            Yaml yaml = new Yaml();
            Map<String, Object> config = yaml.load(reader);
            return config != null ? config : new LinkedHashMap<>();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read config file: " + configFilePath, e);
        }
    }

    public void writeConfig(Map<String, Object> config) {
        try (FileWriter writer = new FileWriter(configFilePath)) {
            Yaml yaml = new Yaml();
            yaml.dump(config, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write config file: " + configFilePath, e);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> maskSecrets(Map<String, Object> config) {
        Map<String, Object> masked = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            masked.put(key, maskValue(key, value));
        }
        return masked;
    }

    @SuppressWarnings("unchecked")
    private Object maskValue(String key, Object value) {
        if (value instanceof Map) {
            return maskSecrets((Map<String, Object>) value);
        }
        if (value instanceof List) {
            return maskList((List<Object>) value);
        }
        if (isSecretKey(key)) {
            return "********";
        }
        if (value instanceof String strValue) {
            Matcher matcher = URL_CREDENTIAL_PATTERN.matcher(strValue);
            if (matcher.matches()) {
                return matcher.group(1) + "********" + matcher.group(3);
            }
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private List<Object> maskList(List<Object> list) {
        List<Object> masked = new java.util.ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map) {
                masked.add(maskSecrets((Map<String, Object>) item));
            } else {
                masked.add(item);
            }
        }
        return masked;
    }

    private boolean isSecretKey(String key) {
        String lower = key.toLowerCase();
        return lower.contains("password")
                || lower.contains("token")
                || lower.contains("secret")
                || lower.contains("credential")
                || lower.endsWith("key");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> restoreSecrets(Map<String, Object> incoming, Map<String, Object> current) {
        Map<String, Object> restored = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : incoming.entrySet()) {
            String key = entry.getKey();
            Object incomingValue = entry.getValue();
            Object currentValue = current.get(key);

            if (incomingValue instanceof Map && currentValue instanceof Map) {
                restored.put(key, restoreSecrets(
                        (Map<String, Object>) incomingValue,
                        (Map<String, Object>) currentValue));
            } else if (incomingValue instanceof List && currentValue instanceof List) {
                restored.put(key, restoreListSecrets((List<Object>) incomingValue, (List<Object>) currentValue));
            } else if ("********".equals(incomingValue) && currentValue != null) {
                restored.put(key, currentValue);
            } else if (incomingValue instanceof String strValue
                    && strValue.contains(":********@") && currentValue instanceof String) {
                restored.put(key, currentValue);
            } else {
                restored.put(key, incomingValue);
            }
        }
        return restored;
    }

    @SuppressWarnings("unchecked")
    private List<Object> restoreListSecrets(List<Object> incomingList, List<Object> currentList) {
        List<Object> restored = new java.util.ArrayList<>();
        for (int i = 0; i < incomingList.size(); i++) {
            Object incomingItem = incomingList.get(i);
            Object currentItem = i < currentList.size() ? currentList.get(i) : null;
            if (incomingItem instanceof Map && currentItem instanceof Map) {
                restored.add(restoreSecrets(
                        (Map<String, Object>) incomingItem,
                        (Map<String, Object>) currentItem));
            } else {
                restored.add(incomingItem);
            }
        }
        return restored;
    }
}
