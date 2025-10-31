package fr.tictak.dema.dto.in;


public record UpdatePhoneWithTokenRequest(
        String userId,
       String phoneNumber,
       String nom,
        String prenom
) {}