package fr.tictak.dema.controller;

import fr.tictak.dema.dto.in.SendReclamationRequest;
import fr.tictak.dema.exception.GlobalExceptionHandler;
import fr.tictak.dema.model.enums.Role;
import fr.tictak.dema.model.user.User;
import fr.tictak.dema.repository.UserRepository; // Inject this if not already
import fr.tictak.dema.service.UserService;
import fr.tictak.dema.model.Notification;
import fr.tictak.dema.service.implementation.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Getter;
import lombok.Setter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/reclamation")
@Tag(name = "Gestion des réclamations", description = "API pour l'envoi de réclamations.")
public class ReclamationController {

    private final UserService userService;
    private final NotificationService notificationService;
    private final UserRepository userRepository; // Add this injection

    public ReclamationController(UserService userService, NotificationService notificationService, UserRepository userRepository) {
        this.userService = userService;
        this.notificationService = notificationService;
        this.userRepository = userRepository;
    }

    @Operation(summary = "Envoi d'une réclamation", description = "Permet à un utilisateur authentifié d'envoyer une réclamation par email.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Réclamation envoyée avec succès",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ResponseMessage.class))),
            @ApiResponse(responseCode = "400", description = "Données invalides",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
    })
    @PostMapping(value = "/send", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ResponseMessage> sendReclamation(
            @AuthenticationPrincipal String email,
            @RequestBody SendReclamationRequest request) {
        userService.sendReclamation(email, request.getSentFromEmail(), request.getSenderName(), request.getMailContent());

        // Notify all admins in real-time
        List<User> admins = userRepository.findByRole(Role.ADMIN);
        for (User admin : admins) {
            Notification notification = new Notification();
            notification.setUserId(admin.getId());
            notification.setNotificationType("RECLAMATION_SUBMITTED");
            notification.setTimestamp(LocalDateTime.now());
            notification.setRead(false);
            notificationService.sendAndSaveNotification(notification, request); // Payload is the request for body/metadata
        }

        return ResponseEntity.ok(new ResponseMessage("Réclamation envoyée avec succès"));
    }

    // Inner class to structure the JSON response
    @Setter
    @Getter
    public static class ResponseMessage {
        private String message;

        public ResponseMessage(String message) {
            this.message = message;
        }

    }
}