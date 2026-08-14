package io.github.moar0210.assetpulse.telemetry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public class TelemetryBatchFingerprint {

    public String calculate(TelemetryBatchRequest request) {
        MessageDigest digest = sha256();
        for (TelemetryBatchRequest.Reading reading : request.readings()) {
            update(digest, reading.sensorId().toString());
            update(digest, reading.value().stripTrailingZeros().toPlainString());
            update(digest, Long.toString(reading.observedAt().getEpochSecond()));
            update(digest, Integer.toString(reading.observedAt().getNano()));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
