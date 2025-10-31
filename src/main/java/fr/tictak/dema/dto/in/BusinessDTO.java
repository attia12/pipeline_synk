package fr.tictak.dema.dto.in;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record BusinessDTO(
        @NotBlank(message = "Name is required")
        String name,

        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        String email,

        @NotBlank(message = "Address is required")
        String address,

        @NotBlank(message = "Phone is required")
        String phone,

        String website, // Optional

        @NotBlank(message = "Attestation Capacite URL is required")
        String attestationCapaciteUrl,

        @NotBlank(message = "Kbis URL is required")
        String kbisUrl,

        @NotBlank(message = "Assurance Transport URL is required")
        String assuranceTransportUrl,

        @NotBlank(message = "Identity Proof URL is required")
        String identityProofUrl,

        @NotBlank(message = "Attestation Vigilance URL is required")
        String attestationVigilanceUrl,

        @NotBlank(message = "Attestation Regularite Fiscale URL is required")
        String attestationRegulariteFiscaleUrl
) {}