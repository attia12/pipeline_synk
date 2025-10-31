package fr.tictak.dema.dto.in;

import jakarta.validation.constraints.NotBlank;

public record VerifyOtpRequest(
        @NotBlank String email,
        @NotBlank String otpCode
) {}