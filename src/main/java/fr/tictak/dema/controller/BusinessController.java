package fr.tictak.dema.controller;

import fr.tictak.dema.dto.in.BusinessDTO;
import fr.tictak.dema.model.Business;
import fr.tictak.dema.model.enums.Role;
import fr.tictak.dema.model.user.User;
import fr.tictak.dema.repository.UserRepository;
import fr.tictak.dema.service.BusinessService;
import fr.tictak.dema.service.implementation.NotificationService;
import fr.tictak.dema.model.Notification;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/business")
public class BusinessController {

    private final BusinessService businessService;
    private final NotificationService notificationService;
    private final UserRepository userRepository;

    public BusinessController(BusinessService businessService, NotificationService notificationService, UserRepository userRepository) {
        this.businessService = businessService;
        this.notificationService = notificationService;
        this.userRepository = userRepository;
    }

    @PostMapping("/create")
    public ResponseEntity<Business> createBusiness(@Valid @RequestBody BusinessDTO dto) {
        Business savedBusiness = businessService.createBusiness(dto);

        // Notify all admins
        List<User> admins = userRepository.findByRole(Role.ADMIN);
        for (User admin : admins) {
            Notification notification = new Notification();
            notification.setUserId(admin.getId());
            notification.setNotificationType("BUSINESS_REQUEST_SUBMITTED");
            notification.setTimestamp(LocalDateTime.now());
            notification.setRead(false);
            notificationService.sendAndSaveNotification(notification, dto);
        }

        return ResponseEntity.ok(savedBusiness);
    }
}