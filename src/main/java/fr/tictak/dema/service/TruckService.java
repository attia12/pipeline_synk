package fr.tictak.dema.service;

import fr.tictak.dema.dto.in.CreateTruckDto;
import fr.tictak.dema.model.Truck;

import java.util.List;

public interface TruckService {
    Truck createTruck(CreateTruckDto truck, String subAdminId);
    List<Truck> getTrucksForSubAdmin(String subAdminId);
    Truck findById(String truckId);
    void deleteTruck(String truckId);
}