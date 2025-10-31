package fr.tictak.dema.model;

import java.time.LocalDateTime;


public record MissionHistory(String missionId, String eventType, LocalDateTime timestamp, String details,
                             String triggeredBy) {
}