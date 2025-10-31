package fr.tictak.dema.interceptor;

import fr.tictak.dema.model.MoveRequest;
import fr.tictak.dema.repository.MoveRequestRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.security.Principal;

@Component
@Tag(name = "Intercepteur d'abonnement WebSocket", description = "Intercepteur pour sécuriser les abonnements WebSocket aux topics de mission. Vérifie que l'utilisateur est authentifié et autorisé (client ou chauffeur assigné) à s'abonner aux topics des missions de déménagement.")
public class SubscriptionInterceptor implements ChannelInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionInterceptor.class);

    private final MoveRequestRepository moveRequestRepository;

    public SubscriptionInterceptor(MoveRequestRepository moveRequestRepository) {
        this.moveRequestRepository = moveRequestRepository;
    }

    @Override
    public Message<?> preSend(@NotNull Message<?> message, @NotNull MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            String destination = accessor.getDestination();
            if (destination != null && destination.startsWith("/topic/mission/")) {
                String missionId = extractMissionId(destination);
                logger.info("Tentative d'abonnement au topic de la mission: {} par l'utilisateur: {}", missionId, accessor.getUser() != null ? accessor.getUser().getName() : "anonyme");
                Principal principal = accessor.getUser();
                if (principal == null) {
                    logger.warn("Échec de l'abonnement: Utilisateur non authentifié pour la mission: {}", missionId);
                    throw new AccessDeniedException("Non authentifié");
                }
                String userEmail = principal.getName();
                MoveRequest moveRequest = moveRequestRepository.findById(missionId)
                        .orElseThrow(() -> {
                            logMissionNotFound(missionId);
                            return new RuntimeException("Mission non trouvée : " + missionId);
                        });
                boolean isClient = moveRequest.getClientEmail().equals(userEmail);
                boolean isDriver = moveRequest.getDriver() != null && moveRequest.getDriver().getEmail().equals(userEmail);
                if (!isClient && !isDriver) {
                    logger.warn("Échec de l'abonnement: Utilisateur non autorisé pour la mission: {}", missionId);
                    throw new AccessDeniedException("Non autorisé à s'abonner à la mission : " + missionId);
                }
                logger.info("Abonnement autorisé pour la mission: {} par l'utilisateur: {}", missionId, userEmail);
            }
        }
        return message;
    }

    private String extractMissionId(String destination) {
        String[] parts = destination.split("/");
        if (parts.length >= 4) {
            return parts[3];
        }
        logger.error("Échec de l'extraction de l'ID de mission: Format de destination invalide: {}", destination);
        throw new IllegalArgumentException("Format de destination invalide : " + destination);
    }

    private void logMissionNotFound(String missionId) {
        logger.warn("Mission non trouvée pour l'ID: {}", missionId);
    }
}