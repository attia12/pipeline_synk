package fr.tictak.dema.repository;

import fr.tictak.dema.model.MoveRequest;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;
import java.util.Optional;

public interface MoveRequestRepository extends MongoRepository<MoveRequest, String> {
    Optional<MoveRequest> findByConfirmationToken(String token);
    Optional<MoveRequest> findByPhotoConfirmationToken(String token);
    Optional<MoveRequest> findByDriverIdAndAssignmentStatus(String driverId, String assignmentStatus);
    List<MoveRequest> findByAssignmentStatus(String assignmentStatus);
    List<MoveRequest> findByClientId(String userId);

    @Query("{ 'driverId': ?0, 'planned': true, 'paid': true }")
    List<MoveRequest> findAvailablePlannedMissionsByDriverId(String driverId);

    @Query("{ '_id' : ?0 }")
    Optional<MoveRequest> findByIdWithLock(String moveId);


}