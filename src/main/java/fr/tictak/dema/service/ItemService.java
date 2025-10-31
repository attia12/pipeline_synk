package fr.tictak.dema.service;

import fr.tictak.dema.model.Item;
import java.util.List;

public interface ItemService {
    List<Item> searchItems(String query);
}