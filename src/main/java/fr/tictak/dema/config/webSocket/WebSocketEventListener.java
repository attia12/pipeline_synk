package fr.tictak.dema.config.webSocket;

import fr.tictak.dema.service.implementation.MoveServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.context.event.EventListener;

import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
public class WebSocketEventListener {

    private final MoveServiceImpl onlineDriverService;

    public WebSocketEventListener(MoveServiceImpl onlineDriverService) {
        this.onlineDriverService = onlineDriverService;
    }

    @EventListener
    public void handleSessionConnected(SessionConnectEvent event) {
        SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.wrap(event.getMessage());
        Map<String, Object> sessionAttributes = headerAccessor.getSessionAttributes();
        log.debug("Attributs de session dans l'événement de connexion : {}", sessionAttributes);
        assert sessionAttributes != null;
        String driverId = (String) sessionAttributes.get("driverId");
        if (driverId != null) {
            onlineDriverService.addOnlineDriver(driverId);
            log.info("Chauffeur connecté : {}", driverId);
        } else {
            log.warn("driverId est null dans les attributs de session");
        }
    }

    @EventListener
    public void handleSessionDisconnect(SessionDisconnectEvent event) {
        SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.wrap(event.getMessage());
        Map<String, Object> sessionAttributes = headerAccessor.getSessionAttributes();
        log.debug("Attributs de session dans l'événement de déconnexion : {}", sessionAttributes);
        String driverId = (String) sessionAttributes.get("driverId");
        if (driverId != null) {
            onlineDriverService.removeOnlineDriver(driverId);
            log.info("Chauffeur déconnecté : {}", driverId);
        } else {
            log.warn("driverId est null dans les attributs de session pendant la déconnexion");
        }
    }
}