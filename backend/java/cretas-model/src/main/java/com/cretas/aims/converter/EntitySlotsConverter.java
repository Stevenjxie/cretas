package com.cretas.aims.converter;

import com.cretas.aims.entity.conversation.ConversationMemory.EntitySlotData;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.HashMap;
import java.util.Map;

/**
 * JPA 转换器：Map<String, EntitySlotData> <-> JSON String
 *
 * 用于将实体槽位数据在数据库和 Java 对象之间转换
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-15
 */
@Slf4j
@Converter(autoApply = false)
public class EntitySlotsConverter implements AttributeConverter<Map<String, EntitySlotData>, String> {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(Map<String, EntitySlotData> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            log.error("Failed to convert EntitySlots to JSON: {}", e.getMessage(), e);
            return null;
        }
    }

    @Override
    public Map<String, EntitySlotData> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isEmpty()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(dbData, new TypeReference<Map<String, EntitySlotData>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to convert JSON to EntitySlots: {}", e.getMessage(), e);
            return new HashMap<>();
        }
    }
}
