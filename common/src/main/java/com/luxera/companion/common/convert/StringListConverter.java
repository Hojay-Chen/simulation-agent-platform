package com.luxera.companion.common.convert;

import com.fasterxml.jackson.core.type.TypeReference;
import com.luxera.companion.common.JsonCodec;

import javax.persistence.AttributeConverter;
import javax.persistence.Converter;
import java.util.ArrayList;
import java.util.List;

@Converter
public class StringListConverter implements AttributeConverter<List<String>, String> {
    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        return JsonCodec.toJson(attribute);
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        List<String> list = JsonCodec.fromJson(dbData, new TypeReference<List<String>>() {});
        return list != null ? list : new ArrayList<>();
    }
}
