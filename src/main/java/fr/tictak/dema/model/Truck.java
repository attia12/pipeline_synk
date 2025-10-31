package fr.tictak.dema.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "trucks")
@Data
@NoArgsConstructor
public class Truck {

    @Id
    private String id;

    private String licensePlate;
    private double capacity;
    private String model;
    private int year;
    private boolean active;
    private String addedBy;
    private String assuranceCamion;
    private String photoCamion;
    private String carteGrise;
    private String vignetteTaxe;

    public boolean isAvailable() {
        return active;
    }
}