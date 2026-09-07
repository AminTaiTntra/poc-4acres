package io.fouracres.dto;

import java.util.List;

public record WeatherData(
    double tempC,
    String condition,
    String conditionIconUrl,
    double windKph,
    int    humidity,
    double uvIndex,
    List<ForecastDay> forecast
) {
    public record ForecastDay(
        String date,
        double maxTempC,
        double minTempC,
        String condition
    ) {}
}
