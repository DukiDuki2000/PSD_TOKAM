package org.example.models;

import com.fasterxml.jackson.annotation.JsonProperty;

public class Location {
    @JsonProperty("latitude")
    public double latitude;

    @JsonProperty("longitude")
    public double longitude;

    @JsonProperty("city")
    public String city;

    @JsonProperty("country")
    public String country;

    @JsonProperty("country_code")
    public String countryCode;

    public Location() {}

    public Location(double latitude, double longitude, String city, String country, String countryCode) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.city = city;
        this.country = country;
        this.countryCode = countryCode;
    }


}
