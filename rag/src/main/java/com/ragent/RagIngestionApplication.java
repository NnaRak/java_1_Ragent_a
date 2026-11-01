package com.ragent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RagIngestionApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagIngestionApplication.class, args);
    }
}
