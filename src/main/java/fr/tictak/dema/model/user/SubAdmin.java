    package fr.tictak.dema.model.user;

    import fr.tictak.dema.model.enums.DocumentType;
    import fr.tictak.dema.model.enums.Role;
    import lombok.*;

    import java.util.Date;
    import java.util.List;
    import java.util.Map;

    @Data
    @NoArgsConstructor
    @EqualsAndHashCode(callSuper = true)
    public class SubAdmin extends User {

        private String subAdminCode;
        private String assignedRegion;
        private Date subAdminCreatedAt;
        private boolean isContractSigned;
        private String companyName;
        private String siretNumber;
        private int numberOfTrucks;
        private List<String> truckTypes;
        private String contractDuration;
        private String critAirType;
        private Map<DocumentType, String> legalDocuments;
        private boolean accountActive = false;


        private boolean isEmailVerified;
        private Date verificationTokenExpiresAt;
        // Add getters and setters
        @Setter
        @Getter
        private Date lastDocumentUpdate;
        @Getter
        private boolean warningEmailSent = false;

        {
            this.setRole(Role.SUB_ADMIN);
        }


        @Override
        public String getUsername() {
            return getEmail() != null ? getEmail() : "";
        }
    }
