package fr.tictak.dema.service.implementation;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import fr.tictak.dema.dto.in.BusinessDTO;
import fr.tictak.dema.dto.in.MissionNotification;
import fr.tictak.dema.dto.in.SendReclamationRequest;
import fr.tictak.dema.exception.ForbiddenException;
import fr.tictak.dema.exception.ResourceNotFoundException;
import fr.tictak.dema.model.MoveRequest;
import fr.tictak.dema.model.Notification;
import fr.tictak.dema.model.enums.MissionStatus;
import fr.tictak.dema.model.user.User;
import fr.tictak.dema.repository.MoveRequestRepository;
import fr.tictak.dema.repository.NotificationRepository;
import fr.tictak.dema.repository.UserRepository;
import fr.tictak.dema.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private static final DateTimeFormatter BODY_DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("dd MMMM yyyy 'à' HH:mm", Locale.FRENCH);
    private static final DateTimeFormatter METADATA_DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final SimpMessagingTemplate messagingTemplate;
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final UserService userService;
    private final MongoTemplate mongoTemplate;
    private final GoogleMapsService googleMapsService;
    private final MoveRequestRepository moveRepository;


    public NotificationService(SimpMessagingTemplate messagingTemplate, NotificationRepository notificationRepository,
                               UserRepository userRepository, UserService userService, MongoTemplate mongoTemplate, GoogleMapsService googleMapsService, MoveRequestRepository moveRepository) {
        this.messagingTemplate = messagingTemplate;
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.userService = userService;
        this.mongoTemplate = mongoTemplate;
        this.googleMapsService = googleMapsService;
        this.moveRepository = moveRepository;
    }

    public void sendAndSaveNotification(Notification notification, Object payload) {
        boolean skipWebSocket = false;

        if (("MISSION_OFFER".equals(notification.getNotificationType()) ||
                "LAST_OFFER".equals(notification.getNotificationType())) &&
                payload instanceof MissionNotification mission) {

            // Check the real booked status from DB
            String moveId = mission.moveId();
            Optional<MoveRequest> moveInDbOptional = moveRepository.findById(moveId);
            if (moveInDbOptional.isPresent() && Boolean.TRUE.equals(moveInDbOptional.get().getBooked())) {
                log.info("🚫 Mission {} is booked in DB, skipping WebSocket notification", moveId);
                skipWebSocket = true; // ✅ mark to skip WebSocket
            }
        }

        // Handle MISSION_COMPLETED conversion
        if ("MISSION_STATUS_UPDATE".equals(notification.getNotificationType()) &&
                payload instanceof MoveRequest moveRequest &&
                moveRequest.getMissionStatus() == MissionStatus.COMPLETED) {
            notification.setNotificationType("MISSION_COMPLETED");
            if (notification.getRelatedMoveId() == null && moveRequest.getMoveId() != null) {
                notification.setRelatedMoveId(moveRequest.getMoveId());
            }
        }

        // Save notification metadata and title/body
        notification.setTitle(getFrenchTitle(notification.getNotificationType(), payload));
        notification.setBody(generateNotificationBody(notification.getNotificationType(), payload));
        notification.setMetadata(extractMetadata(payload));
        if (notification.getTimestamp() == null) {
            notification.setTimestamp(LocalDateTime.now());
        }

        notificationRepository.save(notification);
        log.info("Notification saved to database for userId: {}", notification.getUserId());

        // Bulk update for MISSION_COMPLETED
        if ("MISSION_COMPLETED".equals(notification.getNotificationType())) {
            String relatedMoveId = notification.getRelatedMoveId();
            if (relatedMoveId != null) {
                Query query = new Query(Criteria.where("relatedMoveId").is(relatedMoveId));
                Update update = new Update().set("notificationType", "MISSION_COMPLETED");
                mongoTemplate.updateMulti(query, update, Notification.class);
                log.info("Updated notificationType to MISSION_COMPLETED for all notifications with relatedMoveId: {}", relatedMoveId);
            } else {
                log.warn("relatedMoveId is null for MISSION_COMPLETED notification, skipping bulk update");
            }
        }

        long unreadCount = notificationRepository.countByUserIdAndRead(notification.getUserId(), false);
        notification.setUnreadCount(unreadCount);

        // Skip WebSocket for booked missions or certain notification types
        if (skipWebSocket || "ORDER_UPDATE".equals(notification.getNotificationType()) ||
                "NO_DRIVERS_IN_RANGE".equals(notification.getNotificationType())) {
            return;
        }

        // Send WebSocket notification
        String destination = determineDestination(notification);
        messagingTemplate.convertAndSend(destination, notification);
        log.info("WebSocket notification sent to {} for userId: {} with unread count: {}",
                destination, notification.getUserId(), unreadCount);

        // Send push notification via FCM
        sendFcmNotification(notification);
    }

    public long getUnreadNotificationCount(String userId) {
        return notificationRepository.countByUserIdAndRead(userId, false);
    }

    private void updateAndSendUnreadCount(String userId) {
        long unreadCount = notificationRepository.countByUserIdAndRead(userId, false);
        Notification countUpdate = new Notification();
        countUpdate.setUserId(userId);
        countUpdate.setUnreadCount(unreadCount);
        String destination = "/topic/user/" + userId + "/unreadCount";
        messagingTemplate.convertAndSend(destination, countUpdate);
        log.info("Sent unread count update {} to {} for userId: {}", unreadCount, destination, userId);
    }

    private String getFrenchTitle(String notificationType, Object payload) {
        return switch (notificationType) {
            case "QUOTE_READY" -> "Votre demande de devis est prête !";
            case "MISSION_OFFER" -> "Nouvelle offre de mission";
            case "LAST_OFFER" -> "LAST_OFFER";
            case "MOVER_EN_ROUTE" -> "Votre déménageur est en route !";
            case "RECLAMATION_SUBMITTED" -> "Nouvelle réclamation soumise";
            case "BUSINESS_REQUEST_SUBMITTED" -> "Nouvelle demande d'entreprise soumise";
            case "NO_DRIVERS_IN_RANGE" -> "Aucun déménageur disponible";
            case "MISSION_EXPIRED" -> "Mission expirée";
            case "ORDER_UPDATE" -> "Mise à jour de votre commande";
            case "MISSION_STATUS_UPDATE" -> {
                if (payload instanceof MoveRequest moveRequest && moveRequest.getMissionStatus() != null) {
                    yield switch (moveRequest.getMissionStatus()) {
                        case PENDING -> "Mission en attente";
                        case ACCEPTED -> "Mission acceptée";
                        case EN_ROUTE_TO_DEPART -> "Déménageur en route vers le départ";
                        case ARRIVED_AT_DEPART -> "Déménageur arrivé au départ";
                        case LOADING_COMPLETE -> "Chargement terminé, en route vers la destination";
                        case COMPLETED -> "Mission terminée";
                        case CANCELED -> "Mission annulée";
                    };
                }
                yield "Mise à jour du statut de la mission";
            }
            case "MISSION_COMPLETED" -> "Mission terminée";
            default -> "Notification";
        };
    }

    private String generateNotificationBody(String notificationType, Object payload) {
        return switch (notificationType) {
            case "MISSION_OFFER" -> {
                if (payload instanceof MissionNotification mission) {
                    String baseMessage = String.format(
                            "Vous avez une nouvelle mission disponible pour %s produits de %s à %s. Distance: %s km, Durée: %s min, Coût: %s€. Client: %s (%s). Expire dans %d s.",
                            mission.numberOfProducts(), mission.sourceAddress(), mission.destinationAddress(),
                            mission.distanceInKm(), mission.durationInMinutes(), mission.postCommissionCost(),
                            mission.clientName(), mission.phoneNumber(), mission.remainingSeconds());
                    if (mission.plannedDate() != null && mission.plannedTime() != null) {
                        LocalDateTime plannedDateTime = mission.getPlannedDateTime();
                        assert plannedDateTime != null;
                        String formattedDateTime = plannedDateTime.format(BODY_DATE_TIME_FORMATTER);
                        yield baseMessage + String.format(" Planifiée pour : %s", formattedDateTime);
                    }
                    yield baseMessage;
                }
                yield "Vous avez une nouvelle offre de mission.";
            }
            case "RECLAMATION_SUBMITTED" -> {
                if (payload instanceof SendReclamationRequest request) {
                    yield String.format(
                            "Une réclamation a été soumise par %s (%s): %s",
                            request.getSenderName(),
                            request.getSentFromEmail(),
                            request.getMailContent()
                    );
                }
                yield "Une nouvelle réclamation a été soumise.";
            }
            case "BUSINESS_REQUEST_SUBMITTED" -> {
                if (payload instanceof BusinessDTO dto) {
                    yield String.format(
                            "Une demande d'entreprise a été soumise par %s (%s). Adresse: %s, Téléphone: %s, Site web: %s",
                            dto.name(),
                            dto.email(),
                            dto.address(),
                            dto.phone(),
                            dto.website()
                    );
                }
                yield "Une nouvelle demande d'entreprise a été soumise.";
            }
            case "QUOTE_READY" -> {
                if (payload instanceof MoveRequest moveRequest) {
                    yield String.format(
                            "Consultez votre estimation et planifiez votre déménagement dès maintenant. Devis ID: %s, Montant: %.2f€",
                            moveRequest.getMoveId(), moveRequest.getPreCommissionCost());
                }
                yield "Votre devis est prêt à être consulté.";
            }
            case "NO_DRIVERS_IN_RANGE" -> {
                if (payload instanceof MoveRequest moveRequest) {
                    yield String.format(
                            "Désolé, aucun déménageur n'est actuellement disponible pour votre mission de %s à %s. Veuillez réessayer plus tard.",
                            moveRequest.getSourceAddress(), moveRequest.getDestinationAddress());
                }
                yield "Aucun déménageur n'est disponible pour le moment. Veuillez réessayer plus tard.";
            }
            case "MISSION_EXPIRED" -> {
                if (payload instanceof MoveRequest moveRequest) {
                    yield String.format(
                            "Votre mission de %s à %s a expiré. Veuillez soumettre une nouvelle demande si nécessaire.",
                            moveRequest.getSourceAddress(), moveRequest.getDestinationAddress());
                }
                yield "Votre mission a expiré. Veuillez soumettre une nouvelle demande si nécessaire.";
            }
            case "ORDER_UPDATE" -> {
                if (payload instanceof MoveRequest moveRequest) {
                    yield String.format(
                            "Votre commande de %s à %s a été mise à jour. Veuillez vérifier les détails de votre commande.",
                            moveRequest.getSourceAddress(), moveRequest.getDestinationAddress());
                }
                yield "Votre commande a été mise à jour. Veuillez vérifier les détails.";
            }
            case "MISSION_STATUS_UPDATE" -> {
                if (payload instanceof MoveRequest moveRequest && moveRequest.getMissionStatus() != null) {
                    // Skip generating body for COMPLETED status since it will be handled by MISSION_COMPLETED
                    if (moveRequest.getMissionStatus() == MissionStatus.COMPLETED) {
                        yield null; // This will prevent duplicate notification
                    }

                    String statusMessage = switch (moveRequest.getMissionStatus()) {
                        case PENDING -> "Votre mission est en attente d'acceptation.";
                        case ACCEPTED -> "Votre mission a été acceptée par le déménageur.";
                        case EN_ROUTE_TO_DEPART -> "Le déménageur est en route vers votre adresse de départ.";
                        case ARRIVED_AT_DEPART -> "Le déménageur est arrivé à votre adresse de départ.";
                        case LOADING_COMPLETE -> "Le chargement est terminé, le déménageur est en route vers votre destination.";
                        case COMPLETED -> "Votre mission est terminée. Merci d'avoir utilisé nos services !";
                        case CANCELED -> "Votre mission a été annulée.";
                    };
                    yield String.format("%s De %s à %s.", statusMessage, moveRequest.getSourceAddress(), moveRequest.getDestinationAddress());
                }
                yield "Le statut de votre mission a été mis à jour.";
            }
            case "MISSION_COMPLETED" -> {
                if (payload instanceof MoveRequest moveRequest) {
                    yield String.format(
                            "Votre mission de %s à %s est terminée. Merci d'avoir utilisé nos services !",
                            moveRequest.getSourceAddress(), moveRequest.getDestinationAddress());
                }
                yield "Votre mission est terminée.";
            }
            default -> "Vous avez reçu une notification.";
        };
    }

    private Map<String, String> extractMetadata(Object payload) {
        Map<String, String> metadata = new HashMap<>();

        if (payload instanceof MissionNotification mission) {
            // ✅ booked : ajouté seulement s'il existe
            if (mission.booked() != null) {
                metadata.put("booked", String.valueOf(mission.booked()));
            } else {
                metadata.put("booked", null); // ou tu peux simplement ne pas le mettre du tout
            }

            metadata.put("moveId", mission.moveId());
            metadata.put("sourceAddress", mission.sourceAddress());
            metadata.put("destinationAddress", mission.destinationAddress());
            metadata.put("postCommissionCost", mission.postCommissionCost());
            metadata.put("durationInMinutes", mission.durationInMinutes());
            metadata.put("numberOfProducts", mission.numberOfProducts());
            metadata.put("distanceInKm", mission.distanceInKm());
            metadata.put("remainingSeconds", String.valueOf(mission.remainingSeconds()));
            metadata.put("clientName", mission.clientName());
            metadata.put("phoneNumber", mission.phoneNumber());
            metadata.put("items", mission.getItems());

            if (mission.plannedDate() != null && mission.plannedTime() != null) {
                LocalDateTime plannedDateTime = mission.getPlannedDateTime();
                if (plannedDateTime != null) {
                    metadata.put("plannedDateTime", plannedDateTime.format(METADATA_DATE_TIME_FORMATTER));
                }
                metadata.put("plannedDate", mission.plannedDate());
                metadata.put("plannedTime", mission.plannedTime());
            }

        } else if (payload instanceof SendReclamationRequest request) {
            metadata.put("sentFromEmail", request.getSentFromEmail());
            metadata.put("senderName", request.getSenderName());
            metadata.put("mailContent", request.getMailContent());

        } else if (payload instanceof BusinessDTO(
                String name, String email, String address, String phone, String website, String attestationCapaciteUrl,
                String kbisUrl, String assuranceTransportUrl, String identityProofUrl, String attestationVigilanceUrl,
                String attestationRegulariteFiscaleUrl
        )) {
            metadata.put("name", name);
            metadata.put("email", email);
            metadata.put("address", address);
            metadata.put("phone", phone);
            metadata.put("website", website);
            metadata.put("attestationCapaciteUrl", attestationCapaciteUrl);
            metadata.put("kbisUrl", kbisUrl);
            metadata.put("assuranceTransportUrl", assuranceTransportUrl);
            metadata.put("identityProofUrl", identityProofUrl);
            metadata.put("attestationVigilanceUrl", attestationVigilanceUrl);
            metadata.put("attestationRegulariteFiscaleUrl", attestationRegulariteFiscaleUrl);
            metadata.put("sentAt", LocalDateTime.now().toString());

        } else if (payload instanceof MoveRequest moveRequest) {
            metadata.put("moveId", moveRequest.getMoveId());
            metadata.put("sourceAddress", moveRequest.getSourceAddress());
            metadata.put("clientName", moveRequest.getClient().getFirstName() + " " + moveRequest.getClient().getLastName());
            metadata.put("phoneNumber", moveRequest.getClient().getPhoneNumber());
            metadata.put("distanceInKm", String.valueOf(googleMapsService.getDistance(
                    moveRequest.getSourceAddress(), moveRequest.getDestinationAddress(), moveRequest.getClientEmail())));
            metadata.put("numberOfProducts", String.valueOf(googleMapsService.getDurationInMinutes(
                    moveRequest.getSourceAddress(), moveRequest.getDestinationAddress(), moveRequest.getClientEmail())));
            metadata.put("durationInMinutes", String.valueOf(googleMapsService.getDurationInMinutes(
                    moveRequest.getSourceAddress(), moveRequest.getDestinationAddress(), moveRequest.getClientEmail())));
            metadata.put("destinationAddress", moveRequest.getDestinationAddress());
            metadata.put("items", moveRequest.getStringItems());
            metadata.put("preCommissionCost", String.valueOf(moveRequest.getPreCommissionCost()));

            if (moveRequest.getMissionStatus() != null) {
                metadata.put("missionStatus", moveRequest.getMissionStatus().getDescription());
            }

            if (moveRequest.getPlannedDate() != null && moveRequest.getPlannedTime() != null) {
                LocalDateTime plannedDateTime = moveRequest.getPlannedDateTime();
                metadata.put("plannedDateTime", plannedDateTime.format(METADATA_DATE_TIME_FORMATTER));
                metadata.put("plannedDate", moveRequest.getPlannedDate());
                metadata.put("plannedTime", moveRequest.getPlannedTime());
            }
        }

        return metadata;
    }

    private void sendFcmNotification(Notification notification) {
        // Skip FCM notification if body is null (prevented duplicate)
        if (notification.getBody() == null) {
            log.info("Skipping FCM notification for userId: {} as body is null (prevented duplicate)", notification.getUserId());
            return;
        }

        String userId = notification.getUserId();
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || user.getFcmToken() == null || user.getFcmToken().isEmpty()) {
            log.warn("No FCM token found for user: {}", userId);
            return;
        }
        String fcmToken = user.getFcmToken();

        String title = notification.getTitle();
        String body = notification.getBody();
        Map<String, String> data = new HashMap<>();
        Map<String, String> metadata = notification.getMetadata();
        if (metadata != null) {
            data.putAll(metadata);
        }

        Message message = Message.builder()
                .setNotification(com.google.firebase.messaging.Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build())
                .putAllData(data)
                .setToken(fcmToken)
                .build();

        try {
            FirebaseMessaging.getInstance().send(message);
            log.info("FCM notification sent to user: {}", userId);
        } catch (Exception e) {
            log.error("Failed to send FCM notification to user {}: {}", userId, e.getMessage());
        }
    }

    private String determineDestination(Notification notification) {
        String userId = notification.getUserId();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        String role = user.getRole().name();
        if ("DRIVER".equals(role)) {
            return "/topic/driver/" + userId;
        } else if ("CLIENT".equals(role)) {
            return "/topic/client/" + userId + "/mission";
        } else {
            return "/queue/notifications/" + userId;
        }
    }

    public void markNotificationAsRead(String notificationId, String userEmail) {
        User user = userService.findByEmail(userEmail);
        if (user == null) {
            throw new ResourceNotFoundException("User not found");
        }
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));
        if (!notification.getUserId().equals(user.getId())) {
            throw new ForbiddenException("You do not have permission to mark this notification as read");
        }
        notification.setRead(true);
        notificationRepository.save(notification);
        updateAndSendUnreadCount(user.getId());
    }

    public void markAllNotificationsAsRead(String userEmail) {
        User user = userService.findByEmail(userEmail);
        if (user == null) {
            throw new ResourceNotFoundException("User not found");
        }
        List<Notification> notifications = notificationRepository.findByUserId(user.getId());
        for (Notification notification : notifications) {
            notification.setRead(true);
            notificationRepository.save(notification);
        }
        updateAndSendUnreadCount(user.getId());
    }

    public void deleteNotification(String notificationId, String userEmail) {
        User user = userService.findByEmail(userEmail);
        if (user == null) {
            throw new ResourceNotFoundException("User not found");
        }
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));
        if (!notification.getUserId().equals(user.getId())) {
            throw new ForbiddenException("You do not have permission to delete this notification");
        }
        notificationRepository.deleteById(notificationId);
        updateAndSendUnreadCount(user.getId());
    }

    public List<Notification> getNotificationsByUserId(String userId) {
        return notificationRepository.findByUserId(userId);
    }
}