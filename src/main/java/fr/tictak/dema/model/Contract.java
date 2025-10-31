package fr.tictak.dema.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

@Data
@NoArgsConstructor
@Document
public class Contract {
    @Id
    private String id;
    private Date dateSigned;
    private double feeAmount;
    private boolean isValidated;
    private Date startDate;
    private Date endDate;
    private String contractTerms;


}