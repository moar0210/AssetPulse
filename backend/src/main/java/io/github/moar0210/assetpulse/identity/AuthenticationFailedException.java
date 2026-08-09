package io.github.moar0210.assetpulse.identity;

public class AuthenticationFailedException extends RuntimeException {

    public AuthenticationFailedException() {
        super("Authentication failed");
    }
}
