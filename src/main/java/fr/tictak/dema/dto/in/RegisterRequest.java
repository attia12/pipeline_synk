package fr.tictak.dema.dto.in;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * GDPR-Compliant Registration Request
 */
public record RegisterRequest(
        @Email(message = "Veuillez fournir un email valide")
        @NotBlank(message = "Email est requis")
        String email,

        @NotBlank(message = "Mot de passe est requis")
        @Size(min = 6, max = 30, message = "Le mot de passe doit être entre 6 et 30 caractères")
        String password,

        @NotBlank(message = "Le prénom est requis")
        String firstName,

        @NotBlank(message = "Le nom est requis")
        String lastName,

        @NotBlank(message = "Le numéro de téléphone est requis")
        @Pattern(
                regexp = "^((\\+|00)33|0)\\d{9}$",
                message = "Le numéro doit être un numéro de téléphone français valide (ex: +33612345678 ou 0612345678)"
        )
        String phoneNumber,

        @NotBlank(message = "Le genre est requis")
        String gender,

        @NotNull(message = "La date de naissance est requise")
        String dateOfBirth
) {

}