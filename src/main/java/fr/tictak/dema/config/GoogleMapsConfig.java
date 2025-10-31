package fr.tictak.dema.config;

import com.google.maps.GeoApiContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GoogleMapsConfig {

    @Value("${google.maps.api.key}")
    private String apiKey;

    @Bean
    public GeoApiContext geoApiContext() {
        return new GeoApiContext.Builder()
                .apiKey(apiKey)
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS) // default 10s
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)   // default 10s
                .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)  // default 10s
                .build();
    }
}
