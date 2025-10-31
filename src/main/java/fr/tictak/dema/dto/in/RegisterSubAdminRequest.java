package fr.tictak.dema.dto.in;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;

public record RegisterSubAdminRequest(
        @NotBlank String email,
        @NotBlank String firstName,
        @NotBlank String lastName,
        @NotBlank String companyName,
        String phoneNumber,
        boolean contractSigned,
        String assignedRegion,
        Integer numberOfTrucks,
        List<String> truckTypes,
        String contractDuration,
        String critAirType,
        @NotEmpty Map<String, String> legalDocuments
) {
}