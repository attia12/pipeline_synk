package fr.tictak.dema.controller;

import fr.tictak.dema.dto.in.CreateTruckDto;
import fr.tictak.dema.dto.in.DriverDTO;
import fr.tictak.dema.exception.ForbiddenException;
import fr.tictak.dema.model.enums.Role;
import fr.tictak.dema.model.Truck;
import fr.tictak.dema.model.user.Driver;
import fr.tictak.dema.model.user.User;
import fr.tictak.dema.service.DriverService;
import fr.tictak.dema.service.TruckService;
import fr.tictak.dema.service.UserService;
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
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/subadmin")
@Tag(name = "Gestion des sous-administrateurs", description = "API pour les opérations des sous-administrateurs, incluant la récupération et la création des chauffeurs et camions.")
public class SubAdminController {

    private static final Logger logger = LoggerFactory.getLogger(SubAdminController.class);

    private final DriverService driverService;
    private final UserService userService;
    private final TruckService truckService;

    public SubAdminController(DriverService driverService, UserService userService, TruckService truckService) {
        this.driverService = driverService;
        this.userService = userService;
        this.truckService = truckService;
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
                                            "id": "1",
                                            "email": "chauffeur1@example.com",
                                            "role": "DRIVER",
                                            "subAdminId": "2"
                                        },
                                        {
                                            "id": "2",
                                            "email": "chauffeur2@example.com",
                                            "role": "DRIVER",
                                            "subAdminId": "2"
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
    @GetMapping("/drivers-details")
    public ResponseEntity<List<DriverDTO>> getDriversForSubAdmin(@AuthenticationPrincipal String email) {
        logger.info("Tentative de récupération des chauffeurs pour le sous-administrateur avec email: {}", email);
        if (email == null) {
            logger.warn("Échec de récupération des chauffeurs: Authentification introuvable");
            throw new ForbiddenException("Authentification introuvable !");
        }

        User currentUser = userService.findByEmail(email);
        if (currentUser == null || currentUser.getRole() != Role.SUB_ADMIN) {
            logger.warn("Utilisateur non trouvé ou rôle incorrect pour l'email : {}", email);
            throw new ForbiddenException("Seuls les sous-administrateurs valides peuvent récupérer leurs chauffeurs !");
        }

        logger.info("Récupération des chauffeurs pour le sous-administrateur avec ID: {}", currentUser.getId());
        List<Driver> drivers = driverService.findDriversBySubAdminId(currentUser.getId());

        // Map to DTOs
        List<DriverDTO> driverDTOs = drivers.stream()
                .map(DriverDTO::fromEntity)
                .collect(Collectors.toList());

        logger.info("Chauffeurs récupérés avec succès pour le sous-administrateur avec ID: {}", currentUser.getId());
        return ResponseEntity.ok(driverDTOs);
    }

    @Operation(
            summary = "Récupérer les camions d'un sous-administrateur",
            description = "Permet à un sous-administrateur authentifié de récupérer la liste de tous les camions qu'il a créés."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Liste des camions récupérée avec succès",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Truck.class))),
            @ApiResponse(responseCode = "401", description = "Authentification invalide ou absente",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Map.class))),
            @ApiResponse(responseCode = "403", description = "L'utilisateur n'a pas le rôle SUB_ADMIN ou n'est pas valide",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Map.class)))
    })
    @GetMapping("/trucks")
    public ResponseEntity<List<Truck>> getTrucksForSubAdmin(@AuthenticationPrincipal String email) {
        logger.info("Tentative de récupération des camions pour le sous-administrateur avec email: {}", email);
        if (email == null) {
            logger.warn("Échec de récupération des camions: Authentification introuvable");
            throw new ForbiddenException("Authentification introuvable !");
        }

        User currentUser = userService.findByEmail(email);
        if (currentUser == null || currentUser.getRole() != Role.SUB_ADMIN) {
            logger.warn("Utilisateur non trouvé ou rôle incorrect pour l'email   :   {}", email);
            throw new ForbiddenException("Seuls les sous-administrateurs valides peuvent récupérer leurs camions !");
        }

        logger.info("Récupération des camions pour le sous-administrateur avec ID: {}", currentUser.getId());
        List<Truck> trucks = truckService.getTrucksForSubAdmin(currentUser.getId());
        logger.info("Camions récupérés avec succès pour le sous-administrateur avec ID: {}", currentUser.getId());
        return ResponseEntity.ok(trucks);
    }

    @Operation(
            summary = "Créer un camion pour un sous-administrateur",
            description = "Permet à un sous-administrateur authentifié de créer un nouveau camion."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Camion créé avec succès",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Truck.class))),
            @ApiResponse(responseCode = "400", description = "Données invalides",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Map.class))),
            @ApiResponse(responseCode = "401", description = "Authentification introuvable",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Map.class))),
            @ApiResponse(responseCode = "403", description = "L'utilisateur n'a pas le rôle SUB_ADMIN ou n'est pas valide",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Map.class)))
    })
    @PostMapping("/trucks")
    public ResponseEntity<Truck> createTruckForSubAdmin(@AuthenticationPrincipal String email, @RequestBody CreateTruckDto truckDto) {
        logger.info("Tentative de création d'un camion pour le sous-administrateur avec email: {}", email);
        if (email == null) {
            logger.warn("Échec de création du camion: Authentification introuvable");
            throw new ForbiddenException("Authentification introuvable !");
        }

        User currentUser = userService.findByEmail(email);
        if (currentUser == null || currentUser.getRole() != Role.SUB_ADMIN) {
            logger.warn("Utilisateur non  trouvé ou rôle incorrect pour l'email: {}", email);
            throw new ForbiddenException("Seuls les sous-administrateurs valides peuvent créer des camions !");
        }

        if (truckDto.model() == null || truckDto.capacity() <= 0) {
            logger.warn("Données invalides pour la création du camion: {}", truckDto);
            throw new IllegalArgumentException("Les champs obligatoires (modèle, capacité) doivent être valides.");
        }

        if (truckDto.assuranceCamion() != null && !truckDto.assuranceCamion().isEmpty()) {
            logger.debug("Assurance camion URL fourni: {}", truckDto.assuranceCamion());
        }
        if (truckDto.carteGrise() != null && !truckDto.carteGrise().isEmpty()) {
            logger.debug("Carte grise URL fourni: {}", truckDto.carteGrise());
        }
        if (truckDto.vignetteTaxe() != null && !truckDto.vignetteTaxe().isEmpty()) {
            logger.debug("Vignette taxe URL fourni: {}", truckDto.vignetteTaxe());
        }

        Truck createdTruck = truckService.createTruck(truckDto, currentUser.getId());
        logger.info("Camion créé avec succès pour le sous-administrateur avec ID: {}", currentUser.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(createdTruck);
    }

    @DeleteMapping("/trucks/{truckId}")
    public ResponseEntity<Void> deleteTruckForSubAdmin(@AuthenticationPrincipal String email, @PathVariable String truckId) {
        logger.info("Tentative de suppression du camion avec ID: {} pour le sous-administrateur avec email: {}", truckId, email);
        if (email == null) {
            logger.warn("Échec de suppression du camion: Authentification introuvable");
            throw new ForbiddenException("Authentification introuvable !");
        }

        User currentUser = userService.findByEmail(email);
        if (currentUser == null || currentUser.getRole() != Role.SUB_ADMIN) {
            logger.warn("Utilisateur non trouvé ou rôle incorrect pour l'email: {}", email);
            throw new ForbiddenException("Seuls les sous-administrateurs valides peuvent supprimer leurs camions !");
        }

        Truck truck = truckService.findById(truckId);
        if (truck == null || !truck.getAddedBy().equals(currentUser.getId())) {
            logger.warn("Camion non trouvé ou n'appartient pas au sous-administrateur  avec ID: {}", currentUser.getId());
            throw new IllegalArgumentException("Camion non trouvé ou n'appartient pas au sous-administrateur.");
        }

        truckService.deleteTruck(truckId);
        logger.info("Camion avec ID: {} supprimé avec succès pour le sous-administrateur avec ID: {}", truckId, currentUser.getId());
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Assigner un camion à un chauffeur",
            description = "Permet à un sous-administrateur authentifié d'assigner un camion à un chauffeur."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Camion assigné avec succès",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Driver.class))),
            @ApiResponse(responseCode = "400", description = "Données invalides ou camion déjà assigné",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Map.class))),
            @ApiResponse(responseCode = "401", description = "Authentification invalide ou absente",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Map.class))),
            @ApiResponse(responseCode = "403", description = "L'utilisateur n'a pas le rôle SUB_ADMIN ou n'est pas valide",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Map.class))),
            @ApiResponse(responseCode = "404", description = "Chauffeur ou camion non trouvé",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Map.class)))
    })
    @PatchMapping("/drivers/{driverId}")
    public ResponseEntity<Driver> assignCamionToDriver(
            @PathVariable String driverId,
            @RequestBody Map<String, String> request,
            @AuthenticationPrincipal String email) {
        logger.info("Tentative d'assignation d'un camion au chauffeur avec ID: {} par le sous-administrateur avec email: {}", driverId, email);
        if (email == null) {
            logger.warn("Échec d'assignation: Authentification introuvable");
            throw new ForbiddenException("Authentification introuvable !");
        }

        User currentUser = userService.findByEmail(email);
        if (currentUser == null || currentUser.getRole() != Role.SUB_ADMIN) {
            logger.warn("Utilisateur  non trouvé ou rôle incorrect pour l'email : {}", email);
            throw new ForbiddenException("Seuls les sous-administrateurs valides peuvent assigner des camions !");
        }

        Driver driver = driverService.findById(driverId);
        if (driver == null || !driver.getCreatedBySubAdminId().getId().equals(currentUser.getId())) {
            logger.warn("Chauffeur non trouvé ou n'appartient pas au sous-administrateur avec ID: {}", currentUser.getId());
            throw new IllegalArgumentException("Chauffeur non trouvé ou n'appartient pas au sous-administrateur.");
        }

        String camionId = request.get("camion");
        String camion = null;

        if (camionId != null && !camionId.equals("none")) {
            Truck truck = truckService.findById(camionId);
            if (truck == null || !truck.getAddedBy().equals(currentUser.getId())) {
                logger.warn("Camion non trouvé ou n'appartient pas au sous-administrateur avec ID: {}", currentUser.getId());
                throw new IllegalArgumentException("Camion non trouvé ou n'appartient pas au sous-administrateur.");
            }
            if (!truck.isAvailable()) {
                logger.warn("Le camion avec ID: {} n'est pas disponible", camionId);
                throw new IllegalArgumentException("Le camion n'est pas disponible.");
            }
            List<Driver> driversWithTruck = driverService.findDriversByCamion(camionId);
            if (!driversWithTruck.isEmpty()) {
                Driver assignedDriver = driversWithTruck.getFirst();
                String driverName = assignedDriver.getFirstName() + " " + assignedDriver.getLastName();
                logger.warn("Le camion avec ID: {} est déjà assigné au chauffeur: {}", camionId, driverName);
                throw new IllegalArgumentException("Ce camion est déjà assigné à " + driverName + ". Veuillez le désassigner avant de le réassigner.");
            }
            camion = camionId;
        }

        driver.setCamion(camion);
        logger.debug("Assignation du camion ID: {} au chauffeur ID: {}", camion, driverId);
        driverService.save(driver);
        logger.info("Camion assigné avec succès au chauffeur avec ID: {} par le sous-administrateur avec ID: {}", driverId, currentUser.getId());
        return ResponseEntity.ok(driver);
    }
    @Operation(
            summary = "Supprimer un chauffeur d'un sous-administrateur",
            description = "Permet à un sous-administrateur authentifié de supprimer un chauffeur qu'il a créé."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Chauffeur supprimé avec succès"),
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
                                        "message": "Seuls les sous-administrateurs valides peuvent supprimer leurs chauffeurs !"
                                    }
                                    """))),
            @ApiResponse(responseCode = "404", description = "Chauffeur non trouvé ou n'appartient pas au sous-administrateur",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Map.class),
                            examples = @ExampleObject(value = """
                                    {
                                        "error": "Non trouvé",
                                        "message": "Chauffeur non trouvé ou n'appartient pas au sous-administrateur."
                                    }
                                    """)))
    })
    @DeleteMapping("/drivers/{driverId}")
    public ResponseEntity<Void> deleteDriverForSubAdmin(@AuthenticationPrincipal String email, @PathVariable String driverId) {
        logger.info("Tentative de suppression du chauffeur avec ID: {} pour le sous-administrateur avec email: {}", driverId, email);
        if (email == null) {
            logger.warn("Échec de suppression du chauffeur: Authentification introuvable");
            throw new ForbiddenException("Authentification introuvable !");
        }

        User currentUser = userService.findByEmail(email);
        if (currentUser == null || currentUser.getRole() != Role.SUB_ADMIN) {
            logger.warn("Utilisateur non trouvé ou rôle incorrect pour  l'email : {}", email);
            throw new ForbiddenException("Seuls les sous-administrateurs valides peuvent supprimer leurs chauffeurs !");
        }

        Driver driver = driverService.findById(driverId);
        if (driver == null || !driver.getCreatedBySubAdminId().getId().equals(currentUser.getId())) {
            logger.warn("Chauffeur non trouvé ou n'appartient pas au  sous-administrateur avec ID: {}", currentUser.getId());
            throw new IllegalArgumentException("Chauffeur non trouvé ou n'appartient pas au sous-administrateur.");
        }

        driverService.deleteDriver(driverId);
        logger.info("Chauffeur avec ID: {} supprimé avec succès pour le sous-administrateur avec ID: {}", driverId, currentUser.getId());
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Récupérer la note globale de la société",
            description = "Permet à un sous-administrateur authentifié de récupérer la note globale de sa société basée sur les notes des chauffeurs (ignorant ceux avec note 0.0)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Note globale récupérée avec succès",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Double.class))),
            @ApiResponse(responseCode = "401", description = "Authentification invalide ou absente",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Map.class))),
            @ApiResponse(responseCode = "403", description = "L'utilisateur n'a pas le rôle SUB_ADMIN ou n'est pas valide",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Map.class)))
    })
    @GetMapping("/company-rating")
    public ResponseEntity<Double> getCompanyRating(@AuthenticationPrincipal String email) {
        logger.info("Tentative de récupération de la note globale pour le sous-administrateur avec email: {}", email);
        if (email == null) {
            logger.warn("Échec de récupération de la note globale: Authentification introuvable");
            throw new ForbiddenException("Authentification introuvable !");
        }

        User currentUser = userService.findByEmail(email);
        if (currentUser == null || currentUser.getRole() != Role.SUB_ADMIN) {
            logger.warn("Utilisateur non trouvé  ou rôle incorrect pour l'email: {}", email);
            throw new ForbiddenException("Seuls les sous-administrateurs valides peuvent récupérer la note de leur société !");
        }

        List<Driver> drivers = driverService.findDriversBySubAdminId(currentUser.getId());

        double sum = 0.0;
        int count = 0;
        for (Driver driver : drivers) {
            if (driver.getAverageRating() > 0.0) {
                sum += driver.getAverageRating();
                count++;
            }
        }

        double average = count > 0 ? sum / count : 0.0;

        logger.info("Note globale récupérée avec succès pour le sous-administrateur avec ID: {}", currentUser.getId());
        return ResponseEntity.ok(average);
    }
}