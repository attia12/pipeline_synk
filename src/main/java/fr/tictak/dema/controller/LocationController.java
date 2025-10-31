package fr.tictak.dema.controller;

import fr.tictak.dema.dto.in.LocationMessage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

@Controller
@Tag(name = "Gestion des emplacements de mission", description = "API WebSocket pour la mise à jour en temps réel des emplacements liés à une mission de déménagement.")
public class LocationController {

    private static final Logger logger = LoggerFactory.getLogger(LocationController.class);

    private final SimpMessagingTemplate messagingTemplate;

    public LocationController(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @Operation(
            summary = "Mettre à jour l'emplacement d'une mission",
            description = "Met à jour l'emplacement d'une mission via un message WebSocket et transmet les coordonnées (latitude et longitude) aux abonnés du topic de la mission spécifiée."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Emplacement de la mission mis à jour et message transmis avec succès"),
            @ApiResponse(responseCode = "400", description = "Message de localisation invalide")
    })
    @MessageMapping("/mission/{moveId}/location")
    public void updateLocation(@DestinationVariable String moveId,
                               LocationMessage location) {
        logger.info("Mise à jour de l'emplacement reçue – missionId={}, latitude={}, longitude={}",
                moveId, location.latitude(), location.longitude());
        messagingTemplate.convertAndSend(
                "/topic/mission/" + moveId + "/location",
                location
        );
        logger.info("Message de localisation envoyé pour la mission {}", moveId);
    }
}