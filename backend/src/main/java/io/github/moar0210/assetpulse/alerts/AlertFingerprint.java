package io.github.moar0210.assetpulse.alerts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class AlertFingerprint {

    private static final String NAMESPACE = "assetpulse:threshold-alert:v1:";

    public String calculate(UUID organisationId, UUID thresholdRuleId) {
        Objects.requireNonNull(organisationId);
        Objects.requireNonNull(thresholdRuleId);

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] value =
                    (NAMESPACE + organisationId + ":" + thresholdRuleId)
                            .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(digest.digest(value));
        } catch (NoSuchAlgorithmException unavailableAlgorithm) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailableAlgorithm);
        }
    }
}
