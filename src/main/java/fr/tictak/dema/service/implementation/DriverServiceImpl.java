package fr.tictak.dema.service.implementation;

import fr.tictak.dema.exception.BadRequestException;
import fr.tictak.dema.exception.ResourceNotFoundException;
import fr.tictak.dema.model.Review;
import fr.tictak.dema.model.user.Driver;
import fr.tictak.dema.repository.DriverRepository;
import fr.tictak.dema.repository.ReviewRepository;
import fr.tictak.dema.service.DriverService;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

@Service
public class DriverServiceImpl implements DriverService {

    private final DriverRepository driverRepository;
    private final ReviewRepository reviewRepository;

    public DriverServiceImpl(DriverRepository driverRepository, ReviewRepository reviewRepository) {
        this.driverRepository = driverRepository;
        this.reviewRepository = reviewRepository;
    }

    @Override
    public List<Driver> findDriversBySubAdminId(String subAdminId) {
        return driverRepository.findByCreatedBySubAdminId(subAdminId);
    }



    @Override
    public Driver findById(String driverId) {
        return driverRepository.findById(driverId).orElse(null);
    }

    @Override
    public Driver findByEmail(String email) {
        return driverRepository.findByEmail(email);
    }

    @Override
    public void save(Driver driver) {
        driverRepository.save(driver);
    }

    @Override
    public void deleteDriver(String driverId) {
        if (!driverRepository.existsById(driverId)) {
            throw new IllegalArgumentException("Camion non trouvé avec l'ID: " +driverId);
        }
        driverRepository.deleteById(driverId);
    }

    @Override
    public List<Driver> findDriversByCamion(String camionId) {
        return driverRepository.findByCamion(camionId);
    }

    @Override
    public void addReview(String driverId, String clientId, double rating, String comment) {
        if (rating < 0 || rating > 5) {
            throw new BadRequestException("La note doit être comprise entre 0 et 5");
        }



        Review review = new Review();
        review.setDriverId(driverId);
        review.setClientId(clientId);
        review.setRating(rating);
        review.setComment(comment);
        review.setCreatedAt(new Date());
        reviewRepository.save(review);

        // Update driver's average rating
        Driver driver = findById(driverId);
        if (driver == null) {
            throw new ResourceNotFoundException("Chauffeur non trouvé avec l'ID : " + driverId);
        }
        int newCount = driver.getRatingCount() + 1;
        double newAverage = (driver.getAverageRating() * driver.getRatingCount() + rating) / newCount;
        driver.setRatingCount(newCount);
        driver.setAverageRating(newAverage);
        save(driver);
    }

    @Override
    public double getAverageRating(String driverId) {
        Driver driver = findById(driverId);
        if (driver == null) {
            throw new ResourceNotFoundException("Chauffeur non trouvé avec l'ID : " + driverId);
        }
        return driver.getAverageRating();
    }

    @Override
    public List<Review> getReviewsForDriver(String driverId) {
        return reviewRepository.findByDriverId(driverId);
    }
}