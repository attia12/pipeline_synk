package fr.tictak.dema.interceptor;

import com.sun.security.auth.UserPrincipal;
import fr.tictak.dema.security.JwtUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
public class AuthenticationInterceptor implements ChannelInterceptor {
    private static final Logger log = LoggerFactory.getLogger(AuthenticationInterceptor.class);
    private final JwtUtils jwtUtils; // Assume this is your JWT utility class

    public AuthenticationInterceptor(JwtUtils jwtUtils) {
        this.jwtUtils = jwtUtils;
    }

    @Override
    public Message<?> preSend(@NotNull Message<?> message, @NotNull MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        String command = accessor.getCommand() != null ? accessor.getCommand().name() : "UNKNOWN";
        log.debug("Processing STOMP command: {}", command);

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            String authHeader = accessor.getFirstNativeHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);
                log.debug("Authorization header found in CONNECT: Bearer <token>");

                try {
                    String email = jwtUtils.extractUserEmail(token);
                    String userId = jwtUtils.getUserIdFromToken(token);
                    List<String> roles = jwtUtils.getRolesFromToken(token);
                    log.debug("Token validated - userId: {}, email: {}, roles: {}", userId, email, roles);
                    Objects.requireNonNull(accessor.getSessionAttributes()).put("driverId", userId);
                    UserPrincipal principal = new UserPrincipal(email);
                    accessor.setUser(principal);
                    log.debug("User principal set for CONNECT: {}", userId);
                } catch (Exception e) {
                    log.error("Failed to validate JWT token in CONNECT: {}", e.getMessage());
                }
            } else {
                log.debug("No Authorization header found in CONNECT");
            }
        }

        return message;
    }
}