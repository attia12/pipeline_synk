package fr.tictak.dema.dto.in;

public record ChangeInitialPasswordRequest(String oldPassword, String newPassword) {}