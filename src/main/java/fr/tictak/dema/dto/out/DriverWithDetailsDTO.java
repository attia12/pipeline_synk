package fr.tictak.dema.dto.out;

import java.util.List;
import java.util.Map;

public record DriverWithDetailsDTO(
        String driverId,
        String firstName,
        String lastName,
        String email,
        String phoneNumber,
        String driverLicenseNumber,
        int yearsOfExperience,
        String status,
        String truckLicensePlate,
        String truckModel,
        Integer truckYear,
        Double truckCapacity,
        String subAdminFirstName,
        String subAdminLastName,
        String subAdminEmail,
        String subAdminPhoneNumber,
        String subAdminCompanyName,
        String subAdminAssignedRegion,
        String driverSince,
        List<String> documentTypes,
        Map<String, String> documentUrls,
        String camion,
        String driverLicenseExpiration
) {}