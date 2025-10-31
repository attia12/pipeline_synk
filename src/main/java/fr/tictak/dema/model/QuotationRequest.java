package fr.tictak.dema.model;


import fr.tictak.dema.model.enums.QuotationStatus;
import fr.tictak.dema.model.enums.QuotationType;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

@Data
@NoArgsConstructor
@Document
public class QuotationRequest {

    @Id
    private String id;

    private String departureAddress;
    private String arrivalAddress;
    private Date requestedDate;
    private int floorDeparture;
    private int floorArrival;

    private QuotationType type;
    private QuotationStatus status;

    private double basePrice;
    private double finalPrice;

    private Date createdAt;
    private Date updatedAt;

}