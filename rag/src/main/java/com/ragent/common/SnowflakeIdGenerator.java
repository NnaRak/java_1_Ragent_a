package com.ragent.common;

import org.springframework.stereotype.Component;

/**
 * 雪花 ID，满足 t_rag_knowledge_* 表 VARCHAR(20) 的主键约束。
 */
@Component
public class SnowflakeIdGenerator {

    private static final long EPOCH = 1704067200000L;
    private static final long WORKER_ID_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long MAX_SEQUENCE = ~(-1L << SEQUENCE_BITS);
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS + 5L;
    private static final long TIMESTAMP_SHIFT = WORKER_ID_SHIFT + WORKER_ID_BITS;

    private final long workerId;
    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeIdGenerator() {
        this.workerId = Math.floorMod(ProcessHandle.current().pid(), MAX_WORKER_ID + 1);
    }

    public synchronized String nextId() {
        long timestamp = System.currentTimeMillis();
        if (timestamp < lastTimestamp) {
            timestamp = lastTimestamp;
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                timestamp = waitNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;
        long id = ((timestamp - EPOCH) << TIMESTAMP_SHIFT) | (workerId << WORKER_ID_SHIFT) | sequence;
        return Long.toString(id);
    }

    private long waitNextMillis(long current) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= current) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }
}
