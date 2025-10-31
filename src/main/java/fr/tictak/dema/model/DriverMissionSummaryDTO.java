package fr.tictak.dema.model;

import lombok.Data;
import java.util.List;

@Data
public class DriverMissionSummaryDTO {
    private String moveId;               // Identifiant unique de la mission
    private String sourceAddress;        // Adresse source
    private String destinationAddress;   // Adresse destination

    private String postCommissionCost;   // Coût après commission
    private String durationInMinutes;    // Durée en minutes (sous forme de texte)
    private String numberOfProducts;     // Nombre total de produits (texte)
    private String distanceInKm;         // Distance en km (texte)
    private long remainingSeconds;       // Temps restant en secondes

    private String status;               // Statut de la mission
    private String clientName;           // Nom du client
    private String phoneNumber;          // Téléphone du client

    private String plannedDate;          // Date planifiée
    private String plannedTime;          // Heure planifiée

    private List<ItemQuantity> items;    // Liste des articles
    private Boolean booked;              // Indique si la mission est réservée

    // Champs supplémentaires utiles pour le conducteur
    private String driverFullName;       // Nom complet du conducteur
    private String driverPhoneNumber;    // Téléphone du conducteur

    // Champs dérivés
    private String duration;             // Durée formatée (ex: "1h20")
    private double distanceKm;           // Distance numérique en km
    private double amount;               // Montant numérique
    private int productCount;            // Nombre total de produits (numérique)
    private String date;                 // Date affichée combinée
}
