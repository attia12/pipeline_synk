package fr.tictak.dema.model;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class MissionStatusUpdateRequest {
    private String moveId;
    private String driverId;

}