package fr.tictak.dema.model.mapper;

import fr.tictak.dema.model.LastMinuteMove;
import fr.tictak.dema.model.MoveRequest;

public class LastMinuteMoveMapper {

    public static MoveRequest toMoveRequest(LastMinuteMove lastMinuteMove) {
        if (lastMinuteMove == null) {
            return null;
        }

        MoveRequest moveRequest = new MoveRequest();
        moveRequest.setMoveId(lastMinuteMove.getId());
        moveRequest.setSourceAddress(lastMinuteMove.getSourceAddress());
        moveRequest.setDestinationAddress(lastMinuteMove.getDestinationAddress());
        moveRequest.setSourceFloors(lastMinuteMove.getSourceFloors() != null ? lastMinuteMove.getSourceFloors() : 0);
        moveRequest.setSourceElevator(lastMinuteMove.isSourceElevator());
        moveRequest.setDestinationFloors(lastMinuteMove.getDestinationFloors() != null ? lastMinuteMove.getDestinationFloors() : 0);
        moveRequest.setDestinationElevator(lastMinuteMove.isDestinationElevator());
        moveRequest.setItems(lastMinuteMove.getItems());
        moveRequest.setMode(lastMinuteMove.getMode());
        moveRequest.setPreCommissionCost(lastMinuteMove.getPreCommissionCost());
        moveRequest.setPostCommissionCost(lastMinuteMove.getPostCommissionCost());
        moveRequest.setClientEmail(lastMinuteMove.getClientEmail());
        moveRequest.setClient(lastMinuteMove.getClient());
        moveRequest.setDriver(lastMinuteMove.getDriver());
        moveRequest.setPaymentStatus(lastMinuteMove.getPaymentStatus());
        moveRequest.setAssignmentStatus(lastMinuteMove.getAssignmentStatus());
        moveRequest.setAssignmentTimestamp(lastMinuteMove.getAssignmentTimestamp());
        moveRequest.setMissionStatus(lastMinuteMove.getMissionStatus());
        if (lastMinuteMove.getPlannedDate() != null) {
            moveRequest.setPlannedDate(lastMinuteMove.getPlannedDate().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        }
        if (lastMinuteMove.getPlannedTime() != null) {
            moveRequest.setPlannedTime(lastMinuteMove.getPlannedTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));
        }

        moveRequest.setEstimatedTotalMinutes(lastMinuteMove.getEstimatedTotalMinutes());

        return moveRequest;
    }
}
