package fr.tictak.dema.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Document(collection = "notifications")
public class Notification {
    @Id
    private String id;
    private String title;

    private String body; // JSON string for flexibility
    private LocalDateTime timestamp;
    private boolean read;
    private String notificationType;
    private String relatedMoveId;
    private String userId;
    private String status;
    private Map<String, String> metadata;
    @Transient
    private long unreadCount;


    public Notification() {

    }
}