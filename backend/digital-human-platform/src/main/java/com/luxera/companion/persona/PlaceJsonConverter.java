package com.luxera.companion.persona;

import com.luxera.companion.common.JsonCodec;
import com.luxera.companion.persona.Place;

import javax.persistence.AttributeConverter;
import javax.persistence.Converter;

@Converter
public class PlaceJsonConverter implements AttributeConverter<Place, String> {
    @Override
    public String convertToDatabaseColumn(Place attribute) {
        return JsonCodec.toJson(attribute);
    }

    @Override
    public Place convertToEntityAttribute(String dbData) {
        return JsonCodec.fromJson(dbData, Place.class);
    }
}
