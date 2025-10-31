package fr.tictak.dema.controller;

import com.google.api.gax.rpc.NotFoundException;
import fr.tictak.dema.exception.BadRequestException;
import fr.tictak.dema.exception.ForbiddenException;
import fr.tictak.dema.exception.UnauthorizedException;
import fr.tictak.dema.model.user.User;
import fr.tictak.dema.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.crossstore.ChangeSetPersister;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/profile")
@Tag(name = "Gestion du profil", description = "API pour la gestion du profil utilisateur, incluant la mise à jour du mot de passe et du numéro de téléphone.")
public class ProfileController {

    private static final Logger logger = LoggerFactory.getLogger(ProfileController.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public ProfileController( UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Operation(
            summary = "Mettre à jour le mot de passe",
            description = "Met à jour le mot de passe pour l'utilisateur authentifié après vérification de l'ancien mot de passe."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Mot de passe mis à jour avec succès",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = String.class),
                            examples = @ExampleObject(value = """
                                    "Mot de passe mis à jour avec succès"
                                    """))),
            @ApiResponse(responseCode = "400", description = "Requête invalide (champs manquants)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = String.class),
                            examples = @ExampleObject(value = """
                                    "Champs requis manquants"
                                    """))),
            @ApiResponse(responseCode = "401", description = "Ancien mot de passe incorrect",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = String.class),
                            examples = @ExampleObject(value = """
                                    "Ancien mot de passe incorrect"
                                    """))),
            @ApiResponse(responseCode = "404", description = "Utilisateur non trouvé",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = String.class),
                            examples = @ExampleObject(value = """
                                    "Utilisateur non trouvé avec l'email : utilisateur@example.com"
                                    """))),
            @ApiResponse(responseCode = "401", description = "Authentification invalide ou absente",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Map.class)))
    })
    @PostMapping("/update-password")
    public ResponseEntity<String> updatePassword(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, String> request) {
        logger.info("Tentative de mise à jour du mot de passe pour l'email: {}", email);
        String oldPassword = request.get("oldPassword");
        String newPassword = request.get("newPassword");
        if (oldPassword == null || newPassword == null) {
            logger.warn("Champs requis manquants pour la mise à jour du mot de passe pour l'email: {}", email);
            return ResponseEntity.badRequest().body("Champs requis manquants");
        }
        Optional<User> userOptional = userRepository.findByEmail(email);
        if (userOptional.isEmpty()) {
            logUserNotFound(email);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Utilisateur non trouvé avec l'email : " + email);
        }
        User user = userOptional.get();
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            logger.warn("Ancien mot de passe incorrect pour l'email: {}", email);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Ancien mot de passe incorrect");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        logger.info("Mot de passe mis à jour avec succès pour l'email: {}", email);
        return ResponseEntity.ok("Mot de passe mis à jour avec succès");
    }

    @Operation(
            summary = "Mettre à jour le numéro de téléphone",
            description = "Met à jour le numéro de téléphone pour l'utilisateur authentifié."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Numéro de téléphone mis à jour avec succès",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = String.class),
                            examples = @ExampleObject(value = """
                                    "Numéro de téléphone mis à jour avec succès"
                                    """))),
            @ApiResponse(responseCode = "404", description = "Utilisateur non trouvé",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = String.class),
                            examples = @ExampleObject(value = """
                                    "Utilisateur non trouvé avec l'email : utilisateur@example.com"
                                    """))),
            @ApiResponse(responseCode = "401", description = "Authentification invalide ou absente",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Map.class)))
    })
    @PostMapping("/update-phone")
    public ResponseEntity<String> updatePhoneNumber(
            @AuthenticationPrincipal String email,
            @RequestBody String newPhoneNumber) {
        logger.info("Tentative de mise à jour du numéro de téléphone pour l'email: {}", email);
        Optional<User> userOptional = userRepository.findByEmail(email);
        if (userOptional.isEmpty()) {
            logUserNotFound(email);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Utilisateur non trouvé avec l'email : " + email);
        }
        User user = userOptional.get();
        user.setPhoneNumber(newPhoneNumber);
        userRepository.save(user);
        logger.info("Numéro de téléphone mis à jour avec succès pour l'email: {}", email);
        return ResponseEntity.ok("Numéro de téléphone mis à jour avec succès");
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, String>> handleForbiddenException(ForbiddenException ex) {
        logger.warn("Exception d'accès interdit: {}", ex.getMessage());
        Map<String, String> response = new HashMap<>();
        response.put("error", "Interdit");
        response.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }


    private void logUserNotFound(String email) {
        logger.warn("Utilisateur non trouvé pour l'email: {}", email);
    }


    @Operation(
            summary = "Mettre à jour le profil (mot de passe ou téléphone)",
            description = "Permet à l'utilisateur authentifié de mettre à jour son mot de passe, son numéro de téléphone, ou les deux."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Profil mis à jour avec succès",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = String.class),
                            examples = @ExampleObject(value = """
                                "Profil mis à jour avec succès"
                                """))),
            @ApiResponse(responseCode = "400", description = "Requête invalide (champs manquants ou incohérents)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = String.class),
                            examples = @ExampleObject(value = """
                                "Aucune mise à jour spécifiée"
                                """))),
            @ApiResponse(responseCode = "401", description = "Ancien mot de passe incorrect ou authentification invalide",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = String.class),
                            examples = @ExampleObject(value = """
                                "Ancien mot de passe incorrect"
                                """))),
            @ApiResponse(responseCode = "404", description = "Utilisateur non trouvé",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = String.class),
                            examples = @ExampleObject(value = """
                                "Utilisateur non trouvé avec l'email : utilisateur@example.com"
                                """)))
    })
    @PatchMapping("/update-profile")
    public ResponseEntity<Map<String, Object>> updateProfile(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, String> request) {

        logger.info("Tentative de mise à jour du profil pour l'utilisateur: {}", email);

        Optional<User> userOptional = userRepository.findByEmail(email);
        if (userOptional.isEmpty()) {
            logger.warn("Utilisateur non trouvé pour l'email: {}", email);
            Map<String, Object> response = new HashMap<>();
            response.put("error", "NOT_FOUND");
            response.put("message", "Utilisateur non trouvé avec l'email : " + email);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }

        User user = userOptional.get();
        boolean updated = false;

        Map<String, Object> response = new HashMap<>();

        // --- Mise à jour du mot de passe ---
        String oldPassword = request.get("oldPassword");
        String newPassword = request.get("newPassword");
        if (oldPassword != null && newPassword != null) {
            if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
                logger.warn("Ancien mot de passe incorrect pour l'email: {}", email);
                response.put("error", "UNAUTHORIZED");
                response.put("message", "Ancien mot de passe incorrect");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
            }
            user.setPassword(passwordEncoder.encode(newPassword));
            updated = true;
            logger.info("Mot de passe mis à jour pour l'utilisateur: {}", email);
        }

        // --- Mise à jour du numéro de téléphone ---
        String newPhoneNumber = request.get("phoneNumber");
        if (newPhoneNumber != null) {
            // Regex pour numéro français : 10 chiffres commençant par 0 ou +33
            String frenchPhoneRegex = "^(0[1-9](\\d{8})|\\+33[1-9](\\d{8}))$";
            if (!newPhoneNumber.replaceAll("\\s+", "").matches(frenchPhoneRegex)) {
                logger.warn("Numéro de téléphone non valide pour l'utilisateur: {}", email);
                response.put("error", "BAD_REQUEST");
                response.put("message", "Numéro de téléphone invalide");
                return ResponseEntity.badRequest().body(response);
            }
            user.setPhoneNumber(newPhoneNumber);
            updated = true;
            logger.info("Numéro de téléphone mis à jour pour l'utilisateur: {}", email);
        }

        if (!updated) {
            logger.warn("Aucune modification spécifiée pour l'utilisateur: {}", email);
            response.put("error", "BAD_REQUEST");
            response.put("message", "Aucune mise à jour spécifiée (aucun champ fourni)");
            return ResponseEntity.badRequest().body(response);
        }

        userRepository.save(user);
        logger.info("Profil mis à jour avec succès pour l'utilisateur: {}", email);

        response.put("message", "Profil mis à jour avec succès");
        response.put("user", user); // retourne l'objet user mis à jour
        return ResponseEntity.ok(response);
    }



    // Gestion des mauvaises requêtes (champs manquants ou invalides)
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Map<String, String>> handleBadRequestException(BadRequestException ex) {
        logger.warn("Requête invalide: {}", ex.getMessage());
        Map<String, String> response = new HashMap<>();
        response.put("error", "BAD_REQUEST");
        response.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    // Gestion des tentatives non autorisées (ex: mot de passe incorrect)
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Map<String, String>> handleUnauthorizedException(UnauthorizedException ex) {
        logger.warn("Non autorisé: {}", ex.getMessage());
        Map<String, String> response = new HashMap<>();
        response.put("error", "UNAUTHORIZED");
        response.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    // Gestion des utilisateurs non trouvés
    @ExceptionHandler(ChangeSetPersister.NotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFoundException(NotFoundException ex) {
        logger.warn("Non trouvé: {}", ex.getMessage());
        Map<String, String> response = new HashMap<>();
        response.put("error", "NOT_FOUND");
        response.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }



}