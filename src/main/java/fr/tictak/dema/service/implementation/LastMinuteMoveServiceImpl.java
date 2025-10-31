package fr.tictak.dema.service.implementation;

import fr.tictak.dema.model.LastMinuteMove;
import fr.tictak.dema.repository.LastMinuteMoveRepository;
import fr.tictak.dema.service.LastMinuteMoveService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class LastMinuteMoveServiceImpl implements LastMinuteMoveService {

    private static final Logger logger = LoggerFactory.getLogger(LastMinuteMoveServiceImpl.class);
    private final LastMinuteMoveRepository lastMinuteMoveRepository;

    @Override
    public List<LastMinuteMove> getAll() {
        logger.info("Fetching all LastMinuteMove records");
        return lastMinuteMoveRepository.findAll();
    }


}