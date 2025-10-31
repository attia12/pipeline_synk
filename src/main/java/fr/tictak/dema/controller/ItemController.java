package fr.tictak.dema.controller;

import fr.tictak.dema.model.Item;
import fr.tictak.dema.service.ItemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@Tag(name = "Gestion des objets", description = "API pour la gestion et la recherche d'objets dans la plateforme de déménagement.")
public class ItemController {

    private static final Logger logger = LoggerFactory.getLogger(ItemController.class);

    private final ItemService itemService;

    public ItemController(ItemService itemService) {
        this.itemService = itemService;
    }

    @GetMapping("/api/items/search")
    @Operation(
            summary = "Rechercher des objets par requête",
            description = "Recherche des objets dans la base de données en faisant correspondre la requête aux champs 'key' ou 'label'. Si aucune requête n'est fournie, retourne tous les objets."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Liste des objets correspondants retournée avec succès",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Item.class),
                            examples = @ExampleObject(value = """
                                    [
                                        {
                                            "id": 1,
                                            "key": "frigo",
                                            "label": "Réfrigérateur"
                                        },
                                        {
                                            "id": 2,
                                            "key": "bench",
                                            "label": "Banc"
                                        }
                                    ]
                                    """))),
            @ApiResponse(responseCode = "400", description = "Paramètre de requête invalide (si une validation est ajoutée ultérieurement)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Map.class),
                            examples = @ExampleObject(value = """
                                    {
                                        "error": "Requête incorrecte",
                                        "message": "Paramètre de requête invalide"
                                    }
                                    """)))
    })
    public ResponseEntity<List<Item>> searchItems(
            @Parameter(description = "Requête de recherche (par exemple, 'banc' ou 'frigo') pour filtrer les objets par clé ou libellé")
            @RequestParam(value = "q", required = false) String query) {
        logger.info("Recherche d'objets avec la requête: {}", query);
        List<Item> items = itemService.searchItems(query);
        logger.info("Recherche d'objets réussie, {} objets trouvés", items.size());
        return ResponseEntity.ok(items);
    }
}