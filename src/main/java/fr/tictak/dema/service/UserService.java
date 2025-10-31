package fr.tictak.dema.service;

import com.google.firebase.auth.FirebaseToken;
import fr.tictak.dema.dto.out.AuthResponse;
import fr.tictak.dema.dto.in.CreateDriverRequest;
import fr.tictak.dema.dto.in.RegisterRequest;
import fr.tictak.dema.dto.in.RegisterSubAdminRequest;
import fr.tictak.dema.model.user.User;


public interface UserService {

    User findByEmail(String email);
    User registerClient(RegisterRequest request);
    User registerSubAdmin(RegisterSubAdminRequest request);
    User registerDriver(CreateDriverRequest req, User registeredBy);
    void changeInitialPassword(String email, String oldPassword, String newPassword);
    AuthResponse registerGoogleUser(FirebaseToken firebaseToken); // Changed to AuthResponse
    AuthResponse updatePhoneNumber(String userId, String phoneNumber,String nom, String prenom);
    AuthResponse registerAppleUser(FirebaseToken firebaseToken); // Changed to AuthResponse
    void generateOtpForReset(User user, String method);
    boolean verifyOtp(User user, String code);
    boolean resetPassword(User user, String newPassword);

    void updateUser(User user);

    void sendReclamation(String authenticatedEmail, String sentFromEmail, String senderName, String mailContent);
    void deleteClientAccount(String email);
}