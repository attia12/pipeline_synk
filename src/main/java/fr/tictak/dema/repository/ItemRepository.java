package fr.tictak.dema.repository;

import fr.tictak.dema.model.Item;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;

public interface ItemRepository extends MongoRepository<Item, String> {
    @Query("{ $or: [ { 'key': { $regex: ?0, $options: 'i' } }, { 'label': { $regex: ?0, $options: 'i' } } ] }")
    List<Item> findByKeyOrLabel(String query);
    List<Item> findByLabelIn(List<String> labels);

}