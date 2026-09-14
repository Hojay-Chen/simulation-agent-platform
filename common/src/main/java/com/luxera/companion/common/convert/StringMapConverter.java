package com.luxera.companion.common.convert;

import com.fasterxml.jackson.core.type.TypeReference;
import com.luxera.companion.common.JsonCodec;

import javax.persistence.AttributeConverter;
import javax.persistence.Converter;
import java.util.HashMap;
import java.util.Map;

@Converter
public class StringMapConverter implements AttributeConverter<Map<String, Object>, String> {
    @Override
    public String convertToDatabaseColumn(Map<String, Object> attribute) {
        return JsonCodec.toJson(attribute);
    }

    @Override
    public Map<String, Object> convertToEntityAttribute(String dbData) {
        Map<String, Object> map = JsonCodec.fromJson(dbData, new TypeReference<Map<String, Object>>() {});
        return map != null ? map : new HashMap<>();
    }
}
