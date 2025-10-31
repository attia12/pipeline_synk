package fr.tictak.dema.dto.out;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        String id,
        boolean passwordChangeRequired
) {}
