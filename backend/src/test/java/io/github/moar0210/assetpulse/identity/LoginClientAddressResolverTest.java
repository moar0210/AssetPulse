package io.github.moar0210.assetpulse.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class LoginClientAddressResolverTest {

    private final LoginClientAddressResolver resolver = new LoginClientAddressResolver();

    @Test
    void ignoresForwardingHeadersFromNonLoopbackCallers() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("2001:DB8::10");
        request.addHeader("Forwarded", "for=198.51.100.1");
        request.addHeader("X-Forwarded-For", "198.51.100.2");
        request.addHeader(
                LoginClientAddressResolver.TRUSTED_PROXY_CLIENT_ADDRESS_HEADER, "198.51.100.3");

        assertThat(resolver.resolve(request)).isEqualTo("2001:db8::10");
    }

    @Test
    void usesTheBoundedClientAddressReplacedByTheLoopbackProxy() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "198.51.100.1, 198.51.100.2");
        request.addHeader(
                LoginClientAddressResolver.TRUSTED_PROXY_CLIENT_ADDRESS_HEADER, "2001:DB8::10");

        assertThat(resolver.resolve(request)).isEqualTo("2001:db8::10");
    }

    @Test
    void rejectsAnInvalidAddressFromTheLoopbackProxy() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("::1");
        request.addHeader(
                LoginClientAddressResolver.TRUSTED_PROXY_CLIENT_ADDRESS_HEADER,
                "198.51.100.1, 198.51.100.2");

        assertThat(resolver.resolve(request)).isEqualTo(LoginClientAddressResolver.UNKNOWN_ADDRESS);
    }

    @Test
    void replacesUnexpectedOrUnboundedAddressesWithOneSafeBucket() {
        MockHttpServletRequest unexpected = new MockHttpServletRequest();
        unexpected.setRemoteAddr("client-controlled.example");
        MockHttpServletRequest oversized = new MockHttpServletRequest();
        oversized.setRemoteAddr("1".repeat(65));

        assertThat(resolver.resolve(unexpected))
                .isEqualTo(LoginClientAddressResolver.UNKNOWN_ADDRESS);
        assertThat(resolver.resolve(oversized))
                .isEqualTo(LoginClientAddressResolver.UNKNOWN_ADDRESS);
    }
}
