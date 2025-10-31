package fr.tictak.dema.model.user;

import fr.tictak.dema.model.enums.Role;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.Date;
import java.util.List;


@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class Client extends User {

    private String clientReference;
    private String address;
    private Date clientSince;
    @Setter
    private String photoUrl;

    {
        this.setRole(Role.CLIENT);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public String getUsername() {
        return "";
    }


}
