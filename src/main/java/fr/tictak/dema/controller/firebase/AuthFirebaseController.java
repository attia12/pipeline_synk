package fr.tictak.dema.controller.firebase;

import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import com.google.firebase.auth.UserRecord;
import fr.tictak.dema.service.firebase.FirebaseAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Getter;
import lombok.Setter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Contrôleur REST pour la gestion de l'authentification via Firebase.
 */
@RestController
@RequestMapping("/auth")
@Tag(name = "Authentification Firebase", description = "Endpoints pour l'authentification des utilisateurs via Firebase")
public class AuthFirebaseController {

    private final FirebaseAuthService firebaseAuthService;

    public AuthFirebaseController(FirebaseAuthService firebaseAuthService) {
        this.firebaseAuthService = firebaseAuthService;
    }

    /**
     * Vérifie l'identité d'un utilisateur à l'aide d'un ID token Firebase (authentification par téléphone).
     *
     * @param request Objet contenant l'ID token.
     * @return Réponse indiquant si l'utilisateur est authentifié avec succès ou non.
     */
    @Operation(
            summary = "Vérifier l'authentification par téléphone",
            description = "Vérifie si l'utilisateur est authentifié via un ID token Firebase fourni."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Utilisateur vérifié avec succès",
                    content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "400", description = "Échec de la vérification ou utilisateur non trouvé",
                    content = @Content(mediaType = "application/json"))
    })
    @PostMapping("/phone")
    public ResponseEntity<?> verifyPhoneLogin(@RequestBody IdTokenRequest request) {
        try {
            FirebaseToken decodedToken = firebaseAuthService.verifyIdToken(request.getIdToken());
            String uid = decodedToken.getUid();

            UserRecord userRecord;
            try {
                userRecord = firebaseAuthService.getUserByUid(uid);
            } catch (FirebaseAuthException e) {
                return ResponseEntity.badRequest()
                        .body("Utilisateur introuvable dans Firebase. Une inscription supplémentaire est nécessaire.");
            }

            return ResponseEntity.ok("Utilisateur vérifié avec succès. Téléphone : " + userRecord.getPhoneNumber());

        } catch (FirebaseAuthException e) {
            return ResponseEntity.badRequest().body("Échec de la vérification du token : " + e.getMessage());
        }
    }

    /**
     * Requête contenant le token d'identification Firebase.
     */
    @Setter
    @Getter
    @Schema(description = "Requête contenant le token d'identification Firebase")
    public static class IdTokenRequest {

        @Schema(description = "ID Token Firebase JWT", example = "eyJhbGciOiJSUzI1NiIsImtpZCI6Ij...")
        private String idToken;
    }
}
