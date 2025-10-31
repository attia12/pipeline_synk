package fr.tictak.dema.model.user;

import fr.tictak.dema.model.enums.DocumentType;
import fr.tictak.dema.model.enums.Role;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.data.annotation.Transient;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.mapping.DBRef;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;

@EqualsAndHashCode(callSuper = true)
@Data
public class Driver extends User {

    private String driverLicenseNumber;
    private Date driverSince;
    private Date driverLicenseExpiration;
    private double averageRating = 0.0;
    private int ratingCount = 0;

    @Field("documentTypes")
    private List<DocumentType> documentTypes;
    @Field("documentUrls")
    private Map<DocumentType, String> documentUrls;
    @DBRef
    private User createdBySubAdminId;
    private boolean accountActive = false;

    private String camion; // ID du camion assigné
    @Transient
    private GeoJsonPoint location;

    // private String phoneNumber;

    @Transient
    private String status;

    private String companyName;

    public Driver() {
        setRole(Role.DRIVER);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_DRIVER"));
    }

    @Override
    public String getUsername() {
        return super.getEmail() != null ? super.getEmail() : "";
    }
}