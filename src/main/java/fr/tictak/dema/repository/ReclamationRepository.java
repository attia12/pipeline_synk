package fr.tictak.dema.repository;

import fr.tictak.dema.model.Reclamation;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ReclamationRepository extends MongoRepository<Reclamation, String> {
}
