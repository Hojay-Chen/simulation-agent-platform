package com.luxera.companion.common.convert;

import com.fasterxml.jackson.core.type.TypeReference;
import com.luxera.companion.common.JsonCodec;

import javax.persistence.AttributeConverter;
import javax.persistence.Converter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 通用 List<Object> 转换(evidence/metadata 等) */
@Converter
public class ObjectListConverter implements AttributeConverter<List<Object>, String> {
    @Override
    public String convertToDatabaseColumn(List<Object> attribute) {
        return JsonCodec.toJson(attribute);
    }

    @Override
    public List<Object> convertToEntityAttribute(String dbData) {
        List<Object> list = JsonCodec.fromJson(dbData, new TypeReference<List<Object>>() {});
        return list != null ? list : new ArrayList<>();
    }
}
