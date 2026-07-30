package com.cretas.aims.util.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * JPA 属性转换器: List<String> <-> JSON String
 *
 * 用于将 Java List<String> 转换为数据库 JSON 列存储。
 *
 * 使用示例:
 * <pre>
 * @Column(name = "keywords", columnDefinition = "json")
 * @Convert(converter = StringListConverter.class)
 * private List<String> keywords;
 * </pre>
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-15
 */
@Slf4j
@Converter
public class StringListConverter implements AttributeConverter<List<String>, String> {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<List<String>>() {};

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize List<String> to JSON: {}", attribute, e);
            return "[]";
        }
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.trim().isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(dbData, LIST_TYPE);
        } catch (IOException e) {
            log.error("Failed to deserialize JSON to List<String>: {}", dbData, e);
            return new ArrayList<>();
        }
    }
}
