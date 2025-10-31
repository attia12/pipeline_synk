package fr.tictak.dema.dto.in;

import fr.tictak.dema.model.ItemQuantity;
import fr.tictak.dema.model.enums.QuotationType;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

public record QuoteCalculationRequest(
        String sourceAddress,
        String destinationAddress,
        int sourceFloors,
        boolean sourceElevator,
        int destinationFloors,
        boolean destinationElevator,
        List<ItemQuantity> items,
        QuotationType mode,
        String plannedDate,  // Date in "dd/MM/yyyy" format
        String plannedTime,   // Time in "hh:mm" format
        String stopover,
        String clientAddressPoint,
        String destinationStopover,
        String clientDestinationPoint

) {
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * Converts plannedDate and plannedTime to a LocalDateTime object.
     * @return LocalDateTime combining plannedDate and plannedTime, or null if both are null.
     * @throws IllegalArgumentException if the date or time format is invalid.
     */
    public LocalDateTime getPlannedDateTime() {
        if (plannedDate == null || plannedTime == null) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(plannedDate, DATE_FORMATTER);
            LocalTime time = LocalTime.parse(plannedTime, TIME_FORMATTER);
            return LocalDateTime.of(date, time);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date or time format. Expected 'dd/MM/yyyy' for date and 'hh:mm' for time.", e);
        }
    }



}