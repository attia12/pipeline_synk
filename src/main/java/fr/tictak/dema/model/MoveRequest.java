package fr.tictak.dema.model;

import fr.tictak.dema.model.enums.MissionStatus;
import fr.tictak.dema.model.enums.QuotationStatus;
import fr.tictak.dema.model.enums.QuotationType;
import fr.tictak.dema.model.user.User;
import lombok.Data;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.mapping.DBRef;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Document(collection = "moves")
@Data
public class MoveRequest {

    @Id
    private String moveId;
    private String ref;
    private String driverId;
    private String sourceAddress;
    private String destinationAddress;
    private int sourceFloors;
    private boolean sourceElevator;
    private int destinationFloors;
    private boolean destinationElevator;
    private List<ItemQuantity> items;
    private List<ItemQuantity> verifiedItemsByDriver;
    private QuotationType mode;
    private Integer waitingTime;
    private QuotationStatus status;
    private Double preCommissionCost;
    private Double postCommissionCost;
    private String confirmationToken;
    private LocalDateTime confirmationTokenExpiry;
    private GeoJsonPoint currentLocation;
    private List<String> photoLinks;
    private String clientEmail;
    private String photoConfirmationToken;
    private LocalDateTime photoConfirmationTokenExpiry;
    private boolean photosConfirmed = false;
    private List<MissionHistory> historyEvents = new ArrayList<>();
    @DBRef
    private User client;
    @DBRef
    private User driver;
    private String paymentStatus;
    private LocalDateTime dateOfPayment;
    private GeoJsonPoint sourceLocation;
    private List<String> candidateDrivers;
    private int currentDriverIndex;
    private String assignmentStatus;
    private LocalDateTime assignmentTimestamp;
    private MissionStatus missionStatus;
    private boolean adminAlertSent = false; // New field for PLANNED moves

    private boolean adminNotified = false;
    @Setter
    private String plannedDate;
    @Setter
    private String plannedTime;
    private Boolean booked;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private LocalDateTime acceptedAt;
    private Integer estimatedTotalMinutes;
    private GeoJsonPoint clientAddressPoint;

    @Version
    private Long version; // ✅ Add for optimistic locking

    private LocalDateTime lastRetryAttempt; // ✅ Add for retry throttling
    private LocalDateTime lastModified; // ✅ Add for tracking updates
    public LocalDateTime getPlannedDateTime() {
        if (plannedDate == null || plannedTime == null) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(plannedDate, DATE_FORMATTER);
            LocalTime time = LocalTime.parse(plannedTime, TIME_FORMATTER);
            return LocalDateTime.of(date, time);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date or time format. Expected 'dd/MM/yyyy' for date and 'hh:mm' for time.", e);
        }
    }

    public String getStringItems() {
        if (items == null || items.isEmpty()) {
            return "No items";
        }
        return items.stream()
                .map(item -> item.getItemLabel() + ": " + item.getQuantity())
                .collect(Collectors.joining(", "));
    }
}