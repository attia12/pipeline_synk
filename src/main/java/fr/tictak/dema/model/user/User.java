package fr.tictak.dema.model.user;

import fr.tictak.dema.model.enums.Role;

import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Collections;
import java.util.Date;

@Data
@NoArgsConstructor
@Document(collection = "users") 
public abstract class User implements UserDetails {

    @Id
    private String id;

    private String firstName;
    private String lastName;
    private String email;
    private String verificationToken;
    private String resetOtp;
    private String password;
   private String phoneNumber;
    
    private String gender;
    private String dateOfBirth;
    private Date createdAt;
    private Date updatedAt;
    private boolean isActive;
    private Role role;
    private Date resetOtpExpiresAt;
    private String fcmToken;
    
    @Setter
    @Getter
    private Date passwordResetAllowedUntil;


    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        
        return email;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        if (role == null) return Collections.emptyList();
        return Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public boolean isAccountNonExpired() { return true; }

    @Override
    public boolean isAccountNonLocked() { return true; }

    @Override
    public boolean isCredentialsNonExpired() { return true; }

    @Override
    public boolean isEnabled() {
        return isActive;
    }



    public void setEmail(String email) {
        this.email = email != null ? email.toLowerCase() : null;
    }
}

