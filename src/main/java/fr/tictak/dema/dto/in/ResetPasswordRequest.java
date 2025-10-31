package fr.tictak.dema.dto.in;

import jakarta.validation.constraints.NotBlank;

public record ResetPasswordRequest(
        @NotBlank String email,
        @NotBlank String newPassword
) {}