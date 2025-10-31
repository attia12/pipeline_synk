package fr.tictak.dema.service;


import fr.tictak.dema.model.user.User;

import java.util.List;

public interface AdminService {
    /**
     * Returns a list of all Sub-Admins in the system.
     */
    List<User> getAllSubAdmins();
    List<User> findAllDrivers();
    void deleteSubAdmin(String id);
}
