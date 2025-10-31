package fr.tictak.dema.model;

import fr.tictak.dema.model.enums.MissionStatus;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

@Data
@NoArgsConstructor
@Document
public class Mission {
    @Id
    private String id;

    private Date scheduledDate;
    private MissionStatus status;

    private double distance;
    private double travelTime;
    private double handlingTime;
    private double totalPriceExclTax;
    private double totalPriceInclTax;
    private double vatPercent;
    private double surge;

    private Date assignedAt;
    private Date completedAt;


}