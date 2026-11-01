package com.ragent.common;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

public final class JsonSupport {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private JsonSupport() {
    }

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("JSON 序列化失败", ex);
        }
    }
}
