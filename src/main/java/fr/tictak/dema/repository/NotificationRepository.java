package fr.tictak.dema.repository;
import fr.tictak.dema.model.Notification;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;


public interface NotificationRepository extends MongoRepository<Notification, String> {
    List<Notification> findByUserId(String userId);
    long countByUserIdAndRead(String userId, boolean read);
}