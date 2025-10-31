package fr.tictak.dema.repository;


import fr.tictak.dema.model.enums.Role;
import fr.tictak.dema.model.user.SubAdmin;
import fr.tictak.dema.model.user.User;
import org.springframework.data.mongodb.repository.MongoRepository;


import java.util.List;
import java.util.Optional;

public interface UserRepository extends MongoRepository<User, String> {
    Optional<User> findByEmail(String email);

    User findByVerificationToken(String token);

    Optional<User> findByPhoneNumber(String phoneNumber);

    List<User> findByRole(Role role);

}