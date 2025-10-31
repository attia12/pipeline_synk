package fr.tictak.dema.dto.out;

public record TruckWithSubAdminDTO(
        String truckId,
        double capacity,
        String model,
        boolean active,
        String assuranceCamion,
        String carteGrise,
        String vignetteTaxe,
        String subAdminFirstName,
        String subAdminLastName,
        String subAdminEmail,
        String subAdminPhoneNumber,
        String subAdminCompanyName,
        String subAdminAssignedRegion
) {}