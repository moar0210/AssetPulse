package io.github.moar0210.assetpulse.identity;

public class AuthenticationUnavailableException extends RuntimeException {

    public AuthenticationUnavailableException() {
        super("Authentication is unavailable");
    }
}
