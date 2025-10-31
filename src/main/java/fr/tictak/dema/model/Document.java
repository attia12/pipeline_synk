package fr.tictak.dema.model;

import fr.tictak.dema.model.enums.DocumentType;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@NoArgsConstructor
public class Document {

    private Long id;
    private DocumentType documentType;
    private String filePath;
    private Date uploadedAt;

    public String getFileName() {
        if (filePath == null) return null;
        int idx = filePath.lastIndexOf('/');
        return (idx != -1) ? filePath.substring(idx + 1) : filePath;
    }
}