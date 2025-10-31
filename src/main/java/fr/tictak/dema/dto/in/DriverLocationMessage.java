package fr.tictak.dema.dto.in;

public record DriverLocationMessage(
        String driverId,
        double latitude,
        double longitude,
        String status) {

}