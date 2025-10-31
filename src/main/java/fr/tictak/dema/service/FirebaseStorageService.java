package fr.tictak.dema.service;

import com.google.cloud.storage.Acl;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.Bucket;
import com.google.firebase.FirebaseApp;
import com.google.firebase.cloud.StorageClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Service
public class FirebaseStorageService {

    private static final Logger logger = LoggerFactory.getLogger(FirebaseStorageService.class);

    private final Bucket bucket;

    public FirebaseStorageService(FirebaseApp firebaseApp) {
        try {
            this.bucket = StorageClient.getInstance(firebaseApp).bucket("tictak-14c0b.firebasestorage.app");
            if (this.bucket == null) {
                logger.error("Failed to initialize Firebase Storage bucket: tictak-14c0b.firebasestorage.app");
                throw new IllegalStateException("Firebase Storage bucket tictak-14c0b.firebasestorage.app is not accessible.");
            }
            logger.info("Firebase Storage bucket initialized: {}", this.bucket.getName());
        } catch (Exception e) {
            logger.error("Error initializing Firebase Storage: {}", e.getMessage(), e);
            throw new IllegalStateException("Failed to initialize Firebase Storage bucket", e);
        }
    }

    public String uploadFile(MultipartFile file, String fileName) throws IOException {
        logger.info("Uploading file {} to Firebase Storage", fileName);
        Blob blob = bucket.create(fileName, file.getInputStream(), file.getContentType());
        blob.createAcl(Acl.of(Acl.User.ofAllUsers(), Acl.Role.READER));
        String url = String.format("https://storage.googleapis.com/%s/%s", bucket.getName(), fileName);
        logger.info("File uploaded successfully: {}", url);
        return url;
    }
}