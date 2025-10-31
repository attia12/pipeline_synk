package fr.tictak.dema.repository;

import fr.tictak.dema.model.user.Driver;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DriverRepository extends MongoRepository<Driver, String> {

    // Retrieve all drivers created by a specific Sub-Admin
    List<Driver> findByCreatedBySubAdminId(String createdBySubAdminId);
    List<Driver> findByCamion(String camionId);
    Driver findByEmail(String email);

}