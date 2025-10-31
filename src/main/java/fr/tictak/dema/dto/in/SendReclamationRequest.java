package fr.tictak.dema.dto.in;

import lombok.Data;

@Data
public class SendReclamationRequest {
    private String sentFromEmail;
    private String senderName;
    private String mailContent;
}