package fr.tictak.dema.service.implementation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.maps.errors.ApiException;
import fr.tictak.dema.dto.in.*;
import fr.tictak.dema.dto.out.MoveDetails;
import fr.tictak.dema.exception.BadRequestException;
import fr.tictak.dema.exception.ForbiddenException;
import fr.tictak.dema.exception.ResourceNotFoundException;
import fr.tictak.dema.model.*;
import fr.tictak.dema.model.enums.MissionStatus;
import fr.tictak.dema.model.enums.QuotationStatus;
import fr.tictak.dema.model.enums.QuotationType;
import fr.tictak.dema.model.enums.Role;
import fr.tictak.dema.model.user.Admin;
import fr.tictak.dema.model.user.Driver;
import fr.tictak.dema.model.user.User;
import fr.tictak.dema.repository.*;
import fr.tictak.dema.service.MoveService;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import com.mongodb.client.result.UpdateResult;

import java.io.IOException;
import java.text.DecimalFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.Comparator;

@Slf4j
@Service
@Tag(name = "Service de gestion des déménagements", description = "Service pour la gestion des demandes de déménagement.")
public class MoveServiceImpl implements MoveService {

    private final MoveRequestRepository moveRequestRepository;
    private final ItemRepository itemRepository;
    private final GoogleMapsService googleMapsService;
    private final SimpMessagingTemplate messagingTemplate;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final TaskScheduler taskScheduler;
    private final DriverRepository driverRepository;
    private final MongoTemplate mongoTemplate;
    private final SpringTemplateEngine templateEngine;
    private final ObjectMapper objectMapper;
    private final JavaMailSender mailSender;
    private final LastMinuteMoveRepository lastMinuteMoveRepository;

    // ✅ Concurrency control structures
    private final ConcurrentHashMap<String, ScheduledFuture<?>> assignmentTimeouts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Bucket> userBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DriverLocation> onlineDrivers = new ConcurrentHashMap<>();
    private final Set<String> activeAssignments = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Lock> missionLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LocalDateTime> lastAssignmentAttempts = new ConcurrentHashMap<>();

    // ✅ Configuration constants
    private static final double MAX_RADIUS_KM = 500000.0;
    private static final int ASSIGNMENT_TIMEOUT_SECONDS = 60;
    private static final int ASSIGNMENT_RETRY_COOLDOWN_SECONDS = 30;

    public MoveServiceImpl(MoveRequestRepository moveRequestRepository, ItemRepository itemRepository,
                           GoogleMapsService googleMapsService, SimpMessagingTemplate messagingTemplate,
                           UserRepository userRepository, NotificationService notificationService,
                           TaskScheduler taskScheduler, DriverRepository driverRepository,
                           MongoTemplate mongoTemplate, SpringTemplateEngine templateEngine,
                           ObjectMapper objectMapper, JavaMailSender mailSender,
                           LastMinuteMoveRepository lastMinuteMoveRepository) {
        this.moveRequestRepository = moveRequestRepository;
        this.itemRepository = itemRepository;
        this.googleMapsService = googleMapsService;
        this.messagingTemplate = messagingTemplate;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.taskScheduler = taskScheduler;
        this.driverRepository = driverRepository;
        this.mongoTemplate = mongoTemplate;
        this.templateEngine = templateEngine;
        this.objectMapper = objectMapper;
        this.mailSender = mailSender;
        this.lastMinuteMoveRepository = lastMinuteMoveRepository;
    }

    private Bucket getUserBucket(String email) {
        return userBuckets.computeIfAbsent(email, k -> {
            Bandwidth limit = Bandwidth.classic(20, Refill.intervally(20, Duration.ofHours(24)));
            return Bucket.builder().addLimit(limit).build();
        });
    }

    @Data
    public static class DriverLocation {
        private String driverId;
        private double latitude;
        private double longitude;

        public DriverLocation(String driverId, double latitude, double longitude) {
            this.driverId = driverId;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    // ✅ FIXED: Thread-safe driver management
    public void addOnlineDriver(String driverId) {
        onlineDrivers.putIfAbsent(driverId, new DriverLocation(driverId, 0.0, 0.0));
        log.info("Chauffeur ajouté aux chauffeurs en ligne : {}, Taille actuelle : {}", driverId, onlineDrivers.size());
    }

    // ✅ FIXED: Improved removeOnlineDriver with better concurrency control
    public void removeOnlineDriver(String driverId) {
        onlineDrivers.remove(driverId);
        log.info("Chauffeur retiré des chauffeurs en ligne : {}, Taille actuelle : {}", driverId, onlineDrivers.size());

        // Find missions waiting for this specific driver
        Query query = new Query();
        query.addCriteria(Criteria.where("assignmentStatus").is("WAITING_FOR_DRIVER")
                .and("candidateDrivers").in(driverId));
        List<MoveRequest> affectedMoves = mongoTemplate.find(query, MoveRequest.class);

        for (MoveRequest moveRequest : affectedMoves) {
            Lock lock = getMissionLock(moveRequest.getMoveId());
            try {
                if (lock.tryLock(5, TimeUnit.SECONDS)) {
                    try {
                        handleDriverDisconnection(moveRequest, driverId);
                    } finally {
                        lock.unlock();
                    }
                } else {
                    log.warn("Could not acquire lock for mission {} during driver disconnection", moveRequest.getMoveId());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted while handling driver disconnection for mission {}", moveRequest.getMoveId());
            }
        }
    }

    // ✅ NEW: Helper method for handling driver disconnection
    private void handleDriverDisconnection(MoveRequest moveRequest, String driverId) {
        List<String> candidates = moveRequest.getCandidateDrivers();
        int index = candidates.indexOf(driverId);

        if (index == -1) {
            return;
        }

        candidates.remove(index);
        int currentIndex = moveRequest.getCurrentDriverIndex();
        boolean wasCurrent = (index == currentIndex);

        if (index < currentIndex) {
            currentIndex--;
            moveRequest.setCurrentDriverIndex(currentIndex);
        }

        if (candidates.isEmpty() || currentIndex >= candidates.size()) {
            moveRequest.setAssignmentStatus("NO_DRIVERS_AVAILABLE");
            addHistoryEvent(moveRequest, "STATUS_CHANGED",
                    "Assignment status changed to NO_DRIVERS_AVAILABLE due to driver disconnection", "SYSTEM");
            moveRequestRepository.save(moveRequest);
            cancelAssignmentTimeout(moveRequest.getMoveId());
            return;
        }

        if (wasCurrent) {
            cancelAssignmentTimeout(moveRequest.getMoveId());
            addHistoryEvent(moveRequest, "DRIVER_DISCONNECTED",
                    String.format("Driver %s disconnected", driverId), "SYSTEM");

            String nextDriverId = candidates.get(currentIndex);
            sendMissionOfferToDriver(moveRequest, nextDriverId, "after disconnection");
        }

        moveRequestRepository.save(moveRequest);
    }

    @Override
    public List<DriverMissionSummaryDTO> getAvailablePlannedMissions() {
        log.info("Fetching all available planned missions.");
        Query query = new Query(Criteria.where("mode").is(QuotationType.PLANNED)
                .and("booked").is(false)
                .and("paymentStatus").is("paid"));
        query.with(Sort.by(Sort.Direction.ASC, "plannedDate", "plannedTime"));

        List<MoveRequest> missions = mongoTemplate.find(query, MoveRequest.class);

        return missions.stream().map(move -> {
            DriverMissionSummaryDTO dto = new DriverMissionSummaryDTO();
            dto.setMoveId(move.getMoveId());
            dto.setSourceAddress(move.getSourceAddress());
            dto.setDestinationAddress(move.getDestinationAddress());
            dto.setAmount(move.getPostCommissionCost());
            dto.setDate(move.getPlannedDate() +" "+ move.getPlannedTime());
            dto.setStatus(move.getAssignmentStatus());
            dto.setProductCount(move.getItems().stream().mapToInt(ItemQuantity::getQuantity).sum());

            int durationMinutes = googleMapsService.getDurationInMinutes(
                    move.getSourceAddress(), move.getDestinationAddress(), move.getClientEmail());
            dto.setDuration(String.format("%dh%02d", durationMinutes / 60, durationMinutes % 60));

            double distanceKm = googleMapsService.getDistance(
                    move.getSourceAddress(), move.getDestinationAddress(), move.getClientEmail());
            dto.setDistanceKm(distanceKm);

            dto.setDriverFullName("");
            dto.setDriverPhoneNumber("");

            return dto;
        }).collect(Collectors.toList());
    }

    @Override
    public synchronized MoveRequest bookPlannedMission(String moveId, String driverId) {
        log.info("Driver {} attempting to book planned mission {}", driverId, moveId);

        // ✅ Use atomic update to prevent race conditions
        Query query = new Query(Criteria.where("moveId").is(moveId)
                .and("booked").ne(true)
                .and("driver").exists(false)
                .and("mode").is(QuotationType.PLANNED));

        Update update = new Update()
                .set("booked", true)
                .set("driverId", driverId)
                .set("acceptedAt", LocalDateTime.now());

        MoveRequest moveRequest = mongoTemplate.findAndModify(
                query,
                update,
                new FindAndModifyOptions().returnNew(true),
                MoveRequest.class
        );

        if (moveRequest == null) {
            log.warn("Mission {} is already booked or not available", moveId);
            throw new BadRequestException("Mission is already booked or not available.");
        }

        // Check for overlapping missions
        LocalDateTime newMissionStart = moveRequest.getPlannedDateTime();
        Integer estimatedMinutes = moveRequest.getEstimatedTotalMinutes();
        if (estimatedMinutes == null) {
            estimatedMinutes = 120;
        }
        LocalDateTime newMissionEnd = newMissionStart.plusMinutes(estimatedMinutes);

        Query assignedMovesQuery = new Query(Criteria.where("driver.id").is(driverId)
                .and("assignmentStatus").is("ASSIGNED"));
        List<MoveRequest> assignedMissions = mongoTemplate.find(assignedMovesQuery, MoveRequest.class);

        for (MoveRequest assigned : assignedMissions) {
            LocalDateTime existingStart = assigned.getPlannedDateTime();
            Integer existingDuration = assigned.getEstimatedTotalMinutes();
            if (existingStart == null || existingDuration == null) continue;
            LocalDateTime existingEnd = existingStart.plusMinutes(existingDuration);

            if (newMissionStart.isBefore(existingEnd) && existingStart.isBefore(newMissionEnd)) {
                // Rollback the booking
                moveRequest.setBooked(false);
                moveRequest.setDriverId(null);
                moveRequest.setAcceptedAt(null);
                moveRequestRepository.save(moveRequest);

                throw new BadRequestException("Booking failed: This mission conflicts with another mission in your schedule.");
            }
        }

        addHistoryEvent(moveRequest, "MISSION_BOOKED",
                String.format("Mission booked by driver %s", driverId), driverId);
        moveRequestRepository.save(moveRequest);

        log.info("Mission {} successfully booked by driver {}", moveId, driverId);
        return moveRequest;
    }

    @Override
    public List<DriverMissionSummaryDTO> getAvailablePlannedMissionsByDriver(String driverId) {
        log.info("Fetching available planned missions for driverId: {}", driverId);

        Query query = new Query(Criteria.where("mode").is(QuotationType.PLANNED)
                .and("booked").is(true)
                .and("paymentStatus").is("paid")
                .and("driverId").is(driverId)
                .and("missionStatus").ne("COMPLETED"));

        query.with(Sort.by(Sort.Direction.ASC, "plannedDate", "plannedTime"));

        List<MoveRequest> missions = mongoTemplate.find(query, MoveRequest.class);

        return missions.stream().map(move -> {
            DriverMissionSummaryDTO dto = new DriverMissionSummaryDTO();
            dto.setMoveId(move.getMoveId());
            dto.setSourceAddress(move.getSourceAddress());
            dto.setDestinationAddress(move.getDestinationAddress());
            dto.setStatus(move.getAssignmentStatus());
            dto.setClientName(move.getClient().getFirstName());
            dto.setPhoneNumber(move.getClient().getPhoneNumber());
            dto.setPlannedDate(move.getPlannedDate());
            dto.setPlannedTime(move.getPlannedTime());
            dto.setItems(move.getItems());
            dto.setBooked(move.getBooked());
            dto.setPostCommissionCost(String.valueOf(move.getPostCommissionCost()));

            int productCount = move.getItems() != null
                    ? move.getItems().stream().mapToInt(ItemQuantity::getQuantity).sum()
                    : 0;
            dto.setProductCount(productCount);
            dto.setNumberOfProducts(String.valueOf(productCount));

            int durationMinutes = googleMapsService.getDurationInMinutes(
                    move.getSourceAddress(), move.getDestinationAddress(), move.getClientEmail());
            dto.setDurationInMinutes(String.valueOf(durationMinutes));
            dto.setDuration(String.format("%dh%02d", durationMinutes / 60, durationMinutes % 60));

            double distanceKm = googleMapsService.getDistance(
                    move.getSourceAddress(), move.getDestinationAddress(), move.getClientEmail());
            dto.setDistanceKm(distanceKm);
            dto.setDistanceInKm(String.format("%.2f", distanceKm));

            dto.setAmount(move.getPostCommissionCost());
            dto.setDate(move.getPlannedDate() + " " + move.getPlannedTime());

            return dto;
        }).collect(Collectors.toList());
    }

    @Override
    public void notifyAllDriversOfPlannedMission(MoveRequest moveRequest) {
        log.info("Notifying all drivers of new planned mission: {}", moveRequest.getMoveId());

        List<User> drivers = userRepository.findByRole(Role.DRIVER);

        if (drivers.isEmpty()) {
            log.warn("No drivers found to notify for planned mission {}", moveRequest.getMoveId());
            return;
        }

        double distanceInKm = googleMapsService.getDistance(
                moveRequest.getSourceAddress(), moveRequest.getDestinationAddress(), moveRequest.getClientEmail());
        int durationInMinutes = googleMapsService.getDurationInMinutes(
                moveRequest.getSourceAddress(), moveRequest.getDestinationAddress(), moveRequest.getClientEmail());
        int totalItems = moveRequest.getItems().stream().mapToInt(ItemQuantity::getQuantity).sum();

        String formattedDuration = String.format("%dh%02d", durationInMinutes / 60, durationInMinutes % 60);

        DecimalFormat decimalFormat = new DecimalFormat("#0.00");
        String formattedPostCommissionCost = decimalFormat.format(moveRequest.getPostCommissionCost());
        String formattedDistanceInKm = decimalFormat.format(distanceInKm);

        MissionNotification missionNotification = new MissionNotification(
                moveRequest.getMoveId(),
                moveRequest.getSourceAddress(),
                moveRequest.getDestinationAddress(),
                formattedPostCommissionCost,
                formattedDuration,
                String.valueOf(totalItems),
                formattedDistanceInKm,
                0,
                "AVAILABLE",
                moveRequest.getClient().getLastName() + ' ' + moveRequest.getClient().getFirstName(),
                moveRequest.getClient().getPhoneNumber(),
                moveRequest.getPlannedDate(),
                moveRequest.getPlannedTime(),
                moveRequest.getItems()
        );

        String body;
        try {
            body = objectMapper.writeValueAsString(missionNotification);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize MissionNotification to JSON", e);
            return;
        }

        for (User driver : drivers) {
            Notification notification = new Notification();
            notification.setTitle("New Planned Mission Available");
            notification.setBody(body);
            notification.setTimestamp(LocalDateTime.now());
            notification.setRead(false);
            notification.setNotificationType("PLANNED");
            notification.setRelatedMoveId(moveRequest.getMoveId());
            notification.setUserId(driver.getId());
            notification.setStatus("SENT");

            notificationService.sendAndSaveNotification(notification, missionNotification);
        }

        log.info("Notified {} drivers about new planned mission {}", drivers.size(), moveRequest.getMoveId());
    }

    @Scheduled(cron = "0 0 * * * *")
    public void checkAndAlertForUnassignedPlannedMissions() {
        log.info("Scheduler: Checking for unassigned planned missions that are overdue.");

        Query query = new Query(Criteria.where("mode").is(QuotationType.PLANNED)
                .and("paymentStatus").is("paid")
                .and("booked").is(false)
                .and("adminAlertSent").is(false));

        List<MoveRequest> unassignedMoves = mongoTemplate.find(query, MoveRequest.class);

        for (MoveRequest move : unassignedMoves) {
            if (move.getPlannedDateTime() != null &&
                    move.getPlannedDateTime().plusHours(1).isBefore(LocalDateTime.now())) {

                log.warn("Unassigned planned mission {} is overdue. Sending alert to admin.", move.getMoveId());
                sendEmailToAdminForPlannedMove(move);
                move.setAdminAlertSent(true);
                moveRequestRepository.save(move);
            }
        }
        log.info("Scheduler: Finished checking for overdue unassigned planned missions. Found {} candidates.",
                unassignedMoves.size());
    }

    private void sendEmailToAdminForPlannedMove(MoveRequest move) {
        try {
            Context context = new Context();
            context.setVariable("moveId", move.getMoveId());
            context.setVariable("clientEmail", move.getClientEmail());
            context.setVariable("plannedDateTime", move.getPlannedDateTime());
            context.setVariable("sourceAddress", move.getSourceAddress());

            String htmlContent = templateEngine.process("unassigned-planned-move-alert", context);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            helper.setFrom("ramasquare22@gmail.com");

            List<User> admins = userRepository.findByRole(Role.ADMIN);
            String[] adminEmails = admins.stream().map(User::getEmail).toArray(String[]::new);

            if (adminEmails.length == 0) {
                log.error("No ADMIN user found to send the alert for moveId: {}", move.getMoveId());
                return;
            }

            helper.setTo(adminEmails);
            helper.setSubject("Alerte: Mission planifiée non assignée dans moins de 24h - ID: " + move.getMoveId());
            helper.setText(htmlContent, true);

            mailSender.send(mimeMessage);
            log.info("Email alert sent to admins for unassigned planned move: {}", move.getMoveId());
        } catch (MessagingException e) {
            log.error("Failed to send unassigned planned move alert email for moveId: {}", move.getMoveId(), e);
        }
    }

    public List<DriverLocation> getOnlineDrivers() {
        log.info("Récupération des chauffeurs en ligne, Taille actuelle : {}", onlineDrivers.size());
        return new ArrayList<>(onlineDrivers.values());
    }

    public List<String> getBusyDriverIds() {
        return moveRequestRepository.findByAssignmentStatus("ASSIGNED")
                .stream()
                .map(mr -> mr.getDriver() != null ? mr.getDriver().getId() : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    // ✅ FIXED: Major refactoring with proper locking and state management
    public void startDriverAssignment(String moveId) {
        log.info("🚀 Début du processus d'assignation de chauffeur pour moveId : {}", moveId);

        // ✅ Check assignment cooldown
        LocalDateTime lastAttempt = lastAssignmentAttempts.get(moveId);
        if (lastAttempt != null &&
                lastAttempt.isAfter(LocalDateTime.now().minusSeconds(ASSIGNMENT_RETRY_COOLDOWN_SECONDS))) {
            log.info("⏱️ Assignment cooldown active for moveId: {} (last attempt: {})", moveId, lastAttempt);
            return;
        }

        // ✅ Concurrency guard
        if (!activeAssignments.add(moveId)) {
            log.warn("🚫 Assignment for moveId {} already running — skipping duplicate start", moveId);
            return;
        }

        Lock lock = getMissionLock(moveId);
        try {
            if (!lock.tryLock(10, TimeUnit.SECONDS)) {
                log.warn("⏰ Could not acquire lock for moveId {} within timeout", moveId);
                return;
            }

            try {
                performDriverAssignment(moveId);
            } finally {
                lock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("❌ Interrupted while acquiring lock for moveId {}", moveId);
        } finally {
            activeAssignments.remove(moveId);
            lastAssignmentAttempts.put(moveId, LocalDateTime.now());
        }
    }

    // ✅ NEW: Core assignment logic extracted for better testability
    private void performDriverAssignment(String moveId) {
        // ✅ Use atomic update to set assignment status
        Query query = new Query(Criteria.where("moveId").is(moveId)
                .and("assignmentStatus").nin("WAITING_FOR_DRIVER", "ASSIGNED"));

        Update update = new Update()
                .set("assignmentStatus", "ASSIGNING")
                .set("lastModified", LocalDateTime.now());

        UpdateResult result = mongoTemplate.updateFirst(query, update, MoveRequest.class);

        if (result.getModifiedCount() == 0) {
            log.warn("⚠️ Mission {} already being assigned or in wrong state", moveId);
            return;
        }

        // Fetch fresh copy after atomic update
        MoveRequest moveRequest = moveRequestRepository.findById(moveId)
                .orElseThrow(() -> new IllegalArgumentException("Move request not found: " + moveId));

        GeoJsonPoint sourceLocation = moveRequest.getSourceLocation();
        double sourceLat = sourceLocation.getY();
        double sourceLon = sourceLocation.getX();
        String originCoords = sourceLat + "," + sourceLon;

        List<String> busyDriverIds = getBusyDriverIds();
        List<DriverLocation> availableDrivers = getOnlineDrivers().stream()
                .filter(driver -> !busyDriverIds.contains(driver.getDriverId()))
                .filter(driver -> driver.getLatitude() != 0.0 && driver.getLongitude() != 0.0)
                .toList();

        log.info("📊 Nombre de chauffeurs disponibles : {}", availableDrivers.size());

        if (availableDrivers.isEmpty()) {
            log.warn("❌ Aucun chauffeur disponible pour moveId : {}", moveId);
            updateMissionStatus(moveId, "NO_DRIVERS_AVAILABLE",
                    "Assignment status changed to NO_DRIVERS_AVAILABLE");
            return;
        }

        List<String> destinationCoords = availableDrivers.stream()
                .map(driver -> driver.getLatitude() + "," + driver.getLongitude())
                .collect(Collectors.toList());

        List<Double> drivingDistances;
        try {
            drivingDistances = googleMapsService.getDistances(originCoords, destinationCoords, moveRequest.getClientEmail());
            log.info("📏 Distances de conduite récupérées : {}", drivingDistances);
        } catch (RuntimeException e) {
            log.error("❌ Échec du calcul des distances pour moveId : {}", moveId, e);
            updateMissionStatus(moveId, "DISTANCE_CALCULATION_FAILED",
                    "Assignment status changed to DISTANCE_CALCULATION_FAILED");
            return;
        }

        if (drivingDistances.isEmpty() || drivingDistances.size() != availableDrivers.size()) {
            log.warn("⚠️ Résultats de distance invalides pour moveId : {}", moveId);
            updateMissionStatus(moveId, "DISTANCE_CALCULATION_FAILED",
                    "Assignment status changed to DISTANCE_CALCULATION_FAILED");
            return;
        }

        List<DriverDistancePair> driverDistances = new ArrayList<>();
        for (int i = 0; i < availableDrivers.size(); i++) {
            double distance = drivingDistances.get(i) / 1000.0;
            if (distance <= MAX_RADIUS_KM) {
                driverDistances.add(new DriverDistancePair(availableDrivers.get(i), distance));
            }
        }

        driverDistances.sort(Comparator.comparingDouble(pair -> pair.distance));

        List<DriverLocation> sortedDrivers = driverDistances.stream()
                .map(pair -> pair.driver)
                .toList();

        if (sortedDrivers.isEmpty()) {
            log.warn("❌ Aucun chauffeur dans un rayon de {} km pour moveId : {}", MAX_RADIUS_KM, moveId);
            updateMissionStatus(moveId, "NO_DRIVERS_IN_RANGE",
                    "Assignment status changed to NO_DRIVERS_IN_RANGE");
            return;
        }

        // ✅ Atomic update to set candidate drivers
        Query updateQuery = new Query(Criteria.where("moveId").is(moveId)
                .and("assignmentStatus").is("ASSIGNING"));

        Update candidateUpdate = new Update()
                .set("candidateDrivers", sortedDrivers.stream().map(DriverLocation::getDriverId).collect(Collectors.toList()))
                .set("currentDriverIndex", 0)
                .set("assignmentStatus", "WAITING_FOR_DRIVER")
                .set("lastModified", LocalDateTime.now());

        result = mongoTemplate.updateFirst(updateQuery, candidateUpdate, MoveRequest.class);

        if (result.getModifiedCount() == 0) {
            log.warn("⚠️ Failed to update candidate drivers for moveId: {}", moveId);
            return;
        }

        // Refresh after update
        moveRequest = moveRequestRepository.findById(moveId).orElseThrow();

        String closestDriverId = sortedDrivers.getFirst().getDriverId();
        addHistoryEvent(moveRequest, "MISSION_OFFERED_TO_DRIVER",
                String.format("Offer sent to driver %s", closestDriverId), "SYSTEM");
        moveRequestRepository.save(moveRequest);

        sendMissionOfferToDriver(moveRequest, closestDriverId, "initial assignment");
    }

    // ✅ NEW: Helper method to send mission offer
    private void sendMissionOfferToDriver(MoveRequest moveRequest, String driverId, String context) {
        double distanceInKm = googleMapsService.getDistance(
                moveRequest.getSourceAddress(), moveRequest.getDestinationAddress(), moveRequest.getClientEmail());
        int durationInMinutes = googleMapsService.getDurationInMinutes(
                moveRequest.getSourceAddress(), moveRequest.getDestinationAddress(), moveRequest.getClientEmail());
        int totalItems = moveRequest.getItems().stream().mapToInt(ItemQuantity::getQuantity).sum();

        DecimalFormat decimalFormat = new DecimalFormat("#0.00");
        String formattedPostCommissionCost = decimalFormat.format(moveRequest.getPostCommissionCost());
        String formattedDistanceInKm = decimalFormat.format(distanceInKm);
        String formattedDurationInMinutes = String.valueOf(durationInMinutes);
        String formattedTotalItems = String.valueOf(totalItems);

        MissionNotification missionNotification = new MissionNotification(
                moveRequest.getMoveId(),
                moveRequest.getSourceAddress(),
                moveRequest.getDestinationAddress(),
                formattedPostCommissionCost,
                formattedDurationInMinutes,
                formattedTotalItems,
                formattedDistanceInKm,
                ASSIGNMENT_TIMEOUT_SECONDS,
                "OFFERED",
                moveRequest.getClient().getLastName() + ' ' + moveRequest.getClient().getFirstName(),
                moveRequest.getClient().getPhoneNumber(),
                moveRequest.getPlannedDate(),
                moveRequest.getPlannedTime(),
                moveRequest.getItems()
        );

        String body;
        try {
            body = objectMapper.writeValueAsString(missionNotification);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize MissionNotification to JSON", e);
            return;
        }

        Notification notification = new Notification();
        notification.setTitle("New Mission Offer");
        notification.setBody(body);
        notification.setTimestamp(LocalDateTime.now());
        notification.setRead(false);
        notification.setNotificationType("MISSION_OFFER");
        notification.setRelatedMoveId(moveRequest.getMoveId());
        notification.setUserId(driverId);
        notification.setStatus("SENT");

        notificationService.sendAndSaveNotification(notification, missionNotification);
        log.info("✅ Mission offered to driver {} for moveId {} ({})", driverId, moveRequest.getMoveId(), context);

        scheduleAssignmentTimeout(moveRequest.getMoveId());
    }

    // ✅ NEW: Helper to schedule timeout
    private void scheduleAssignmentTimeout(String moveId) {
        cancelAssignmentTimeout(moveId); // Cancel any existing timeout

        ScheduledFuture<?> future = taskScheduler.schedule(
                () -> checkAssignmentTimeout(moveId),
                new Date(System.currentTimeMillis() + ASSIGNMENT_TIMEOUT_SECONDS * 1000)
        );
        assignmentTimeouts.put(moveId, future);
    }

    // ✅ NEW: Helper to cancel timeout
    private void cancelAssignmentTimeout(String moveId) {
        ScheduledFuture<?> future = assignmentTimeouts.remove(moveId);
        if (future != null) {
            future.cancel(false);
            log.debug("🚫 Cancelled timeout for moveId: {}", moveId);
        }
    }

    // ✅ FIXED: Improved reStartDriverAssignment with atomic operations
    public void reStartDriverAssignment(String moveId) {
        Lock lock = getMissionLock(moveId);
        try {
            if (!lock.tryLock(5, TimeUnit.SECONDS)) {
                log.warn("Could not acquire lock for restarting assignment for moveId: {}", moveId);
                return;
            }

            try {
                MoveRequest moveRequest = moveRequestRepository.findById(moveId)
                        .orElseThrow(() -> new IllegalArgumentException("Move request not found: " + moveId));

                int currentIndex = moveRequest.getCurrentDriverIndex();
                List<String> candidates = moveRequest.getCandidateDrivers();

                cancelAssignmentTimeout(moveId);

                if (currentIndex + 1 < candidates.size()) {
                    String currentDriverId = candidates.get(currentIndex);
                    sendMissionRevokedNotification(currentDriverId, moveRequest);

                    // ✅ Atomic update
                    Query query = new Query(Criteria.where("moveId").is(moveId)
                            .and("assignmentStatus").is("WAITING_FOR_DRIVER"));

                    Update update = new Update()
                            .set("currentDriverIndex", currentIndex + 1)
                            .set("lastModified", LocalDateTime.now());

                    UpdateResult result = mongoTemplate.updateFirst(query, update, MoveRequest.class);

                    if (result.getModifiedCount() == 0) {
                        log.warn("Failed to update driver index for moveId: {}", moveId);
                        return;
                    }

                    moveRequest = moveRequestRepository.findById(moveId).orElseThrow();
                    String nextDriverId = candidates.get(currentIndex + 1);

                    addHistoryEvent(moveRequest, "MISSION_OFFERED_TO_DRIVER",
                            String.format("Offer sent to driver %s", nextDriverId), "SYSTEM");
                    moveRequestRepository.save(moveRequest);

                    sendMissionOfferToDriver(moveRequest, nextDriverId, "after timeout/decline");
                } else {
                    log.warn("❌ Aucun chauffeur disponible dans un rayon de {} km pour moveId : {}",
                            MAX_RADIUS_KM, moveId);
                    updateMissionStatus(moveId, "NO_DRIVERS_IN_RANGE",
                            "Assignment status changed to NO_DRIVERS_IN_RANGE");

                    String lastDriverId = candidates.get(currentIndex);
                    sendNoDriversNotification(moveRequest, lastDriverId);
                }
            } finally {
                lock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while restarting assignment for moveId: {}", moveId);
        }
    }

    // ✅ NEW: Helper method to update mission status atomically
    private void updateMissionStatus(String moveId, String status, String historyDetails) {
        Query query = new Query(Criteria.where("moveId").is(moveId));
        Update update = new Update()
                .set("assignmentStatus", status)
                .set("lastModified", LocalDateTime.now());

        mongoTemplate.updateFirst(query, update, MoveRequest.class);

        MoveRequest moveRequest = moveRequestRepository.findById(moveId).orElse(null);
        if (moveRequest != null) {
            addHistoryEvent(moveRequest, "STATUS_CHANGED", historyDetails, "SYSTEM");
            moveRequestRepository.save(moveRequest);
        }
    }

    // ✅ NEW: Helper to send revoked notification
    private void sendMissionRevokedNotification(String driverId, MoveRequest moveRequest) {
        try {
            MissionNotification revokedNotification = new MissionNotification(
                    moveRequest.getMoveId(),
                    moveRequest.getSourceAddress(),
                    moveRequest.getDestinationAddress(),
                    "0", "0", "0", "0", 0,
                    "REVOKED",
                    moveRequest.getClient().getLastName() + ' ' + moveRequest.getClient().getFirstName(),
                    moveRequest.getClient().getPhoneNumber(),
                    moveRequest.getPlannedDate(),
                    moveRequest.getPlannedTime(),
                    moveRequest.getItems()
            );

            String body = objectMapper.writeValueAsString(revokedNotification);

            Notification notification = new Notification();
            notification.setTitle("Mission Revoked");
            notification.setBody(body);
            notification.setTimestamp(LocalDateTime.now());
            notification.setRead(false);
            notification.setNotificationType("MISSION_REVOKED");
            notification.setRelatedMoveId(moveRequest.getMoveId());
            notification.setUserId(driverId);
            notification.setStatus("SENT");

            notificationService.sendAndSaveNotification(notification, revokedNotification);
            log.info("🚫 Sent MISSION_REVOKED to previous driver {}", driverId);
        } catch (Exception e) {
            log.error("❌ Failed to send MISSION_REVOKED to driver {}: {}", driverId, e.getMessage());
        }
    }

    // ✅ NEW: Helper to send no drivers notification
    private void sendNoDriversNotification(MoveRequest moveRequest, String driverId) {
        double distanceInKm = googleMapsService.getDistance(
                moveRequest.getSourceAddress(), moveRequest.getDestinationAddress(), moveRequest.getClientEmail());
        int durationInMinutes = googleMapsService.getDurationInMinutes(
                moveRequest.getSourceAddress(), moveRequest.getDestinationAddress(), moveRequest.getClientEmail());
        int totalItems = moveRequest.getItems().stream().mapToInt(ItemQuantity::getQuantity).sum();

        DecimalFormat decimalFormat = new DecimalFormat("#0.00");
        String formattedPostCommissionCost = decimalFormat.format(moveRequest.getPostCommissionCost());
        String formattedDistanceInKm = decimalFormat.format(distanceInKm);
        String formattedDurationInMinutes = String.valueOf(durationInMinutes);
        String formattedTotalItems = String.valueOf(totalItems);

        MissionNotification missionNotification = new MissionNotification(
                moveRequest.getMoveId(),
                moveRequest.getSourceAddress(),
                moveRequest.getDestinationAddress(),
                formattedPostCommissionCost,
                formattedDurationInMinutes,
                formattedTotalItems,
                formattedDistanceInKm,
                0,
                "NO_DRIVERS_IN_RANGE",
                moveRequest.getClient().getLastName() + ' ' + moveRequest.getClient().getFirstName(),
                moveRequest.getClient().getPhoneNumber(),
                moveRequest.getPlannedDate(),
                moveRequest.getPlannedTime(),
                moveRequest.getItems()
        );

        try {
            String body = objectMapper.writeValueAsString(missionNotification);

            Notification notification = new Notification();
            notification.setTitle("No Drivers Available");
            notification.setBody(body);
            notification.setTimestamp(LocalDateTime.now());
            notification.setRead(false);
            notification.setNotificationType("NO_DRIVERS_IN_RANGE");
            notification.setRelatedMoveId(moveRequest.getMoveId());
            notification.setUserId(driverId);
            notification.setStatus("SENT");

            notificationService.sendAndSaveNotification(notification, missionNotification);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize notification", e);
        }
    }

    private void sendMissionExpiredNotification(String driverId, MoveRequest moveRequest) {
        double distanceInKm = googleMapsService.getDistance(
                moveRequest.getSourceAddress(), moveRequest.getDestinationAddress(), moveRequest.getClientEmail());
        int durationInMinutes = googleMapsService.getDurationInMinutes(
                moveRequest.getSourceAddress(), moveRequest.getDestinationAddress(), moveRequest.getClientEmail());
        int totalItems = moveRequest.getItems().stream().mapToInt(ItemQuantity::getQuantity).sum();

        DecimalFormat decimalFormat = new DecimalFormat("#0.00");
        String formattedPostCommissionCost = decimalFormat.format(moveRequest.getPostCommissionCost());
        String formattedDistanceInKm = decimalFormat.format(distanceInKm);
        String formattedDurationInMinutes = String.valueOf(durationInMinutes);
        String formattedTotalItems = String.valueOf(totalItems);

        MissionNotification updatedNotification = new MissionNotification(
                moveRequest.getMoveId(),
                moveRequest.getSourceAddress(),
                moveRequest.getDestinationAddress(),
                formattedPostCommissionCost,
                formattedDurationInMinutes,
                formattedTotalItems,
                formattedDistanceInKm,
                0,
                "EXPIRED",
                moveRequest.getClient().getLastName() + ' ' + moveRequest.getClient().getFirstName(),
                moveRequest.getClient().getPhoneNumber(),
                moveRequest.getPlannedDate(),
                moveRequest.getPlannedTime(),
                moveRequest.getItems()
        );

        try {
            String body = objectMapper.writeValueAsString(updatedNotification);

            Notification notification = new Notification();
            notification.setTitle("Mission Offer Expired");
            notification.setBody(body);
            notification.setTimestamp(LocalDateTime.now());
            notification.setRead(false);
            notification.setNotificationType("MISSION_EXPIRED");
            notification.setRelatedMoveId(moveRequest.getMoveId());
            notification.setUserId(driverId);
            notification.setStatus("SENT");

            notificationService.sendAndSaveNotification(notification, updatedNotification);
            log.info("Notification mise à jour en EXPIRÉ pour driverId : {} et moveId : {}",
                    driverId, moveRequest.getMoveId());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize notification", e);
        }
    }

    @Override
    public MoveDetails getMoveDetails(String moveId) {
        MoveRequest moveRequest = moveRequestRepository.findById(moveId)
                .orElseThrow(() -> new RuntimeException("Move not found: " + moveId));

        double distance = googleMapsService.getDistance(
                moveRequest.getSourceAddress(), moveRequest.getDestinationAddress(), moveRequest.getClientEmail());
        int durationMinutes = googleMapsService.getDurationInMinutes(
                moveRequest.getSourceAddress(), moveRequest.getDestinationAddress(), moveRequest.getClientEmail());

        LocalDateTime departureTime = moveRequest.getAssignmentTimestamp() != null
                ? moveRequest.getAssignmentTimestamp()
                : LocalDateTime.now();
        LocalDate date = departureTime.toLocalDate();
        LocalDateTime arrivalTime = departureTime.plusMinutes(durationMinutes);

        return new MoveDetails(
                distance,
                durationMinutes,
                date,
                departureTime,
                arrivalTime,
                moveRequest.getItems(),
                moveRequest.getPaymentStatus(),
                moveRequest.getSourceAddress(),
                moveRequest.getDestinationAddress()
        );
    }

    @Override
    public List<DriverMissionSummaryDTO> getLatestMissionSummaryForClient(String clientId) {
        log.info("Fetching latest mission summary for clientId: {}", clientId);

        Query query = new Query();
        query.addCriteria(Criteria.where("client.id").is(clientId));
        query.with(Sort.by(Sort.Order.desc("assignmentTimestamp")));
        List<MoveRequest> moveRequests = mongoTemplate.find(query, MoveRequest.class);

        if (moveRequests.isEmpty()) {
            log.info("No mission found for clientId: {}", clientId);
            return Collections.emptyList();
        }

        List<DriverMissionSummaryDTO> summaries = new ArrayList<>();
        for (MoveRequest moveRequest : moveRequests) {
            DriverMissionSummaryDTO summary = new DriverMissionSummaryDTO();
            summary.setMoveId(moveRequest.getMoveId());

            int durationMinutes = googleMapsService.getDurationInMinutes(
                    moveRequest.getSourceAddress(), moveRequest.getDestinationAddress(), moveRequest.getClientEmail());
            String duration = String.format("%dh%02d", durationMinutes / 60, durationMinutes % 60);
            summary.setDuration(duration);

            double distanceKm = googleMapsService.getDistance(
                    moveRequest.getSourceAddress(), moveRequest.getDestinationAddress(), moveRequest.getClientEmail());
            summary.setDistanceKm(distanceKm);

            summary.setSourceAddress(moveRequest.getSourceAddress());
            summary.setDestinationAddress(moveRequest.getDestinationAddress());
            summary.setDate(moveRequest.getPlannedDate() != null ? moveRequest.getPlannedDate() : null);

            int productCount = moveRequest.getItems() != null
                    ? moveRequest.getItems().stream().mapToInt(ItemQuantity::getQuantity).sum()
                    : 0;
            summary.setProductCount(productCount);

            summary.setAmount(moveRequest.getPreCommissionCost());

            summary.setStatus(moveRequest.getMissionStatus() != null
                    ? moveRequest.getMissionStatus().getNameValue()
                    : moveRequest.getStatus() != null ? moveRequest.getStatus().name() : "");

            if (moveRequest.getDriver() != null) {
                summary.setDriverFullName(moveRequest.getDriver().getFirstName() + " " +
                        moveRequest.getDriver().getLastName());
                summary.setDriverPhoneNumber(moveRequest.getDriver().getPhoneNumber());
            } else {
                summary.setDriverFullName("");
                summary.setDriverPhoneNumber("");
            }

            summaries.add(summary);
        }

        log.info("Mission summaries retrieved successfully for clientId: {}", clientId);
        return summaries;
    }

    // ✅ FIXED: handleDriverAcceptance with proper locking
    public void handleDriverAcceptance(String moveId, String driverId) {
        Lock lock = getMissionLock(moveId);
        try {
            if (!lock.tryLock(5, TimeUnit.SECONDS)) {
                log.warn("Could not acquire lock for driver acceptance for moveId: {}", moveId);
                throw new IllegalStateException("Mission is being processed by another request");
            }

            try {
                // ✅ Atomic update to prevent double acceptance
                Query query = new Query(Criteria.where("moveId").is(moveId)
                        .and("assignmentStatus").is("WAITING_FOR_DRIVER"));

                Update update = new Update()
                        .set("assignmentStatus", "ACCEPTING")
                        .set("lastModified", LocalDateTime.now());

                UpdateResult result = mongoTemplate.updateFirst(query, update, MoveRequest.class);

                if (result.getModifiedCount() == 0) {
                    log.warn("Mission {} is not in WAITING_FOR_DRIVER state", moveId);
                    throw new IllegalStateException("Mission is not available for acceptance");
                }

                MoveRequest moveRequest = moveRequestRepository.findById(moveId)
                        .orElseThrow(() -> new IllegalArgumentException("Move request not found: " + moveId));

                // Verify this driver is the current candidate
                String currentCandidate = moveRequest.getCandidateDrivers()
                        .get(moveRequest.getCurrentDriverIndex());

                if (!currentCandidate.equals(driverId)) {
                    // Rollback status
                    updateMissionStatus(moveId, "WAITING_FOR_DRIVER", "Acceptance rejected - wrong driver");
                    throw new IllegalStateException("You are not the current assigned driver");
                }

                // Check if driver is already busy
                Optional<MoveRequest> existingAssignment = moveRequestRepository
                        .findByDriverIdAndAssignmentStatus(driverId, "ASSIGNED");

                if (existingAssignment.isPresent()) {
                    updateMissionStatus(moveId, "WAITING_FOR_DRIVER", "Acceptance rejected - driver busy");
                    log.warn("Driver {} is already assigned to another mission", driverId);
                    throw new IllegalStateException("Driver is already assigned to another mission");
                }

                Driver driver = driverRepository.findById(driverId)
                        .orElseThrow(() -> new IllegalArgumentException("Driver not found: " + driverId));

                // Final atomic update to ASSIGNED
                query = new Query(Criteria.where("moveId").is(moveId)
                        .and("assignmentStatus").is("ACCEPTING"));

                update = new Update()
                        .set("assignmentStatus", "ASSIGNED")
                        .set("driver", driver)
                        .set("missionStatus", MissionStatus.ACCEPTED)
                        .set("acceptedAt", LocalDateTime.now())
                        .set("lastModified", LocalDateTime.now());

                result = mongoTemplate.updateFirst(query, update, MoveRequest.class);

                if (result.getModifiedCount() == 0) {
                    log.error("Failed to finalize acceptance for moveId: {}", moveId);
                    throw new IllegalStateException("Failed to finalize mission acceptance");
                }

                moveRequest = moveRequestRepository.findById(moveId).orElseThrow();
                addHistoryEvent(moveRequest, "MISSION_ACCEPTED",
                        String.format("Accepted by driver %s", driverId), driverId);
                moveRequestRepository.save(moveRequest);

                cancelAssignmentTimeout(moveId);

                log.info("✅ Mission acceptée par le chauffeur {} pour moveId : {}", driverId, moveId);

                // Notify client
                if (moveRequest.getClient() != null) {
                    Notification clientNotification = new Notification();
                    clientNotification.setNotificationType("MISSION_STATUS_UPDATE");
                    clientNotification.setTimestamp(LocalDateTime.now());
                    clientNotification.setRead(false);
                    clientNotification.setRelatedMoveId(moveId);
                    clientNotification.setUserId(moveRequest.getClient().getId());
                    clientNotification.setStatus("SENT");
                    notificationService.sendAndSaveNotification(clientNotification, moveRequest);
                }

                // Send email to driver
                sendDriverMissionEmail(driver, moveRequest);

            } finally {
                lock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while handling driver acceptance for moveId: {}", moveId);
            throw new IllegalStateException("Failed to process acceptance");
        }
    }

    // ✅ NEW: Helper method for sending driver email
    private void sendDriverMissionEmail(Driver driver, MoveRequest moveRequest) {
        try {
            String driverEmail = driver.getEmail();

            Context context = new Context();
            context.setVariable("source", moveRequest.getSourceAddress());
            context.setVariable("destination", moveRequest.getDestinationAddress());
            context.setVariable("clientEmail", moveRequest.getClientEmail());
            context.setVariable("clientPhone", moveRequest.getClient() != null ?
                    moveRequest.getClient().getPhoneNumber() : "N/A");

            DecimalFormat decimalFormat = new DecimalFormat("#0.000");
            String formattedCost = decimalFormat.format(moveRequest.getPreCommissionCost());
            context.setVariable("preCommissionCost", formattedCost);

            String htmlContent = templateEngine.process("driver-mission-details", context);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            helper.setFrom("no-reply@dema.com");
            helper.setTo(driverEmail);
            helper.setSubject("Détails de la mission acceptée");
            helper.setText(htmlContent, true);
            mailSender.send(mimeMessage);

            log.info("Email sent to driver {} for moveId: {}", driver.getId(), moveRequest.getMoveId());
        } catch (MessagingException e) {
            log.error("Failed to send mission details email to driver {}: {}",
                    driver.getId(), e.getMessage());
        }
    }

    private void checkAssignmentTimeout(String moveId) {
        Lock lock = getMissionLock(moveId);
        try {
            if (!lock.tryLock(3, TimeUnit.SECONDS)) {
                log.warn("Could not acquire lock for timeout check for moveId: {}", moveId);
                return;
            }

            try {
                MoveRequest moveRequest = moveRequestRepository.findById(moveId).orElse(null);

                if (moveRequest != null && "WAITING_FOR_DRIVER".equals(moveRequest.getAssignmentStatus())) {
                    log.info("⏰ Le chauffeur n'a pas répondu dans le délai pour moveId : {}", moveId);

                    String currentDriverId = moveRequest.getCandidateDrivers()
                            .get(moveRequest.getCurrentDriverIndex());

                    addHistoryEvent(moveRequest, "MISSION_TIMEOUT",
                            String.format("Timeout for driver %s", currentDriverId), "SYSTEM");
                    moveRequestRepository.save(moveRequest);

                    sendMissionExpiredNotification(currentDriverId, moveRequest);

                    assignmentTimeouts.remove(moveId);
                    reStartDriverAssignment(moveId);
                }
            } finally {
                lock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted during timeout check for moveId: {}", moveId);
        }
    }

    public void handleDriverDecline(String moveId, String driverId) {
        log.info("🚚 Driver {} declining mission {}", driverId, moveId);

        Lock lock = getMissionLock(moveId);
        try {
            if (!lock.tryLock(5, TimeUnit.SECONDS)) {
                log.warn("Could not acquire lock for driver decline for moveId: {}", moveId);
                throw new IllegalStateException("Mission is being processed");
            }

            try {
                MoveRequest moveRequest = moveRequestRepository.findById(moveId)
                        .orElseThrow(() -> new IllegalArgumentException("Move request not found: " + moveId));

                if (!"WAITING_FOR_DRIVER".equals(moveRequest.getAssignmentStatus())) {
                    log.warn("Mission {} is not in WAITING_FOR_DRIVER state", moveId);
                    throw new IllegalStateException("Mission cannot be declined. Current status: " +
                            moveRequest.getAssignmentStatus());
                }

                String currentCandidate = moveRequest.getCandidateDrivers()
                        .get(moveRequest.getCurrentDriverIndex());

                if (!currentCandidate.equals(driverId)) {
                    log.warn("Driver {} is not the current candidate for mission {}", driverId, moveId);
                    throw new IllegalStateException("You are not the assigned driver for this mission");
                }

                addHistoryEvent(moveRequest, "MISSION_REFUSED",
                        String.format("Declined by driver %s", driverId), driverId);
                moveRequestRepository.save(moveRequest);

                log.info("✅ Mission declined by driver {} for moveId: {}", driverId, moveId);

                reStartDriverAssignment(moveId);

            } finally {
                lock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while handling driver decline for moveId: {}", moveId);
            throw new IllegalStateException("Failed to process decline");
        }
    }

    private static class DriverDistancePair {
        DriverLocation driver;
        double distance;

        DriverDistancePair(DriverLocation driver, double distance) {
            this.driver = driver;
            this.distance = distance;
        }
    }

    // ✅ FIXED: updateDriverLocation with better concurrency control
    public void updateDriverLocation(String driverId, double latitude, double longitude) {
        DriverLocation dl = onlineDrivers.get(driverId);
        if (dl != null) {
            dl.setLatitude(latitude);
            dl.setLongitude(longitude);
            log.info("Mise à jour de l'emplacement pour le chauffeur {} à ({}, {})", driverId, latitude, longitude);
        } else {
            DriverLocation newDl = new DriverLocation(driverId, latitude, longitude);
            onlineDrivers.put(driverId, newDl);
            log.info("Ajout d'un nouveau chauffeur avec driverId : {} et emplacement ({}, {})",
                    driverId, latitude, longitude);
        }

        // ✅ Only retry ONE pending mission at a time with throttling
        Query pendingQuery = new Query();
        pendingQuery.addCriteria(Criteria.where("assignmentStatus")
                .in("NO_DRIVERS_AVAILABLE", "NO_DRIVERS_IN_RANGE")
                .and("paymentStatus").is("paid"));
        pendingQuery.with(Sort.by(Sort.Direction.DESC, "dateOfPayment"));
        pendingQuery.limit(1); // ✅ Process only ONE at a time

        MoveRequest pending = mongoTemplate.findOne(pendingQuery, MoveRequest.class);

        if (pending != null) {
            // ✅ Check if we recently attempted this mission
            LocalDateTime lastAttempt = lastAssignmentAttempts.get(pending.getMoveId());
            if (lastAttempt != null &&
                    lastAttempt.isAfter(LocalDateTime.now().minusSeconds(ASSIGNMENT_RETRY_COOLDOWN_SECONDS))) {
                log.debug("⏱️ Skipping pending mission {} - cooldown active", pending.getMoveId());
                return;
            }

            // ✅ Atomic update to mark as being retried
            Query updateQuery = new Query(Criteria.where("moveId").is(pending.getMoveId())
                    .and("assignmentStatus").in("NO_DRIVERS_AVAILABLE", "NO_DRIVERS_IN_RANGE"));

            Update update = new Update()
                    .set("lastRetryAttempt", LocalDateTime.now());

            UpdateResult result = mongoTemplate.updateFirst(updateQuery, update, MoveRequest.class);

            if (result.getModifiedCount() > 0) {
                log.info("♻️ Retrying driver assignment for pending paid mission {}", pending.getMoveId());
                startDriverAssignment(pending.getMoveId());
            }
        }
    }

    @Override
    public MoveRequest getMoveRequestById(String moveId) {
        log.info("Récupération de la demande de déménagement avec moveId : {}", moveId);
        return moveRequestRepository.findById(moveId)
                .orElseThrow(() -> {
                    logResourceNotFound(moveId);
                    return new ResourceNotFoundException("Move request not found: " + moveId);
                });
    }

    @Override
    public List<String> getAddressSuggestions(String query) {
        log.info("Récupération des suggestions d'adresses pour la requête : {}", query);
        List<String> suggestions = googleMapsService.getAddressSuggestions(query);
        log.info("Suggestions d'adresses récupérées : {} résultats", suggestions.size());
        return suggestions;
    }

    private String generateReference() {
        long randomNumber = (long) (Math.random() * 1_000_000_0000L);
        String formattedNumber = String.format("%010d", randomNumber);
        return "TIC" + formattedNumber;
    }

    public MoveRequest calculateQuote(QuoteCalculationRequest request, String email) {
        log.info("🧮 [START] Calcul du devis pour l'utilisateur : {}", email);

        Bucket bucket = getUserBucket(email);
        if (!bucket.tryConsume(1)) {
            log.warn("🚫 [RATE LIMIT] Limite dépassée pour : {}", email);
            throw new BadRequestException(
                    "Vous avez dépassé la limite de 20 demandes de devis par 24 heures. Veuillez réessayer plus tard.");
        }

        if (request.sourceAddress() == null || request.destinationAddress() == null ||
                request.items() == null || request.items().isEmpty() || request.mode() == null) {
            log.warn("⚠️ [VALIDATION] Champs requis manquants");
            throw new IllegalArgumentException("Missing required fields.");
        }

        if (QuotationType.PLANNED.equals(request.mode())) {
            log.debug("📆 Mode planifié détecté");
            if (request.plannedDate() == null || request.plannedTime() == null) {
                throw new BadRequestException("Planned date and time are required for a planned mission.");
            }
            try {
                LocalDate date = LocalDate.parse(request.plannedDate(), DateTimeFormatter.ofPattern("dd/MM/yyyy"));
                LocalTime time = LocalTime.parse(request.plannedTime(), DateTimeFormatter.ofPattern("HH:mm"));
                LocalDateTime.of(date, time);
            } catch (DateTimeParseException e) {
                throw new BadRequestException("Invalid date or time format. Use 'dd/MM/yyyy' and 'HH:mm'.");
            }
        }

        log.info("📦 Validation et récupération des articles...");
        List<Item> items = validateAndFetchItems(request.items());

        log.info("🗺️ Récupération des données de distance et géolocalisation...");
        double distanceKm = googleMapsService.getDistance(
                request.sourceAddress(), request.destinationAddress(), email);
        GeoJsonPoint sourceLocation = googleMapsService.getCoordinates(request.sourceAddress(), email);

        int totalFloors = request.sourceFloors() + request.destinationFloors();
        double totalVolume = 0.0;
        int maxMinTruckSize = 0;
        int totalItems = 0;
        List<ItemQuantity> updatedItems = new ArrayList<>();

        for (ItemQuantity iq : request.items()) {
            String key = iq.getItemLabel();
            Item item = items.stream().filter(i -> i.getLabel().equals(key)).findFirst().orElseThrow();

            totalVolume += Double.parseDouble(item.getVolume()) * iq.getQuantity();
            if (iq.getQuantity() > 0) {
                maxMinTruckSize = Math.max(maxMinTruckSize, item.getMinTruckSize());
            }

            if (item.getStair_time() != null && !item.getStair_time().isEmpty()) {
                double stairTime = Double.parseDouble(item.getStair_time()) * iq.getQuantity();
                iq.setStairTime((int) stairTime);
            }

            totalItems += iq.getQuantity();
            updatedItems.add(iq);
        }

        // Truck calculation
        class Truck {
            final int volume;
            final double price;
            Truck(int volume, double price) {
                this.volume = volume;
                this.price = price;
            }
        }

        List<Truck> truckTypes = Arrays.asList(new Truck(12, 107.91), new Truck(20, 129.7));
        int minTruckVolume = truckTypes.stream().mapToInt(t -> t.volume).min().orElse(0);
        int maxTruckVolume = truckTypes.stream().mapToInt(t -> t.volume).max().orElse(0);

        if (maxMinTruckSize > maxTruckVolume || maxMinTruckSize < minTruckVolume) {
            throw new IllegalArgumentException("Selected items require incompatible truck sizes.");
        }

        double basePrice;
        if (totalVolume == 0) {
            basePrice = 0;
        } else if (maxMinTruckSize > 12) {
            int numLarge = (int) Math.ceil(totalVolume / 20.0);
            basePrice = numLarge * 129.7;
        } else {
            double minCost = Double.MAX_VALUE;
            int maxLarge = (int) Math.ceil(totalVolume / 12.0);
            for (int numLarge = 0; numLarge <= maxLarge; numLarge++) {
                double remainingVolume = Math.max(0, totalVolume - numLarge * 20.0);
                int numSmall = (int) Math.ceil(remainingVolume / 12.0);
                double cost = numLarge * 129.7 + numSmall * 107.91;
                if (cost < minCost) minCost = cost;
            }
            basePrice = minCost;
        }

        // Handling cost calculation
        double totalStairTimeSeconds = updatedItems.stream().mapToDouble(ItemQuantity::getStairTime).sum();
        double handlingMinutesPerFloor = totalStairTimeSeconds / 60.0;
        double handlingCost;

        if (totalFloors == 0 && totalItems <= 9) {
            handlingCost = 0;
        } else {
            double rate;
            if (totalItems <= 5) rate = 2.5;
            else if (totalItems <= 10) rate = 3.5;
            else if (totalItems <= 15) rate = 4.5;
            else if (totalItems <= 20) rate = 6.0;
            else if (totalItems <= 25) rate = 7.0;
            else rate = 7.0 + Math.ceil((totalItems - 25) / 5.0);

            int effectiveFloors = totalFloors > 0 ? totalFloors : 1;
            handlingCost = rate * handlingMinutesPerFloor * effectiveFloors;
        }

        double urgencyMultiplier = 1.0;
        double totalPrice = (basePrice + handlingCost) * urgencyMultiplier;
        if (distanceKm > 20) {
            totalPrice += 2.6 * distanceKm;
        }

        Admin admin = (Admin) userRepository.findByRole(Role.ADMIN).getFirst();
        double commissionRate = admin.getCommissionRate();
        double postCommissionCost = totalPrice * (1 - commissionRate);

        MoveRequest moveRequest = new MoveRequest();
        moveRequest.setMoveId(UUID.randomUUID().toString());
        moveRequest.setRef(generateReference());
        moveRequest.setSourceAddress(request.sourceAddress());
        moveRequest.setDestinationAddress(request.destinationAddress());
        moveRequest.setSourceLocation(sourceLocation);
        moveRequest.setSourceFloors(request.sourceFloors());
        moveRequest.setDestinationFloors(request.destinationFloors());
        moveRequest.setItems(updatedItems);
        moveRequest.setMode(request.mode());
        moveRequest.setStatus(QuotationStatus.PENDING);
        moveRequest.setPreCommissionCost(totalPrice);
        moveRequest.setPostCommissionCost(postCommissionCost);
        moveRequest.setClientEmail(email);
        moveRequest.setClient(userRepository.findByEmail(email).orElse(null));
        moveRequest.setConfirmationToken(UUID.randomUUID().toString());
        moveRequest.setConfirmationTokenExpiry(LocalDateTime.now().plusHours(24));
        moveRequest.setPaymentStatus("pending");
        moveRequest.setPlannedDate(request.plannedDate());
        moveRequest.setPlannedTime(request.plannedTime());

        if (QuotationType.PLANNED.equals(request.mode())) {
            moveRequest.setBooked(false);
            moveRequest.setAssignmentStatus("UNASSIGNED");
        }

        addHistoryEvent(moveRequest, "MISSION_CREATED",
                "Mission created with cost: " + totalPrice, "SYSTEM");

        // Calculate estimated duration
        int stairTimeTotalSeconds = updatedItems.stream()
                .mapToInt(iq -> iq.getStairTime() * (totalFloors > 0 ? totalFloors : 1))
                .sum();
        int riskMarginMinutes = 15;
        int durationInMinutes = googleMapsService.getDurationInMinutes(
                moveRequest.getSourceAddress(),
                moveRequest.getDestinationAddress(),
                moveRequest.getClientEmail()
        );
        int estimatedTotalMinutes = durationInMinutes +
                (int) Math.ceil(stairTimeTotalSeconds / 60.0) + riskMarginMinutes;
        moveRequest.setEstimatedTotalMinutes(estimatedTotalMinutes);

        // Calculate waiting time
        List<String> busyDriverIds = getBusyDriverIds();
        List<DriverLocation> availableDrivers = getOnlineDrivers().stream()
                .filter(driver -> !busyDriverIds.contains(driver.getDriverId()))
                .filter(driver -> driver.getLatitude() != 0.0 && driver.getLongitude() != 0.0)
                .toList();

        if (!availableDrivers.isEmpty()) {
            moveRequest.setWaitingTime(0);
        } else {
            long minWaitingTime = Long.MAX_VALUE;
            for (String busyDriverId : busyDriverIds) {
                Query busyQuery = new Query();
                busyQuery.addCriteria(Criteria.where("driver.$id").is(new ObjectId(busyDriverId))
                        .and("assignmentStatus").is("ASSIGNED"));
                MoveRequest busyMission = mongoTemplate.findOne(busyQuery, MoveRequest.class);

                if (busyMission != null) {
                    long missionDurationMin = busyMission.getEstimatedTotalMinutes();
                    int travelMin = googleMapsService.getDurationInMinutes(
                            busyMission.getDestinationAddress(),
                            moveRequest.getSourceAddress(),
                            moveRequest.getClientEmail()
                    );
                    long totalWaitMin = missionDurationMin + travelMin + 60;
                    minWaitingTime = Math.min(minWaitingTime, totalWaitMin);
                }
            }
            moveRequest.setWaitingTime(minWaitingTime != Long.MAX_VALUE ? (int) minWaitingTime : 0);
        }

        moveRequestRepository.save(moveRequest);
        log.info("✅ [SUCCESS] Devis calculé et sauvegardé avec moveId : {}", moveRequest.getMoveId());
        return moveRequest;
    }

    @Override
    public List<MoveRequest> getAllMoveRequests() {
        log.info("Récupération de toutes les demandes de déménagement");
        List<MoveRequest> moves = moveRequestRepository.findAll();
        log.info("Demandes de déménagement récupérées : {} demandes", moves.size());
        return moves;
    }

    public void initiateDriverAssignment(String moveId) {
        log.info("Initiating driver assignment for moveId: {}", moveId);
        MoveRequest moveRequest = moveRequestRepository.findById(moveId)
                .orElseThrow(() -> new IllegalArgumentException("Move request not found: " + moveId));

        log.info("Treating moveId: {} as immediate move", moveId);
        startDriverAssignment(moveId);
    }

    @Override
    public List<DriverMissionSummaryDTO> getDriverMissionSummaries(String driverId) {
        log.info("Fetching mission summaries for driverId: {}", driverId);

        Query query = new Query();
        query.addCriteria(
                new Criteria().orOperator(
                        Criteria.where("driver.id").is(driverId),
                        Criteria.where("candidateDrivers").in(driverId)
                )
        );
        List<MoveRequest> missions = mongoTemplate.find(query, MoveRequest.class);

        List<DriverMissionSummaryDTO> summaries = missions.stream().map(moveRequest -> {
            DriverMissionSummaryDTO summary = new DriverMissionSummaryDTO();
            summary.setMoveId(moveRequest.getMoveId());

            int durationMinutes = googleMapsService.getDurationInMinutes(
                    moveRequest.getSourceAddress(), moveRequest.getDestinationAddress(),
                    moveRequest.getClientEmail());
            String duration = String.format("%dh%02d", durationMinutes / 60, durationMinutes % 60);
            summary.setDuration(duration);

            double distanceKm = googleMapsService.getDistance(
                    moveRequest.getSourceAddress(), moveRequest.getDestinationAddress(),
                    moveRequest.getClientEmail());
            summary.setDistanceKm(distanceKm);

            summary.setSourceAddress(moveRequest.getSourceAddress());
            summary.setDestinationAddress(moveRequest.getDestinationAddress());
            summary.setClientName(moveRequest.getClient().getFirstName());
            summary.setPhoneNumber(moveRequest.getClient().getPhoneNumber());

            int productCount = moveRequest.getItems().stream()
                    .mapToInt(ItemQuantity::getQuantity)
                    .sum();
            summary.setProductCount(productCount);

            summary.setAmount(moveRequest.getPostCommissionCost());
            summary.setStatus(moveRequest.getAssignmentStatus() != null ?
                    moveRequest.getAssignmentStatus() : "NO_STATUS");

            return summary;
        }).filter(m -> m.getStatus().equals("DRIVER_FREE")).collect(Collectors.toList());

        log.info("Mission summaries retrieved successfully for driverId: {}, {} missions",
                driverId, summaries.size());
        return summaries;
    }

    @Override
    public void updateMoveLocation(String moveId, GeoJsonPoint location) {
        log.info("Mise à jour de l'emplacement pour moveId : {}", moveId);
        MoveRequest moveRequest = moveRequestRepository.findById(moveId)
                .orElseThrow(() -> new RuntimeException("Move request not found: " + moveId));

        moveRequest.setCurrentLocation(location);
        addHistoryEvent(moveRequest, "LOCATION_UPDATED", "Location updated", "SYSTEM");
        moveRequestRepository.save(moveRequest);

        messagingTemplate.convertAndSend("/topic/mission/" + moveId + "/location",
                new LocationUpdateRequest(location.getY(), location.getX()));
        log.info("Emplacement mis à jour avec succès pour moveId : {}", moveId);
    }

    @Override
    public void addPhotosToMove(String moveId, List<String> photoLinks) {
        log.info("Ajout de photos pour moveId : {}", moveId);
        MoveRequest moveRequest = moveRequestRepository.findById(moveId)
                .orElseThrow(() -> new RuntimeException("Move request not found: " + moveId));

        if (moveRequest.getPhotoLinks() == null) {
            moveRequest.setPhotoLinks(new ArrayList<>());
        }
        moveRequest.getPhotoLinks().addAll(photoLinks);
        moveRequest.setPhotoConfirmationToken(UUID.randomUUID().toString());
        moveRequest.setPhotoConfirmationTokenExpiry(LocalDateTime.now().plusHours(24));
        moveRequest.setPhotosConfirmed(false);

        addHistoryEvent(moveRequest, "PHOTOS_ADDED", "Photos uploaded", "SYSTEM");
        moveRequestRepository.save(moveRequest);

        log.info("Photos ajoutées avec succès pour moveId : {}", moveId);
    }

    @Override
    public void confirmPhotos(String token) {
        log.info("Tentative de confirmation des photos avec le jeton : {}", token);
        MoveRequest moveRequest = moveRequestRepository.findByPhotoConfirmationToken(token)
                .orElseThrow(() -> new IllegalArgumentException("Invalid token"));

        if (moveRequest.getPhotoConfirmationTokenExpiry().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("Token expired");
        }

        moveRequest.setPhotosConfirmed(true);
        moveRequest.setPhotoConfirmationToken(null);
        moveRequest.setPhotoConfirmationTokenExpiry(null);

        addHistoryEvent(moveRequest, "PHOTOS_CONFIRMED", "Photos confirmed", "SYSTEM");
        moveRequestRepository.save(moveRequest);

        log.info("Photos confirmées avec succès pour moveId : {}", moveRequest.getMoveId());
    }

    @Override
    public void confirmMoveRequest(String token) {
        log.info("Tentative de confirmation de la demande de déménagement avec le jeton : {}", token);
        MoveRequest moveRequest = moveRequestRepository.findByConfirmationToken(token)
                .orElseThrow(() -> new IllegalArgumentException("Invalid token"));

        if (moveRequest.getConfirmationTokenExpiry().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("Token expired");
        }

        moveRequest.setStatus(QuotationStatus.APPROVED);
        moveRequest.setConfirmationToken(null);
        moveRequest.setConfirmationTokenExpiry(null);

        addHistoryEvent(moveRequest, "STATUS_CHANGED", "Quote status changed to APPROVED", "SYSTEM");
        moveRequestRepository.save(moveRequest);

        log.info("Demande de déménagement confirmée avec succès pour moveId : {}", moveRequest.getMoveId());
    }

    // ✅ FIXED: assignNextPendingToDriver with atomic operations
    public void assignNextPendingToDriver(String driverId) {
        log.info("Offering next pending mission to driver: {}", driverId);

        // ✅ Atomic find-and-modify
        Query pendingQuery = new Query();
        pendingQuery.addCriteria(Criteria.where("assignmentStatus")
                .in("NO_DRIVERS_AVAILABLE", "NO_DRIVERS_IN_RANGE")
                .and("paymentStatus").is("paid"));
        pendingQuery.with(Sort.by(Sort.Direction.ASC, "dateOfPayment"));

        Update update = new Update()
                .set("assignmentStatus", "WAITING_FOR_DRIVER")
                .set("candidateDrivers", List.of(driverId))
                .set("currentDriverIndex", 0)
                .set("lastModified", LocalDateTime.now());

        MoveRequest nextPending = mongoTemplate.findAndModify(
                pendingQuery,
                update,
                new FindAndModifyOptions().returnNew(true),
                MoveRequest.class
        );

        if (nextPending == null) {
            log.info("No pending missions available for driver: {}", driverId);
            return;
        }

        Driver driver = driverRepository.findById(driverId).orElse(null);
        if (driver == null) {
            log.warn("Driver not found: {}", driverId);
            // Rollback
            updateMissionStatus(nextPending.getMoveId(), "NO_DRIVERS_AVAILABLE",
                    "Driver not found during assignment");
            return;
        }

        addHistoryEvent(nextPending, "MISSION_OFFERED_TO_DRIVER",
                String.format("Offer sent to driver %s automatically after previous completion", driverId),
                "SYSTEM");

        // Calculate estimated duration if not set
        if (nextPending.getEstimatedTotalMinutes() == null) {
            int stairTimeTotalSeconds = 0;
            int totalFloors = nextPending.getSourceFloors() + nextPending.getDestinationFloors();

            for (ItemQuantity iq : nextPending.getItems()) {
                int itemStairTimeSeconds = totalFloors > 0 ?
                        iq.getStairTime() * totalFloors : iq.getStairTime();
                stairTimeTotalSeconds += itemStairTimeSeconds;
            }

            int durationInMinutes = googleMapsService.getDurationInMinutes(
                    nextPending.getSourceAddress(), nextPending.getDestinationAddress(),
                    nextPending.getClientEmail());
            int riskMarginMinutes = 15;
            int estimatedTotalMinutes = durationInMinutes +
                    (int) Math.ceil(stairTimeTotalSeconds / 60.0) + riskMarginMinutes;
            nextPending.setEstimatedTotalMinutes(estimatedTotalMinutes);
        }

        moveRequestRepository.save(nextPending);

        // Send notification
        sendMissionOfferToDriver(nextPending, driverId, "automatic after completion");

        // Notify client
        User clientDetails = userRepository.findByEmail(nextPending.getClientEmail()).orElse(null);
        if (clientDetails != null) {
            Notification clientNotification = new Notification();
            clientNotification.setTitle("Mission Offer Sent to Driver");
            clientNotification.setNotificationType("MISSION_STATUS_UPDATE");
            clientNotification.setTimestamp(LocalDateTime.now());
            clientNotification.setRead(false);
            clientNotification.setRelatedMoveId(nextPending.getMoveId());
            clientNotification.setUserId(clientDetails.getId());
            clientNotification.setStatus("SENT");
            notificationService.sendAndSaveNotification(clientNotification, nextPending);
        }
    }

    @Override
    public void completeMove(String moveId) {
        log.info("Tentative de marquage du déménagement comme terminé pour moveId : {}", moveId);
        MoveRequest moveRequest = moveRequestRepository.findById(moveId)
                .orElseThrow(() -> new RuntimeException("Move request not found: " + moveId));

        moveRequest.setStatus(QuotationStatus.COMPLETED);
        moveRequest.setAssignmentStatus("COMPLETED");

        addHistoryEvent(moveRequest, "STATUS_CHANGED", "Quote status changed to COMPLETED", "SYSTEM");
        moveRequestRepository.save(moveRequest);

        messagingTemplate.convertAndSend("/topic/mission/" + moveId + "/updates", moveRequest);

        // Notify client
        Notification notification = new Notification();
        notification.setTitle("Chargement terminé, en route vers la destination");
        notification.setBody("Vos biens sont en sécurité dans le camion et en direction de votre destination.");
        notification.setTimestamp(LocalDateTime.now());
        notification.setRead(false);
        notification.setNotificationType("LOADING_COMPLETE");
        notification.setRelatedMoveId(moveId);
        notification.setUserId(moveRequest.getClient() != null ? moveRequest.getClient().getId() : null);
        notification.setStatus("SENT");

        notificationService.sendAndSaveNotification(notification, moveRequest);
        log.info("Déménagement marqué comme terminé pour moveId : {}", moveId);

        // Retry pending assignments
        Query pendingQuery = new Query();
        pendingQuery.addCriteria(Criteria.where("assignmentStatus")
                .in("NO_DRIVERS_AVAILABLE", "NO_DRIVERS_IN_RANGE")
                .and("paymentStatus").is("paid"));
        pendingQuery.limit(3); // ✅ Limit concurrent retries

        List<MoveRequest> pendingMoves = mongoTemplate.find(pendingQuery, MoveRequest.class);
        for (MoveRequest pending : pendingMoves) {
            startDriverAssignment(pending.getMoveId());
        }
    }

    @Override
    public List<String> viewMovePhotos(String moveId, User currentUser) {
        log.info("Tentative de visualisation des photos pour moveId : {} par l'utilisateur : {}",
                moveId, currentUser.getEmail());

        MoveRequest moveRequest = moveRequestRepository.findById(moveId)
                .orElseThrow(() -> new RuntimeException("Move request not found: " + moveId));

        Role userRole = currentUser.getRole();

        if (userRole == Role.CLIENT && !moveRequest.getClientEmail().equalsIgnoreCase(currentUser.getEmail())) {
            throw new RuntimeException("Access denied: This is not your move");
        } else if (userRole == Role.SUB_ADMIN && (moveRequest.getDriver() == null ||
                !((Driver) moveRequest.getDriver()).getCreatedBySubAdminId().getId().equals(currentUser.getId()))) {
            throw new RuntimeException("Access denied: This driver does not belong to you");
        } else if (userRole != Role.CLIENT && userRole != Role.SUB_ADMIN) {
            throw new RuntimeException("Access denied: Invalid role");
        }

        List<String> photoLinks = moveRequest.getPhotoLinks() != null ?
                moveRequest.getPhotoLinks() : List.of();

        log.info("Photos récupérées avec succès pour moveId : {}, {} photos", moveId, photoLinks.size());
        return photoLinks;
    }

    private List<Item> validateAndFetchItems(List<ItemQuantity> items) {
        log.info("Validation et récupération des objets pour {} éléments", items.size());

        List<String> itemLabels = items.stream()
                .map(ItemQuantity::getItemLabel)
                .collect(Collectors.toList());

        Set<String> uniqueInputLabels = new HashSet<>(itemLabels);
        if (uniqueInputLabels.size() != itemLabels.size()) {
            Map<String, Long> labelCounts = itemLabels.stream()
                    .filter(Objects::nonNull)
                    .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
            List<String> duplicates = labelCounts.entrySet().stream()
                    .filter(entry -> entry.getValue() > 1)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
            log.warn("Duplicate item labels detected: {}", duplicates);
        }

        List<Item> fetchedItems = itemRepository.findByLabelIn(itemLabels);

        if (fetchedItems.size() != uniqueInputLabels.size()) {
            Set<String> fetchedLabels = fetchedItems.stream()
                    .map(Item::getLabel)
                    .collect(Collectors.toSet());
            Set<String> missingLabels = new HashSet<>(uniqueInputLabels);
            missingLabels.removeAll(fetchedLabels);

            String errorMessage = "Invalid item labels.";
            if (!missingLabels.isEmpty()) {
                errorMessage += " Missing labels: " + missingLabels;
            }
            throw new IllegalArgumentException(errorMessage);
        }

        return fetchedItems;
    }

    private void logResourceNotFound(String identifier) {
        log.warn("Demande de déménagement non trouvée avec ID : {}", identifier);
    }

    public Map<String, List<MissionHistory>> getDriverHistoryForMove(String moveId) {
        MoveRequest moveRequest = moveRequestRepository.findById(moveId)
                .orElseThrow(() -> new RuntimeException("Move not found: " + moveId));

        Map<String, List<MissionHistory>> driverHistoryMap = new HashMap<>();
        for (MissionHistory event : moveRequest.getHistoryEvents()) {
            String driverId = extractDriverIdFromDetails(event.details());
            if (driverId != null) {
                driverHistoryMap.computeIfAbsent(driverId, k -> new ArrayList<>()).add(event);
            }
        }
        return driverHistoryMap;
    }

    private String extractDriverIdFromDetails(String details) {
        if (details != null && details.contains("driver ")) {
            String[] parts = details.split("driver ");
            if (parts.length > 1) {
                return parts[1].split(" ")[0];
            }
        }
        return null;
    }

    @Async
    public void sendSpecialRequest(String content, String senderEmail) {
        log.info("Envoi d'une demande spéciale de déménagement de: {}", senderEmail);
        try {
            Context context = new Context();
            context.setVariable("senderEmail", senderEmail);
            context.setVariable("content", content);
            String htmlContent = templateEngine.process("special-move-request", context);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            helper.setFrom(senderEmail);
            helper.setTo("ramasquare22@gmail.com");
            helper.setSubject("Demande spéciale de déménagement de " + senderEmail);
            helper.setText(htmlContent, true);
            mailSender.send(mimeMessage);

            log.info("Demande spéciale envoyée avec succès de: {}", senderEmail);
        } catch (MessagingException e) {
            log.error("Échec de l'envoi de la demande spéciale de {}: {}", senderEmail, e.getMessage());
            throw new RuntimeException("Échec de l'envoi de la demande spéciale", e);
        }
    }

    @Override
    public List<MissionHistory> getDriverMissionHistory(String driverId, User currentUser) {
        Driver driver = driverRepository.findById(driverId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver not found: " + driverId));

        if (currentUser.getRole() == Role.SUB_ADMIN) {
            if (!driver.getCreatedBySubAdminId().getId().equals(currentUser.getId())) {
                throw new AccessDeniedException("You do not have permission to view this driver's history.");
            }
        }

        Query query = new Query();
        query.addCriteria(
                new Criteria().orOperator(
                        Criteria.where("historyEvents.triggeredBy").is(driverId),
                        Criteria.where("historyEvents.details").regex("driver " + driverId)
                )
        );
        List<MoveRequest> missions = mongoTemplate.find(query, MoveRequest.class);

        List<MissionHistory> allEvents = new ArrayList<>();
        for (MoveRequest mission : missions) {
            for (MissionHistory event : mission.getHistoryEvents()) {
                if (event.triggeredBy().equals(driverId) ||
                        event.details().contains("driver " + driverId)) {
                    allEvents.add(event);
                }
            }
        }

        allEvents.sort(Comparator.comparing(MissionHistory::timestamp));
        return allEvents;
    }

    @Override
    public List<MoveRequest> getMovesForSubadmin(String subadminId) {
        Query driverQuery = new Query(Criteria.where("createdBySubAdminId.$id").is(new ObjectId(subadminId)));
        List<Driver> drivers = mongoTemplate.find(driverQuery, Driver.class);
        List<String> driverIds = drivers.stream().map(User::getId).collect(Collectors.toList());

        if (driverIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<ObjectId> driverObjectIds = driverIds.stream()
                .map(ObjectId::new)
                .collect(Collectors.toList());

        Query moveQuery = new Query(Criteria.where("driver.$id").in(driverObjectIds));
        return mongoTemplate.find(moveQuery, MoveRequest.class);
    }

    @Override
    public void updateMoveItems(String moveId, List<ItemQuantity> items, User currentUser) {
        log.info("Attempting to update items for moveId: {} by user: {}", moveId, currentUser.getEmail());

        MoveRequest moveRequest = moveRequestRepository.findById(moveId)
                .orElseThrow(() -> new ResourceNotFoundException("Demande de déménagement non trouvée"));

        if (moveRequest.getClient() == null || moveRequest.getDriver() == null ||
                (!moveRequest.getClient().getId().equals(currentUser.getId()) &&
                        !moveRequest.getDriver().getId().equals(currentUser.getId()))) {
            throw new ForbiddenException("Vous n'êtes pas autorisé à modifier les objets de cette mission");
        }

        if (items == null || items.isEmpty()) {
            throw new BadRequestException("La liste des objets ne peut pas être nulle ou vide");
        }

        moveRequest.setVerifiedItemsByDriver(items);
        moveRequestRepository.save(moveRequest);

        log.info("Items updated successfully for moveId: {}", moveId);
    }

    @Override
    public MoveDetails getLastMinuteMoveDetails(String moveId) {
        log.info("🚀 [START] Fetching last-minute move details");
        log.info("🆔 Move ID: {}", moveId);

        // 1️⃣ Récupération du move
        MoveRequest lastMinuteMove = moveRequestRepository.findById(moveId)
                .orElseThrow(() -> new RuntimeException("❌ MoveRequest not found with id: " + moveId));
        log.info("📦 MoveRequest found successfully!");
        log.info("🏠 Source Address: {}", lastMinuteMove.getSourceAddress());
        log.info("🏁 Destination Address: {}", lastMinuteMove.getDestinationAddress());
        log.info("📧 Client Email: {}", lastMinuteMove.getClientEmail());

        // 2️⃣ Calcul de la distance
        log.info("📏 Calculating distance via Google Maps API...");
        double distance = googleMapsService.getDistance(
                lastMinuteMove.getSourceAddress(),
                lastMinuteMove.getDestinationAddress(),
                lastMinuteMove.getClientEmail());
        log.info("✅ Distance calculated: {} km", distance);

        // 3️⃣ Calcul de la durée
        log.info("⏱️ Calculating estimated duration...");
        int durationMinutes = googleMapsService.getDurationInMinutes(
                lastMinuteMove.getSourceAddress(),
                lastMinuteMove.getDestinationAddress(),
                lastMinuteMove.getClientEmail());
        log.info("✅ Estimated duration: {} minutes", durationMinutes);
        lastMinuteMove.setEstimatedTotalMinutes(durationMinutes);
        // 4️⃣ Détermination des horaires
        LocalDateTime departureTime = lastMinuteMove.getAssignmentTimestamp() != null
                ? lastMinuteMove.getAssignmentTimestamp()
                : LocalDateTime.now();
        log.info("🕒 Departure Time: {}", departureTime);

        LocalDate date = departureTime.toLocalDate();
        log.info("📅 Move Date: {}", date);

        LocalDateTime arrivalTime = departureTime.plusMinutes(durationMinutes);
        log.info("🛬 Expected Arrival Time: {}", arrivalTime);

        // 5️⃣ Statut de mission
        String missionStatus = lastMinuteMove.getMissionStatus() != null
                ? lastMinuteMove.getMissionStatus().getNameValue()
                : "PENDING";
        log.info("🚦 Mission Status: {}", missionStatus);

        // 6️⃣ Items inclus dans le déménagement
        log.info("📦 Items to move: {}", lastMinuteMove.getItems());

        // 7️⃣ Construction de l’objet final
        MoveDetails moveDetails = new MoveDetails(
                distance,
                durationMinutes,
                date,
                departureTime,
                arrivalTime,
                lastMinuteMove.getItems(),
                missionStatus,
                lastMinuteMove.getSourceAddress(),
                lastMinuteMove.getDestinationAddress()
        );

        log.info("🧾 MoveDetails object successfully created ✅");
        log.info("🔍 Final MoveDetails: {}", moveDetails);
        log.info("🏁 [END] Last-minute move details fetched successfully");

        return moveDetails;
    }


    @Override
    public LastMinuteMove createFromAcceptedMove(String moveId, LastMinuteMoveRequest request)
            throws IOException, InterruptedException, ApiException, ExecutionException {
        log.info("🚚 Creating LAST_MINUTE move from moveId: {}", moveId);

        MoveRequest originalMove = moveRequestRepository.findById(moveId)
                .orElseThrow(() -> new IllegalArgumentException("Move request not found: " + moveId));

        if (originalMove.getDriver() == null) {
            throw new IllegalStateException("No driver assigned to the original move.");
        }

        LastMinuteMove lastMinuteMove = new LastMinuteMove();
        lastMinuteMove.setRef(generateReference());
        lastMinuteMove.setSourceAddress(originalMove.getDestinationAddress());
        lastMinuteMove.setDestinationAddress(request.getDestinationAddress());
        lastMinuteMove.setEstimatedTotalMinutes(originalMove.getEstimatedTotalMinutes());
        lastMinuteMove.setCreatedAt(LocalDateTime.now());
        lastMinuteMove.setDriver(originalMove.getDriver());
        lastMinuteMove.setMode(QuotationType.LAST_MINUTE);

        List<Itinerary> itineraries = googleMapsService.getItineraries(
                originalMove.getDestinationAddress(),
                request.getDestinationAddress()
        );

        log.info("🗺️ Itineraries fetched: {}", itineraries);
        lastMinuteMove.setItineraries(itineraries);

        LastMinuteMove savedMove = lastMinuteMoveRepository.save(lastMinuteMove);
        log.info("✅ LAST_MINUTE move created: {}", savedMove.getId());

        return savedMove;
    }

    @Override
    public LastMinuteMove calculateLastMinuteQuote(String lastMinuteMoveId,
                                                   QuoteCalculationRequest request,
                                                   String email) {
        log.info("📦 Calculating LAST_MINUTE quote for moveId: {}", lastMinuteMoveId);

        Bucket bucket = getUserBucket(email);
        if (!bucket.tryConsume(1)) {
            throw new BadRequestException(
                    "Vous avez dépassé la limite de 20 demandes de devis par 24 heures. Veuillez réessayer plus tard.");
        }

        if (request.items() == null || request.items().isEmpty()) {
            throw new IllegalArgumentException("Items are required.");
        }

        LastMinuteMove lastMinuteMove = lastMinuteMoveRepository.findById(lastMinuteMoveId)
                .orElseThrow(() -> new IllegalArgumentException("LastMinuteMove not found: " + lastMinuteMoveId));

        if (lastMinuteMove.getDestinationAddress() == null || lastMinuteMove.getDestinationAddress().isBlank()) {
            throw new IllegalArgumentException("Destination address is missing.");
        }
        if (request.stopover() == null || request.stopover().isBlank()) {
            throw new IllegalArgumentException("Stopover address is missing.");
        }
        if (request.destinationStopover() == null || request.destinationStopover().isBlank()) {
            throw new IllegalArgumentException("Destination stopover address is missing.");
        }

        double distanceKm = googleMapsService.getDistance(
                request.stopover(),
                request.destinationStopover(),
                email
        );
        log.info("Distance from destination to stopover: {} km", distanceKm);

        List<Item> items = validateAndFetchItems(request.items());
        int totalFloors = lastMinuteMove.getSourceFloors() + request.destinationFloors();
        double totalVolume = 0.0;
        int maxMinTruckSize = 0;
        int totalItems = 0;
        List<ItemQuantity> updatedItems = new ArrayList<>();

        for (ItemQuantity iq : request.items()) {
            String key = iq.getItemLabel();
            Item item = items.stream().filter(i -> i.getLabel().equals(key)).findFirst().orElseThrow();

            totalVolume += Double.parseDouble(item.getVolume()) * iq.getQuantity();
            if (iq.getQuantity() > 0) {
                maxMinTruckSize = Math.max(maxMinTruckSize, item.getMinTruckSize());
            }

            if (item.getStair_time() != null && !item.getStair_time().isEmpty()) {
                double stairTime = Double.parseDouble(item.getStair_time()) * iq.getQuantity();
                iq.setStairTime((int) stairTime);
            }

            totalItems += iq.getQuantity();
            updatedItems.add(iq);
        }

        // Truck calculation
        class Truck {
            final int volume;
            final double price;
            Truck(int volume, double price) {
                this.volume = volume;
                this.price = price;
            }
        }

        List<Truck> truckTypes = Arrays.asList(new Truck(12, 107.91), new Truck(20, 129.7));
        int minTruckVolume = truckTypes.stream().mapToInt(t -> t.volume).min().orElse(0);
        int maxTruckVolume = truckTypes.stream().mapToInt(t -> t.volume).max().orElse(0);

        if (maxMinTruckSize > maxTruckVolume || maxMinTruckSize < minTruckVolume) {
            throw new IllegalArgumentException("Selected items require a truck size incompatible with available trucks.");
        }

        double basePrice;
        if (totalVolume == 0) {
            basePrice = 0;
        } else if (maxMinTruckSize > 12) {
            basePrice = Math.ceil(totalVolume / 20.0) * 129.7;
        } else {
            double minCost = Double.MAX_VALUE;
            int maxLarge = (int) Math.ceil(totalVolume / 12.0);
            for (int numLarge = 0; numLarge <= maxLarge; numLarge++) {
                double remainingVolume = Math.max(0, totalVolume - numLarge * 20.0);
                int numSmall = (int) Math.ceil(remainingVolume / 12.0);
                double cost = numLarge * 129.7 + numSmall * 107.91;
                if (cost < minCost) minCost = cost;
            }
            basePrice = minCost;
        }

        // Handling cost
        double totalStairTime = updatedItems.stream().mapToDouble(ItemQuantity::getStairTime).sum();
        double handlingMinutes = totalStairTime / 60.0;
        double handlingCost;

        if (totalFloors == 0 && totalItems < 15) {
            handlingCost = 0;
        } else if (totalFloors == 0) {
            List<double[]> bracketsZero = Arrays.asList(
                    new double[]{15, 1}, new double[]{20, 1.5}, new double[]{25, 2},
                    new double[]{30, 2.5}, new double[]{35, 4.5}, new double[]{40, 6},
                    new double[]{45, 7}, new double[]{50, 10}, new double[]{55, 12},
                    new double[]{60, 20}, new double[]{65, 26}, new double[]{70, 40}
            );
            double rate = 49.8;
            for (double[] bracket : bracketsZero) {
                if (totalItems <= bracket[0]) {
                    rate = bracket[1];
                    break;
                }
            }
            handlingCost = rate * handlingMinutes;
        } else {
            List<double[]> bracketsFirst = Arrays.asList(
                    new double[]{10, 3}, new double[]{20, 4.375}, new double[]{30, 11.5},
                    new double[]{40, 15}, new double[]{50, 20}, new double[]{60, 34.54},
                    new double[]{70, 64.74}
            );
            List<double[]> bracketsAdditional = Arrays.asList(
                    new double[]{10, 3}, new double[]{20, 5}, new double[]{30, 10},
                    new double[]{40, 14.4}, new double[]{50, 21}, new double[]{60, 34.5},
                    new double[]{70, 124.5}
            );
            double firstRate = 49.8, additionalRate = 49.8;
            for (double[] bracket : bracketsFirst) {
                if (totalItems <= bracket[0]) {
                    firstRate = bracket[1];
                    break;
                }
            }
            for (double[] bracket : bracketsAdditional) {
                if (totalItems <= bracket[0]) {
                    additionalRate = bracket[1];
                    break;
                }
            }
            handlingCost = firstRate * handlingMinutes + additionalRate * handlingMinutes * (totalFloors - 1);
        }

        double urgencyMultiplier = 1.0;
        double totalPrice = (basePrice + handlingCost) * urgencyMultiplier;
        if (distanceKm > 20) {
            totalPrice += 2.6 * distanceKm;
        }

        // Apply 50% discount
        double discountedPrice = totalPrice * 0.5;

        Admin admin = (Admin) userRepository.findByRole(Role.ADMIN).getFirst();
        double postCommissionCost = discountedPrice * (1 - admin.getCommissionRate());

        Optional<User> clientOpt = userRepository.findByEmail(email);
        if (clientOpt.isPresent()) {
            lastMinuteMove.setClient(clientOpt.get());
            lastMinuteMove.setClientEmail(clientOpt.get().getEmail());
        } else {
            throw new IllegalArgumentException("Authenticated user not found in the system.");
        }

        lastMinuteMove.setEstimatedTotalMinutes((int) Math.ceil(totalStairTime + totalFloors * 10));
        lastMinuteMove.setPreCommissionCost(totalPrice);
        lastMinuteMove.setPreCommissionCostAfterDiscount(discountedPrice);
        lastMinuteMove.setPostCommissionCost(postCommissionCost);
        lastMinuteMove.setItems(updatedItems);
        lastMinuteMove.setMode(QuotationType.LAST_MINUTE);
        lastMinuteMove.setStopover(request.stopover());
        lastMinuteMove.setDestinationStopover(request.destinationStopover());
        lastMinuteMove.setClientAddressPoint(request.clientAddressPoint());
        lastMinuteMove.setClientDestinationPoint(request.clientDestinationPoint());

        lastMinuteMoveRepository.save(lastMinuteMove);

        log.info("✅ LAST_MINUTE quote calculated successfully for moveId: {}", lastMinuteMoveId);

        return lastMinuteMove;
    }

    @Override
    public void assignDriverToMission(String moveId, String driverId, User subadmin) {
        log.info("Assigning driver {} to mission {} by subadmin {}", driverId, moveId, subadmin.getId());

        Lock lock = getMissionLock(moveId);
        try {
            if (!lock.tryLock(5, TimeUnit.SECONDS)) {
                log.warn("Could not acquire lock for subadmin assignment for moveId: {}", moveId);
                throw new BadRequestException("La mission est en cours de traitement");
            }

            try {
                MoveRequest moveRequest = moveRequestRepository.findById(moveId)
                        .orElseThrow(() -> new ResourceNotFoundException("Demande de déménagement non trouvée: " + moveId));

                if (!"PENDING".equals(moveRequest.getStatus().name()) &&
                        !"APPROVED".equals(moveRequest.getStatus().name())) {
                    throw new BadRequestException(
                            "La mission n'est pas dans un état permettant l'assignation (doit être PENDING ou APPROVED)");
                }

                Driver driver = driverRepository.findById(driverId)
                        .orElseThrow(() -> new ResourceNotFoundException("Chauffeur non trouvé: " + driverId));

                if (driver.getCreatedBySubAdminId() == null ||
                        !driver.getCreatedBySubAdminId().getId().equals(subadmin.getId())) {
                    throw new ForbiddenException("Ce chauffeur n'est pas géré par ce sous-administrateur");
                }

                DriverLocation driverLocation = onlineDrivers.get(driverId);
                if (driverLocation == null) {
                    throw new BadRequestException("Le chauffeur n'est pas en ligne");
                }

                List<String> busyDriverIds = getBusyDriverIds();
                if (busyDriverIds.contains(driverId)) {
                    throw new BadRequestException("Le chauffeur est déjà assigné à une autre mission");
                }

                // ✅ Atomic update
                Query query = new Query(Criteria.where("moveId").is(moveId)
                        .and("assignmentStatus").nin("WAITING_FOR_DRIVER", "ASSIGNED"));

                Update update = new Update()
                        .set("assignmentStatus", "WAITING_FOR_DRIVER")
                        .set("candidateDrivers", List.of(driverId))
                        .set("currentDriverIndex", 0)
                        .set("lastModified", LocalDateTime.now());

                UpdateResult result = mongoTemplate.updateFirst(query, update, MoveRequest.class);

                if (result.getModifiedCount() == 0) {
                    throw new BadRequestException("La mission ne peut pas être assignée dans son état actuel");
                }

                moveRequest = moveRequestRepository.findById(moveId).orElseThrow();

                addHistoryEvent(moveRequest, "MISSION_OFFERED_TO_DRIVER",
                        String.format("Offer sent to driver %s by subadmin %s", driverId, subadmin.getId()),
                        subadmin.getId());
                moveRequestRepository.save(moveRequest);

                sendMissionOfferToDriver(moveRequest, driverId, "subadmin assignment");

                log.info("Driver {} offered mission {} by subadmin {}", driverId, moveId, subadmin.getId());

            } finally {
                lock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while assigning driver to mission: {}", moveId);
            throw new BadRequestException("Échec de l'assignation du chauffeur");
        }
    }

    @Override
    public List<MoveRequest> getFilteredMissionsForSubadmin(String subadminId) {
        log.info("Fetching filtered missions for subadminId: {}", subadminId);

        Query driverQuery = new Query(Criteria.where("createdBySubAdminId.$id").is(new ObjectId(subadminId)));
        List<Driver> drivers = mongoTemplate.find(driverQuery, Driver.class);
        List<String> driverIds = drivers.stream().map(Driver::getId).toList();

        Query moveQuery = new Query();
        moveQuery.addCriteria(
                new Criteria().orOperator(
                        new Criteria().andOperator(
                                Criteria.where("driver.$id").in(driverIds.stream()
                                        .map(ObjectId::new).collect(Collectors.toList())),
                                Criteria.where("missionStatus").is(MissionStatus.ACCEPTED.getNameValue())
                        ),
                        new Criteria().andOperator(
                                Criteria.where("status").is("PENDING"),
                                Criteria.where("paymentStatus").is("paid")
                        )
                )
        );

        List<MoveRequest> missions = mongoTemplate.find(moveQuery, MoveRequest.class);
        log.info("Filtered missions retrieved successfully for subadminId: {}, {} missions",
                subadminId, missions.size());
        return missions;
    }

    @Scheduled(fixedRate = 3600000)
    public void checkAndSendEmailsForDelayedMoves() {
        log.info("Checking for delayed paid moves without assignment...");
        LocalDateTime now = LocalDateTime.now();

        Query query = new Query();
        query.addCriteria(Criteria.where("paymentStatus").is("paid")
                .and("assignmentStatus").in("NO_DRIVERS_AVAILABLE", "NO_DRIVERS_IN_RANGE")
                .and("adminNotified").is(false));

        List<MoveRequest> delayedMoves = mongoTemplate.find(query, MoveRequest.class);

        for (MoveRequest move : delayedMoves) {
            if (move.getDateOfPayment() != null &&
                    move.getDateOfPayment().plusHours(24).isBefore(now)) {
                sendEmailToAdmin(move);
                move.setAdminNotified(true);
                moveRequestRepository.save(move);
                log.info("Marked move {} as admin notified", move.getMoveId());
            }
        }

        log.info("Finished checking delayed moves. Processed: {}", delayedMoves.size());
    }

    private void sendEmailToAdmin(MoveRequest move) {
        try {
            Context context = new Context();
            context.setVariable("moveId", move.getMoveId());
            context.setVariable("clientEmail", move.getClientEmail());
            context.setVariable("paymentDate", move.getDateOfPayment());
            context.setVariable("sourceAddress", move.getSourceAddress());
            context.setVariable("destinationAddress", move.getDestinationAddress());
            context.setVariable("assignmentStatus", move.getAssignmentStatus());

            String htmlContent = templateEngine.process("delayed-move-alert", context);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            helper.setFrom("ramasquare22@gmail.com");

            List<User> subAdmins = userRepository.findByRole(Role.SUB_ADMIN);
            List<String> recipientEmails = new ArrayList<>();
            recipientEmails.add("aymen.ellouzeee@gmail.com");

            for (User subAdmin : subAdmins) {
                if (subAdmin.getEmail() != null && !subAdmin.getEmail().isEmpty()) {
                    recipientEmails.add(subAdmin.getEmail());
                }
            }

            if (recipientEmails.isEmpty()) {
                log.warn("No valid recipient emails found for moveId: {}", move.getMoveId());
                return;
            }

            helper.setTo(recipientEmails.toArray(new String[0]));
            helper.setSubject("Alerte: Déménagement payé en attente depuis plus de 24h - ID: " +
                    move.getMoveId());
            helper.setText(htmlContent, true);

            mailSender.send(mimeMessage);
            log.info("Email sent to admin and sub-admins for delayed move: {}. Recipients: {}",
                    move.getMoveId(), recipientEmails);
        } catch (MessagingException e) {
            log.error("Failed to send delayed move alert email for moveId: {}", move.getMoveId(), e);
        }
    }

    // ✅ NEW: Helper method to get mission-specific lock
    private Lock getMissionLock(String moveId) {
        return missionLocks.computeIfAbsent(moveId, k -> new ReentrantLock());
    }

    // ✅ NEW: Helper method to add history events
    private void addHistoryEvent(MoveRequest moveRequest, String eventType,
                                 String details, String triggeredBy) {
        MissionHistory event = new MissionHistory(
                moveRequest.getMoveId(),
                eventType,
                LocalDateTime.now(),
                details,
                triggeredBy
        );
        moveRequest.getHistoryEvents().add(event);
    }

    // ✅ NEW: Cleanup method to prevent memory leaks
    @Scheduled(fixedRate = 3600000) // Every hour
    public void cleanupStaleLocks() {
        log.info("🧹 Cleaning up stale locks and timeout handlers");

        // Remove timeouts for completed missions
        Set<String> completedMissions = moveRequestRepository.findAll().stream()
                .filter(m -> "ASSIGNED".equals(m.getAssignmentStatus()) ||
                        "COMPLETED".equals(m.getAssignmentStatus()))
                .map(MoveRequest::getMoveId)
                .collect(Collectors.toSet());

        assignmentTimeouts.keySet().removeIf(moveId -> {
            if (completedMissions.contains(moveId)) {
                ScheduledFuture<?> future = assignmentTimeouts.get(moveId);
                if (future != null) {
                    future.cancel(false);
                }
                return true;
            }
            return false;
        });

        // Clean up old assignment attempts
        LocalDateTime cutoff = LocalDateTime.now().minusHours(1);
        lastAssignmentAttempts.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));

        // Clean up locks for completed missions
        missionLocks.keySet().removeIf(completedMissions::contains);

        log.info("✅ Cleanup complete: {} timeouts, {} attempts tracked, {} locks active",
                assignmentTimeouts.size(), lastAssignmentAttempts.size(), missionLocks.size());
    }
}