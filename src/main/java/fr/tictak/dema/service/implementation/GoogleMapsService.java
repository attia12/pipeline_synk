package fr.tictak.dema.service.implementation;

import com.google.maps.DirectionsApi;
import com.google.maps.GeoApiContext;
import com.google.maps.GeocodingApi;
import com.google.maps.model.*;
import fr.tictak.dema.exception.ApiException;
import fr.tictak.dema.model.Itinerary;
import fr.tictak.dema.repository.ItineraryRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleMapsService {

    @Value("${google.maps.api.key}")
    private String apiKey;

    private final RestTemplate restTemplate;
    private final ItineraryRepository repository;
    private final GeoApiContext context;


    public List<String> getAddressSuggestions(String query) {
        if (query == null || query.trim().isEmpty()) {
            log.warn("Empty or null query provided for address suggestions.");
            return Collections.emptyList();
        }

        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = UriComponentsBuilder
                    .fromUriString("https://maps.googleapis.com/maps/api/place/autocomplete/json")
                    .queryParam("input", encodedQuery)
                    .queryParam("key", apiKey)
                    .toUriString();

            log.debug("Fetching address suggestions for query: {}", query);
            AutocompleteResponse response = restTemplate.getForObject(url, AutocompleteResponse.class);

            if (response != null && "OK".equals(response.getStatus())) {
                List<String> suggestions = response.getPredictions().stream()
                        .map(Prediction::getDescription)
                        .collect(Collectors.toList());
                log.debug("Found {} suggestions for query: {}", suggestions.size(), query);
                return suggestions;
            }

            log.warn("No suggestions found for query: {}. Status: {}", query, response != null ? response.getStatus() : "null");
            return Collections.emptyList();
        } catch (RestClientException e) {
            log.error("Failed to fetch suggestions for query: {}. Error: {}", query, e.getMessage());
            throw new RuntimeException("Unable to fetch address suggestions: " + e.getMessage(), e);
        }
    }

    public double getDistance(String origin, String destination, String clientEmail) {
        if (origin == null || origin.trim().isEmpty() || destination == null || destination.trim().isEmpty()) {
            log.warn("Invalid origin or  destination: origin={}, destination={} ", origin, destination);
            throw new IllegalArgumentException("Origin and destination must not be null or empty.");
        }

        try {
            String encodedOrigin = URLEncoder.encode(origin, StandardCharsets.UTF_8);
            String encodedDestination = URLEncoder.encode(destination, StandardCharsets.UTF_8);
            String url = UriComponentsBuilder
                    .fromUriString("https://maps.googleapis.com/maps/api/directions/json")
                    .queryParam("origin", encodedOrigin)
                    .queryParam("destination", encodedDestination)
                    .queryParam("key", apiKey)
                    .queryParam("mode", "driving")
                    .queryParam("alternatives", "true")
                    .toUriString();

            log.debug("Calculating distance between origin: {} and destination: {}", origin, destination);
            DirectionsResponse response = restTemplate.getForObject(url, DirectionsResponse.class);

            if (response == null) {
                log.error("Null response from Directions API for origin: {}, destination: {}", origin, destination);
                throw new IllegalArgumentException("Unable to calculate distance: API returned null response.");
            }

            if ("OK".equals(response.getStatus()) && !response.getRoutes().isEmpty()) {
                Route firstRoute = response.getRoutes().getFirst();
                if (!firstRoute.getLegs().isEmpty()) {
                    double distanceKm = firstRoute.getLegs().getFirst().getDistance().getValue() / 1000.0;
                    log.debug("Distance between {} and {}: {} km", origin, destination, distanceKm);
                    return distanceKm;
                }
            }

            log.error("Failed to calculate distance for origin: {}, destination: {}. Status: {}",
                    origin, destination, response.getStatus());
            throw new IllegalArgumentException("Unable to calculate distance: " + response.getStatus());
        } catch (RestClientException e) {
            log.error("API call  failed for origin: {}, destination: {}. Error: {}",
                    origin, destination, e.getMessage());
            throw new RuntimeException("Unable to calculate distance: " + e.getMessage(), e);
        }
    }

    public int getDurationInMinutes(String origin, String destination, String clientEmail) {
        if (origin == null || origin.trim().isEmpty() || destination == null || destination.trim().isEmpty()) {
            log.warn("Invalid origin or destination: origin={}, destination={}", origin, destination);
            throw new IllegalArgumentException("Origin and destination must not be null or empty.");
        }

        try {
            String encodedOrigin = URLEncoder.encode(origin, StandardCharsets.UTF_8);
            String encodedDestination = URLEncoder.encode(destination, StandardCharsets.UTF_8);
            String url = UriComponentsBuilder
                    .fromUriString("https://maps.googleapis.com/maps/api/directions/json")
                    .queryParam("origin", encodedOrigin)
                    .queryParam("destination", encodedDestination)
                    .queryParam("key", apiKey)
                    .queryParam("mode", "driving")
                    .toUriString();

            log.debug("Calculating duration between origin: {} and destination: {}", origin, destination);
            DirectionsResponse response = restTemplate.getForObject(url, DirectionsResponse.class);

            if (response != null && "OK".equals(response.getStatus()) && !response.getRoutes().isEmpty()) {
                Route firstRoute = response.getRoutes().getFirst();
                if (!firstRoute.getLegs().isEmpty()) {
                    int durationSeconds = firstRoute.getLegs().getFirst().getDuration().getValue();
                    int durationMinutes = durationSeconds / 60; // Convert seconds to minutes
                    log.debug("Duration between {} and {}: {} minutes", origin, destination, durationMinutes);
                    return durationMinutes;
                }
            }

            assert response != null;
            log.error("Failed to calculate duration for origin: {}, destination: {}. Status: {}",
                    origin, destination, response.getStatus());
            throw new IllegalArgumentException("Unable to calculate duration: " + response.getStatus());
        } catch (RestClientException e) {
            log.error("API call failed for origin: {}, destination: {}. Error: {}",
                    origin, destination, e.getMessage());
            throw new RuntimeException("Unable to calculate duration: " + e.getMessage(), e);
        }
    }

    public GeoJsonPoint getCoordinates(String address, String email) {
        if (address == null || address.trim().isEmpty()) {
            log.warn("Empty or null address provided for geocoding.");
            throw new IllegalArgumentException("Address must not be null or empty.");
        }

        try {
            String encodedAddress = URLEncoder.encode(address, StandardCharsets.UTF_8);
            String url = UriComponentsBuilder
                    .fromUriString("https://maps.googleapis.com/maps/api/geocode/json")
                    .queryParam("address", encodedAddress)
                    .queryParam("key", apiKey)
                    .toUriString();

            log.debug("Fetching coordinates for address: {}", address);
            GeocodeResponse response = restTemplate.getForObject(url, GeocodeResponse.class);

            if (response != null && "OK".equals(response.getStatus()) && !response.getResults().isEmpty()) {
                GeoLocation geoLocation = response.getResults().getFirst().getGeometry().getLocation();
                GeoJsonPoint location = new GeoJsonPoint(geoLocation.getLng(), geoLocation.getLat());
                log.debug("Coordinates for '{}': lat={}, lng={}", address, geoLocation.getLat(), geoLocation.getLng());
                return location;
            }

            log.warn("No coordinates found for address: {}. Status: {}",
                    address, response != null ? response.getStatus() : "null");
            throw new IllegalArgumentException("Unable to geocode address: " + address);
        } catch (RestClientException e) {
            log.error("Failed to fetch coordinates for address: {}. Error: {}", address, e.getMessage());
            throw new RuntimeException("Unable to fetch coordinates: " + e.getMessage(), e);
        }
    }

    public List<Double> getDistances(String originCoords, List<String> destinationCoords, String clientEmail) {
        if (destinationCoords.isEmpty()) {
            return new ArrayList<>();
        }

        String destinationsStr = String.join("|", destinationCoords);
        String url = "https://maps.googleapis.com/maps/api/distancematrix/json?origins=" + originCoords +
                "&destinations=" + destinationsStr + "&mode=driving&key=" + apiKey;

        DistanceMatrixResponse response = restTemplate.getForObject(url, DistanceMatrixResponse.class);

        if ( response == null || !"OK".equals(response.getStatus())) {
            throw new RuntimeException("Invalid response from Google Maps API");
        }
        if (response.getRows().isEmpty()) {
            throw new RuntimeException("No rows in the response");
        }

        Row row = response.getRows().getFirst();
        List<Element> elements = row.getElements();
        List<Double> distances = new ArrayList<>();
        for (Element element : elements) {
            if ("OK".equals(element.getStatus()) && element.getDistance() != null) {
                distances.add((double) element.getDistance().getValue());
            } else {
                distances.add(Double.MAX_VALUE);
            }
        }

        return distances;
    }


    public List<Itinerary> getItineraries(String start, String end) throws IOException, InterruptedException, ApiException, com.google.maps.errors.ApiException, ExecutionException {
        DirectionsResult result = DirectionsApi.getDirections(context, start, end)
                .mode(TravelMode.DRIVING)
                .alternatives(true)
                .await();

        List<Itinerary> itineraries = new ArrayList<>();

        for (DirectionsRoute route : result.routes) {
            List<LatLng> sampledPoints = sampleRoute(route);

            // Get a name for each coordinate
            List<String> names = getNamesForCoordinates(sampledPoints);

            Itinerary itinerary = new Itinerary();
            itinerary.setStart(start);
            itinerary.setEnd(end);
            List<String> cities = new ArrayList<>();
            cities.add(start);
            cities.addAll(names);
            cities.add(end);
            itinerary.setCities(cities);

            List<Itinerary.Coordinate> coordinates = sampledPoints.stream()
                    .map(p -> new Itinerary.Coordinate(p.lat, p.lng))
                    .toList();
            itinerary.setCoordinates(coordinates);

            repository.save(itinerary);
            itineraries.add(itinerary);
        }

        return itineraries;
    }

    // Sample points along route every ~5km
    private List<LatLng> sampleRoute(DirectionsRoute route) {
        List<LatLng> points = new ArrayList<>();
        double distanceThreshold = 10000;
        double accumulated = 0.0;

        for (DirectionsLeg leg : route.legs) {
            for (DirectionsStep step : leg.steps) {
                double stepDistance = step.distance.inMeters;

                if (accumulated + stepDistance >= distanceThreshold) {
                    List<LatLng> decoded = step.polyline.decodePath();
                    if (!decoded.isEmpty()) {
                        points.add(decoded.get(decoded.size() / 2));
                    }
                    accumulated = 0;
                } else {
                    accumulated += stepDistance;
                }
            }
        }
        return points;
    }

    // Returns a name for each coordinate
// Returns only the city name for each coordinate
    private List<String> getNamesForCoordinates(List<LatLng> points) throws InterruptedException, ExecutionException {
        ExecutorService executor = Executors.newFixedThreadPool(5); // parallel requests
        List<CompletableFuture<String>> futures = points.stream()
                .map(point -> CompletableFuture.supplyAsync(() -> {
                    try {
                        GeocodingResult[] results = GeocodingApi.reverseGeocode(context, point).await();
                        if (results.length > 0) {
                            for (AddressComponent component : results[0].addressComponents) {
                                for (AddressComponentType type : component.types) {
                                    if (type == AddressComponentType.LOCALITY) {
                                        return component.longName; // City
                                    }
                                    if (type == AddressComponentType.ADMINISTRATIVE_AREA_LEVEL_2) {
                                        return component.longName; // Fallback (county)
                                    }
                                }
                            }
                            // fallback: if no locality found, return first part of formatted address
                            return results[0].formattedAddress.split(",")[0];
                        }
                    } catch (Exception e) {
                        System.err.println("❌ Reverse geocode failed for " + point + ": " + e.getMessage());
                    }
                    return "";
                }, executor))
                .toList();

        List<String> names = new ArrayList<>();
        for (CompletableFuture<String> future : futures) {
            names.add(future.get()); // wait for each to finish
        }
        executor.shutdown();
        return names;
    }


    @Setter
    @Getter
    static class AutocompleteResponse {
        private String status;
        private List<Prediction> predictions;
    }

    @Setter
    @Getter
    static class Prediction {
        private String description;
    }

    @Setter
    @Getter
    static class DirectionsResponse {
        private String status;
        private List<Route> routes;
    }

    @Setter
    @Getter
    static class Route {
        private List<Leg> legs;
    }

    @Setter
    @Getter
    static class Leg {
        private Distance distance;
        private Duration duration;
    }

    @Setter
    @Getter
    static class Distance {
        private long value; // Distance in meters
    }

    @Setter
    @Getter
    static class Duration {
        private int value; // Duration in seconds
    }

    @Setter
    @Getter
    static class GeocodeResponse {
        private String status;
        private List<GeocodeResult> results;
    }

    @Setter
    @Getter
    static class GeocodeResult {
        private Geometry geometry;
    }

    @Setter
    @Getter
    static class Geometry {
        private GeoLocation location;
    }

    @Setter
    @Getter
    static class GeoLocation {
        private double lat;
        private double lng;
    }

    @Setter
    @Getter
    static class DistanceMatrixResponse {
        private String status;
        private List<Row> rows;
    }

    @Setter
    @Getter
    static class Row {
        private List<Element> elements;
    }

    @Setter
    @Getter
    static class Element {
        private Distance distance;
        private Duration duration;
        private String status;
    }
}