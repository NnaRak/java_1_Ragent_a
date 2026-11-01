package com.ragent.storage;

public interface ObjectStorage {

    void put(String key, byte[] content, String contentType);

    boolean exists(String key);
}
