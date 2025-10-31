package fr.tictak.dema.repository;

import fr.tictak.dema.model.Truck;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface TruckRepository extends MongoRepository<Truck, String> {
    List<Truck> findByAddedBy(String addedBy);
}