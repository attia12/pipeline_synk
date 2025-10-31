
package fr.tictak.dema.service.implementation;

import com.google.firebase.auth.FirebaseToken;
import com.google.firebase.auth.UserRecord;
import fr.tictak.dema.dto.out.AuthResponse;
import fr.tictak.dema.dto.in.CreateDriverRequest;
import fr.tictak.dema.dto.in.RegisterRequest;
import fr.tictak.dema.dto.in.RegisterSubAdminRequest;
import fr.tictak.dema.exception.BadRequestException;
import fr.tictak.dema.exception.MissingPhoneNumberException;
import fr.tictak.dema.exception.ResourceNotFoundException;
import fr.tictak.dema.exception.UnauthorizedException;
import fr.tictak.dema.model.Reclamation;
import fr.tictak.dema.model.enums.DocumentType;
import fr.tictak.dema.model.enums.Role;
import fr.tictak.dema.model.user.Admin;
import fr.tictak.dema.model.user.Client;
import fr.tictak.dema.model.user.Driver;
import fr.tictak.dema.model.user.SubAdmin;
import fr.tictak.dema.model.user.User;
import fr.tictak.dema.repository.DriverRepository;
import fr.tictak.dema.repository.ReclamationRepository;
import fr.tictak.dema.repository.UserRepository;
import fr.tictak.dema.security.JwtUtils;
import fr.tictak.dema.service.UserService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import java.security.SecureRandom;
import java.util.*;

@Service
@Tag(name = "Service de gestion des utilisateurs", description = "Service pour la gestion des utilisateurs, incluant l'inscription, la vérification, la réinitialisation de mot de passe et l'envoi de notifications par email pour les clients, chauffeurs, sous-administrateurs et administrateurs.")
public class UserServiceImpl implements UserService {

    private static final Logger logger = LoggerFactory.getLogger(UserServiceImpl.class);

    private final UserRepository userRepository;
    private final DriverRepository driverRepository;
    private final PasswordEncoder passwordEncoder;
    private final JavaMailSender mailSender;
    private final SpringTemplateEngine templateEngine;
    private final JwtUtils jwtUtils;
    private final ReclamationRepository reclamationRepository;

    public UserServiceImpl(UserRepository userRepository,
                           PasswordEncoder passwordEncoder,
                           JavaMailSender mailSender,
                           SpringTemplateEngine templateEngine,
                           DriverRepository driverRepository,
                           JwtUtils jwtUtils,
                           ReclamationRepository reclamationRepository
                          ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.driverRepository = driverRepository;
        this.jwtUtils = jwtUtils;
        this.reclamationRepository = reclamationRepository;
    }

    @Override
    public User findByEmail(String email) {
        logger.info("Recherche d'utilisateur par email: {}", email);
        if (email == null || email.isEmpty()) {
            logger.warn("Échec de la recherche: Email ne peut pas être nul ou vide");
            throw new BadRequestException("L'email ne peut pas être nul ou vide");
        }
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            logResourceNotFound("Utilisateur", "email", email);
        }
        return user;
    }

    @Override
    public User registerClient(RegisterRequest req) {
        logger.info("Inscription d'un client avec email: {}", req.email());
        if (findByEmail(req.email()) != null) {
            logResourceNotFound("Email déjà utilisé", "email", req.email());
            throw new BadRequestException("L'email est déjà utilisé : " + req.email());
        }

        Client client = new Client();
        client.setEmail(req.email());
        client.setPassword(passwordEncoder.encode(req.password()));
        client.setFirstName(req.firstName());
        client.setLastName(req.lastName());
        client.setPhoneNumber(req.phoneNumber());
        client.setCreatedAt(new Date());
        client.setActive(true);
        client.setDateOfBirth(req.dateOfBirth());
        client.setRole(Role.CLIENT);
        User saved = userRepository.save(client);
        logger.info("Client inscrit avec succès, ID: {}", saved.getId());
        return saved;
    }

    @Override
    public User registerSubAdmin(RegisterSubAdminRequest req) {
        logger.info("Inscription d'un sous-administrateur avec email: {}", req.email());
        if (findByEmail(req.email()) != null) {
            logResourceNotFound("Email déjà utilisé", "email", req.email());
            throw new BadRequestException("L'email est déjà utilisé : " + req.email());
        }
        SubAdmin subAdmin = new SubAdmin();
        subAdmin.setEmail(req.email());
        String randomPassword = generateRandomPassword();
        subAdmin.setPassword(passwordEncoder.encode(randomPassword));
        subAdmin.setActive(true);
        subAdmin.setFirstName(req.firstName());
        subAdmin.setLastName(req.lastName());
        subAdmin.setCompanyName(req.companyName());
        subAdmin.setPhoneNumber(req.phoneNumber());
        subAdmin.setContractSigned(req.contractSigned());
        subAdmin.setAssignedRegion(req.assignedRegion());
        subAdmin.setNumberOfTrucks(req.numberOfTrucks() != null ? req.numberOfTrucks() : 0);
        subAdmin.setTruckTypes(req.truckTypes());
        subAdmin.setContractDuration(req.contractDuration());
        subAdmin.setCritAirType(req.critAirType());
        subAdmin.setSubAdminCreatedAt(new Date());
        subAdmin.setRole(Role.SUB_ADMIN);
        subAdmin.setAccountActive(false);
        Calendar calTemp = Calendar.getInstance();
        calTemp.set(2025, Calendar.FEBRUARY, 4, 15, 3, 9);
        subAdmin.setLastDocumentUpdate(calTemp.getTime());
        subAdmin.setWarningEmailSent(false);

        if (req.legalDocuments() != null) {
            Map<DocumentType, String> documents = new HashMap<>();
            List<DocumentType> requiredTypes = Arrays.asList(
                    DocumentType.ATTESTATION_CAPACITE,
                    DocumentType.KBIS,
                    DocumentType.ASSURANCE_TRANSPORT,
                    DocumentType.IDENTITY_PROOF,
                    DocumentType.ATTESTATION_VIGILANCE,
                    DocumentType.ATTESTATION_REGULARITE_FISCALE
            );
            for (DocumentType requiredType : requiredTypes) {
                String key = requiredType.name();
                if (!req.legalDocuments().containsKey(key) || req.legalDocuments().get(key) == null || req.legalDocuments().get(key).isEmpty()) {
                    logger.warn("Document requis manquant: {}", key);
                    throw new BadRequestException("Le document requis est manquant ou invalide : " + key);
                }
                documents.put(requiredType, req.legalDocuments().get(key));
            }
            for (Map.Entry<String, String> entry : req.legalDocuments().entrySet()) {
                try {
                    DocumentType type = DocumentType.valueOf(entry.getKey());
                    if (!requiredTypes.contains(type)) {
                        documents.put(type, entry.getValue());
                    }
                } catch (IllegalArgumentException e) {
                    logger.warn("Échec de l'inscription: Type de document invalide: {}", entry.getKey());
                    throw new BadRequestException("Type de document invalide : " + entry.getKey());
                }
            }
            subAdmin.setLegalDocuments(documents);
        } else {
            logger.warn("Documents légaux manquants");
            throw new BadRequestException("Les documents légaux sont requis");
        }

        SubAdmin saved = userRepository.save(subAdmin);
        sendSubAdminInviteEmail(saved.getEmail(), randomPassword);
        logger.info("Sous-administrateur inscrit avec succès, ID: {}", saved.getId());
        return saved;
    }

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom random = new SecureRandom();

    private String generateRandomPassword() {
        StringBuilder password = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            password.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return password.toString();
    }

    private void sendSubAdminInviteEmail(String toEmail, String password) {
        logger.info("Envoi d'un email d'invitation de sous-administrateur à: {}", toEmail);
        try {
            Context context = new Context();
            context.setVariable("email", toEmail);
            context.setVariable("password", password);
            String htmlContent = templateEngine.process("subadmin-invite", context);
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            helper.setFrom("ramasquare22@gmail.com");
            helper.setTo(toEmail);
            helper.setSubject("Invitation de sous-administrateur - Vos identifiants de connexion");
            helper.setText(htmlContent, true);
            mailSender.send(mimeMessage);
            logger.info("Email d'invitation de sous-administrateur envoyé avec succès à: {}", toEmail);
        } catch (MessagingException e) {
            logger.error("Échec de l'envoi de l'email d'invitation de sous-administrateur à {}: {}", toEmail, e.getMessage());
            throw new RuntimeException("Échec de l'envoi de l'email d'invitation", e);
        }
    }


    @Override
    public AuthResponse updatePhoneNumber(String userId, String phoneNumber, String nom, String prenom) {
        logger.info("Mise à jour du numéro de téléphone pour l'utilisateur ID: {}", userId);
        if (userId == null || userId.isEmpty()) {
            logger.warn("Échec de la mise à jour: User ID ne peut pas être nul ou vide");
            throw new BadRequestException("L'ID utilisateur ne peut pas être nul ou vide");
        }
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            logger.warn("Échec de la mise à jour: Numéro de téléphone ne peut pas être nul ou vide");
            throw new BadRequestException("Le numéro de téléphone ne peut pas être nul ou vide");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> {
                    logResourceNotFound("Utilisateur", "ID", userId);
                    return new ResourceNotFoundException("Utilisateur non trouvé avec l'ID : " + userId);
                });

        if (!phoneNumber.matches("\\+?[1-9]\\d{1,14}")) {
            logger.warn("Échec de la mise à jour: Format de numéro de téléphone invalide pour l'utilisateur ID: {}", userId);
            throw new BadRequestException("Format de numéro de téléphone invalide");
        }

        User existingUserWithPhone = userRepository.findByPhoneNumber(phoneNumber).orElse(null);
        if (existingUserWithPhone != null && !existingUserWithPhone.getId().equals(userId)) {
            logger.warn("Échec de la mise à jour: Numéro de téléphone déjà utilisé pour l'utilisateur ID: {}", userId);
            throw new BadRequestException("Ce numéro de téléphone est déjà utilisé par un autre utilisateur");
        }

        user.setPhoneNumber(phoneNumber.trim());

        if (prenom != null && !prenom.trim().isEmpty()) {
            user.setFirstName(prenom.trim());
        }
        if (nom != null && !nom.trim().isEmpty()) {
            user.setLastName(nom.trim());
        }

        User savedUser = userRepository.save(user);
        logger.info("Numéro de téléphone mis à jour avec succès pour l'utilisateur ID: {}", savedUser.getId());

        String accessToken = jwtUtils.generateAccessToken(savedUser);
        String refreshToken = jwtUtils.generateRefreshToken(savedUser);
        return new AuthResponse(accessToken, refreshToken, savedUser.getId(), false);
    }



    @Override
    public User registerDriver(CreateDriverRequest req, User registeredBy) {
        logger.info("Inscription d'un chauffeur avec email: {}", req.email());

        if (findByEmail(req.email()) != null) {
            logger.warn("Email déjà utilisé: {}", req.email());
            throw new BadRequestException("L'email est déjà utilisé : " + req.email());
        }

        Driver driver = new Driver();
        driver.setEmail(req.email());

        String randomPassword = generateRandomPassword();
        driver.setPassword(passwordEncoder.encode(randomPassword));

        driver.setFirstName(req.firstName());
        driver.setLastName(req.lastName());
        driver.setPhoneNumber(req.phoneNumber());
        driver.setDriverLicenseExpiration(req.driverLicenseExpiration());
        driver.setDocumentTypes(req.documentTypes());
        driver.setDocumentUrls(req.documentUrls() != null ? req.documentUrls() : new HashMap<>());
        driver.setCreatedAt(new Date());
        driver.setActive(true);
        driver.setRole(Role.DRIVER);
        driver.setAccountActive(false);
        driver.setCreatedBySubAdminId(registeredBy);

        if (registeredBy instanceof SubAdmin subAdmin) {
            driver.setCompanyName(subAdmin.getCompanyName());
        }

        Driver saved = driverRepository.save(driver);

        sendDriverInviteEmail(saved.getEmail(), randomPassword);

        logger.info("Chauffeur inscrit avec succès, ID: {}", saved.getId());
        return saved;
    }

    @Override
    public void changeInitialPassword(String email, String oldPassword, String newPassword) {
        logger.info("Changing initial password for user: {}", email);
        User user = findByEmail(email);
        if (user == null) {
            logResourceNotFound("User", "email", email);
            throw new ResourceNotFoundException("User not found with email: " + email);
        }
        if (!(user instanceof SubAdmin || user instanceof Driver)) {
            logger.warn("Invalid user type for initial password change: {}", email);
            throw new BadRequestException("This operation is only applicable for sub-admins and drivers.");
        }
        boolean isSubAdmin = user instanceof SubAdmin;
        SubAdmin subAdmin = isSubAdmin ? (SubAdmin) user : null;
        Driver driver = isSubAdmin ? null : (Driver) user;
        boolean accountActive = isSubAdmin ? subAdmin.isAccountActive() : driver.isAccountActive();
        if (accountActive) {
            logger.warn("Account already activated for user: {}", email);
            throw new BadRequestException("Account already activated. Initial password change not required.");
        }
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            logger.warn("Incorrect old password for user: {}", email);
            throw new UnauthorizedException("Incorrect old password.");
        }
        if (newPassword == null || newPassword.length() < 8) {
            logger.warn("Invalid new password for user: {}", email);
            throw new BadRequestException("New password must be at least 8 characters long.");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        if (isSubAdmin) {
            subAdmin.setAccountActive(true);
        } else {
            driver.setAccountActive(true);
        }
        userRepository.save(user);
        logger.info("Initial password changed and account activated for user: {}", email);
    }

    @Async
    protected void sendDriverInviteEmail(String toEmail, String password) {
        logger.info("Envoi d'un email d'invitation de chauffeur à: {}", toEmail);
        try {
            Context context = new Context();
            context.setVariable("email", toEmail);
            context.setVariable("password", password);
            String htmlContent = templateEngine.process("driver-invite", context);
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            helper.setFrom("ramasquare22@gmail.com");
            helper.setTo(toEmail);
            helper.setSubject("Vos identifiants de connexion à l'application mobile");
            helper.setText(htmlContent, true);
            mailSender.send(mimeMessage);
            logger.info("Email d'invitation de chauffeur envoyé avec succès à: {}", toEmail);
        } catch (MessagingException e) {
            logger.error("Échec de l'envoi de l'email d'invitation de chauffeur à {}: {}", toEmail, e.getMessage());
            throw new RuntimeException("Échec de l'envoi de l'email d'invitation", e);
        }
    }



    @Override
    public AuthResponse registerGoogleUser(FirebaseToken firebaseToken) {
        logger.info("Inscription d'un utilisateur Google avec email: {}", firebaseToken.getEmail());
        String email = firebaseToken.getEmail();
        if (email == null || email.isEmpty()) {
            logger.warn("Échec de l'inscription: Email de l'utilisateur Google nul ou vide");
            throw new BadRequestException("L'email de l'utilisateur Google ne peut pas être nul ou vide");
        }

        User existingUser = findByEmail(email);
        if (existingUser != null) {
            if (existingUser.getPhoneNumber() == null || existingUser.getPhoneNumber().isEmpty()) {
                logger.info("Nouvel utilisateur Google créé sans numéro de téléphone, ID: {}", existingUser.getId());
                throw new MissingPhoneNumberException("Le numéro de téléphone est requis pour compléter l'inscription", existingUser.getId());
            }
            logger.info("Utilisateur Google existant trouvé, ID: {}", existingUser.getId());
            String accessToken = jwtUtils.generateAccessToken(existingUser);
            String refreshToken = jwtUtils.generateRefreshToken(existingUser);
            return new AuthResponse(accessToken, refreshToken, existingUser.getId(), false);
        }

        Client newClient = new Client();
        newClient.setEmail(email);
        newClient.setFirstName(extractFirstName(firebaseToken.getName()));
        newClient.setLastName(extractLastName(firebaseToken.getName()));
        newClient.setPhotoUrl(firebaseToken.getPicture());
        newClient.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
        newClient.setCreatedAt(new Date());
        newClient.setActive(true);
        newClient.setRole(Role.CLIENT);

        User savedUser = userRepository.save(newClient);

        if (savedUser.getPhoneNumber() == null || savedUser.getPhoneNumber().isEmpty()) {
            logger.info("Nouvel utilisateur Google créé sans numéro de téléphone, ID: {}", savedUser.getId());
            throw new MissingPhoneNumberException("Le numéro de téléphone est requis pour compléter l'inscription", savedUser.getId());
        }

        String accessToken = jwtUtils.generateAccessToken(savedUser);
        String refreshToken = jwtUtils.generateRefreshToken(savedUser);
        logger.info("Utilisateur Google inscrit avec succès, ID: {}", savedUser.getId());
        return new AuthResponse(accessToken, refreshToken, savedUser.getId(), false);
    }

    @Override
    public AuthResponse registerAppleUser(FirebaseToken firebaseToken) {
        logger.info("Inscription d'un utilisateur Apple avec UID: {}", firebaseToken.getUid());
        String email = firebaseToken.getEmail();
        String uid = firebaseToken.getUid();
        if (email == null || email.isEmpty()) {
            email = "apple_" + uid + "@private.relay.appleid.com";
        }
        User existingUser = findByEmail(email);
        if (existingUser != null) {
            logger.info("Utilisateur Apple existant trouvé, ID: {}", existingUser.getId());
            String accessToken = jwtUtils.generateAccessToken(existingUser);
            String refreshToken = jwtUtils.generateRefreshToken(existingUser);
            return new AuthResponse(accessToken, refreshToken, existingUser.getId(), false);
        }
        Client newClient = new Client();
        newClient.setEmail(email);
        newClient.setFirstName(extractFirstName(firebaseToken.getName()));
        newClient.setLastName(extractLastName(firebaseToken.getName()));
        newClient.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
        newClient.setCreatedAt(new Date());
        newClient.setActive(true);
        newClient.setRole(Role.CLIENT);
        User savedUser = userRepository.save(newClient);
        if (savedUser.getPhoneNumber() == null || savedUser.getPhoneNumber().isEmpty()) {
            logger.info("Nouvel utilisateur Apple créé sans numéro de téléphone, ID: {}", savedUser.getId());
            throw new MissingPhoneNumberException("Le numéro de téléphone est requis pour compléter l'inscription", savedUser.getId());
        }
        String accessToken = jwtUtils.generateAccessToken(savedUser);
        String refreshToken = jwtUtils.generateRefreshToken(savedUser);
        logger.info("Utilisateur Apple inscrit avec succès, ID: {}", savedUser.getId());
        return new AuthResponse(accessToken, refreshToken, savedUser.getId(), false);
    }

    @Override
    public void deleteClientAccount(String email) {
        logger.info("Suppression du compte utilisateur pour l'email: {}", email);

        User user = findByEmail(email);
        if (user == null) {
            logResourceNotFound("Utilisateur", "email", email);
            throw new ResourceNotFoundException("Utilisateur non trouvé avec l'email : " + email);
        }

        // Vérifie que l’utilisateur est soit un Client soit un Driver
        if (!(user instanceof Client) && !(user instanceof Driver)) {
            logger.warn("Tentative de suppression de compte non autorisée par l'email: {}", email);
            throw new BadRequestException("Seuls les clients et les chauffeurs peuvent supprimer leur propre compte");
        }

        try {
            userRepository.delete(user);

            if (user instanceof Client) {
                logger.info("Compte client supprimé avec succès pour l'email: {}", email);
            } else if (user instanceof Driver) {
                logger.info("Compte chauffeur supprimé avec succès pour l'email: {}", email);
            }
        } catch (Exception e) {
            logger.error("Erreur lors de la suppression du compte utilisateur pour l'email: {} - Erreur: {}", email, e.getMessage());
            throw new RuntimeException("Erreur lors de la suppression du compte", e);
        }
    }


    @Override
    public void updateUser(User user) {
        userRepository.save(user);
    }

    @Override
    public void generateOtpForReset(User user, String method) {
        logger.info("Génération d'un OTP pour la réinitialisation du mot de passe pour l'utilisateur: {}", user.getEmail());
        if (method == null || method.isEmpty()) {
            logger.warn("Échec de la génération d'OTP: Méthode de réinitialisation nulle ou vide");
            throw new BadRequestException("La méthode de réinitialisation ne peut pas être nulle ou vide");
        }
        String otp = String.format("%06d", (int) (Math.random() * 900000) + 100000);
        user.setResetOtp(otp);
        user.setResetOtpExpiresAt(new Date(System.currentTimeMillis() + 180_000));
        userRepository.save(user);
        if ("phone".equalsIgnoreCase(method)) {
            logger.warn("L'envoi d'OTP par SMS n'est pas encore implémenté pour l'utilisateur: {}", user.getEmail());
        } else {
            sendOtpByEmail(user.getEmail(), otp);
        }
        logger.info("OTP généré avec succès pour l'utilisateur: {}", user.getEmail());
    }

    private void sendOtpByEmail(String toEmail, String otpCode) {
        logger.info("Envoi d'un email OTP à: {}", toEmail);
        if (toEmail == null || toEmail.isEmpty()) {
            logger.warn("Échec de l'envoi d'OTP: Email nul ou vide");
            throw new BadRequestException("L'email ne peut pas être nul ou vide pour l'OTP");
        }
        try {
            Context context = new Context();
            context.setVariable("otpCode", otpCode);
            String htmlContent = templateEngine.process("otp-email.html", context);
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            helper.setFrom("ramasquare22@gmail.com");
            helper.setTo(toEmail);
            helper.setSubject("OTP de réinitialisation de mot de passe");
            helper.setText(htmlContent, true);
            mailSender.send(mimeMessage);
            logger.info("Email OTP envoyé avec succès à: {}", toEmail);
        } catch (MessagingException e) {
            logger.error("Échec de l'envoi de l'email OTP à {}: {}", toEmail, e.getMessage());
            throw new RuntimeException("Échec de l'envoi de l'email OTP", e);
        }
    }

    @Override
    public boolean verifyOtp(User user, String code) {
        logger.info("Vérification de l'OTP pour l'utilisateur: {}", user.getEmail());
        if (user.getResetOtp() == null || !user.getResetOtp().equals(code)) {
            logger.warn("Échec de la vérification: OTP invalide pour l'utilisateur: {}", user.getEmail());
            return false;
        }
        if (user.getResetOtpExpiresAt() == null || user.getResetOtpExpiresAt().before(new Date())) {
            logger.warn("Échec de la vérification: OTP expiré pour l'utilisateur: {}", user.getEmail());
            return false;
        }
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.MINUTE, 10);
        user.setPasswordResetAllowedUntil(cal.getTime());
        user.setResetOtp(null);
        user.setResetOtpExpiresAt(null);
        userRepository.save(user);
        logger.info("OTP vérifié avec succès pour l'utilisateur: {}", user.getEmail());
        return true;
    }

    @Override
    public boolean resetPassword(User user, String newPassword) {
        logger.info("Réinitialisation du mot de passe pour l'utilisateur: {}", user.getEmail());
        if (user.getPasswordResetAllowedUntil() == null ||
                user.getPasswordResetAllowedUntil().before(new Date())) {
            logger.warn("Échec de la réinitialisation: Réinitialisation non autorisée ou expirée pour l'utilisateur: {}", user.getEmail());
            return false;
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setPasswordResetAllowedUntil(null);
        userRepository.save(user);
        logger.info("Mot de passe réinitialisé avec succès pour l'utilisateur: {}", user.getEmail());
        return true;
    }

    private String extractFirstName(String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) {
            logger.debug("Nom d'affichage vide, utilisation de 'Utilisateur' comme prénom");
            return "Utilisateur";
        }
        String[] names = displayName.trim().split("\\s+");
        String firstName = names.length > 0 ? names[0] : "Utilisateur";
        logger.debug("Prénom extrait: {}", firstName);
        return firstName;
    }

    private String extractLastName(String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) {
            logger.debug("Nom d'affichage vide, nom de famille vide retourné");
            return "";
        }
        String[] names = displayName.trim().split("\\s+");
        String lastName = names.length > 1 ? String.join(" ", Arrays.copyOfRange(names, 1, names.length)) : "";
        logger.debug("Nom de famille extrait: {}", lastName);
        return lastName;
    }

    private void logResourceNotFound(String resourceType, String identifierType, String identifier) {
        logger.warn("{} non trouvé avec {}: {}", resourceType, identifierType, identifier);
    }

    @Override
    public void sendReclamation(String authenticatedEmail, String sentFromEmail, String senderName, String mailContent) {
        logger.info("Envoi d'une réclamation de l'utilisateur: {}", authenticatedEmail);

        Reclamation reclamation = new Reclamation();
        reclamation.setAuthenticatedEmail(authenticatedEmail);
        reclamation.setSentFromEmail(sentFromEmail);
        reclamation.setSenderName(senderName);
        reclamation.setMailContent(mailContent);
        reclamationRepository.save(reclamation);

        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            helper.setFrom(sentFromEmail);
            helper.setTo("ramasquare22@gmail.com");
            helper.setSubject("Réclamation de " + senderName);
            helper.setText(mailContent, true);
            mailSender.send(mimeMessage);
            logger.info("Réclamation envoyée avec succès de: {}", sentFromEmail);
        } catch (MessagingException e) {
            logger.error("Échec de l'envoi de la réclamation: {}", e.getMessage());
            throw new RuntimeException("Échec de l'envoi de la réclamation", e);
        }
    }

    @Scheduled(cron = "0 0 0 * * ?")
    public void checkSubAdminDocumentUpdates() {
        logger.info("Exécution de la tâche planifiée : Vérification des mises à jour de documents pour les sous-administrateurs");
        List<SubAdmin> subAdmins = userRepository.findAll().stream()
                .filter(user -> user.getRole() == Role.SUB_ADMIN)
                .map(SubAdmin.class::cast)
                .toList();
        Date now = new Date();

        for (SubAdmin subAdmin : subAdmins) {
            if (subAdmin.isActive() && subAdmin.getLastDocumentUpdate() != null) {
                Calendar cal = Calendar.getInstance();
                cal.setTime(subAdmin.getLastDocumentUpdate());
                cal.add(Calendar.MONTH, 6);
                Date blockDate = cal.getTime();

                if (now.after(blockDate)) {
                    subAdmin.setActive(false);
                    userRepository.save(subAdmin);
                    logger.info("Sous-administrateur bloqué automatiquement pour inactivité de documents: {}", subAdmin.getEmail());
                } else {
                    cal.setTime(blockDate);
                    cal.add(Calendar.DATE, -5);
                    Date warningDate = cal.getTime();

                    if (now.after(warningDate) && !subAdmin.isWarningEmailSent()) {
                        sendWarningEmailToAdmins(subAdmin);
                        subAdmin.setWarningEmailSent(true);
                        userRepository.save(subAdmin);
                        logger.info("Email d'avertissement envoyé pour le sous-administrateur: {}", subAdmin.getEmail());
                    }
                }
            }
        }
        logger.info("Tâche planifiée terminée : Vérification des mises à jour de documents");
    }

    @Async
    protected void sendWarningEmailToAdmins(SubAdmin subAdmin) {
        try {
            Context context = new Context();
            context.setVariable("subAdminEmail", subAdmin.getEmail());
            String htmlContent = templateEngine.process("subadmin-warning", context);
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            helper.setFrom("ramasquare22@gmail.com");
            helper.setTo("mohamed.aichaoui.tic@gmail.com");
            helper.setSubject("Avertissement : Blocage imminent d'un sous-administrateur");
            helper.setText(htmlContent, true);
            mailSender.send(mimeMessage);
            logger.info("Email d'avertissement envoyé à l'admin: mohamed.aichaoui.tic@gmail.com");
        } catch (MessagingException e) {
            logger.error("Échec de l'envoi de l'email d'avertissement à mohamed.aichaoui.tic@gmail.com pour le sous-admin: {} - Erreur: {}", subAdmin.getEmail(), e.getMessage());
        }
    }
}
