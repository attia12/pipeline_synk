package fr.tictak.dema.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Document(collection = "items")
@Data
public class Item {
    @Id
    private String id;

    private String key;

    @Field("min_truck_size")
    private int minTruckSize;


    private String label;
    private String volume;
    private String banned;
    private String elevator;
    private String two_people;
    private String stair_time;
}