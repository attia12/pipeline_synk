package fr.tictak.dema.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

@Data
@Document(collection = "reviews")
public class Review {
    @Id
    private String id;
    private String driverId;
    private String clientId;
    private double rating;
    private String comment;
    private Date createdAt = new Date();
}