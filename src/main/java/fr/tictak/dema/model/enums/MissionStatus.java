package fr.tictak.dema.model.enums;

import lombok.Getter;

@Getter
public enum MissionStatus {
    PENDING("En attente"),
    ACCEPTED("Mission acceptée"),
    EN_ROUTE_TO_DEPART("Je suis en route vers le départ"),
    ARRIVED_AT_DEPART("Je suis arrivé"),
    LOADING_COMPLETE("Chargement terminé, en route vers la destination"),
    COMPLETED("Mission terminée"),
    CANCELED("Annulée");

    private final String description;

    MissionStatus(String description) {
        this.description = description;
    }

    public String getNameValue() {
        return name();
    }

    public MissionStatus next() {
        return switch (this) {
            case PENDING -> ACCEPTED;
            case ACCEPTED -> EN_ROUTE_TO_DEPART;
            case EN_ROUTE_TO_DEPART -> ARRIVED_AT_DEPART;
            case ARRIVED_AT_DEPART -> LOADING_COMPLETE;
            case LOADING_COMPLETE -> COMPLETED;
            case COMPLETED, CANCELED -> this;
        };
    }
}