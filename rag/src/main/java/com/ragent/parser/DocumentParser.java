package com.ragent.parser;

import com.ragent.parser.model.ParsedDocument;
import com.ragent.parser.model.RawDocument;

import java.util.Set;

/**
 * 文档解析 SPI：新增格式只需实现本接口并声明认领的后缀/MIME，
 * 注册表会在启动期自动建索引，主链路无需改动。
 */
public interface DocumentParser {

    String parserType();

    Set<String> supportedExtensions();

    Set<String> supportedMimeTypes();

    ParsedDocument parse(RawDocument document);
}
