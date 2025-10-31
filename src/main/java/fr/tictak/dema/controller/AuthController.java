package fr.tictak.dema.controller;

import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import fr.tictak.dema.dto.out.AuthResponse;
import fr.tictak.dema.dto.in.*;
import fr.tictak.dema.exception.*;
import fr.tictak.dema.model.user.Driver;
import fr.tictak.dema.model.user.SubAdmin;
import fr.tictak.dema.model.user.User;
import fr.tictak.dema.security.JwtUtils;
import fr.tictak.dema.service.UserService;
import fr.tictak.dema.service.firebase.FirebaseAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/auth")
@Tag(name = "Authentification", description = "API pour la gestion de l'authentification des utilisateurs (connexion par téléphone, Google et Apple).")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    private final UserService userService;
    private final JwtUtils jwtUtils;
    private final PasswordEncoder passwordEncoder;
    private final FirebaseAuthService firebaseAuthService;

    public AuthController(UserService userService,
                          JwtUtils jwtUtils,
                          PasswordEncoder passwordEncoder,
                          FirebaseAuthService firebaseAuthService) {
        this.userService = userService;
        this.jwtUtils = jwtUtils;
        this.passwordEncoder = passwordEncoder;
        this.firebaseAuthService = firebaseAuthService;
    }

    @Setter
    @Getter
    public static class IdTokenRequest {
        private String idToken;
    }

    @Operation(summary = "Connexion ou inscription avec Google",
            description = "Valide un jeton Google ID et inscrit ou connecte l'utilisateur.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Utilisateur authentifié avec succès",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = AuthResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                        "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
                                        "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
                                    }
                                    """))),
            @ApiResponse(responseCode = "400", description = "Jeton Google ID invalide ou expiré",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                        "timestamp": "2025-04-04T12:00:00.000Z",
                                        "status": 400,
                                        "error": "Requête incorrecte",
                                        "message": "Échec de la vérification du jeton Google ID : Jeton invalide",
                                        "path": "/auth/google/signin"
                                    }
                                    """)))
    })
    @PostMapping("/google/login")
    public ResponseEntity<AuthResponse> googleSignIn(@RequestBody IdTokenRequest request) {
        try {
            logger.info("Tentative de connexion avec Google pour le jeton: {}", request.getIdToken());
            FirebaseToken decodedToken = firebaseAuthService.verifyIdToken(request.getIdToken());
            AuthResponse authResponse = userService.registerGoogleUser(decodedToken);
            logger.info("Connexion Google réussie pour l'utilisateur: {}", decodedToken.getEmail());
            return ResponseEntity.ok(authResponse);
        } catch (FirebaseAuthException e) {
            logger.error("Échec de la vérification du jeton Google: {}", e.getMessage());
            throw new BadRequestException("Échec de la vérification du jeton Google ID : " + e.getMessage());
        }
    }

    @Operation(summary = "Suppression du compte client",
            description = "Permet à un client de supprimer définitivement son propre compte de la base de données.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Compte supprimé avec succès",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Map.class),
                            examples = @ExampleObject(value = """
                        {
                            "message": "Compte supprimé avec succès"
                        }
                        """))),
            @ApiResponse(responseCode = "403", description = "Action réservée aux clients uniquement",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Utilisateur non trouvé",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
    })
    @DeleteMapping("/delete-account")
    @PreAuthorize("hasAnyRole('CLIENT', 'DRIVER')")
    public ResponseEntity<Map<String, String>> deleteClientAccount() {
        logger.info("Tentative de suppression de compte client");
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        userService.deleteClientAccount(email);
        logger.info("Compte client supprimé avec succès pour l'email: {}", email);
        Map<String, String> response = new HashMap<>();
        response.put("message", "Compte supprimé avec succès");
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Connexion ou inscription avec Apple",
            description = "Valide un jeton Apple ID et inscrit ou connecte l'utilisateur.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Utilisateur authentifié avec succès",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = AuthResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                        "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
                                        "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
                                    }
                                    """))),
            @ApiResponse(responseCode = "400", description = "Jeton Apple ID invalide ou expiré",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                        "timestamp": "2025-04-04T12:00:00.000Z",
                                        "status": 400,
                                        "error": "Requête incorrecte",
                                        "message": "Échec de la vérification du jeton Apple ID : Jeton invalide",
                                        "path": "/auth/apple/signin"
                                    }
                                    """)))
    })
    @PostMapping("/apple/login")
    public ResponseEntity<AuthResponse> appleSignIn(@RequestBody IdTokenRequest request) {
        try {
            logger.info("Tentative de connexion avec Apple pour le jeton: {}", request.getIdToken());
            FirebaseToken decodedToken = firebaseAuthService.verifyIdToken(request.getIdToken());
            AuthResponse authResponse = userService.registerAppleUser(decodedToken);
            logger.info("Connexion Apple réussie pour l'utilisateur: {}", decodedToken.getEmail());
            return ResponseEntity.ok(authResponse);
        } catch (FirebaseAuthException e) {
            logger.error("Échec de la vérification du jeton Apple: {}", e.getMessage());
            throw new BadRequestException("Échec de la vérification du jeton Apple ID : " + e.getMessage());
        }
    }

    @Operation(summary = "Inscription d'un client (email/mot de passe)",
            description = "Crée un nouveau compte utilisateur client avec un email, un mot de passe et des informations personnelles.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Compte client créé avec succès",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = User.class))),
            @ApiResponse(responseCode = "400", description = "Données invalides ou email déjà utilisé",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
    })
    @PostMapping("/register")
    public ResponseEntity<User> registerClient(@Valid @RequestBody RegisterRequest request) {
        logger.info("Tentative d'inscription client pour l'email: {}", request.email());
        String lowercaseEmail = request.email().toLowerCase();
        if (userService.findByEmail(lowercaseEmail) != null) {
            logger.warn("Email déjà utilisé: {}", lowercaseEmail);
            throw new BadRequestException("Cet email est déjà utilisé !");
        }
        User newClient = userService.registerClient(request);
        logger.info("Inscription client réussie pour l'email: {}", lowercaseEmail);
        return ResponseEntity.status(HttpStatus.CREATED).body(newClient);
    }
    @Operation(summary = "Mise à jour du numéro de téléphone",
            description = "Met à jour le numéro de téléphone d'un utilisateur après la première connexion via Google ou Apple, en utilisant un jeton temporaire.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Numéro de téléphone mis à jour avec succès, jetons retournés",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = AuthResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                        "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
                                        "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
                                        "userId": "12345"
                                    }
                                    """))),
            @ApiResponse(responseCode = "401", description = "Jeton temporaire invalide",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                        "timestamp": "2025-04-04T12:00:00.000Z",
                                        "status": 401,
                                        "error": "Non autorisé",
                                        "message": "Invalid temporary token",
                                        "path": "/auth/update-phone"
                                    }
                                    """))),
            @ApiResponse(responseCode = "404", description = "Utilisateur non trouvé",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
    })
    @PatchMapping("/update-phone")
    public ResponseEntity<AuthResponse> updatePhoneNumber(@Valid @RequestBody UpdatePhoneWithTokenRequest request) {
        logger.info("Tentative de mise à jour du numéro de téléphone pour l'utilisateur: {}", request.userId());
        AuthResponse authResponse = userService.updatePhoneNumber(request.userId(), request.phoneNumber(), request.nom(), request.prenom());
        logger.info("Numéro de téléphone mis à jour avec succès pour l'utilisateur: {}", request.userId());
        return ResponseEntity.ok(authResponse);
    }

    @Operation(summary = "Création d'un sous-administrateur (email/mot de passe)",
            description = "Crée un compte sous-administrateur. Accessible uniquement aux utilisateurs avec le rôle ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Compte sous-administrateur créé avec succès",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = User.class))),
            @ApiResponse(responseCode = "403", description = "Action réservée aux administrateurs",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
            @ApiResponse(responseCode = "400", description = "Email déjà utilisé",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
    })
    @PostMapping("/createSubAdmin")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<User> createSubAdmin(@Valid @RequestBody RegisterSubAdminRequest request) {
        logger.info("Tentative de création d'un sous-administrateur pour l'email: {}", request.email());
        String lowercaseEmail = request.email().toLowerCase();
        if (userService.findByEmail(lowercaseEmail) != null) {
            logger.warn("l'Email déjà utilisé : {}", lowercaseEmail);
            throw new BadRequestException("Cet email est déjà utilisé !");
        }
        User subAdmin = userService.registerSubAdmin(request);
        logger.info("Création sous-administrateur réussie pour l'email: {}", lowercaseEmail);
        return ResponseEntity.status(HttpStatus.CREATED).body(subAdmin);
    }


    @Operation(summary = "Création d'un chauffeur (email/mot de passe)",
            description = "Crée un compte chauffeur. Accessible aux utilisateurs avec les rôles ADMIN ou SUB_ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Compte chauffeur créé avec succès",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = User.class))),
            @ApiResponse(responseCode = "403", description = "Action réservée aux administrateurs ou sous-administrateurs",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
            @ApiResponse(responseCode = "400", description = "Email déjà utilisé",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
    })
    @PostMapping("/createDriver")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUB_ADMIN')")
    public ResponseEntity<User> createDriver(@Valid @RequestBody CreateDriverRequest driverRequest) {
        logger.info("Received createDriver request for email: {}", driverRequest.email());
        logger.debug("Driver request data: {}", driverRequest);
        String lowercaseEmail = driverRequest.email().toLowerCase();

        // Check if email is already in use
        if (userService.findByEmail(lowercaseEmail) != null) {
            logger.warn("Email already in use: {}", lowercaseEmail);
            throw new BadRequestException("Cet email est déjà utilisé !");
        }

        // Get the current authenticated user
        String subAdminEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userService.findByEmail(subAdminEmail);
        if (currentUser == null) {
            logger.warn("Utilisateur non trouvé avec l'email: {}", subAdminEmail);
            throw new ForbiddenException("Utilisateur non authentifié ou non trouvé");
        }

        // Register the driver
        User driver = userService.registerDriver(driverRequest, currentUser);
        logger.info("Driver created successfully for email: {}", lowercaseEmail);

        return ResponseEntity.status(HttpStatus.CREATED).body(driver);
    }

    @Operation(summary = "Connexion par email/mot de passe",
            description = "Authentifie un utilisateur avec un email et un mot de passe, retourne des jetons JWT.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Connexion réussie",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = AuthResponse.class))),
            @ApiResponse(responseCode = "401", description = "Identifiants invalides",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
    })
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        logger.info("Tentative de connexion pour l'email: {}", request.email());
        String lowercaseEmail = request.email().toLowerCase();
        User user = userService.findByEmail(lowercaseEmail);
        if (user == null) {
            logUserNotFound(request.email());
            throw new UnauthorizedException("Email ou mot de passe invalide");
        }
        boolean matches = passwordEncoder.matches(request.password(), user.getPassword());
        if (!matches) {
            logger.warn("Échec de connexion: Mot de passe invalide pour l'email: {}", request.email());
            throw new UnauthorizedException("Email ou mot de passe invalide");
        }
        boolean passwordChangeRequired = (user instanceof SubAdmin && !((SubAdmin) user).isAccountActive()) ||
                (user instanceof Driver && !((Driver) user).isAccountActive());
        String accessToken = jwtUtils.generateAccessToken(user);
        String refreshToken = jwtUtils.generateRefreshToken(user);

        logger.info("Connexion réussie pour l'email: {}", request.email());
        return ResponseEntity.ok(new AuthResponse(accessToken, refreshToken, user.getId(), passwordChangeRequired));
    }

    @Operation(summary = "Rafraîchissement de jeton",
            description = "Rafraîchit un jeton d'accès à l'aide d'un jeton de rafraîchissement, émet de nouveaux jetons d'accès et de rafraîchissement.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Jetons rafraîchis avec succès",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = AuthResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                        "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
                                        "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
                                    }
                                    """))),
            @ApiResponse(responseCode = "401", description = "Jeton de rafraîchissement invalide",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                        "timestamp": "2025-04-04T12:00:00.000Z",
                                        "status": 401,
                                        "error": "Non autorisé",
                                        "message": "Jeton de rafraîchissement invalide",
                                        "path": "/auth/refresh"
                                    }
                                    """))),
            @ApiResponse(responseCode = "404", description = "Utilisateur non trouvé",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
    })
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        logger.info("Tentative de rafraîchissement de jeton");
        String refreshToken = request.refreshToken();
        if (refreshToken == null || !jwtUtils.validateToken(refreshToken, true)) {
            logger.warn("Échec de rafraîchissement: Jeton de rafraîchissement invalide");
            throw new UnauthorizedException("Jeton de rafraîchissement invalide");
        }
        String email = jwtUtils.extractUserEmail(refreshToken);
        User user = userService.findByEmail(email);
        boolean passwordChangeRequired = (user instanceof SubAdmin && !((SubAdmin) user).isAccountActive()) ||
                (user instanceof Driver && !((Driver) user).isAccountActive());
        String newAccessToken = jwtUtils.generateAccessToken(user);
        String newRefreshToken = jwtUtils.generateRefreshToken(user);
        logger.info("Rafraîchissement de jeton réussi pour l'email: {}", email);
        return ResponseEntity.ok(new AuthResponse(newAccessToken, newRefreshToken, user.getId(), passwordChangeRequired));
    }

    @Operation(summary = "Demande de réinitialisation de mot de passe",
            description = "Lance le processus de réinitialisation de mot de passe en envoyant un OTP à l'email ou au téléphone de l'utilisateur.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OTP envoyé avec succès",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Map.class),
                            examples = @ExampleObject(value = """
                            {
                                "message": "OTP envoyé avec succès",
                                "method": "email"
                            }
                            """))),
            @ApiResponse(responseCode = "400", description = "Email ou méthode invalide",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Utilisateur non trouvé",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
    })
    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        logger.info("Demande de réinitialisation de mot de passe pour l'email: {}", request.email());
        String lowercaseEmail = request.email().toLowerCase();
        User user = userService.findByEmail(lowercaseEmail);
        if (user == null) {
            logUserNotFound(lowercaseEmail);
            throw new ResourceNotFoundException("Utilisateur non trouvé avec l'email : " + lowercaseEmail);
        }
        String method = request.method() != null ? request.method().toLowerCase() : "email";
        if (!method.equals("email") && !method.equals("phone")) {
            logger.warn("Méthode invalide pour la réinitialisation: {}", method);
            throw new BadRequestException("La méthode doit être 'email' ou 'phone'");
        }
        userService.generateOtpForReset(user, method);
        logger.info("OTP envoyé avec succès via {} pour l'email: {}", method, lowercaseEmail);
        Map<String, String> response = new HashMap<>();
        response.put("message", "OTP envoyé avec succès");
        response.put("method", method);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Vérification de l'OTP",
            description = "Vérifie l'OTP fourni pour permettre la réinitialisation du mot de passe.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OTP vérifié avec succès",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Map.class),
                            examples = @ExampleObject(value = """
                            {
                                "message": "OTP vérifié avec succès. Vous pouvez maintenant réinitialiser votre mot de passe."
                            }
                            """))),
            @ApiResponse(responseCode = "400", description = "OTP invalide ou expiré",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Utilisateur non trouvé",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
    })
    @PostMapping("/verify-otp")
    public ResponseEntity<Map<String, String>> verifyOtp(@Valid @RequestBody VerifyOtpRequest request) {
        logger.info("Vérification de l'OTP pour l'email: {}", request.email());
        String lowercaseEmail = request.email().toLowerCase();
        User user = userService.findByEmail(lowercaseEmail);
        if (user == null) {
            logUserNotFound(lowercaseEmail);
            throw new ResourceNotFoundException("Utilisateur non trouvé avec l'email : " + lowercaseEmail);
        }
        boolean success = userService.verifyOtp(user, request.otpCode());
        if (!success) {
            logger.warn("Échec de la vérification de l'OTP pour l'email: {}", lowercaseEmail);
            throw new BadRequestException("OTP invalide ou expiré");
        }
        logger.info("OTP vérifié avec succès pour l'email: {}", lowercaseEmail);
        Map<String, String> response = new HashMap<>();
        response.put("message", "OTP vérifié avec succès. Vous pouvez maintenant réinitialiser votre mot de passe.");
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Réinitialisation du mot de passe",
            description = "Réinitialise le mot de passe de l'utilisateur après vérification de l'OTP.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Mot de passe réinitialisé avec succès",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Map.class),
                            examples = @ExampleObject(value = """
                            {
                                "message": "Mot de passe réinitialisé avec succès"
                            }
                            """))),
            @ApiResponse(responseCode = "400", description = "Réinitialisation non autorisée, OTP non vérifié",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Utilisateur non trouvé",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
    })
    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        logger.info("Réinitialisation du mot de passe pour l'email: {}", request.email());
        String lowercaseEmail = request.email().toLowerCase();
        User user = userService.findByEmail(lowercaseEmail);
        if (user == null) {
            logUserNotFound(lowercaseEmail);
            throw new ResourceNotFoundException("Utilisateur non trouvé avec l'email : " + lowercaseEmail);
        }
        boolean success = userService.resetPassword(user, request.newPassword());
        if (!success) {
            logger.warn("Échec de la réinitialisation du mot de passe pour l'email: {}", lowercaseEmail);
            throw new BadRequestException("Réinitialisation du mot de passe non autorisée. Veuillez d'abord vérifier l'OTP.");
        }
        logger.info("Mot de passe réinitialisé avec succès pour l'email: {}", lowercaseEmail);
        Map<String, String> response = new HashMap<>();
        response.put("message", "Mot de passe réinitialisé avec succès");
        return ResponseEntity.ok(response);
    }

    private void logUserNotFound(String email) {
        logger.warn("Utilisateur non trouvé pour l'email: {}", email);
    }


    @Operation(summary = "Changer le mot de passe initial",
            description = "Permet aux sous-administrateurs et chauffeurs de changer leur mot de passe initial après la première connexion.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Mot de passe changé avec succès, nouveaux jetons retournés",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = AuthResponse.class))),
            @ApiResponse(responseCode = "400", description = "Requête invalide ou changement non requis",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Ancien mot de passe incorrect",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
    })
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/change-initial-password")
    public ResponseEntity<AuthResponse> changeInitialPassword(@Valid @RequestBody ChangeInitialPasswordRequest request) {
        logger.info("Tentative de changement de mot de passe initial");
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        userService.changeInitialPassword(email, request.oldPassword(), request.newPassword());
        User user = userService.findByEmail(email);
        String accessToken = jwtUtils.generateAccessToken(user);
        String refreshToken = jwtUtils.generateRefreshToken(user);
        logger.info("Mot de passe initial changé avec succès pour l'email: {}", email);
        return ResponseEntity.ok(new AuthResponse(accessToken, refreshToken, user.getId(), false));
    }
}