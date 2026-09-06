package io.github.moar0210.assetpulse.security;

import io.github.moar0210.assetpulse.audit.AuditAction;
import io.github.moar0210.assetpulse.audit.AuditService;
import io.github.moar0210.assetpulse.identity.AuthenticatedActor;
import io.github.moar0210.assetpulse.identity.DatabaseUserDetailsService;
import io.github.moar0210.assetpulse.observability.RequestLogFilter;
import jakarta.servlet.DispatcherType;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.transaction.TransactionException;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    AuthenticationManager authenticationManager(
            DatabaseUserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider authenticationProvider =
                new DaoAuthenticationProvider(userDetailsService);
        authenticationProvider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(authenticationProvider);
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    HttpSessionCsrfTokenRepository csrfTokenRepository() {
        HttpSessionCsrfTokenRepository repository = new HttpSessionCsrfTokenRepository();
        repository.setHeaderName("X-CSRF-TOKEN");
        return repository;
    }

    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy(
            HttpSessionCsrfTokenRepository csrfTokenRepository) {
        return new CompositeSessionAuthenticationStrategy(
                List.of(
                        new ChangeSessionIdAuthenticationStrategy(),
                        new CsrfAuthenticationStrategy(csrfTokenRepository)));
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityContextRepository securityContextRepository,
            HttpSessionCsrfTokenRepository csrfTokenRepository,
            ApiProblemWriter problemWriter,
            AuditService auditService)
            throws Exception {
        CsrfTokenRequestAttributeHandler csrfRequestHandler =
                new CsrfTokenRequestAttributeHandler();
        PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();

        http.securityContext(
                        security ->
                                security.requireExplicitSave(true)
                                        .securityContextRepository(securityContextRepository))
                .sessionManagement(
                        session ->
                                session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                                        .sessionFixation(fixation -> fixation.changeSessionId()))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .headers(
                        headers ->
                                headers.contentSecurityPolicy(
                                                policy ->
                                                        policy.policyDirectives(
                                                                "default-src 'self'; base-uri 'self'; connect-src 'self'; font-src 'self'; form-action 'self'; frame-ancestors 'none'; img-src 'self' data:; object-src 'none'; script-src 'self'; style-src 'self'"))
                                        .referrerPolicy(
                                                policy -> policy.policy(ReferrerPolicy.NO_REFERRER))
                                        .addHeaderWriter(
                                                new StaticHeadersWriter(
                                                        "Permissions-Policy",
                                                        "camera=(), geolocation=(), microphone=()")))
                .csrf(
                        csrf ->
                                csrf.csrfTokenRepository(csrfTokenRepository)
                                        .csrfTokenRequestHandler(csrfRequestHandler)
                                        .ignoringRequestMatchers(paths.matcher("/api/v1/status")))
                .exceptionHandling(
                        exceptions ->
                                exceptions
                                        .authenticationEntryPoint(
                                                (request, response, exception) ->
                                                        problemWriter.write(
                                                                request,
                                                                response,
                                                                HttpStatus.UNAUTHORIZED.value(),
                                                                "AUTHENTICATION_REQUIRED",
                                                                "Authentication required",
                                                                "Sign in to access this resource."))
                                        .accessDeniedHandler(
                                                (request, response, exception) -> {
                                                    boolean csrfFailure =
                                                            exception
                                                                            instanceof
                                                                            InvalidCsrfTokenException
                                                                    || exception
                                                                            instanceof
                                                                            MissingCsrfTokenException;
                                                    problemWriter.write(
                                                            request,
                                                            response,
                                                            HttpStatus.FORBIDDEN.value(),
                                                            csrfFailure
                                                                    ? "CSRF_REJECTED"
                                                                    : "ACCESS_DENIED",
                                                            csrfFailure
                                                                    ? "Request verification failed"
                                                                    : "Access denied",
                                                            csrfFailure
                                                                    ? "The request could not be verified."
                                                                    : "The authenticated user cannot access this resource.");
                                                }))
                .logout(
                        logout ->
                                logout.logoutRequestMatcher(
                                                paths.matcher(HttpMethod.DELETE, "/api/v1/session"))
                                        .invalidateHttpSession(true)
                                        .clearAuthentication(true)
                                        .deleteCookies("ASSETPULSE_SESSION")
                                        .logoutSuccessHandler(
                                                (request, response, authentication) -> {
                                                    if (authentication != null
                                                            && authentication.isAuthenticated()
                                                            && authentication.getPrincipal()
                                                                    instanceof
                                                                    AuthenticatedActor actor) {
                                                        try {
                                                            auditService.record(
                                                                    actor.organisationId(),
                                                                    actor.userId(),
                                                                    AuditAction.SESSION_ENDED,
                                                                    actor.userId(),
                                                                    CorrelationIdFilter.from(
                                                                            request));
                                                        } catch (DataAccessException
                                                                | TransactionException exception) {
                                                            problemWriter.write(
                                                                    request,
                                                                    response,
                                                                    HttpStatus.SERVICE_UNAVAILABLE
                                                                            .value(),
                                                                    "AUDIT_UNAVAILABLE",
                                                                    "Audit unavailable",
                                                                    "The session ended, but its audit record could not be saved.");
                                                            return;
                                                        }
                                                    }
                                                    response.setHeader(
                                                            HttpHeaders.CACHE_CONTROL, "no-store");
                                                    response.setStatus(
                                                            HttpStatus.NO_CONTENT.value());
                                                }))
                .authorizeHttpRequests(
                        authorization ->
                                authorization
                                        .dispatcherTypeMatchers(DispatcherType.ERROR)
                                        .permitAll()
                                        .requestMatchers("/api/v1/status")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.GET, "/api/v1/session/csrf")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.HEAD, "/api/v1/session/csrf")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.POST, "/api/v1/session")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.DELETE, "/api/v1/session")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.GET, "/api/v1/session")
                                        .authenticated()
                                        .requestMatchers(HttpMethod.HEAD, "/api/v1/session")
                                        .authenticated()
                                        .requestMatchers(HttpMethod.GET, "/api/v1/dashboard")
                                        .hasAnyRole("OPERATIONS_ADMIN", "TECHNICIAN", "VIEWER")
                                        .requestMatchers(HttpMethod.HEAD, "/api/v1/dashboard")
                                        .hasAnyRole("OPERATIONS_ADMIN", "TECHNICIAN", "VIEWER")
                                        .requestMatchers(HttpMethod.POST, "/api/v1/demo/reset")
                                        .hasRole("OPERATIONS_ADMIN")
                                        .requestMatchers(HttpMethod.GET, "/api/v1/audit-events")
                                        .hasRole("OPERATIONS_ADMIN")
                                        .requestMatchers(HttpMethod.HEAD, "/api/v1/audit-events")
                                        .hasRole("OPERATIONS_ADMIN")
                                        .requestMatchers(HttpMethod.GET, "/api/v1/assets")
                                        .hasAnyRole("OPERATIONS_ADMIN", "TECHNICIAN", "VIEWER")
                                        .requestMatchers(HttpMethod.GET, "/api/v1/assets/{assetId}")
                                        .hasAnyRole("OPERATIONS_ADMIN", "TECHNICIAN", "VIEWER")
                                        .requestMatchers(
                                                HttpMethod.HEAD,
                                                "/api/v1/assets",
                                                "/api/v1/assets/{assetId}")
                                        .hasAnyRole("OPERATIONS_ADMIN", "TECHNICIAN", "VIEWER")
                                        .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/v1/sensors/{sensorId}/telemetry-readings")
                                        .hasAnyRole("OPERATIONS_ADMIN", "TECHNICIAN", "VIEWER")
                                        .requestMatchers(
                                                HttpMethod.HEAD,
                                                "/api/v1/sensors/{sensorId}/telemetry-readings")
                                        .hasAnyRole("OPERATIONS_ADMIN", "TECHNICIAN", "VIEWER")
                                        .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/v1/alerts",
                                                "/api/v1/alerts/stream",
                                                "/api/v1/alerts/{alertId}")
                                        .hasAnyRole("OPERATIONS_ADMIN", "TECHNICIAN", "VIEWER")
                                        .requestMatchers(
                                                HttpMethod.HEAD,
                                                "/api/v1/alerts",
                                                "/api/v1/alerts/stream",
                                                "/api/v1/alerts/{alertId}")
                                        .hasAnyRole("OPERATIONS_ADMIN", "TECHNICIAN", "VIEWER")
                                        .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/v1/alerts/{alertId}/acknowledge",
                                                "/api/v1/alerts/{alertId}/resolve")
                                        .hasRole("OPERATIONS_ADMIN")
                                        .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/v1/work-orders/eligible-technicians")
                                        .hasRole("OPERATIONS_ADMIN")
                                        .requestMatchers(
                                                HttpMethod.HEAD,
                                                "/api/v1/work-orders/eligible-technicians")
                                        .hasRole("OPERATIONS_ADMIN")
                                        .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/v1/work-orders",
                                                "/api/v1/work-orders/{workOrderId}")
                                        .hasAnyRole("OPERATIONS_ADMIN", "TECHNICIAN", "VIEWER")
                                        .requestMatchers(
                                                HttpMethod.HEAD,
                                                "/api/v1/work-orders",
                                                "/api/v1/work-orders/{workOrderId}")
                                        .hasAnyRole("OPERATIONS_ADMIN", "TECHNICIAN", "VIEWER")
                                        .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/v1/work-orders",
                                                "/api/v1/work-orders/{workOrderId}/assign")
                                        .hasRole("OPERATIONS_ADMIN")
                                        .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/v1/work-orders/{workOrderId}/start",
                                                "/api/v1/work-orders/{workOrderId}/complete")
                                        .hasRole("TECHNICIAN")
                                        .requestMatchers(
                                                HttpMethod.GET, "/api/v1/processing-events/dead")
                                        .hasRole("OPERATIONS_ADMIN")
                                        .requestMatchers(
                                                HttpMethod.HEAD, "/api/v1/processing-events/dead")
                                        .hasRole("OPERATIONS_ADMIN")
                                        .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/v1/processing-events/{eventId}/retry")
                                        .hasRole("OPERATIONS_ADMIN")
                                        .requestMatchers(
                                                HttpMethod.POST, "/api/v1/telemetry-batches")
                                        .hasRole("OPERATIONS_ADMIN")
                                        .requestMatchers("/api/**")
                                        .denyAll()
                                        .anyRequest()
                                        .denyAll());

        http.addFilterAfter(new RequestLogFilter(), SecurityContextHolderFilter.class);

        return http.build();
    }
}
