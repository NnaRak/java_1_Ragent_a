package com.ragent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "rag.storage")
public record StorageProperties(
        String endpoint,
        String accessKey,
        String secretKey,
        String region,
        String bucket,
        boolean pathStyle
) {

    public StorageProperties {
        endpoint = StringUtils.hasText(endpoint) ? endpoint.trim() : "http://localhost:9000";
        accessKey = StringUtils.hasText(accessKey) ? accessKey.trim() : "rustfsadmin";
        secretKey = StringUtils.hasText(secretKey) ? secretKey.trim() : "rustfsadmin";
        region = StringUtils.hasText(region) ? region.trim() : "us-east-1";
        bucket = StringUtils.hasText(bucket) ? bucket.trim() : "ragent-sources";
    }
}
