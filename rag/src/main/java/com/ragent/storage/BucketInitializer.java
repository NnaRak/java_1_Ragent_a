package com.ragent.storage;

import com.ragent.config.StorageProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Component
public class BucketInitializer {

    private static final Logger log = LoggerFactory.getLogger(BucketInitializer.class);

    private final S3Client s3Client;
    private final StorageProperties properties;

    public BucketInitializer(S3Client s3Client, StorageProperties properties) {
        this.s3Client = s3Client;
        this.properties = properties;
    }

    @PostConstruct
    public void ensureBucket() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(properties.bucket()).build());
            log.info("对象存储桶已存在 bucket={}", properties.bucket());
        } catch (S3Exception ex) {
            if (ex.statusCode() != 404) {
                throw ex;
            }
            s3Client.createBucket(CreateBucketRequest.builder().bucket(properties.bucket()).build());
            log.info("对象存储桶创建成功 bucket={}", properties.bucket());
        }
    }
}
