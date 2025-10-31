package fr.tictak.dema.repository;

import fr.tictak.dema.model.Itinerary;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ItineraryRepository extends MongoRepository<Itinerary, String> {
}
