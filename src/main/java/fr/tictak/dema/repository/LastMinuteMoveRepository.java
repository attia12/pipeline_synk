package fr.tictak.dema.repository;

import fr.tictak.dema.model.LastMinuteMove;
import fr.tictak.dema.model.MoveRequest;
import fr.tictak.dema.model.enums.QuotationType;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LastMinuteMoveRepository extends MongoRepository<LastMinuteMove, String> {
    List<LastMinuteMove> findByModeAndBooked(QuotationType mode, Boolean booked);
    List<LastMinuteMove> findByModeAndBookedAndMissionStatusNot(
            QuotationType mode,
            Boolean booked,
            String missionStatus
    );
    List<LastMinuteMove> findByDriver_Id(String driverId);

}