package fr.tictak.dema.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

@Document(collection = "reclamations")
@Data
public class Reclamation {
    @Id
    private String id;

    private String authenticatedEmail;

    private String sentFromEmail;

    private String senderName;

    private String mailContent;

    private Date sentAt = new Date();
}