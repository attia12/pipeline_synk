package fr.tictak.dema.exception;


import lombok.Getter;

@Getter
public class MissingPhoneNumberException extends RuntimeException {
    private final String userId;

    public MissingPhoneNumberException(String message, String userId) {
        super(message);
        this.userId = userId;
    }


}