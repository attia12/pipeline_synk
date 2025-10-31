package fr.tictak.dema.dto.in;

import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import fr.tictak.dema.model.enums.DocumentType;
import fr.tictak.dema.model.user.Driver;

public record DriverDTO(
        String id,
        String email,
        String firstName,
        String lastName,
        String phoneNumber,
        String driverLicenseNumber,
        String driverSince,
        List<String> documentTypes,
        Map<String, String> documentUrls,
        String camion,
        Date driverLicenseExpiration,
        double averageRating
) {
    // Factory method to create DTO from Driver entity, handling conversions
    public static DriverDTO fromEntity(Driver driver) {
        if (driver == null) {
            return null;
        }

        // Convert Date to ISO string (e.g., "2023-01-01"); adjust format if needed
        String driverSinceStr = driver.getDriverSince() != null
                ? driver.getDriverSince().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
                : null;

        // Convert DocumentType list to String list
        List<String> docTypes = driver.getDocumentTypes() != null
                ? driver.getDocumentTypes().stream().map(DocumentType::name).collect(Collectors.toList())
                : null;

        // Convert Map<DocumentType, String> to Map<String, String>
        Map<String, String> docUrls = driver.getDocumentUrls() != null
                ? driver.getDocumentUrls().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().name(), Map.Entry::getValue))
                : null;

        return new DriverDTO(
                driver.getId(),
                driver.getEmail(),
                driver.getFirstName(),
                driver.getLastName(),
                driver.getPhoneNumber(),
                driver.getDriverLicenseNumber(),
                driverSinceStr,
                docTypes,
                docUrls,
                driver.getCamion(),
                driver.getDriverLicenseExpiration(),
                driver.getAverageRating()
        );
    }
}