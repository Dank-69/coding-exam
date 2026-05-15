package com.oncall;

import com.oncall.common.config.MoonshotProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableConfigurationProperties(MoonshotProperties.class)
public class OnCallApplication {
    public static void main(String[] args) {
        SpringApplication.run(OnCallApplication.class, args);
    }
}
