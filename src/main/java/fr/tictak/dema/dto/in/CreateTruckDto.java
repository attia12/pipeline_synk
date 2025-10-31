package fr.tictak.dema.dto.in;

public record CreateTruckDto(
        double capacity,
        String model,
        boolean active,
        String assuranceCamion,
        String carteGrise,
        String vignetteTaxe
) {
}