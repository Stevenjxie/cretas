package com.cretas.aims.converter;

import com.cretas.aims.entity.conversation.ConversationMemory.MessageData;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.ArrayList;
import java.util.List;

/**
 * JPA 转换器：List<MessageData> <-> JSON String
 *
 * 用于将消息列表在数据库和 Java 对象之间转换
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-15
 */
@Slf4j
@Converter(autoApply = false)
public class MessageListConverter implements AttributeConverter<List<MessageData>, String> {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(List<MessageData> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            log.error("Failed to convert MessageList to JSON: {}", e.getMessage(), e);
            return null;
        }
    }

    @Override
    public List<MessageData> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(dbData, new TypeReference<List<MessageData>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to convert JSON to MessageList: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }
}
