package org.example.models;

import com.fasterxml.jackson.annotation.JsonProperty;

public class Location {
    public double latitude;
    public double longitude;
    public String city;
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

    @Override
    public String toString() {
        return String.format("Location{city='%s', country='%s', lat=%.4f, lng=%.4f}",
                city, country, latitude, longitude);
    }
}
