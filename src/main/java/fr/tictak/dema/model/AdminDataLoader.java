package fr.tictak.dema.model;

import fr.tictak.dema.model.user.Admin;
import fr.tictak.dema.model.enums.Role;
import fr.tictak.dema.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Date;

@Component
public class AdminDataLoader implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminDataLoader(UserRepository userRepository,
                           PasswordEncoder passwordEncoder1) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder1;

    }

    @Override
    public void run(String... args) {
        if (userRepository.findByEmail("admin@example.com").isEmpty()) {
            Admin admin = new Admin();
            admin.setEmail("admin@example.com");
            String randomPassword = generateRandomPassword();
            admin.setPassword(passwordEncoder.encode(randomPassword));
            admin.setActive(true);
            admin.setCreatedAt(new Date());
            admin.setRole(Role.ADMIN);
            userRepository.save(admin);
            System.out.println("✔ Admin created. Email: admin@example.com / Password: " + randomPassword);
        }
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
}