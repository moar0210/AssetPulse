package io.github.moar0210.assetpulse.identity;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.moar0210.assetpulse.audit.AuditService;
import io.github.moar0210.assetpulse.security.ApiProblemWriter;
import io.github.moar0210.assetpulse.security.SecurityConfiguration;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SessionController.class)
@Import({ApiProblemWriter.class, SecurityConfiguration.class})
class SessionSecurityAssuranceTest {

    private static final String CONTENT_SECURITY_POLICY =
            "default-src 'self'; base-uri 'self'; connect-src 'self'; font-src 'self'; form-action 'self'; frame-ancestors 'none'; img-src 'self' data:; object-src 'none'; script-src 'self'; style-src 'self'";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private SessionAuthenticationService authenticationService;
    @MockitoBean private DatabaseUserDetailsService userDetailsService;
    @MockitoBean private AuditService auditService;

    @Test
    void explicitPoliciesAndSpringSecurityDefaultsProtectResponses() throws Exception {
        mockMvc.perform(get("/api/v1/session/csrf"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", CONTENT_SECURITY_POLICY))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(
                        header().string(
                                        "Permissions-Policy",
                                        "camera=(), geolocation=(), microphone=()"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    void missingCsrfStillFailsBeforeTheLoginService() throws Exception {
        mockMvc.perform(
                        post("/api/v1/session")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(loginJson("user@example.test", "not-a-real-password")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));

        verifyNoInteractions(authenticationService);
    }

    @Test
    void rateLimitedResponsesAreGenericProblemsWithRetryAfter() throws Exception {
        doThrow(new LoginRateLimitExceededException(37))
                .when(authenticationService)
                .authenticate(
                        any(LoginRequest.class),
                        nullable(Authentication.class),
                        any(HttpServletRequest.class),
                        any(HttpServletResponse.class));

        for (String email : new String[] {"admin@northstar.example", "missing@example.invalid"}) {
            mockMvc.perform(
                            post("/api/v1/session")
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(loginJson(email, "sensitive-test-password")))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(
                            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("Retry-After", "37"))
                    .andExpect(jsonPath("$.status").value(429))
                    .andExpect(jsonPath("$.code").value("LOGIN_RATE_LIMITED"))
                    .andExpect(jsonPath("$.title").value("Too many sign-in attempts"))
                    .andExpect(
                            jsonPath("$.detail")
                                    .value("Too many sign-in attempts. Try again later."))
                    .andExpect(content().string(not(containsString(email))))
                    .andExpect(content().string(not(containsString("sensitive-test-password"))));
        }
    }

    @Test
    void credentialFailuresRemainGenericForKnownAndUnknownEmails() throws Exception {
        doThrow(new AuthenticationFailedException())
                .when(authenticationService)
                .authenticate(
                        any(LoginRequest.class),
                        nullable(Authentication.class),
                        any(HttpServletRequest.class),
                        any(HttpServletResponse.class));

        for (String email : new String[] {"admin@northstar.example", "missing@example.invalid"}) {
            mockMvc.perform(
                            post("/api/v1/session")
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(loginJson(email, "sensitive-test-password")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"))
                    .andExpect(jsonPath("$.detail").value("The email or password is incorrect."))
                    .andExpect(content().string(not(containsString(email))))
                    .andExpect(content().string(not(containsString("sensitive-test-password"))));
        }
    }

    private String loginJson(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }
}
