package fr.tictak.dema.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

@Data
@Document(collection = "itineraries")
public class Itinerary {
    @Id
    private String id;

    private String start;
    private String end;
    private List<String> cities;
    private List<Coordinate> coordinates;

    @Data
    public static class Coordinate {
        private double lat;
        private double lng;

        public Coordinate(double lat, double lng) {
            this.lat = lat;
            this.lng = lng;
        }
    }
}
