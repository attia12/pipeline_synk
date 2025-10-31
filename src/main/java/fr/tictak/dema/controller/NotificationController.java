package fr.tictak.dema.controller;

import fr.tictak.dema.exception.ForbiddenException;
import fr.tictak.dema.exception.ResourceNotFoundException;
import fr.tictak.dema.model.MoveRequest;
import fr.tictak.dema.model.Notification;
import fr.tictak.dema.model.user.User;
import fr.tictak.dema.repository.MoveRequestRepository;
import fr.tictak.dema.service.UserService;
import fr.tictak.dema.service.implementation.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/notification")
@RequiredArgsConstructor
public class NotificationController {

    private final UserService userService;
    private final NotificationService notificationService;
    private final MoveRequestRepository moveRequestRepository;



    @PatchMapping("/fcm-token")
    public ResponseEntity<String> updateFcmToken(@RequestParam String fcmToken, @AuthenticationPrincipal String userEmail) {
        if (fcmToken == null || fcmToken.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("FCM token cannot be null or empty");
        }

        User user = userService.findByEmail(userEmail);

        if (user == null) {
            return ResponseEntity.status(404).body("User not found");
        }

        user.setFcmToken(fcmToken);
        userService.updateUser(user);
        return ResponseEntity.ok("FCM token updated successfully");
    }

    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<String> markNotificationAsRead(@PathVariable String notificationId, @AuthenticationPrincipal String userEmail) {
        try {
            notificationService.markNotificationAsRead(notificationId, userEmail);
            return ResponseEntity.ok("Notification marked as read");
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.status(404).body("Notification not found");
        } catch (ForbiddenException e) {
            return ResponseEntity.status(403).body("You do not have permission to mark this notification as read");
        }
    }
    @GetMapping("/client")
    public ResponseEntity<List<MoveRequest>> getClientMissions(Authentication authentication) {
        String userId = authentication.getName(); // Assuming userId is the principal
        List<MoveRequest> missions = moveRequestRepository.findByClientId(userId);
        return ResponseEntity.ok(missions);
    }

    @PatchMapping("/read-all")
    public ResponseEntity<String> markAllNotificationsAsRead(@AuthenticationPrincipal String userEmail) {
        try {
            notificationService.markAllNotificationsAsRead(userEmail);
            return ResponseEntity.ok("All notifications marked as read");
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.status(404).body("User not found");
        }
    }

    @DeleteMapping("/{notificationId}")
    public ResponseEntity<String> deleteNotification(@PathVariable String notificationId, @AuthenticationPrincipal String userEmail) {
        try {
            notificationService.deleteNotification(notificationId, userEmail);
            return ResponseEntity.ok("Notification deleted");
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.status(404).body("Notification not found");
        } catch (ForbiddenException e) {
            return ResponseEntity.status(403).body("You do not have permission to delete this notification");
        }
    }

    @GetMapping
    public ResponseEntity<?> getAllNotifications(@AuthenticationPrincipal String userEmail) {
        User user = userService.findByEmail(userEmail);
        if (user == null) {
            return ResponseEntity.status(404).body("User not found");
        }
        List<Notification> notifications = notificationService.getNotificationsByUserId(user.getId());
        return ResponseEntity.ok(notifications);
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Long> getUnreadNotificationCount(@AuthenticationPrincipal String userEmail) {
        User user = userService.findByEmail(userEmail);
        if (user == null) {
            return ResponseEntity.status(404).body(0L);
        }
        long unreadCount = notificationService.getUnreadNotificationCount(user.getId());
        return ResponseEntity.ok(unreadCount);
    }
}