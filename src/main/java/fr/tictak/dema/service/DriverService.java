package fr.tictak.dema.service;

import fr.tictak.dema.model.Review;
import fr.tictak.dema.model.user.Driver;

import java.util.List;

public interface DriverService {
    /**
     * Return all drivers created by a given Sub-Admin ID.
     *
     * @param subAdminId the ID of the Sub-Admin
     * @return list of Driver
     */
    List<Driver> findDriversBySubAdminId(String subAdminId);


    Driver findById(String driverId);
    Driver findByEmail(String email);

    void save(Driver driver);
    void deleteDriver(String driverId);

    void addReview(String driverId, String clientId, double rating, String comment);

    double getAverageRating(String driverId);

    List<Review> getReviewsForDriver(String driverId);

    /**
     * Find drivers by camion ID.
     *
     * @param camionId the ID of the camion
     * @return list of Driver assigned to the camion
     */
    List<Driver> findDriversByCamion(String camionId);
}