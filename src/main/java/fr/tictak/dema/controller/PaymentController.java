package fr.tictak.dema.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import fr.tictak.dema.dto.in.MissionNotification;
import fr.tictak.dema.dto.in.PaymentRequest;
import fr.tictak.dema.exception.ForbiddenException;
import fr.tictak.dema.exception.GlobalExceptionHandler;
import fr.tictak.dema.exception.ResourceNotFoundException;
import fr.tictak.dema.model.LastMinuteMove;
import fr.tictak.dema.model.MoveRequest;
import fr.tictak.dema.model.Notification;
import fr.tictak.dema.model.enums.MissionStatus;
import fr.tictak.dema.model.mapper.LastMinuteMoveMapper;
import fr.tictak.dema.model.user.User;
import fr.tictak.dema.repository.LastMinuteMoveRepository;
import fr.tictak.dema.repository.MoveRequestRepository;
import fr.tictak.dema.repository.UserRepository;
import fr.tictak.dema.service.MoveService;
import fr.tictak.dema.service.StripeService;
import fr.tictak.dema.service.implementation.GoogleMapsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/payments")
@Tag(name = "Paiements", description = "API pour la gestion des opérations de paiement avec Stripe, incluant la création de sessions de paiement, le traitement des webhooks et la gestion des pages de succès et d'annulation.")
public class PaymentController {

    private final MoveRequestRepository moveRequestRepository;
    private final StripeService stripeService;
    private final UserRepository userRepository;
    private final MoveService moveService;
    private final SpringTemplateEngine templateEngine;
    private final JavaMailSender mailSender;
    private final LastMinuteMoveRepository lastMinuteMoveRepository;
    private final ObjectMapper objectMapper;
    private final GoogleMapsService googleMapsService;
    private final SimpMessagingTemplate messagingTemplate;

    @Autowired
    public PaymentController(MoveRequestRepository moveRequestRepository,
                             StripeService stripeService,
                             UserRepository userRepository,
                             MoveService moveService,
                             SpringTemplateEngine templateEngine,
                             JavaMailSender mailSender,
                             LastMinuteMoveRepository lastMinuteMoveRepository,
                             ObjectMapper objectMapper,
                             GoogleMapsService googleMapsService,
                             SimpMessagingTemplate messagingTemplate) {
        this.moveRequestRepository = moveRequestRepository;
        this.stripeService = stripeService;
        this.userRepository = userRepository;
        this.moveService = moveService;
        this.templateEngine = templateEngine;
        this.mailSender = mailSender;
        this.lastMinuteMoveRepository = lastMinuteMoveRepository;
        this.objectMapper = objectMapper;
        this.googleMapsService = googleMapsService;
        this.messagingTemplate = messagingTemplate;
    }

    @Operation(summary = "Créer une session de paiement Stripe",
            description = "Crée une session de paiement Stripe pour une demande de déménagement. Seule le client propriétaire de la demande de déménagement peut initier cette opération.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Session de paiement créée avec succès",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Map.class),
                            examples = @ExampleObject(value = """
                                    {
                                        "url": "https://checkout.stripe.com/pay/cs_test_..."
                                    }
                                    """))),
            @ApiResponse(responseCode = "403", description = "L'utilisateur n'est pas autorisé à initier le paiement pour cette demande de déménagement",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                        "timestamp": "2025-04-15T12:00:00.000Z",
                                        "status": 403,
                                        "error": "Interdit",
                                        "message": "Non autorisé : L'utilisateur ne possède pas cette demande de déménagement",
                                        "path": "/api/payments/create-checkout-session"
                                    }
                                    """))),
            @ApiResponse(responseCode = "404", description = "Utilisateur ou demande de déménagement non trouvé",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                        "timestamp": "2025-04-15T12:00:00.000Z",
                                        "status": 404,
                                        "error": "Non trouvé",
                                        "message": "Utilisateur non trouvé avec l'email : utilisateur@example.com",
                                        "path": "/api/payments/create-checkout-session"
                                    }
                                    """))),
            @ApiResponse(responseCode = "500", description = "Erreur interne du serveur, possiblement liée à Stripe",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                        "timestamp": "2025-04-15T12:00:00.000Z",
                                        "status": 500,
                                        "error": "Erreur interne du serveur",
                                        "message": "Erreur lors de la création de la session Stripe",
                                        "path": "/api/payments/create-checkout-session"
                                    }
                                    """)))
    })
    @PostMapping("/create-checkout-session")
    public ResponseEntity<Map<String, String>> createCheckoutSession(
            @Parameter(description = "Détails de la requête de paiement, incluant l'identifiant de la demande de déménagement", required = true)
            @RequestBody PaymentRequest paymentRequest,
            @AuthenticationPrincipal String email) throws StripeException {

        log.info("Tentative de création d'une session de paiement pour l'email: {} et moveId: {}", email, paymentRequest.moveId());

        String userId = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur non trouvé avec l'email : " + email))
                .getId();

        MoveRequest moveRequest = moveRequestRepository.findById(paymentRequest.moveId())
                .orElse(null);

        LastMinuteMove lastMinuteMove = null;
        if (moveRequest == null) {
            lastMinuteMove = lastMinuteMoveRepository.findById(paymentRequest.moveId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Demande de déménagement non trouvée ni dans MoveRequest ni dans LastMinuteMove avec l'ID : " + paymentRequest.moveId()
                    ));
        }

        String moveClientId;
        String clientEmail;
        double preCommissionCost;

        if (moveRequest != null) {
            moveClientId = moveRequest.getClient().getId();
            clientEmail = moveRequest.getClientEmail();
            preCommissionCost = moveRequest.getPreCommissionCost();
        } else {
            moveClientId = lastMinuteMove.getClient().getId();
            clientEmail = lastMinuteMove.getClientEmail();
            preCommissionCost = lastMinuteMove.getPreCommissionCostAfterDiscount() != null
                    ? lastMinuteMove.getPreCommissionCostAfterDiscount()
                    : lastMinuteMove.getPreCommissionCost();
        }

        if (!moveClientId.equals(userId)) {
            log.warn("Échec de la création de session: Utilisateur non autorisé pour moveId: {}", paymentRequest.moveId());
            throw new ForbiddenException("Non autorisé : L'utilisateur ne possède pas cette demande de déménagement");
        }

        String sessionUrl = stripeService.createCheckoutSession(preCommissionCost, clientEmail, paymentRequest.moveId());
        log.info("Session de paiement créée avec succès pour moveId: {}", paymentRequest.moveId());

        Map<String, String> response = new HashMap<>();
        response.put("url", sessionUrl);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Traiter les webhooks Stripe",
            description = "Traite les événements envoyés par Stripe via webhook, notamment la complétion d'une session de paiement. Met à jour le statut de paiement et déclenche l'assignation d'un chauffeur si nécessaire.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Webhook traité avec succès"),
            @ApiResponse(responseCode = "400", description = "Données du webhook invalides ou identifiant de déménagement manquant",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
            @ApiResponse(responseCode = "404", description = "Demande de déménagement non trouvée",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)
                    )),
            @ApiResponse(responseCode = "500", description = "Erreur interne lors du traitement du webhook",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                        "timestamp": "2025-04-15T12:00:00.000Z",
                                        "status": 500,
                                        "error": "Erreur interne du serveur",
                                        "message": "Erreur lors du traitement du webhook",
                                        "path": "/api/payments/webhook"
                                    }
                                    """)))
    })
    @PostMapping("/webhook")
    public ResponseEntity<Void> handleWebhook(@RequestBody String payload,
                                              @RequestHeader("Stripe-Signature") String sigHeader) {
        log.info("➡️ [WEBHOOK] Stripe webhook received");

        try {
            Event event = stripeService.verifyWebhook(payload, sigHeader);
            log.info("➡️ [WEBHOOK] Event verified. Type: {}", event.getType());

            if (!"checkout.session.completed".equals(event.getType())) {
                log.warn("⚠️ [WEBHOOK] Event type '{}' ignored", event.getType());
                return ResponseEntity.ok().build();
            }

            Session session = (Session) event.getDataObjectDeserializer()
                    .getObject()
                    .orElseThrow(() -> new IllegalStateException("Unable to deserialize Stripe session"));

            String moveId = session.getMetadata().get("moveId");
            log.info("➡️ [WEBHOOK] Processing payment for moveId: {}", moveId);

            if (moveId == null) {
                log.error("❌ [WEBHOOK] Missing moveId in session metadata");
                return ResponseEntity.badRequest().build();
            }

            // ===== Try MoveRequest =====
            Optional<MoveRequest> moveRequestOpt = moveRequestRepository.findById(moveId);

            if (moveRequestOpt.isPresent()) {
                MoveRequest moveRequest = moveRequestOpt.get();
                log.info("📦 [WEBHOOK] MoveRequest found - Mode: {}, Status: {}",
                        moveRequest.getMode(), moveRequest.getPaymentStatus());

                if ("paid".equalsIgnoreCase(moveRequest.getPaymentStatus())) {
                    log.info("✅ [WEBHOOK] Payment already processed for {}", moveId);
                    return ResponseEntity.ok().build();
                }

                // ✅ Update payment status
                moveRequest.setPaymentStatus("paid");
                moveRequest.setDateOfPayment(LocalDateTime.now());
                moveRequestRepository.save(moveRequest);
                log.info("💾 [WEBHOOK] MoveRequest {} marked as PAID", moveId);

                // ✅ Send confirmation email
                try {
                    sendPaymentSuccessEmail(
                            moveRequest.getClientEmail(),
                            moveId,
                            moveRequest.getPreCommissionCost()
                    );
                    log.info("📧 [WEBHOOK] Payment email sent to {}", moveRequest.getClientEmail());
                } catch (Exception emailEx) {
                    log.error("❌ [WEBHOOK] Failed to send email: {}", emailEx.getMessage());
                }

                // 🚨 CRITICAL: Trigger driver assignment based on mode
                try {
                    String mode = moveRequest.getMode().name();
                    log.info("🔍 [WEBHOOK] Mission mode: '{}'", mode);

                    if ("URGENT".equalsIgnoreCase(mode)) {
                        log.info("🚨 [WEBHOOK] URGENT mode detected - starting sequential assignment");
                        moveService.initiateDriverAssignment(moveId);
                        log.info("✅ [WEBHOOK] Driver assignment initiated for URGENT move {}", moveId);

                    } else if ("PLANNED".equalsIgnoreCase(mode)) {
                        log.info("📅 [WEBHOOK] PLANNED mode detected - broadcasting to all drivers");
                       // moveService.notifyAllDriversOfPlannedMission(moveRequest);
                        moveService.initiateDriverAssignment(moveId);

                        log.info("✅ [WEBHOOK] Planned mission broadcast completed for {}", moveId);

                    } else {
                        log.error("❌ [WEBHOOK] Unknown mode: '{}' for moveId: {}", mode, moveId);
                        // Fallback to URGENT behavior
                        log.warn("⚠️ [WEBHOOK] Falling back to URGENT assignment");
                        moveService.initiateDriverAssignment(moveId);
                    }

                } catch (Exception assignEx) {
                    log.error("❌ [WEBHOOK] CRITICAL: Assignment failed for {}: {}",
                            moveId, assignEx.getMessage(), assignEx);
                }

                return ResponseEntity.ok().build();
            }
            // ===== Try LastMinuteMove =====
            Optional<LastMinuteMove> lastMinuteMoveOpt = lastMinuteMoveRepository.findById(moveId);

            if (lastMinuteMoveOpt.isEmpty()) {
                log.error("❌ [WEBHOOK] No MoveRequest or LastMinuteMove found for ID: {}", moveId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }

            LastMinuteMove lastMinuteMove = lastMinuteMoveOpt.get();
            log.info("📦 [WEBHOOK] LastMinuteMove found for {}", moveId);

            if ("paid".equalsIgnoreCase(lastMinuteMove.getPaymentStatus())) {
                log.info("✅ [WEBHOOK] Payment already processed for LastMinuteMove {}", moveId);
                return ResponseEntity.ok().build();
            }

            lastMinuteMove.setPaymentStatus("paid");
            lastMinuteMove.setDateOfPayment(LocalDateTime.now());
            lastMinuteMove.setAssignmentStatus("ASSIGNED");
            lastMinuteMove.setMissionStatus(MissionStatus.ACCEPTED);
            lastMinuteMove.setBooked(true);
            lastMinuteMove.setSourceAddress(lastMinuteMove.getClientAddressPoint());
            lastMinuteMove.setDestinationAddress(lastMinuteMove.getClientDestinationPoint());

            // Calculate duration - ONLY DECLARED ONCE
            int durationMinutes = googleMapsService.getDurationInMinutes(
                    lastMinuteMove.getClientAddressPoint(),
                    lastMinuteMove.getClientDestinationPoint(),
                    lastMinuteMove.getClientEmail()
            );
            lastMinuteMove.setEstimatedTotalMinutes(durationMinutes);
            log.info("⏱️ [WEBHOOK] Duration calculated: {} minutes", durationMinutes);

            double priceToSend = lastMinuteMove.getPreCommissionCostAfterDiscount() != null
                    ? lastMinuteMove.getPreCommissionCostAfterDiscount()
                    : lastMinuteMove.getPreCommissionCost();

            // Save LastMinuteMove before sending email
            lastMinuteMoveRepository.save(lastMinuteMove);

            sendPaymentSuccessEmail(lastMinuteMove.getClientEmail(), moveId, priceToSend);
            log.info("✅ [WEBHOOK] LastMinuteMove {} updated and email sent", moveId);

            // Send driver notification
            User driver = lastMinuteMove.getDriver();
            if (driver != null) {
                double distanceInKm = googleMapsService.getDistance(
                        lastMinuteMove.getClientAddressPoint(),
                        lastMinuteMove.getClientDestinationPoint(),
                        lastMinuteMove.getClientEmail()
                );




                Notification notification = new Notification();
                notification.setTitle("LAST MINUTE MOVE");
                notification.setBody(objectMapper.writeValueAsString(new MissionNotification(
                        lastMinuteMove.getId(),
                        lastMinuteMove.getClientAddressPoint(),
                        lastMinuteMove.getClientDestinationPoint(),
                        String.format("%.2f", priceToSend),
                        String.valueOf(durationMinutes),
                        String.valueOf(lastMinuteMove.getItems() != null ? lastMinuteMove.getItems().size() : 0),
                        String.valueOf((int) Math.ceil(distanceInKm)),
                        durationMinutes,
                        "OFFERED",
                        lastMinuteMove.getClient() != null
                                ? lastMinuteMove.getClient().getLastName() + " " + lastMinuteMove.getClient().getFirstName()
                                : "",
                        lastMinuteMove.getClient() != null ? lastMinuteMove.getClient().getPhoneNumber() : "",
                        null,
                        null,
                        lastMinuteMove.getItems()
                )));
                notification.setTimestamp(LocalDateTime.now());
                notification.setRead(false);
                notification.setNotificationType("LAST_OFFER");
                notification.setRelatedMoveId(moveId);
                notification.setUserId(driver.getId());
                notification.setStatus("SENT");

                messagingTemplate.convertAndSend("/topic/driver/" + driver.getId(),
                        objectMapper.writeValueAsString(notification));
                log.info("✅ [WEBHOOK] Notification sent to driver {}", driver.getId());

                // Clone to MoveRequest and delete LastMinuteMove
                MoveRequest moveRequest = LastMinuteMoveMapper.toMoveRequest(lastMinuteMove);
                moveRequest.setDateOfPayment(lastMinuteMove.getDateOfPayment());
                moveRequestRepository.save(moveRequest);
                lastMinuteMoveRepository.delete(lastMinuteMove);
                log.info("✅ [WEBHOOK] LastMinuteMove {} cloned and deleted", moveId);
            }

            return ResponseEntity.ok().build();

        } catch (Exception e) {
            log.error("❌ [WEBHOOK] CRITICAL EXCEPTION: {}", e.getMessage(), e);
            return ResponseEntity.status(500).build();
        }
    }

    private void sendPaymentSuccessEmail(String toEmail, String moveId, double amount) {
        log.info("Envoi d'un email de confirmation de paiement à: {}", toEmail);
        try {
            Context context = new Context();
            context.setVariable("moveId", moveId);
            context.setVariable("amount", amount);
            String htmlContent = templateEngine.process("payment-success", context);
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            helper.setFrom("ramasquare22@gmail.com");
            helper.setTo(toEmail);
            helper.setSubject("Confirmation de paiement réussi");
            helper.setText(htmlContent, true);
            mailSender.send(mimeMessage);
            log.info("Email de confirmation de paiement envoyé avec succès à: {}", toEmail);
        } catch (MessagingException e) {
            log.error("Échec de l'envoi de l'email de confirmation de paiement à {}: {}", toEmail, e.getMessage());
        }
    }

    @Operation(summary = "Gérer un paiement réussi",
            description = "Redirige vers une page de succès après un paiement réussi.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page de succès du paiement retournée",
                    content = @Content(mediaType = MediaType.TEXT_HTML_VALUE,
                            schema = @Schema(implementation = String.class),
                            examples = @ExampleObject(value = "paymentSuccess")))
    })
    @GetMapping("/success")
    public void paymentSuccess(@RequestParam("session_id") String sessionId, HttpServletResponse response) {
        log.info("Redirection vers la page de succès du paiement avec sessionId: {}", sessionId);
        response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
        response.setHeader("Location", "https://dev.dema.bramasquare.com/accueil?payment=success&session_id=" + sessionId);
    }

    @Operation(summary = "Gérer un paiement annulé",
            description = "Redirige vers une page d'annulation si le paiement est annulé.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page d'annulation du paiement retournée",
                    content = @Content(mediaType = MediaType.TEXT_HTML_VALUE,
                            schema = @Schema(implementation = String.class),
                            examples = @ExampleObject(value = "paymentCancel")))
    })
    @GetMapping("/cancel")
    public void paymentCancel(HttpServletResponse response) {
        log.info("Redirection vers la page d'annulation du paiement");
        response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
        response.setHeader("Location", "http://localhost:4200/accueil");
    }

    @Operation(summary = "Tester l'assignation de chauffeur",
            description = "Démarre manuellement l'assignation de chauffeur pour une demande de déménagement spécifiée. Utilisé à des fins de test.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Assignation de chauffeur démarrée avec succès",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = String.class),
                            examples = @ExampleObject(value = "\"Assignation de chauffeur démarrée\""))),
            @ApiResponse(responseCode = "400", description = "Identifiant de déménagement manquant ou invalide",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = String.class),
                            examples = @ExampleObject(value = "\"Identifiant de déménagement manquant\"")))
    })
    @PostMapping("/test-driver-assignment")
    public ResponseEntity<String> testDriverAssignment(@RequestBody Map<String, String> request) {
        log.info("Tentative de test d'assignation de chauffeur pour moveId: {}", request.get("moveId"));
        moveService.startDriverAssignment(request.get("moveId"));
        log.info("Assignation de chauffeur démarrée avec succès pour moveId: {}", request.get("moveId"));
        return ResponseEntity.ok("Assignation de chauffeur démarrée");
    }

    @Operation(summary = "Récupérer les métadonnées d'une session Stripe",
            description = "Récupère les métadonnées d'une session de paiement Stripe, y compris l'ID de la demande de déménagement.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Métadonnées récupérées avec succès",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Map.class),
                            examples = @ExampleObject(value = """
                {
                    "moveId": "move123"
                }
                """))),
            @ApiResponse(responseCode = "404", description = "Session non trouvée",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Erreur interne lors de la récupération de la session")
    })
    @GetMapping("/session/{sessionId}")
    public ResponseEntity<Map<String, String>> getSessionMetadata(
            @Parameter(description = "Identifiant de la session Stripe", required = true)
            @PathVariable String sessionId) throws StripeException {
        log.info("Récupération des métadonnées pour la sessionId: {}", sessionId);
        try {
            Session session = Session.retrieve(sessionId);
            Map<String, String> metadata = session.getMetadata();
            if (metadata == null || !metadata.containsKey("moveId")) {
                log.warn("MoveId non trouvé dans les métadonnées pour sessionId: {}", sessionId);
                throw new ResourceNotFoundException("MoveId non trouvé dans les métadonnées de la session");
            }
            Map<String, String> response = new HashMap<>();
            response.put("moveId", metadata.get("moveId"));
            return ResponseEntity.ok(response);
        } catch (StripeException e) {
            log.error("Erreur lors de la récupération de la session Stripe: {}", e.getMessage());
            throw new RuntimeException("Erreur lors de la récupération de la session Stripe", e);
        }
    }
}