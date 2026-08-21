package com.icaroerasmo.properties;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

@Configuration
@PropertySource(value = "classpath:/config.yaml", factory = YamlPropertySourceFactory.class)
public class ConfigYaml {}
