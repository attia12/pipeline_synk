package fr.tictak.dema.dto.in;

import fr.tictak.dema.model.enums.DocumentType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.Date;
import java.util.List;
import java.util.Map;

public record CreateDriverRequest(
        @NotBlank
        @Email
        String email,

        @NotBlank(message = "Le prénom est requis")
        String firstName,
        @NotBlank(message = "Le nom est requis")
        String lastName,
        @Pattern(
                regexp = "^((\\+|00)33|0)\\d{9}$",
                message = "Le numéro doit être un numéro de téléphone français valide (ex: +33612345678 ou 0612345678)"
        )
        String phoneNumber,

        Date driverLicenseExpiration,

        List<DocumentType> documentTypes,
        Map<DocumentType, String> documentUrls
) {
}