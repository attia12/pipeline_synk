package fr.tictak.dema.dto.in;

public record ForgotPasswordRequest(
        String email,
        String method
) {}