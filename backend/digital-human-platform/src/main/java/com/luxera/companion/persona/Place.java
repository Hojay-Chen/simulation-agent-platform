package com.luxera.companion.persona;

import lombok.Data;

/** 出生地 / 居住地结构 */
@Data
public class Place {
    private String country;
    private String province;
    private String city;

    public static Place of(String country, String province, String city) {
        Place p = new Place();
        p.setCountry(country);
        p.setProvince(province);
        p.setCity(city);
        return p;
    }
}
