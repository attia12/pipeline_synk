package fr.tictak.dema.dto.in;

import fr.tictak.dema.model.ItemQuantity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record UpdateItemsRequest(
        @NotBlank(message = "Move ID cannot be empty") String moveId,
        @NotEmpty(message = "Items list cannot be empty") List<ItemQuantity> items
) {}