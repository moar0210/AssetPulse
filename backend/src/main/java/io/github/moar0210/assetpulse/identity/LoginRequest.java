package io.github.moar0210.assetpulse.identity;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(max = 128) String password) {

    @Override
    public String toString() {
        return "LoginRequest[email=<redacted>, password=<redacted>]";
    }
}
