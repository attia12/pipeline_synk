package fr.tictak.dema.service.implementation;

import fr.tictak.dema.model.Item;
import fr.tictak.dema.repository.ItemRepository;
import fr.tictak.dema.service.ItemService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ItemServiceImpl implements ItemService {


    private final ItemRepository itemRepository;

    public ItemServiceImpl(ItemRepository itemRepository) {
        this.itemRepository = itemRepository;
    }

    @Override
    public List<Item> searchItems(String query) {
        if (query == null || query.trim().isEmpty()) {
            return itemRepository.findAll();
        }
        return itemRepository.findByKeyOrLabel(query);
    }
}