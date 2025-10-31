package fr.tictak.dema.dto.in;

public record MissionStatusResponse(
        String sourceAddress,
        String destinationAddress,
        Double preCommissionCost,
        String status
) {}