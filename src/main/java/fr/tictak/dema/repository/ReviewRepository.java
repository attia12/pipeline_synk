package fr.tictak.dema.repository;

import fr.tictak.dema.model.Review;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ReviewRepository extends MongoRepository<Review, String> {
    List<Review> findByDriverId(String driverId);
}