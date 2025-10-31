package fr.tictak.dema.dto.out;

import fr.tictak.dema.model.ItemQuantity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record MoveDetails(
        double distance,
        int durationMinutes,
        LocalDate date,
        LocalDateTime departureTime,
        LocalDateTime arrivalTime,
        List<ItemQuantity> items,
        String status,
        String sourceAddress,
        String destinationAddress
) {}