package io.github.moar0210.assetpulse.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class LoginClientAddressResolver {

    static final String UNKNOWN_ADDRESS = "unknown";
    static final String TRUSTED_PROXY_CLIENT_ADDRESS_HEADER = "X-AssetPulse-Client-Address";

    private static final int MAX_ADDRESS_LENGTH = 64;
    private static final Pattern NUMERIC_ADDRESS = Pattern.compile("[0-9a-f:.]+(?:%[a-z0-9_.-]+)?");

    String resolve(HttpServletRequest request) {
        String remoteAddress = normalize(request.getRemoteAddr());
        if (isLoopback(remoteAddress)) {
            String proxiedAddress = request.getHeader(TRUSTED_PROXY_CLIENT_ADDRESS_HEADER);
            if (proxiedAddress != null) {
                return normalize(proxiedAddress);
            }
        }
        return remoteAddress;
    }

    private static String normalize(String address) {
        if (address == null) {
            return UNKNOWN_ADDRESS;
        }
        String normalized = address.strip().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()
                || normalized.length() > MAX_ADDRESS_LENGTH
                || !NUMERIC_ADDRESS.matcher(normalized).matches()) {
            return UNKNOWN_ADDRESS;
        }
        return normalized;
    }

    private static boolean isLoopback(String address) {
        return address.equals("127.0.0.1")
                || address.equals("::1")
                || address.equals("0:0:0:0:0:0:0:1");
    }
}
