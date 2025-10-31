package fr.tictak.dema.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.io.IOException;
import java.io.InputStream;

@Configuration

public class FirebaseConfig {

    private static final Logger logger = LoggerFactory.getLogger(FirebaseConfig.class);

    @Value("${app.firebase-configuration.file}")
    private String firebaseConfigPath;

    @Bean
    public FirebaseApp firebaseApp() throws IOException {
        logger.info("Initialisation de Firebase avec le fichier de configuration : {}", firebaseConfigPath);
        try (InputStream serviceAccount = this.getClass().getClassLoader().getResourceAsStream(firebaseConfigPath)) {
            if (serviceAccount == null) {
                logger.error("Fichier de compte de service Firebase non trouvé à : {}", firebaseConfigPath);
                throw new IllegalArgumentException("Fichier de compte de service Firebase non trouvé : " + firebaseConfigPath);
            }

            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                    .setStorageBucket("tictak-14c0b.appspot.com") // Définir explicitement le bucket de stockage
                    .build();

            // Vérifier si FirebaseApp est déjà initialisé pour éviter une double initialisation
            if (FirebaseApp.getApps().isEmpty()) {
                FirebaseApp app = FirebaseApp.initializeApp(options);
                logger.info("FirebaseApp initialisé avec succès : {}", app.getName());
                return app;
            } else {
                FirebaseApp app = FirebaseApp.getApps().getFirst();
                logger.info("FirebaseApp déjà initialisé : {}", app.getName());
                return app;
            }
        } catch (IOException e) {
            logger.error("Échec de l'initialisation de Firebase : {}", e.getMessage(), e);
            throw e;
        }
    }
}