package fr.tictak.dema.service.implementation;

import fr.tictak.dema.exception.ResourceNotFoundException;
import fr.tictak.dema.model.enums.Role;
import fr.tictak.dema.model.user.User;
import fr.tictak.dema.repository.UserRepository;
import fr.tictak.dema.service.AdminService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AdminServiceImpl implements AdminService {

    private final UserRepository userRepository;

    public AdminServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public List<User> getAllSubAdmins() {
        return userRepository.findByRole(Role.valueOf("SUB_ADMIN"));
    }

    @Override
    public List<User> findAllDrivers() {
        return userRepository.findByRole(Role.valueOf("DRIVER"));
    }

    @Override
    public void deleteSubAdmin(String id) {
        if (!userRepository.existsById(id)) {
            throw new ResourceNotFoundException("Sub-admin not found with ID: " + id);
        }
        userRepository.deleteById(id);
    }
}