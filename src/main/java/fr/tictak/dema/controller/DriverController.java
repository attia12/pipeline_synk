package fr.tictak.dema.controller;


import fr.tictak.dema.exception.ForbiddenException;
import fr.tictak.dema.model.Review;
import fr.tictak.dema.model.enums.Role;
import fr.tictak.dema.model.user.Driver;
import fr.tictak.dema.model.user.User;
import fr.tictak.dema.repository.UserRepository;
import fr.tictak.dema.service.DriverService;
import fr.tictak.dema.service.UserService;
import fr.tictak.dema.service.implementation.MoveServiceImpl;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;


@Slf4j
@RestController
@RequestMapping("/driver")
@Tag(name = "Gestion des chauffeurs", description = "API pour la gestion des chauffeurs, incluant la récupération des chauffeurs par rôle et la mise à jour des jetons FCM.")
public class DriverController {

    private static final Logger logger = LoggerFactory.getLogger(DriverController.class);

    private final DriverService driverService;
    private final UserService userService;
    private final UserRepository userRepository;
    private final MoveServiceImpl moveService;

    public DriverController(DriverService driverService, UserService userService, UserRepository userRepository, MoveServiceImpl moveService) {
        this.driverService = driverService;
        this.userService = userService;
        this.userRepository = userRepository;
        this.moveService = moveService;
    }

    @Operation(
            summary = "Récupérer les chauffeurs d'un sous-administrateur",
            description = "Permet à un sous-administrateur authentifié de récupérer la liste de tous les chauffeurs qu'il a créés."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Liste des chauffeurs récupérée avec succès",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Driver.class),
                            examples = @ExampleObject(value = """
                                    [
                                        {
                                            "id": 1,
                                            "email": "chauffeur1@example.com",
                                            "role": "DRIVER",
                                            "subAdminId": 2
                                        },
                                        {
                                            "id": 2,
                                            "email": "chauffeur2@example.com",
                                            "role": "DRIVER",
                                            "subAdminId": 2
                                        }
                                    ]
                                    """))),
            @ApiResponse(responseCode = "401", description = "Authentification invalide ou absente",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Map.class),
                            examples = @ExampleObject(value = """
                                    {
                                        "error": "Non autorisé",
                                        "message": "Authentification introuvable !"
                                    }
                                    """))),
            @ApiResponse(responseCode = "403", description = "L'utilisateur n'a pas le rôle SUB_ADMIN ou n'est pas valide",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Map.class),
                            examples = @ExampleObject(value = """
                                    {
                                        "error": "Interdit",
                                        "message": "Seuls les sous-administrateurs valides peuvent récupérer leurs chauffeurs !"
                                    }
                                    """)))
    })
    @GetMapping("/subadmin")
    public ResponseEntity<List<Driver>> getDriversForSubAdmin() {
        logger.info("Tentative de récupération des chauffeurs pour le sous-administrateur");
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            logger.warn("Échec de récupération des chauffeurs: Authentification introuvable");
            throw new ForbiddenException("Authentification introuvable !");
        }

        boolean isSubAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_SUB_ADMIN"));
        if (!isSubAdmin) {
            logger.warn("Échec de récupération des chauffeurs: Rôle non autorisé");
            throw new ForbiddenException("Seuls les sous-administrateurs peuvent récupérer leurs chauffeurs !");
        }

        String currentUserEmail = auth.getName();
        User currentUser = userService.findByEmail(currentUserEmail);
        if (currentUser == null || currentUser.getRole() != Role.SUB_ADMIN) {
            logUserNotFound(currentUserEmail);
            throw new ForbiddenException("Seuls les sous-administrateurs valides peuvent récupérer leurs chauffeurs !");
        }

        logger.info("Récupération des chauffeurs pour le sous-administrateur avec ID: {}", currentUser.getId());
        List<Driver> drivers = driverService.findDriversBySubAdminId(currentUser.getId());
        logger.info("Chauffeurs récupérés avec succès pour le sous-administrateur avec ID: {}", currentUser.getId());
        return ResponseEntity.ok(drivers);
    }

    @Operation(
            summary = "Mettre à jour le jeton FCM",
            description = "Met à jour le jeton FCM (Firebase Cloud Messaging) pour l'utilisateur authentifié."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Jeton FCM mis à jour avec succès",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = String.class),
                            examples = @ExampleObject(value = """
                                    "Jeton FCM mis à jour avec succès"
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
    @PostMapping("/update-fcm-token")
    public ResponseEntity<String> updateFcmToken(
            @RequestBody String fcmToken,
            @AuthenticationPrincipal String email) {
        logger.info("Tentative de mise à jour du jeton FCM pour l'email: {}", email);
        Optional<User> userOptional = userRepository.findByEmail(email);
        if (userOptional.isEmpty()) {
            logUserNotFound(email);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Utilisateur non trouvé avec l'email : " + email);
        }
        User user = userOptional.get();
        user.setFcmToken(fcmToken);
        userRepository.save(user);
        logger.info("Jeton FCM mis à jour avec succès pour l'email: {}", email);
        return ResponseEntity.ok("Jeton FCM mis à jour avec succès");
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, String>> handleForbiddenException(ForbiddenException ex) {
        logger.warn("Exception d'accès interdit : {}", ex.getMessage());
        Map<String, String> response = new HashMap<>();
        response.put("error", "Interdit");
        response.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }

    private void logUserNotFound(String email) {
        logger.warn("Utilisateur non trouvé pour l'email: {}", email);
    }



    @PostMapping("/accept-mission/{moveId}")
    public ResponseEntity<Void> acceptMission(
            @PathVariable String moveId,
            @AuthenticationPrincipal String driverId) {
        log.info("Driver {} attempting to accept mission for moveId: {}", driverId, moveId);
        try {
            moveService.handleDriverAcceptance(moveId, driverId);
            return ResponseEntity.ok().build();
        } catch (IllegalStateException e) {
            log.warn("Acceptance failed: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Error during mission acceptance: {}", e.getMessage(), e);
            return ResponseEntity.status(500).build();
        }
    }

    @PostMapping("/decline-mission/{moveId}")
    public ResponseEntity<Void> declineMission(
            @PathVariable String moveId,
            @AuthenticationPrincipal String driverId) {
        log.info("Driver {} attempting to decline mission for moveId: {}", driverId, moveId);
        try {
            moveService.handleDriverDecline(moveId, driverId);
            return ResponseEntity.ok().build();
        } catch (IllegalStateException e) {
            log.warn("Decline failed: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Error during mission decline: {}", e.getMessage(), e);
            return ResponseEntity.status(500).build();
        }
    }



    @Operation(
            summary = "Récupérer la note moyenne d'un chauffeur",
            description = "Retourne la note moyenne globale d'un chauffeur."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Note moyenne récupérée",
                    content = @Content(schema = @Schema(implementation = Double.class))),
            @ApiResponse(responseCode = "404", description = "Chauffeur non trouvé")
    })
    @GetMapping("/rating")
    public ResponseEntity<Double> getDriverRating(@AuthenticationPrincipal String email) {
        Driver driver = driverService.findByEmail(email);
        logger.info("Récupération de la note moyenne pour le chauffeur {}", driver.getId());
        double average = driverService.getAverageRating(driver.getId());
        return ResponseEntity.ok(average);
    }


    @Operation(
            summary = "Récupérer les avis d'un chauffeur",
            description = "Retourne la liste des avis (notes et commentaires) pour un chauffeur."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Liste des avis récupérée",
                    content = @Content(schema = @Schema(implementation = Review.class))),
            @ApiResponse(responseCode = "404", description = "Chauffeur non trouvé (si nécessaire)")
    })
    @GetMapping("/{driverId}/reviews")
    public ResponseEntity<List<Review>> getDriverReviews(@PathVariable String driverId) {
        logger.info("Récupération des avis pour le chauffeur {}", driverId);
        List<Review> reviews = driverService.getReviewsForDriver(driverId);
        return ResponseEntity.ok(reviews);
    }
}