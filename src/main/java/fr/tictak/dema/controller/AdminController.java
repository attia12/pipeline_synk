package fr.tictak.dema.controller;

import fr.tictak.dema.dto.out.DriverWithDetailsDTO;
import fr.tictak.dema.dto.out.TruckWithSubAdminDTO;
import fr.tictak.dema.exception.ForbiddenException;
import fr.tictak.dema.model.Truck;
import fr.tictak.dema.model.user.Driver;
import fr.tictak.dema.model.user.SubAdmin;
import fr.tictak.dema.model.user.User;
import fr.tictak.dema.repository.UserRepository;
import fr.tictak.dema.service.AdminService;
import fr.tictak.dema.repository.TruckRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Contrôleur dédié aux opérations réservées aux utilisateurs ayant le rôle ADMIN.
 */
@RestController
@RequestMapping("/admin")
@Tag(name = "Admin", description = "Endpoints réservés aux utilisateurs de rôle ADMIN.")
public class AdminController {

    private final AdminService adminService;
    private final TruckRepository truckRepository;
    private final UserRepository userRepository;

    public AdminController(AdminService adminService, TruckRepository truckRepository, UserRepository userRepository) {
        this.adminService = adminService;
        this.truckRepository = truckRepository;
        this.userRepository = userRepository;
    }

    /**
     * Récupère la liste de tous les sous-admins du système.
     * Accessible uniquement aux utilisateurs ayant le rôle ADMIN.
     *
     * @return La liste de tous les sous-admins (instances de User).
     */
    @Operation(
            summary = "Récupérer tous les sous-admins",
            description = "Cette route permet à un ADMIN d'obtenir la liste complète de tous les sous-admins."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "La liste des sous-admins a été renvoyée avec succès."),
            @ApiResponse(responseCode = "403", description = "L'utilisateur n'a pas le rôle ADMIN."),
            @ApiResponse(responseCode = "401", description = "Authentification invalide ou inexistante.")
    })
    @GetMapping("/subAdmins")
    public ResponseEntity<List<User>> getAllSubAdmins() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null ||
                auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))) {
            throw new ForbiddenException("Vous devez être un ADMIN pour accéder à cette ressource.");
        }

        List<User> subAdmins = adminService.getAllSubAdmins();
        return ResponseEntity.ok(subAdmins);
    }

    /**
     * Supprime un sous-admin spécifique par son ID.
     * Accessible uniquement aux utilisateurs ayant le rôle ADMIN.
     *
     * @param id L'ID du sous-admin à supprimer.
     * @return Réponse HTTP indiquant le succès de la suppression.
     */
    @Operation(
            summary = "Supprimer un sous-admin",
            description = "Cette route permet à un ADMIN de supprimer un sous-admin spécifique par son ID."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Le sous-admin a été supprimé avec succès."),
            @ApiResponse(responseCode = "404", description = "Sous-admin non trouvé."),
            @ApiResponse(responseCode = "403", description = "L'utilisateur n'a pas le rôle ADMIN."),
            @ApiResponse(responseCode = "401", description = "Authentification invalide ou inexistante.")
    })
    @DeleteMapping("/subAdmins/{id}")
    public ResponseEntity<Void> deleteSubAdmin(@PathVariable String id) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null ||
                auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))) {
            throw new ForbiddenException("Vous devez être un ADMIN pour accéder à cette ressource.");
        }

        adminService.deleteSubAdmin(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Récupère la liste de tous les drivers (chauffeurs) du système avec des détails supplémentaires.
     * Accessible uniquement aux utilisateurs ayant le rôle ADMIN.
     *
     * @return La liste de tous les chauffeurs avec détails (instances de DriverWithDetailsDTO).
     */
    @Operation(
            summary = "Récupérer tous les chauffeurs avec détails",
            description = "Cette route permet à un ADMIN d'obtenir la liste complète de tous les chauffeurs incluant des informations sur les subadmins et camions assignés."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "La liste des chauffeurs a été renvoyée avec succès."),
            @ApiResponse(responseCode = "403", description = "L'utilisateur n'a pas le rôle ADMIN."),
            @ApiResponse(responseCode = "401", description = "Authentification invalide ou inexistante.")
    })
    @GetMapping("/drivers")
    public ResponseEntity<List<DriverWithDetailsDTO>> getAllDriversForAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null ||
                auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))) {
            throw new ForbiddenException("Vous devez être un ADMIN pour accéder à cette ressource.");
        }

        List<User> users = adminService.findAllDrivers();
        List<DriverWithDetailsDTO> dtos = new ArrayList<>();

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");

        for (User user : users) {
            if (user instanceof Driver driver) {
                // Calculate years of experience
                int yearsOfExperience = 0;
                if (driver.getDriverSince() != null) {
                    LocalDate startDate = Instant.ofEpochMilli(driver.getDriverSince().getTime())
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate();
                    LocalDate currentDate = LocalDate.now();
                    yearsOfExperience = Period.between(startDate, currentDate).getYears();
                }

                // Determine status (example logic; adjust based on actual business rules)
                String status = (driver.getCamion() != null) ? "Assigned" : "Available";

                // Fetch truck details if assigned
                String truckLicensePlate = null;
                String truckModel = null;
                Integer truckYear = null;
                Double truckCapacity = null;
                if (driver.getCamion() != null) {
                    Optional<Truck> optTruck = truckRepository.findById(driver.getCamion());
                    if (optTruck.isPresent()) {
                        Truck truck = optTruck.get();
                        truckLicensePlate = truck.getLicensePlate();
                        truckModel = truck.getModel();
                        truckYear = truck.getYear();
                        truckCapacity = truck.getCapacity();
                    }
                }

                // Fetch subadmin details
                String subAdminFirstName = null;
                String subAdminLastName = null;
                String subAdminEmail = null;
                String subAdminPhoneNumber = null;
                String subAdminCompanyName = null;
                String subAdminAssignedRegion = null;
                if (driver.getCreatedBySubAdminId() != null) {
                    Optional<User> optSub = userRepository.findById(driver.getCreatedBySubAdminId().getId());
                    if (optSub.isPresent() && optSub.get() instanceof SubAdmin subAdmin) {
                        subAdminFirstName = subAdmin.getFirstName();
                        subAdminLastName = subAdmin.getLastName();
                        subAdminEmail = subAdmin.getEmail();
                        subAdminPhoneNumber = subAdmin.getPhoneNumber();
                        subAdminCompanyName = subAdmin.getCompanyName();
                        subAdminAssignedRegion = subAdmin.getAssignedRegion();
                    }
                }

                // Format driverSince
                String driverSinceStr = null;
                if (driver.getDriverSince() != null) {
                    driverSinceStr = dateFormat.format(driver.getDriverSince());
                }

                // Format driverLicenseExpiration
                String expirationStr = null;
                if (driver.getDriverLicenseExpiration() != null) {
                    expirationStr = dateFormat.format(driver.getDriverLicenseExpiration());
                }

                // Convert documentTypes to List<String>
                List<String> docTypes = driver.getDocumentTypes().stream()
                        .map(Enum::name)
                        .collect(Collectors.toList());

                // Convert documentUrls to Map<String, String>
                Map<String, String> docUrls = driver.getDocumentUrls().entrySet().stream()
                        .collect(Collectors.toMap(
                                e -> e.getKey().name(),
                                Map.Entry::getValue
                        ));

                dtos.add(new DriverWithDetailsDTO(
                        driver.getId(),
                        driver.getFirstName(),
                        driver.getLastName(),
                        driver.getEmail(),
                        driver.getPhoneNumber(),
                        driver.getDriverLicenseNumber(),
                        yearsOfExperience,
                        status,
                        truckLicensePlate,
                        truckModel,
                        truckYear,
                        truckCapacity,
                        subAdminFirstName,
                        subAdminLastName,
                        subAdminEmail,
                        subAdminPhoneNumber,
                        subAdminCompanyName,
                        subAdminAssignedRegion,
                        driverSinceStr,
                        docTypes,
                        docUrls,
                        driver.getCamion(),
                        expirationStr
                ));
            }
        }

        return ResponseEntity.ok(dtos);
    }

    /**
     * Récupère la liste de tous les camions du système avec des détails supplémentaires sur les subadmins.
     * Accessible uniquement aux utilisateurs ayant le rôle ADMIN.
     *
     * @return La liste de tous les camions avec détails subadmin (instances de TruckWithSubAdminDTO).
     */
    @Operation(
            summary = "Récupérer tous les camions avec détails subadmin",
            description = "Cette route permet à un ADMIN d'obtenir la liste complète de tous les camions incluant des informations sur les subadmins qui les ont ajoutés."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "La liste des camions a été renvoyée avec succès."),
            @ApiResponse(responseCode = "403", description = "L'utilisateur n'a pas le rôle ADMIN."),
            @ApiResponse(responseCode = "401", description = "Authentification invalide ou inexistante.")
    })
    @GetMapping("/trucks")
    public ResponseEntity<List<TruckWithSubAdminDTO>> getAllTrucks() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null ||
                auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))) {
            throw new ForbiddenException("Vous devez être un ADMIN pour accéder à cette ressource.");
        }

        List<Truck> trucks = truckRepository.findAll();
        List<TruckWithSubAdminDTO> dtos = new ArrayList<>();

        for (Truck truck : trucks) {
            Optional<User> optUser = userRepository.findById(truck.getAddedBy());
            if (optUser.isPresent()) {
                User user = optUser.get();
                if (user instanceof SubAdmin subAdmin) {
                    dtos.add(new TruckWithSubAdminDTO(
                            truck.getId(),
                            truck.getCapacity(),
                            truck.getModel(),
                            truck.isActive(),
                            truck.getAssuranceCamion(),
                            truck.getCarteGrise(),
                            truck.getVignetteTaxe(),
                            user.getFirstName(),
                            user.getLastName(),
                            user.getEmail(),
                            subAdmin.getPhoneNumber(),
                            subAdmin.getCompanyName(),
                            subAdmin.getAssignedRegion()
                    ));
                } else {
                    // Skip if not SubAdmin or handle appropriately
                    continue;
                }
            } else {
                // Skip if user not found
                continue;
            }
        }

        return ResponseEntity.ok(dtos);
    }



}