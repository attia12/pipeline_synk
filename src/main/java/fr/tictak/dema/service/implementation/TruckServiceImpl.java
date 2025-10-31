package fr.tictak.dema.service.implementation;

import fr.tictak.dema.dto.in.CreateTruckDto;
import fr.tictak.dema.model.Truck;
import fr.tictak.dema.repository.TruckRepository;
import fr.tictak.dema.service.TruckService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TruckServiceImpl implements TruckService {

    private final TruckRepository truckRepository;


    @Autowired
    public TruckServiceImpl(TruckRepository truckRepository) {
        this.truckRepository = truckRepository;
    }

    @Override
    public Truck createTruck(CreateTruckDto truckDto, String subAdminId) {


        Truck truck = new Truck();
        truck.setCapacity(truckDto.capacity());
        truck.setModel(truckDto.model());
        truck.setActive(truckDto.active());
        truck.setAssuranceCamion(truckDto.assuranceCamion());
        truck.setCarteGrise(truckDto.carteGrise());
        truck.setVignetteTaxe(truckDto.vignetteTaxe());
        truck.setAddedBy(subAdminId);

        return truckRepository.save(truck);
    }

    @Override
    public List<Truck> getTrucksForSubAdmin(String subAdminId) {
        return truckRepository.findByAddedBy(subAdminId);
    }

    @Override
    public Truck findById(String truckId) {
        return truckRepository.findById(truckId)
                .orElseThrow(() -> new IllegalArgumentException("Camion non trouvé avec l'ID: " + truckId));
    }

    @Override
    public void deleteTruck(String truckId) {
        if (!truckRepository.existsById(truckId)) {
            throw new IllegalArgumentException("Camion non trouvé avec l'ID: " + truckId);
        }
        truckRepository.deleteById(truckId);
    }
}