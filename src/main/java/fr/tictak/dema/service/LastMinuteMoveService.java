package fr.tictak.dema.service;

import fr.tictak.dema.model.LastMinuteMove;
import fr.tictak.dema.repository.LastMinuteMoveRepository;

import java.util.List;

public interface LastMinuteMoveService {




    List<LastMinuteMove> getAll();

}
