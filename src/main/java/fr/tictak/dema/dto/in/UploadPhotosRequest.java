package fr.tictak.dema.dto.in;

import java.util.List;

public record UploadPhotosRequest(List<String> photoLinks) {
}