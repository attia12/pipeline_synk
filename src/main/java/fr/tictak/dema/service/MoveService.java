package fr.tictak.dema.service;

import com.google.maps.errors.ApiException;
import fr.tictak.dema.dto.in.LastMinuteMoveRequest;
import fr.tictak.dema.dto.in.QuoteCalculationRequest;
import fr.tictak.dema.dto.out.MoveDetails;
import fr.tictak.dema.model.*;
import fr.tictak.dema.model.user.User;
import fr.tictak.dema.service.implementation.MoveServiceImpl.DriverLocation;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

public interface MoveService {



    List<String> getAddressSuggestions(String query);
    MoveRequest calculateQuote(QuoteCalculationRequest request, String email);
    List<MoveRequest> getAllMoveRequests();
    void updateMoveLocation(String moveId, GeoJsonPoint location);
    void addPhotosToMove(String moveId, List<String> photoLinks);
    void confirmPhotos(String token);
    void confirmMoveRequest(String token);
    void completeMove(String moveId);
    List<String> viewMovePhotos(String moveId, User currentUser);
    void startDriverAssignment(String moveId);
    void handleDriverDecline(String moveId, String driverId);
    void handleDriverAcceptance(String moveId, String driverId);
    MoveRequest getMoveRequestById(String moveId);
    Map<String, List<MissionHistory>> getDriverHistoryForMove(String moveId);
    List<DriverMissionSummaryDTO> getLatestMissionSummaryForClient(String clientId);
    void sendSpecialRequest(String content, String senderEmail);
    List<MissionHistory> getDriverMissionHistory(String driverId, User currentUser);
    MoveDetails getMoveDetails(String moveId);
    void initiateDriverAssignment(String moveId);
    List<DriverMissionSummaryDTO> getDriverMissionSummaries(String driverId);
    List<MoveRequest> getMovesForSubadmin(String subadminId);
    void updateMoveItems(String moveId, List<ItemQuantity> items, User currentUser);
    LastMinuteMove createFromAcceptedMove(String moveId, LastMinuteMoveRequest request) throws IOException, InterruptedException, ApiException, ExecutionException;
    LastMinuteMove calculateLastMinuteQuote(String lastMinuteMoveId, QuoteCalculationRequest request, String email);
    List<String> getBusyDriverIds();
    List<DriverLocation> getOnlineDrivers();
    void assignDriverToMission(String moveId, String driverId, User subadmin);
    List<MoveRequest> getFilteredMissionsForSubadmin(String subadminId);
    MoveDetails getLastMinuteMoveDetails(String moveId);
    List<DriverMissionSummaryDTO> getAvailablePlannedMissions();

    MoveRequest bookPlannedMission(String moveId, String driverId);

    void notifyAllDriversOfPlannedMission(MoveRequest moveRequest);

    List<DriverMissionSummaryDTO> getAvailablePlannedMissionsByDriver(String driverId) ;


}