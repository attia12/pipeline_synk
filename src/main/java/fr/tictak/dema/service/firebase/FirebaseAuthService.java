package fr.tictak.dema.service.firebase;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import com.google.firebase.auth.UserRecord;
import org.springframework.stereotype.Service;


@Service
public class FirebaseAuthService {

    /**
     * Verifies the Firebase ID Token the client sends after OTP verification.
     * @param idToken Token from client
     * @return Decoded token if valid
     * @throws FirebaseAuthException if verification fails
     */
    public FirebaseToken verifyIdToken(String idToken) throws FirebaseAuthException {
        return FirebaseAuth.getInstance().verifyIdToken(idToken);
    }

    /**
     * Retrieves the associated Firebase UserRecord or throws if not found.
     * @param uid The user ID from the verified token
     * @return UserRecord in Firebase
     * @throws FirebaseAuthException if user not found in Firebase
     */
    public UserRecord getUserByUid(String uid) throws FirebaseAuthException {
        return FirebaseAuth.getInstance().getUser(uid);
    }


}
