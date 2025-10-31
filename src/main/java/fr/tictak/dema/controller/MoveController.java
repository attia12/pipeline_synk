package fr.tictak.dema.controller;

import fr.tictak.dema.dto.in.*;
import fr.tictak.dema.dto.out.MoveDetails;
import fr.tictak.dema.exception.*;
import fr.tictak.dema.model.*;
import fr.tictak.dema.model.enums.QuotationType;
import fr.tictak.dema.model.enums.Role;
import fr.tictak.dema.model.user.Driver;
import fr.tictak.dema.model.user.User;
import fr.tictak.dema.repository.DriverRepository;
import fr.tictak.dema.repository.LastMinuteMoveRepository;
import fr.tictak.dema.repository.UserRepository;
import fr.tictak.dema.service.*;
import fr.tictak.dema.service.implementation.GoogleMapsService;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/moves")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Gestion des déménagements", description = "API pour la gestion des demandes de déménagement, des devis, des confirmations, des suggestions d'adresses, des téléversements de photos et des réponses des chauffeurs aux missions.")
public class MoveController {

    private static final Logger logger = LoggerFactory.getLogger(MoveController.class);
    private final MoveService moveService;
    private final UserRepository userRepository;
    private final PdfService pdfService;
    private final DriverService driverService;
    private final UserService userService;
    private final LastMinuteMoveRepository lastMinuteMoveRepository;
    private final DriverRepository driverRepository;
    private final ConcurrentHashMap<String, Bucket> userBuckets = new ConcurrentHashMap<>();
    private final GoogleMapsService service;


    @GetMapping("/itineraries")
    public List<Itinerary> getItineraries(@RequestParam String start, @RequestParam String end)
            throws IOException, InterruptedException, ApiException, com.google.maps.errors.ApiException, ExecutionException {
        return service.getItineraries(start, end);
    }

    private Bucket getUserBucket(String email) {
        return userBuckets.computeIfAbsent(email, k -> {
            // Limit to 20 requests per 24 hours per user
            Bandwidth limit = Bandwidth.classic(20, Refill.intervally(20, Duration.ofHours(24)));
            return Bucket.builder().addLimit(limit).build();
        });
    }

    @Operation(
            summary = "Envoyer une demande spéciale de déménagement",
            description = "Permet d'envoyer un email à l'admin avec le contenu de la demande spéciale et l'email de l'expéditeur."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Demande envoyée avec succès"),
            @ApiResponse(responseCode = "500", description = "Erreur lors de l'envoi de l'email")
    })
    @PostMapping("/special-request")
    public ResponseEntity<Void> sendSpecialRequest(@AuthenticationPrincipal String email, @RequestBody String request) {
        moveService.sendSpecialRequest(request, email);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/autocomplete")
    @Operation(summary = "Obtenir des suggestions d'adresses", description = "Récupère une liste de suggestions d'adresses à partir de l'API Google Maps Autocomplete basée sur une chaîne de requête partielle.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Liste des suggestions d'adresses récupérée avec succès",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = List.class),
                            examples = @ExampleObject(value = "[\"123 Rue de Paris, 75001 Paris, France\", \"124 Rue de Paris, 75002 Paris, France\"]"))),
            @ApiResponse(responseCode = "400", description = "Paramètre de requête invalide ou vide")
    })
    public ResponseEntity<List<String>> getAddressSuggestions(
            @Parameter(description = "Chaîne de requête partielle pour rechercher des suggestions d'adresses (par exemple, 'Paris', '123 Rue Principale')", required = true)
            @RequestParam("query") String query
    ) {
        logger.info("Recherche de suggestions d'adresses avec la requête: {}", query);
        if (query == null || query.trim().isEmpty()) {
            logger.warn("Échec de la recherche d'adresses: Paramètre de requête vide");
            throw new BadRequestException("Le paramètre de requête ne peut pas être vide");
        }
        List<String> suggestions = moveService.getAddressSuggestions(query);
        logger.info("Suggestions d'adresses récupérées avec succès, {} résultats", suggestions.size());
        return ResponseEntity.ok(suggestions);
    }

    @GetMapping("/planned/available")
    @PreAuthorize("hasRole('DRIVER')")
    @Operation(summary = "Get available planned missions", description = "Retrieves a list of all paid, unplanned missions that are available for drivers to book.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of available planned missions retrieved successfully."),
            @ApiResponse(responseCode = "403", description = "Forbidden - User is not a DRIVER.")
    })
    public ResponseEntity<List<DriverMissionSummaryDTO>> getAvailablePlannedMissions() {
        logger.info("Request received to fetch available planned missions.");
        List<DriverMissionSummaryDTO> missions = moveService.getAvailablePlannedMissions();
        return ResponseEntity.ok(missions);
    }

    @GetMapping("/planned/available/{driverId}")
    @PreAuthorize("hasRole('DRIVER')")
    @Operation(summary = "Get available planned missions by driverId")
    public ResponseEntity<List<DriverMissionSummaryDTO>> getAvailablePlannedMissionsByDriver(@PathVariable String driverId) {
        log.info("Request received to fetch available planned missions for driverId: {}", driverId);
        List<DriverMissionSummaryDTO> missions = moveService.getAvailablePlannedMissionsByDriver(driverId);
        return ResponseEntity.ok(missions);
    }

    @PostMapping("/{moveId}/book")
    @PreAuthorize("hasRole('DRIVER')")
    @Operation(summary = "Book a planned mission", description = "Allows an authenticated driver to book an available planned mission.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Mission booked successfully.",
                    content = @Content(schema = @Schema(implementation = MoveRequest.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request - Mission is already booked, not plannable, or conflicts with driver's schedule."),
            @ApiResponse(responseCode = "403", description = "Forbidden - User is not a DRIVER."),
            @ApiResponse(responseCode = "404", description = "Not Found - Mission or Driver not found.")
    })
    public ResponseEntity<MoveRequest> bookMission(
            @Parameter(description = "The ID of the mission to book", required = true)
            @PathVariable String moveId,
            @AuthenticationPrincipal String email
    ) {
        logger.info("Driver {} is attempting to book mission {}", email, moveId);
        User driver = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Driver user not found for email: " + email));

        MoveRequest bookedMove = moveService.bookPlannedMission(moveId, driver.getId());
        return ResponseEntity.ok(bookedMove);
    }

    @GetMapping("/{moveId}/download-facture")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUB_ADMIN', 'CLIENT')")
    @Operation(
            summary = "Télécharger la facture en PDF",
            description = "Génère et télécharge la facture de déménagement au format PDF pour une demande spécifique. Accessible aux administrateurs, sous-administrateurs et clients."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "PDF généré et téléchargé avec succès",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_PDF_VALUE,
                            schema = @Schema(type = "string", format = "binary")
                    )
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Interdit - l'utilisateur n'a pas l'autorisation d'accéder à cette facture"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Demande de déménagement non trouvée"
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Erreur interne du serveur lors de la génération du PDF"
            )
    })
    public ResponseEntity<byte[]> downloadFacturePdf(
            @Parameter(description = "Identifiant unique de la demande de déménagement", required = true)
            @PathVariable String moveId,
            @Parameter(description = "Email de l'utilisateur authentifié")
            @AuthenticationPrincipal String email
    ) {
        return generatePdfResponse(moveId, email, "facture", "Facture");
    }

    @GetMapping("/{moveId}/download-invoice")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUB_ADMIN', 'CLIENT')")
    @Operation(
            summary = "Télécharger le devis en PDF",
            description = "Génère et télécharge le devis de déménagement au format PDF pour une demande spécifique. Accessible aux administrateurs, sous-administrateurs et clients."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "PDF généré et téléchargé avec succès",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_PDF_VALUE,
                            schema = @Schema(type = "string", format = "binary")
                    )
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Interdit - l'utilisateur n'a pas l'autorisation d'accéder à ce devis"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Demande de déménagement non trouvée"
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Erreur interne du serveur lors de la génération du PDF"
            )
    })
    public ResponseEntity<byte[]> downloadInvoicePdf(
            @Parameter(description = "Identifiant unique de la demande de déménagement", required = true)
            @PathVariable String moveId,
            @Parameter(description = "Email de l'utilisateur authentifié")
            @AuthenticationPrincipal String email
    ) {
        return generatePdfResponse(moveId, email, "devis", "Devis");
    }

    /**
     * Common logic for generating PDF response for devis or facture
     * @param moveId the move request ID
     * @param email the authenticated user's email
     * @param templateName the Thymeleaf template name (e.g., "devis" or "facture")
     * @param documentType the document type for filename (e.g., "Devis" or "Facture")
     * @return ResponseEntity with the PDF bytes
     */
    private ResponseEntity<byte[]> generatePdfResponse(String moveId, String email, String templateName, String documentType) {
        logger.info("Request to download PDF for moveId: {}, type: {}, by user: {}", moveId, documentType, email);

        try {
            // Get the authenticated user
            User currentUser = userRepository.findByEmail(email)
                    .orElseThrow(() -> {
                        logger.error("User not found for email : {}", email);
                        return new ResourceNotFoundException("Utilisateur non trouvé");
                    });

            // Get the move request
            MoveRequest moveRequest = moveService.getMoveRequestById(moveId);

            // Check authorization based on user role
            String role = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                    .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                    .findFirst()
                    .orElseThrow(() -> new BadRequestException("L'utilisateur n'a aucun rôle"));

            // Authorization logic
            switch (role) {
                case "ROLE_ADMIN":
                    // Admin can access any document
                    break;
                case "ROLE_CLIENT":
                    // Client can only access their own documents
                    if (moveRequest.getClient() == null ||
                            !moveRequest.getClient().getId().equals(currentUser.getId())) {
                        logger.warn("Client {} not authorized to access {} for moveId: {}", email, documentType, moveId);
                        throw new ForbiddenException("Vous n'êtes pas autorisé à accéder à ce " + documentType.toLowerCase());
                    }
                    break;
                default:
                    logger.warn("Unauthorized role {} attempting to access {} for moveId: {}", role, documentType, moveId);
                    throw new ForbiddenException("Rôle non autorisé");
            }

            // Generate PDF
            byte[] pdfBytes = pdfService.generatePdf(moveRequest, templateName);
            String filename = pdfService.generateFilename(moveRequest, documentType);

            // Prepare response headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDisposition(ContentDisposition.builder("attachment")
                    .filename(filename, StandardCharsets.UTF_8)
                    .build());
            headers.setContentLength(pdfBytes.length);

            logger.info("PDF {} generated successfully for moveId: {}, filename: {}", documentType, moveId, filename);

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(pdfBytes);

        } catch (ResourceNotFoundException | ForbiddenException | BadRequestException e) {
            // Re-throw known exceptions to be handled by global exception handler
            throw e;
        } catch (IOException e) {
            logger.error("Failed to generate PDF {} for moveId: {}", documentType, moveId, e);
            throw new RuntimeException("Erreur lors de la génération du PDF: " + e.getMessage(), e);
        } catch (Exception e) {
            logger.error("Unexpected error while generating PDF {} for moveId: {}", documentType, moveId, e);
            throw new RuntimeException("Erreur inattendue lors de la génération du PDF", e);
        }
    }
    @GetMapping("/{moveId}/details")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUB_ADMIN', 'CLIENT', 'DRIVER')")
    @Operation(summary = "Récupérer les détails d'une demande de déménagement", description = "Récupère les détails tels que la durée, la distance, la date, l'heure de départ, l'heure d'arrivée et les objets pour une demande de déménagement spécifique.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Détails de la demande de déménagement récupérés avec succès",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = MoveDetails.class),
                            examples = @ExampleObject(value = "{\"distance\": 10.5, \"durationMinutes\": 30, \"date\": \"2023-10-01\", \"departureTime\": \"2023-10-01T09:00:00\", \"arrivalTime\": \"2023-10-01T09:30:00\", \"items\": [{\"item\": {\"name\": \"Table\"}, \"quantity\": 2}], \"status\": \"APPROVED\"}"))),
            @ApiResponse(responseCode = "404", description = "Demande de déménagement non trouvée")
    })
    public ResponseEntity<MoveDetails> getMoveDetails(
            @Parameter(description = "Identifiant unique de la demande de déménagement", required = true)
            @PathVariable String moveId) {
        logger.info("Fetching details for moveId: {}", moveId);
        MoveDetails details = moveService.getMoveDetails(moveId);
        logger.info("Move details retrieved successfully for moveId: {}", moveId);
        return ResponseEntity.ok(details);
    }
    @GetMapping("/{moveId}/last-minute-details")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUB_ADMIN', 'CLIENT', 'DRIVER')")
    @Operation(summary = "Récupérer les détails d'une demande de déménagement de dernière minute",
            description = "Récupère les détails tels que la durée, la distance, la date, l'heure de départ, l'heure d'arrivée, les objets, le statut, l'adresse de départ et l'adresse de destination pour une demande de déménagement de dernière minute spécifique.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Détails de la demande de déménagement de dernière minute récupérés avec succès",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = MoveDetails.class),
                            examples = @ExampleObject(value = "{\"distance\": 10.5, \"durationMinutes\": 30, \"date\": \"2023-10-01\", \"departureTime\": \"2023-10-01T09:00:00\", \"arrivalTime\": \"2023-10-01T09:30:00\", \"items\": [{\"item\": {\"name\": \"Table\"}, \"quantity\": 2}], \"status\": \"APPROVED\", \"sourceAddress\": \"123 Rue de Paris\", \"destinationAddress\": \"456 Avenue de Lyon\"}"))),
            @ApiResponse(responseCode = "404", description = "Demande de déménagement de dernière minute non trouvée")
    })
    public ResponseEntity<MoveDetails> getLastMinuteMoveDetails(
            @Parameter(description = "Identifiant unique de la demande de déménagement de dernière minute", required = true)
            @PathVariable String moveId) {
        logger.info("Fetching last-minute details for moveId: {}", moveId);
        MoveDetails details = moveService.getLastMinuteMoveDetails(moveId);
        logger.info("Last-minute move details retrieved successfully for moveId: {}", moveId);
        return ResponseEntity.ok(details);
    }

    @Operation(
            summary = "Noter un chauffeur via un move",
            description = "Permet à un client authentifié d'ajouter une note (0-5) et un commentaire optionnel à un chauffeur associé à un move spécifique."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Note ajoutée avec succès"),
            @ApiResponse(responseCode = "400", description = "Requête invalide (ex: note hors limite, move non terminé)"),
            @ApiResponse(responseCode = "403", description = "Accès interdit (non-client ou non propriétaire du move)"),
            @ApiResponse(responseCode = "404", description = "Move ou chauffeur non trouvé")
    })
    @PostMapping("/move/{moveId}/rate")
    public ResponseEntity<Void> rateDriverViaMove(
            @PathVariable String moveId,
            @RequestBody RateRequest request,
            @AuthenticationPrincipal String email) {
        logger.info("Tentative de notation du chauffeur via move {} par l'utilisateur {}", moveId, email);

        User client = userService.findByEmail(email);
        if (client == null || client.getRole() != Role.CLIENT) {
            throw new ForbiddenException("Utilisateur client invalide");
        }

        MoveRequest move = moveService.getMoveRequestById(moveId);
        if (move == null) {
            throw new ResourceNotFoundException("Move non trouvé avec l'ID : " + moveId);
        }

        if (!move.getClient().getId().equals(client.getId())) {
            throw new ForbiddenException("Vous n'êtes pas le client pour ce move");
        }

        User driver = move.getDriver();
        if (driver == null) {
            throw new BadRequestException("Aucun chauffeur assigné à ce move");
        }

        driverService.addReview(driver.getId(), client.getId(), request.rating(), request.comment());
        logger.info("Note ajoutée avec succès pour le chauffeur via move {}", moveId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/calculate-quote")
    @PreAuthorize("hasRole('CLIENT')")
    @Operation(summary = "Calculer un devis de déménagement", description = "Calcule un devis pour un déménagement basé sur les détails fournis et envoie un email de confirmation au client authentifié. Restreint au rôle CLIENT. Limité à 20 requêtes par 24 heures par utilisateur.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Devis calculé avec succès, demande de déménagement retournée avec le coût et le jeton",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = MoveRequest.class),
                            examples = @ExampleObject(value = "{\"moveId\": \"move123\", \"preCommissionCost\": 500.0, \"status\": \"PENDING\"}"))),
            @ApiResponse(responseCode = "400", description = "Données de la requête invalides (par exemple, adresses ou objets manquants)"),
            @ApiResponse(responseCode = "401", description = "Non autorisé - authentification requise"),
            @ApiResponse(responseCode = "403", description = "Interdit - l'utilisateur n'a pas le rôle CLIENT"),
            @ApiResponse(responseCode = "429", description = "Trop de requêtes - limite de 20 requêtes par 24 heures dépassée")
    })
    public ResponseEntity<MoveRequest> calculateQuote(
            @Parameter(description = "Détails pour calculer le devis de déménagement, incluant les adresses, les étages, les objets et le mode", required = true)
            @RequestBody QuoteCalculationRequest quoteRequest,
            @AuthenticationPrincipal String email
    ) {
        logger.info("Tentative de calcul de devis pour l'email  : {}", email);

        // Check rate limit
        Bucket bucket = getUserBucket(email);
        if (!bucket.tryConsume(1)) {
            logger.warn("Rate limit exceeded for user: {}. Limit: 20 requests per 24 hours.  ", email);
            throw new BadRequestException("Vous avez dépassé la limite de 20 demandes de devis par 24 heures. Veuillez réessayer plus tard.");
        }

        MoveRequest moveRequest = moveService.calculateQuote(quoteRequest, email);
        logger.info("Devis calculé avec succès pour l'email: {}", email);
        return ResponseEntity.ok(moveRequest);
    }

    @GetMapping("/mission/{moveId}/status")
    @PreAuthorize("hasRole('CLIENT')")
    @Operation(
            summary = "Récupérer le statut d'une mission",
            description = "Récupère le statut actuel d'une mission de déménagement pour le client authentifié, incluant l'adresse source, l'adresse de destination, le coût pré-commission et le statut."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Statut de la mission récupéré avec succès",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = MissionStatusResponse.class),
                            examples = @ExampleObject(
                                    value = "{\"sourceAddress\": \"123 Rue Source\", \"destinationAddress\": \"456 Avenue Destination\", \"preCommissionCost\": 150.0, \"status\": \"EN_ROUTE\"}"
                            )
                    )
            ),
            @ApiResponse(responseCode = "403", description = "Interdit - l'utilisateur n'est pas autorisé à voir cette mission"),
            @ApiResponse(responseCode = "404", description = "Mission non trouvée")
    })
    public ResponseEntity<MissionStatusResponse> getMissionStatus(
            @Parameter(description = "Identifiant unique de la demande de déménagement", required = true)
            @PathVariable String moveId,
            @AuthenticationPrincipal String email
    ) {
        logger.info("Fetching mission status for moveId: {} by user: {}", moveId, email);
        User client = userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    logger.error("Utilisateur non trouvé pour l'email: {}", email);
                    return new ResourceNotFoundException("Utilisateur non trouvé");
                });

        MoveRequest moveRequest;
        try {
            moveRequest = moveService.getMoveRequestById(moveId);
        } catch (ResourceNotFoundException e) {
            logger.warn("Mission non trouvée pour moveId: {}", moveId);
            return ResponseEntity.ok(new MissionStatusResponse("", "", null, ""));
        }
        if (moveRequest.getClient() == null || !moveRequest.getClient().getId().equals(client.getId())) {
            logger.warn("Accès interdit: l'utilisateur {} n'est pas autorisé à voir la mission {}", email, moveId);
            throw new ForbiddenException("Vous n'êtes pas autorisé à voir cette mission");
        }

        // Check if driver is null or missionStatus is null
        if (moveRequest.getDriver() == null || moveRequest.getMissionStatus() == null) {
            logger.info("No driver assigned or mission status is null for moveId: {}", moveId);
            return ResponseEntity.ok(new MissionStatusResponse(
                    moveRequest.getSourceAddress(),
                    moveRequest.getDestinationAddress(),
                    moveRequest.getPreCommissionCost(),
                    "Pas encore assigné" // Empty mission status
            ));
        }

        MissionStatusResponse response = new MissionStatusResponse(
                moveRequest.getSourceAddress(),
                moveRequest.getDestinationAddress(),
                moveRequest.getPreCommissionCost(),
                moveRequest.getMissionStatus().getNameValue()
        );

        logger.info("Mission status retrieved successfully for moveId: {}", moveId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/confirm")
    @Operation(summary = "Confirmer une demande de déménagement", description = "Confirme une demande de déménagement à l'aide d'un jeton de confirmation. Met à jour le statut du déménagement à APPROUVÉ si le jeton est valide.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Demande de déménagement confirmée avec succès",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = String.class),
                            examples = @ExampleObject(value = "\"Demande de déménagement confirmée avec succès\""))),
            @ApiResponse(responseCode = "400", description = "Jeton invalide ou expiré")
    })
    public ResponseEntity<String> confirmMove(
            @Parameter(description = "Jeton de confirmation reçu par email pour valider la demande de déménagement", required = true)
            @RequestParam("token") String token
    ) {
        logger.info("Tentative de confirmation de déménagement avec le jeton: {}", token);
        moveService.confirmMoveRequest(token);
        logger.info("Demande de déménagement confirmée avec succès");
        return ResponseEntity.ok("Demande de déménagement confirmée avec succès");
    }

    @PostMapping("/{moveId}/photos")
    @PreAuthorize("hasRole('DRIVER')")
    @Operation(summary = "Téléverser des photos pour un déménagement", description = "Permet à un chauffeur de téléverser des liens de photos pour une demande de déménagement spécifique. Déclenche un email de confirmation au client avec un jeton. Restreint au rôle CHAUFFEUR.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Photos téléversées avec succès",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = String.class),
                            examples = @ExampleObject(value = "\"Photos téléversées avec succès\""))),
            @ApiResponse(responseCode = "400", description = "Données de la requête invalides ou statut inapproprié"),
            @ApiResponse(responseCode = "401", description = "Non autorisé - authentification requise"),
            @ApiResponse(responseCode = "403", description = "Interdit - l'utilisateur n'a pas le rôle CHAUFFEUR"),
            @ApiResponse(responseCode = "404", description = "Demande de déménagement non trouvée")
    })
    public ResponseEntity<String> uploadPhotos(
            @Parameter(description = "Identifiant unique de la demande de déménagement", required = true)
            @PathVariable String moveId,
            @Parameter(description = "Corps de la requête contenant une liste d'URLs de photos", required = true)
            @RequestBody UploadPhotosRequest request
    ) {
        logger.info("Tentative de téléversement de photos pour le déménagement: {}", moveId);
        if (request.photoLinks() == null || request.photoLinks().isEmpty()) {
            logger.warn("Échec du téléversement de photos: Liste de liens de photos vide");
            throw new BadRequestException("La liste des liens de photos ne peut pas être nulle ou vide");
        }
        moveService.addPhotosToMove(moveId, request.photoLinks());
        logger.info("Photos téléversées avec succès pour le déménagement: {}", moveId);
        return ResponseEntity.ok("Photos téléversées avec succès");
    }

    @GetMapping("/confirm-photos")
    @Operation(summary = "Confirmer la réception des photos", description = "Confirme la réception des photos pour une demande de déménagement à l'aide d'un jeton envoyé au client. Met à jour le statut photosConfirmed à true.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Réception des photos confirmée avec succès",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = String.class),
                            examples = @ExampleObject(value = "\"Réception des photos confirmée avec succès\""))),
            @ApiResponse(responseCode = "400", description = "Jeton invalide ou expiré"),
            @ApiResponse(responseCode = "404", description = "Demande de déménagement non trouvée")
    })
    public ResponseEntity<String> confirmPhotos(
            @Parameter(description = "Jeton de confirmation reçu par email du chauffeur", required = true)
            @RequestParam("token") String token
    ) {
        logger.info("Tentative de confirmation des photos avec le jeton: {}", token);
        moveService.confirmPhotos(token);
        logger.info("Réception des photos confirmée avec succès");
        return ResponseEntity.ok("Réception des photos confirmée avec succès");
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'DRIVER', 'CLIENT')")
    @Operation(summary = "Récupérer toutes les demandes de déménagement", description = "Récupère toutes les demandes de déménagement avec une visibilité des coûts basée sur le rôle de l'utilisateur.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Liste des demandes de déménagement récupérée avec succès",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = MoveRequest.class),
                            examples = @ExampleObject(value = "[{\"moveId\": \"move123\", \"status\": \"PENDING\", \"preCommissionCost\": 500.0}]"))),
            @ApiResponse(responseCode = "401", description = "Non autorisé - authentification requise"),
            @ApiResponse(responseCode = "403", description = "Interdit - l'utilisateur n'a pas le rôle requis")
    })
    public ResponseEntity<List<MoveRequest>> getAllMoves() {
        logger.info("Tentative de récupération de toutes les demandes de déménagement");
        List<MoveRequest> moves = moveService.getAllMoveRequests();
        String role = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                .findFirst()
                .orElseThrow(() -> {
                    logger.error("Échec de récupération: L'utilisateur n'a aucun rôle malgré l'authentification");
                    return new BadRequestException("L'utilisateur n'a aucun rôle malgré l'authentification");
                });

        List<MoveRequest> filteredMoves = moves.stream().map(move -> {
            MoveRequest filteredMove = new MoveRequest();
            filteredMove.setMoveId(move.getMoveId());
            filteredMove.setSourceAddress(move.getSourceAddress());
            filteredMove.setDestinationAddress(move.getDestinationAddress());
            filteredMove.setSourceFloors(move.getSourceFloors());
            filteredMove.setSourceElevator(move.isSourceElevator());
            filteredMove.setDestinationFloors(move.getDestinationFloors());
            filteredMove.setDestinationElevator(move.isDestinationElevator());
            filteredMove.setItems(move.getItems());
            filteredMove.setMode(move.getMode());
            filteredMove.setStatus(move.getStatus());
            filteredMove.setClientEmail(move.getClientEmail());
            filteredMove.setConfirmationToken(move.getConfirmationToken());
            filteredMove.setConfirmationTokenExpiry(move.getConfirmationTokenExpiry());
            filteredMove.setCurrentLocation(move.getCurrentLocation());
            filteredMove.setPhotoLinks(move.getPhotoLinks());
            filteredMove.setPhotoConfirmationToken(move.getPhotoConfirmationToken());
            filteredMove.setPhotoConfirmationTokenExpiry(move.getPhotoConfirmationTokenExpiry());
            filteredMove.setPhotosConfirmed(move.isPhotosConfirmed());
            filteredMove.setClient(move.getClient());
            filteredMove.setDriver(move.getDriver());
            filteredMove.setMissionStatus(move.getMissionStatus());

            switch (role) {
                case "ROLE_ADMIN":
                    filteredMove.setPreCommissionCost(move.getPreCommissionCost());
                    filteredMove.setPostCommissionCost(move.getPostCommissionCost());
                    break;
                case "ROLE_CLIENT":
                    filteredMove.setPreCommissionCost(move.getPreCommissionCost());
                    filteredMove.setPostCommissionCost(null);
                    break;
                case "ROLE_DRIVER":
                    filteredMove.setPreCommissionCost(null);
                    filteredMove.setPostCommissionCost(move.getPostCommissionCost());
                    break;
            }
            return filteredMove;
        }).collect(Collectors.toList());

        logger.info("Demandes de déménagement récupérées avec succès, {} demandes", filteredMoves.size());
        return ResponseEntity.ok(filteredMoves);
    }

    @GetMapping("/subadmin")
    @PreAuthorize("hasRole('SUB_ADMIN')")
    @Operation(summary = "Récupérer les missions pour un sous-administrateur", description = "Récupère les missions assignées aux chauffeurs gérés par le sous-administrateur authentifié, avec visibilité sur les coûts post-commission.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Liste des missions récupérée avec succès",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = MoveRequest.class),
                            examples = @ExampleObject(value = "[{\"moveId\": \"move123\", \"status\": \"APPROVED\", \"postCommissionCost\": 450.0}]"))),
            @ApiResponse(responseCode = "401", description = "Non autorisé - authentification requise"),
            @ApiResponse(responseCode = "403", description = "Interdit - l'utilisateur n'a pas le rôle SUB_ADMIN")
    })
    public ResponseEntity<List<MoveRequest>> getSubadminMoves(
            @AuthenticationPrincipal String email
    ) {
        logger.info("Tentative de récupération des missions pour le sous-administrateur: {}", email);
        User subadmin = userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    logger.error("Sous-administrateur non trouvé pour l'email: {}", email);
                    return new ResourceNotFoundException("Sous-administrateur non trouvé");
                });

        List<MoveRequest> moves = moveService.getMovesForSubadmin(subadmin.getId());

        List<MoveRequest> filteredMoves = moves.stream().map(move -> {
            MoveRequest filteredMove = new MoveRequest();
            filteredMove.setMoveId(move.getMoveId());
            filteredMove.setSourceAddress(move.getSourceAddress());
            filteredMove.setDestinationAddress(move.getDestinationAddress());
            filteredMove.setSourceFloors(move.getSourceFloors());
            filteredMove.setSourceElevator(move.isSourceElevator());
            filteredMove.setDestinationFloors(move.getDestinationFloors());
            filteredMove.setDestinationElevator(move.isDestinationElevator());
            filteredMove.setItems(move.getItems());
            filteredMove.setMode(move.getMode());
            filteredMove.setPaymentStatus(move.getPaymentStatus());
            filteredMove.setStatus(move.getStatus());
            filteredMove.setMissionStatus(move.getMissionStatus());
            filteredMove.setClientEmail(move.getClientEmail());
            filteredMove.setConfirmationToken(move.getConfirmationToken());
            filteredMove.setConfirmationTokenExpiry(move.getConfirmationTokenExpiry());
            filteredMove.setCurrentLocation(move.getCurrentLocation());
            filteredMove.setPhotoLinks(move.getPhotoLinks());
            filteredMove.setPhotoConfirmationToken(move.getPhotoConfirmationToken());
            filteredMove.setPhotoConfirmationTokenExpiry(move.getPhotoConfirmationTokenExpiry());
            filteredMove.setPhotosConfirmed(move.isPhotosConfirmed());
            filteredMove.setClient(move.getClient());
            filteredMove.setDriver(move.getDriver());
            filteredMove.setPreCommissionCost(null);
            filteredMove.setPostCommissionCost(move.getPostCommissionCost());
            return filteredMove;
        }).collect(Collectors.toList());

        logger.info("Missions récupérées avec succès pour le sous-administrateur, {} missions", filteredMoves.size());
        return ResponseEntity.ok(filteredMoves);
    }

    @GetMapping("/subadmin/online-drivers")
    @PreAuthorize("hasRole('SUB_ADMIN')")
    @Operation(
            summary = "Récupérer la liste des chauffeurs en ligne et libres",
            description = "Récupère la liste des chauffeurs en ligne et non assignés à une mission, accessibles uniquement aux sous-administrateurs."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Liste des chauffeurs en ligne et libres récupérée avec succès",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Driver.class),
                            examples = @ExampleObject(value = "[{\"id\": \"driver123\", \"firstName\": \"Jean\", \"lastName\": \"Dupont\", \"email\": \"jean.dupont@example.com\", \"phoneNumber\": \"+1234567890\", \"role\": \"DRIVER\"}]")
                    )
            ),
            @ApiResponse(responseCode = "401", description = "Non autorisé - authentification requise"),
            @ApiResponse(responseCode = "403", description = "Interdit - l'utilisateur n'a pas le rôle SUB_ADMIN"),
            @ApiResponse(responseCode = "404", description = "Sous-administrateur non trouvé")
    })
    public ResponseEntity<List<Driver>> getOnlineFreeDrivers(
            @Parameter(description = "Email du sous-administrateur authentifié", hidden = true)
            @AuthenticationPrincipal String email
    ) {
        logger.info("Fetching online and free drivers for subadmin: {}", email);

        // Validate subadmin
        User subadmin = userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    logger.error("Sous-administrateur non trouvé pour l'email : {}", email);
                    return new ResourceNotFoundException("Sous-administrateur non trouvé");
                });

        // Get busy driver IDs
        List<String> busyDriverIds = moveService.getBusyDriverIds();

        // Get online drivers and filter for those managed by the subadmin and not busy
        List<Driver> onlineFreeDrivers = moveService.getOnlineDrivers().stream()
                .filter(driverLocation -> !busyDriverIds.contains(driverLocation.getDriverId()))
                .map(driverLocation -> driverRepository.findById(driverLocation.getDriverId()).orElse(null))
                .filter(driver -> driver != null && driver.getCreatedBySubAdminId() != null &&
                        driver.getCreatedBySubAdminId().getId().equals(subadmin.getId()))
                .collect(Collectors.toList());

        logger.info("Online and free drivers retrieved successfully for subadmin: {}, {} drivers", email, onlineFreeDrivers.size());
        return ResponseEntity.ok(onlineFreeDrivers);
    }

    @PatchMapping("/{moveId}/update-location")
    @PreAuthorize("hasRole('DRIVER')")
    @Operation(summary = "Mettre à jour l'emplacement du chauffeur pour un déménagement", description = "Permet à un chauffeur de mettre à jour son emplacement actuel pour un déménagement assigné.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Emplacement mis à jour avec succès",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = String.class),
                            examples = @ExampleObject(value = "\"Emplacement mis à jour\""))),
            @ApiResponse(responseCode = "403", description = "Interdit - le chauffeur n'est pas assigné à cette mission"),
            @ApiResponse(responseCode = "404", description = "Demande de déménagement non trouvée")
    })
    public ResponseEntity<String> updateMoveLocation(
            @Parameter(description = "Identifiant unique de la demande de déménagement", required = true)
            @PathVariable String moveId,
            @Parameter(description = "Nouvel emplacement avec latitude et longitude", required = true)
            @RequestBody LocationUpdateRequest locationRequest,
            @AuthenticationPrincipal User driver
    ) {
        logger.info("Tentative de mise à jour de l'emplacement pour le déménagement: {} par le chauffeur: {}", moveId, driver.getId());
        MoveRequest moveRequest = moveService.getMoveRequestById(moveId);
        if (moveRequest.getDriver() == null || !moveRequest.getDriver().getId().equals(driver.getId())) {
            logger.warn("Échec de la mise à jour de l'emplacement: Chauffeur non assigné à la mission {}", moveId);
            throw new ForbiddenException("Vous n'êtes pas assigné à cette mission");
        }
        GeoJsonPoint location = new GeoJsonPoint(locationRequest.longitude(), locationRequest.latitude());
        moveService.updateMoveLocation(moveId, location);
        logger.info("Emplacement mis à jour avec succès pour le déménagement: {}", moveId);
        return ResponseEntity.ok("Emplacement mis à jour");
    }

    @PatchMapping("/{moveId}/complete")
    @PreAuthorize("hasRole('DRIVER')")
    @Operation(summary = "Terminer un déménagement", description = "Marque un déménagement comme terminé. Restreint aux utilisateurs avec le rôle CHAUFFEUR.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Déménagement terminé avec succès",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = String.class),
                            examples = @ExampleObject(value = "\"Déménagement terminé avec succès\""))),
            @ApiResponse(responseCode = "401", description = "Non autorisé - authentification requise"),
            @ApiResponse(responseCode = "403", description = "Interdit - l'utilisateur n'a pas le rôle CHAUFFEUR"),
            @ApiResponse(responseCode = "404", description = "Demande de déménagement non trouvée")
    })
    public ResponseEntity<String> completeMove(
            @Parameter(description = "Identifiant unique de la demande de déménagement", required = true)
            @PathVariable String moveId
    ) {
        logger.info("Tentative de marquage du déménagement comme terminé: {}", moveId);
        moveService.completeMove(moveId);
        logger.info("Déménagement terminé avec succès: {}", moveId);
        return ResponseEntity.ok("Déménagement terminé avec succès");
    }

    @GetMapping("/{moveId}/photos/view")
    @PreAuthorize("hasAnyRole('CLIENT', 'SUB_ADMIN')")
    @Operation(summary = "Voir les photos d'une demande de déménagement", description = "Retourne les liens des photos pour un déménagement si l'utilisateur est soit le client du déménagement, soit le sous-administrateur qui a créé le chauffeur de ce déménagement.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Liens des photos récupérés avec succès",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = List.class),
                            examples = @ExampleObject(value = "[\"https://example.com/photo1.jpg\", \"https://example.com/photo2.jpg\"]"))),
            @ApiResponse(responseCode = "401", description = "Non autorisé - authentification requise"),
            @ApiResponse(responseCode = "403", description = "Interdit - l'utilisateur n'a pas la permission requise"),
            @ApiResponse(responseCode = "404", description = "Demande de déménagement non trouvée")
    })
    public ResponseEntity<List<String>> viewMovePhotos(
            @Parameter(description = "Identifiant unique de la demande de déménagement", required = true)
            @PathVariable("moveId") String moveId,
            @AuthenticationPrincipal User currentUser
    ) {
        logger.info("Tentative de récupération des photos pour le déménagement: {} par l'utilisateur: {}", moveId, currentUser.getEmail());
        List<String> photoLinks = moveService.viewMovePhotos(moveId, currentUser);
        logger.info("Photos récupérées avec succès pour le déménagement: {}", moveId);
        return ResponseEntity.ok(photoLinks);
    }

    @PostMapping("/{moveId}/accept")
    @PreAuthorize("hasRole('DRIVER')")
    @Operation(summary = "Accepter une offre de mission", description = "Permet à un chauffeur d'accepter une offre de mission. Le chauffeur doit être authentifié et assigné à la mission.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Mission acceptée avec succès",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = String.class),
                            examples = @ExampleObject(value = "\"Mission acceptée avec succès\""))),
            @ApiResponse(responseCode = "400", description = "La mission n'est plus en attente"),
            @ApiResponse(responseCode = "403", description = "Le chauffeur n'est pas actuellement assigné à cette mission"),
            @ApiResponse(responseCode = "404", description = "Demande de déménagement non trouvée")
    })
    public ResponseEntity<String> acceptMission(
            @Parameter(description = "Identifiant unique de la demande de déménagement", required = true)
            @PathVariable String moveId,
            @AuthenticationPrincipal String email
    ) {
        logger.info("Driver {} attempting to accept mission for moveId: {}", email, moveId);
        User driver = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur non trouvé"));
        moveService.handleDriverAcceptance(moveId, driver.getId());
        logger.info("Mission accepted successfully by driver {} for moveId: {}", driver.getId(), moveId);
        return ResponseEntity.ok("Mission acceptée avec succès");
    }

    @PostMapping("/{moveId}/decline")
    @PreAuthorize("hasRole('DRIVER')")
    @Operation(summary = "Refuser une offre de mission", description = "Permet à un chauffeur de refuser une offre de mission. Le chauffeur doit être authentifié et assigné à la mission.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Mission refusée avec succès",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = String.class),
                            examples = @ExampleObject(value = "\"Mission refusée avec succès\""))),
            @ApiResponse(responseCode = "400", description = "La mission n'est plus en attente"),
            @ApiResponse(responseCode = "403", description = "Le chauffeur n'est pas actuellement assigné à cette mission"),
            @ApiResponse(responseCode = "404", description = "Demande de déménagement non trouvée")
    })
    public ResponseEntity<String> declineMission(
            @Parameter(description = "Identifiant unique de la demande de déménagement", required = true)
            @PathVariable String moveId,
            @AuthenticationPrincipal String email
    ) {
        logger.info("Driver with email {} attempting to decline mission for moveId: {}", email, moveId);
        User driver = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur non trouvé"));
        moveService.handleDriverDecline(moveId, driver.getId());
        logger.info("Mission declined successfully by driver {} for moveId: {}", driver.getId(), moveId);
        return ResponseEntity.ok("Mission refusée avec succès");
    }

    @GetMapping("/{moveId}/driver-history")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUB_ADMIN')")
    @Operation(summary = "Récupérer l'historique des chauffeurs pour un déménagement", description = "Récupère l'historique des actions effectuées par les chauffeurs pour une demande de déménagement spécifique.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Historique des chauffeurs récupéré avec succès",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Map.class),
                            examples = @ExampleObject(value = "{\"driverId1\": [{\"eventType\": \"MISSION_OFFERED_TO_DRIVER\", \"timestamp\": \"2025-05-13T09:29:26.788Z\", \"details\": \"Offered to driver driverId1\", \"triggeredBy\": \"SYSTEM\"}]}"))),
            @ApiResponse(responseCode = "401", description = "Non autorisé - authentification requise"),
            @ApiResponse(responseCode = "403", description = "Interdit - l'utilisateur n'a pas le rôle requis"),
            @ApiResponse(responseCode = "404", description = "Demande de déménagement non trouvée")
    })
    public ResponseEntity<Map<String, List<MissionHistory>>> getDriverHistoryForMove(
            @Parameter(description = "Identifiant unique de la demande de déménagement", required = true)
            @PathVariable String moveId) {
        logger.info("Fetching driver history for moveId: {}", moveId);
        Map<String, List<MissionHistory>> driverHistory = moveService.getDriverHistoryForMove(moveId);
        logger.info("Driver history retrieved successfully for moveId: {}", moveId);
        return ResponseEntity.ok(driverHistory);
    }

    @GetMapping("/subadmin/{driverId}/mission-history")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUB_ADMIN')")
    @Operation(summary = "Get mission history for a driver", description = "Retrieves all mission history events for a specific driver across all missions they have participated in.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of mission history events retrieved successfully",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = MissionHistory.class),
                            examples = @ExampleObject(value = "[{\"missionId\": \"mission1\", \"eventType\": \"MISSION_OFFERED_TO_DRIVER\", \"timestamp\": \"2025-05-13T09:49:54.820Z\", \"details\": \"Offered to driver 67ff782fa445871113f59fcf\", \"triggeredBy\": \"SYSTEM\"}]"))),
            @ApiResponse(responseCode = "403", description = "Forbidden - User does not have permission to view this driver's history"),
            @ApiResponse(responseCode = "404", description = "Driver not found")
    })
    public ResponseEntity<List<MissionHistory>> getDriverMissionHistory(
            @Parameter(description = "Identifiant unique du chauffeur", required = true)
            @PathVariable String driverId,
            @AuthenticationPrincipal String email) {
        logger.info("Fetching mission history for driverId: {} by user: {}", driverId, email);
        User subadmin = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur non trouvé"));
        List<MissionHistory> history = moveService.getDriverMissionHistory(driverId, subadmin);
        logger.info("Mission history retrieved successfully for driverId: {}", driverId);
        return ResponseEntity.ok(history);
    }

    @GetMapping("/driver/mission-summaries")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUB_ADMIN')")
    @Operation(summary = "Get mission summaries for a driver", description = "Retrieves a summary of all missions for a specific driver, including duration, distance, source address, product count, amount, and status.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of mission summaries retrieved successfully",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = DriverMissionSummaryDTO.class),
                            examples = @ExampleObject(value = "[{\"moveId\": \"move123\", \"duration\": \"4h30\", \"distanceKm\": 360.0, \"sourceAddress\": \"6391 Elgin St. Celina, Delaware 10299\", \"productCount\": 16, \"amount\": 2260.30, \"status\": \"COMPLETED\"}]"))),
            @ApiResponse(responseCode = "403", description = "Forbidden - User does not have permission to view this driver's summaries"),
            @ApiResponse(responseCode = "404", description = "Driver not found")
    })
    public ResponseEntity<List<DriverMissionSummaryDTO>> getDriverMissionSummaries(
            @Parameter(description = "Unique identifier of the driver", required = true)
            @AuthenticationPrincipal String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        List<DriverMissionSummaryDTO> summaries = moveService.getDriverMissionSummaries(user.getId());
        logger.info("Mission summaries retrieved successfully for driverId: {}", user.getId());
        return ResponseEntity.ok(summaries);
    }

    @GetMapping("/latest")
    @PreAuthorize("hasRole('CLIENT')")
    @Operation(
            summary = "Récupérer la mission la plus récente du client",
            description = "Récupère la mission la plus récente pour le client authentifié sous forme de résumé (DriverMissionSummaryDTO), basée sur la date d'assignation. Restreint au rôle CLIENT."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Mission la plus récente récupérée avec succès",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = DriverMissionSummaryDTO.class),
                            examples = @ExampleObject(
                                    value = "{\"moveId\": \"move123\", \"duration\": \"4h30\", \"distanceKm\": 360.0, \"sourceAddress\": \"123 Rue Source\", \"productCount\": 16, \"amount\": 150.0, \"status\": \"EN_ROUTE\"}"
                            )
                    )
            ),
            @ApiResponse(responseCode = "204", description = "Aucune mission trouvée pour le client"),
            @ApiResponse(responseCode = "401", description = "Non autorisé - authentification requise"),
            @ApiResponse(responseCode = "403", description = "Interdit - l'utilisateur n'a pas le rôle CLIENT"),
            @ApiResponse(
                    responseCode = "404",
                    description = "Utilisateur non trouvé",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class),
                            examples = @ExampleObject(
                                    value = "{\"timestamp\": \"2025-07-14T12:00:00Z\", \"status\": 404, \"error\": \"Not Found\", \"message\": \"Utilisateur non trouvé\", \"path\": \"/latest\", \"code\": null, \"userId\": null}"
                            )
                    )
            )
    })
    public ResponseEntity<List<DriverMissionSummaryDTO>> getLatestMission(
            @Parameter(description = "Email du client authentifié", hidden = true)
            @AuthenticationPrincipal String email
    ) {
        logger.info("Fetching latest mission for client with email: {}", email);

        // Retrieve the authenticated user
        User client = userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    logger.error("Utilisateur non trouvé pour l'email : {}", email);
                    return new ResourceNotFoundException("Utilisateur non trouvé");
                });

        List<DriverMissionSummaryDTO> latestMissions = moveService.getLatestMissionSummaryForClient(client.getId());
        if (latestMissions.isEmpty()) {
            logger.info("No missions found for client: {}", email);
            return ResponseEntity.noContent().build();
        }

        return ResponseEntity.ok(latestMissions);
    }

    @PatchMapping("/{moveId}/items")
    @PreAuthorize("hasAnyRole('CLIENT', 'DRIVER')")
    @Operation(
            summary = "Update items for a move request",
            description = "Allows a client or assigned driver to update the list of items for a specific move request."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Items updated successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = String.class),
                            examples = @ExampleObject(value = "\"Items updated successfully\"")
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request data (e.g., empty items list)",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Forbidden - User is not authorized to update items for this move",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Move request not found",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)
                    )
            )
    })
    public ResponseEntity<String> updateMoveItems(
            @PathVariable String moveId,
            @RequestBody UpdateItemsRequest request,
            @AuthenticationPrincipal String email
    ) {
        logger.info("Attempting to update items for moveId: {} by user: {}", moveId, email);

        // Validate moveId matches request
        if (!moveId.equals(request.moveId())) {
            logger.warn("Mismatch between path moveId: {} and request moveId: {}", moveId, request.moveId());
            throw new BadRequestException("Move ID in path and request body must match");
        }

        // Fetch authenticated user
        User currentUser = userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    logger.error("User not found for email: {}", email);
                    return new ResourceNotFoundException("Utilisateur non trouvé");
                });

        // Update items via service
        moveService.updateMoveItems(moveId, request.items(), currentUser);

        logger.info("Items updated successfully for moveId: {}", moveId);
        return ResponseEntity.ok("Items updated successfully");
    }
    @GetMapping("/last-minute-moves")
    @Operation(
            summary = "Récupérer toutes les demandes de déménagement Last Minute",
            description = "Retourne la liste complète des déménagements de type LAST_MINUTE avec adresses définies, excluant ceux avec le statut LOADING_COMPLETE."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Liste récupérée avec succès"),
            @ApiResponse(responseCode = "500", description = "Erreur lors de la récupération des données")
    })
    public ResponseEntity<List<LastMinuteMove>> getAllLastMinuteMoves() {
        List<LastMinuteMove> lastMinuteMoves = lastMinuteMoveRepository
                .findByModeAndBookedAndMissionStatusNot(QuotationType.LAST_MINUTE, false, "COMPLETED");
        return ResponseEntity.ok(lastMinuteMoves);
    }




    @PostMapping("/create-last-minute/{moveId}")
    public ResponseEntity<LastMinuteMove> createLastMinuteMove(
            @PathVariable String moveId,
            @RequestBody LastMinuteMoveRequest request) throws IOException, InterruptedException, com.google.maps.errors.ApiException, ExecutionException {
        LastMinuteMove newMove = moveService.createFromAcceptedMove(moveId, request);
        return ResponseEntity.ok(newMove);
    }
    @PatchMapping("/last-minute-moves/{moveId}")
    public LastMinuteMove selectItineraries(
            @PathVariable String moveId,
            @RequestBody SelectItineraryRequest request
    ) {
        LastMinuteMove move = lastMinuteMoveRepository.findById(moveId)
                .orElseThrow(() -> new IllegalArgumentException("Move not found: " + moveId));

        List<Itinerary> selectedItineraries = move.getItineraries().stream()
                .filter(it -> request.getItineraryIds().contains(it.getId()))
                .toList();

        if (selectedItineraries.isEmpty()) {
            throw new IllegalArgumentException("No matching itineraries found for IDs: " + request.getItineraryIds());
        }

        move.setSelectedItineraries(selectedItineraries);

        return lastMinuteMoveRepository.save(move);
    }

    @PatchMapping("/calculate-quote/{lastMinuteMoveId}")
    @PreAuthorize("hasRole('CLIENT')")
    @Operation(summary = "Mettre à jour un devis de déménagement existant", description = "Calcule un devis pour un déménagement existant basé sur les détails fournis et envoie un email de confirmation au client authentifié. Restreint au rôle CLIENT.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Devis mis à jour avec succès, détails du déménagement retournés",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = LastMinuteMove.class),
                            examples = @ExampleObject(value = "{\"id\": \"lastMinuteMove123\", \"sourceAddress\": \"123 Rue Source\", \"destinationAddress\": \"456 Avenue Destination\", \"preCommissionCost\": 150.0, \"postCommissionCost\": 135.0, \"items\": [{\"itemLabel\": \"Table\", \"quantity\": 2}]}"))),
            @ApiResponse(responseCode = "400", description = "Données de la requête invalides"),
            @ApiResponse(responseCode = "401", description = "Non autorisé - authentification requise"),
            @ApiResponse(responseCode = "403", description = "Interdit - l'utilisateur n'a pas le rôle CLIENT"),
            @ApiResponse(responseCode = "429", description = "Trop de requêtes - limite de 20 requêtes par 24 heures dépassée")
    })
    public ResponseEntity<LastMinuteMove> calculateLastMinuteQuote(
            @PathVariable String lastMinuteMoveId,
            @RequestBody QuoteCalculationRequest request,
            @AuthenticationPrincipal String email
    ) {
        logger.info("Tentative de calcul de devis pour l'email: {}", email);

        // Check rate limit
        Bucket bucket = getUserBucket(email);
        if (!bucket.tryConsume(1)) {
            logger.warn("Rate limit exceeded for user: {}. Limit: 20 requests per 24 hours.", email);
            throw new BadRequestException("Vous avez dépassé la limite de 20 demandes de devis par 24 heures. Veuillez réessayer plus tard.");
        }

        LastMinuteMove move = moveService.calculateLastMinuteQuote(lastMinuteMoveId, request, email);
        logger.info("Devis mis à jour avec succès pour l'email: {}", email);
        return ResponseEntity.ok(move);
    }
    @PostMapping("/{moveId}/assign-driver")
    @PreAuthorize("hasRole('SUB_ADMIN')")
    @Operation(
            summary = "Assigner un chauffeur à une mission",
            description = "Permet à un sous-administrateur d'assigner un chauffeur spécifique à une mission de déménagement. Le chauffeur doit être en ligne, non occupé, et géré par le sous-administrateur."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Chauffeur assigné avec succès à la mission",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = String.class),
                            examples = @ExampleObject(value = "\"Chauffeur assigné avec succès à la mission\"")
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Requête invalide - mission non dans un état assignable ou chauffeur non disponible"
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Non autorisé - authentification requise"
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Interdit - l'utilisateur n'a pas le rôle SUB_ADMIN ou le chauffeur n'est pas géré par ce sous-administrateur"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Mission ou chauffeur non trouvé"
            )
    })
    public ResponseEntity<String> assignDriverToMission(
            @Parameter(description = "Identifiant unique de la demande de déménagement", required = true)
            @PathVariable String moveId,
            @Parameter(description = "Identifiant du chauffeur à assigner", required = true)
            @RequestBody AssignDriverRequest request,
            @Parameter(description = "Email du sous-administrateur authentifié", hidden = true)
            @AuthenticationPrincipal String email
    ) {
        logger.info("Subadmin {} attempting to assign driver {} to mission {}", email, request.driverId(), moveId);

        // Validate subadmin
        User subadmin = userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    logger.error("Subadmin not found for email: {}", email);
                    return new ResourceNotFoundException("Sous-administrateur non trouvé");
                });

        if (subadmin.getRole() != Role.SUB_ADMIN) {
            logger.warn("User {} does not have SUB_ADMIN role", email);
            throw new ForbiddenException("Vous n'êtes pas autorisé à effectuer cette action");
        }

        // Call service to assign driver
        moveService.assignDriverToMission(moveId, request.driverId(), subadmin);

        logger.info("Driver {} assigned successfully to mission {} by subadmin {}", request.driverId(), moveId, email);
        return ResponseEntity.ok("Chauffeur assigné avec succès à la mission");
    }

    // Define the request DTO
    public record AssignDriverRequest(String driverId) {}


    @GetMapping("/subadmin/filtered-missions")
    @PreAuthorize("hasRole('SUB_ADMIN')")
    @Operation(
            summary = "Récupérer les missions filtrées pour un sous-administrateur",
            description = "Récupère les missions avec missionStatus=ACCEPTED ou avec status=PENDING et paymentStatus=paid pour les chauffeurs gérés par le sous-administrateur authentifié."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Liste des missions filtrées récupérée avec succès",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = MoveRequest.class),
                            examples = @ExampleObject(value = "[{\"moveId\": \"move123\", \"status\": \"PENDING\", \"paymentStatus\": \"paid\", \"missionStatus\": \"PENDING\", \"postCommissionCost\": 450.0}, {\"moveId\": \"move124\", \"status\": \"APPROVED\", \"paymentStatus\": \"paid\", \"missionStatus\": \"ACCEPTED\", \"postCommissionCost\": 600.0}]")
                    )
            ),
            @ApiResponse(responseCode = "401", description = "Non autorisé - authentification requise"),
            @ApiResponse(responseCode = "403", description = "Interdit - l'utilisateur n'a pas le rôle SUB_ADMIN"),
            @ApiResponse(responseCode = "404", description = "Sous-administrateur non trouvé")
    })
    public ResponseEntity<List<MoveRequest>> getFilteredMissions(
            @Parameter(description = "Email du sous-administrateur authentifié", hidden = true)
            @AuthenticationPrincipal String email
    ) {
        logger.info("Fetching filtered missions for subadmin: {}", email);

        User subadmin = userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    logger.error("Sous-administrateur non trouvé pour l'email  : {}", email);
                    return new ResourceNotFoundException("Sous-administrateur non trouvé");
                });

        List<MoveRequest> missions = moveService.getFilteredMissionsForSubadmin(subadmin.getId());
        logger.info("Filtered missions retrieved successfully for subadmin: {}, {} missions", email, missions.size());
        return ResponseEntity.ok(missions);
    }


}
