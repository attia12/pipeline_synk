package fr.tictak.dema.model;

import fr.tictak.dema.model.enums.MissionStatus;
import fr.tictak.dema.model.enums.QuotationType;
import fr.tictak.dema.model.user.User;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.DBRef;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Document(collection = "last_minute_moves")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LastMinuteMove {

    @Id
    private String id;
    private String ref;
    private String sourceAddress;
    private String destinationAddress;

    private Integer sourceFloors = 0;
    private boolean sourceElevator = false;
    private LocalDateTime dateOfPayment;

    private Integer destinationFloors = 0;
    private boolean destinationElevator = false;
    private Integer estimatedTotalMinutes;
    private LocalDateTime createdAt = LocalDateTime.now();

    private QuotationType mode;

    @DBRef
    private User driver;

    @DBRef
    private User client;

    private String clientEmail;

    private List<ItemQuantity> items;

    private Double preCommissionCost = 0.0;
    private Double preCommissionCostAfterDiscount = 0.0;
    private Double postCommissionCost = 0.0;

    private String paymentStatus = "pending"; // "pending", "paid", etc.
    private LocalDate plannedDate;            // optional, if needed for scheduling
    private LocalTime plannedTime;
    private String assignmentStatus;
    private LocalDateTime assignmentTimestamp;
    private MissionStatus missionStatus;
    private List<Itinerary> itineraries;
    private List<Itinerary> selectedItineraries;
    private String stopover;
    private Boolean booked = false ;
    private String clientAddressPoint;
    private String destinationStopover;
    private String clientDestinationPoint;



}
