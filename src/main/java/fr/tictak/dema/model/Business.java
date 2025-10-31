package fr.tictak.dema.model;// Entity: Business.java (now as a MongoDB Document)


import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "businesses")
@Data
public class Business {

    @Id
    private String id; // Using String for MongoDB ObjectId

    private String name; // Nom de l’entreprise*

    private String email; // Email professionnel*

    private String address; // Adresse complète*

    private String phone; // Numéro de téléphone*

    private String website; // Site web (facultatif)

    // Document URLs (stored via frontend in Firebase)
    private String attestationCapaciteUrl; // Attestation de capacité*

    private String kbisUrl; // Extrait K-BIS*

    private String assuranceTransportUrl; // Assurance de transport*

    private String identityProofUrl; // Pièce d’identité du représentant légal*

    private String attestationVigilanceUrl; // Attestation de vigilance*

    private String attestationRegulariteFiscaleUrl; // Attestation de régularité fiscale*
}