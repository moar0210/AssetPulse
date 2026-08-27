package io.github.moar0210.assetpulse.security;

import io.github.moar0210.assetpulse.identity.DatabaseUserDetailsService;
import jakarta.servlet.DispatcherType;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

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
            ApiProblemWriter problemWriter)
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
                                                (request, response, authentication) ->
                                                        response.setStatus(
                                                                HttpStatus.NO_CONTENT.value())))
                .authorizeHttpRequests(
                        authorization ->
                                authorization
                                        .dispatcherTypeMatchers(DispatcherType.ERROR)
                                        .permitAll()
                                        .requestMatchers("/api/v1/status")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.GET, "/api/v1/session/csrf")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.POST, "/api/v1/session")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.DELETE, "/api/v1/session")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.GET, "/api/v1/session")
                                        .authenticated()
                                        .requestMatchers(HttpMethod.GET, "/api/v1/assets")
                                        .hasAnyRole("OPERATIONS_ADMIN", "TECHNICIAN", "VIEWER")
                                        .requestMatchers(HttpMethod.GET, "/api/v1/assets/{assetId}")
                                        .hasAnyRole("OPERATIONS_ADMIN", "TECHNICIAN", "VIEWER")
                                        .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/v1/sensors/{sensorId}/telemetry-readings")
                                        .hasAnyRole("OPERATIONS_ADMIN", "TECHNICIAN", "VIEWER")
                                        .requestMatchers(
                                                HttpMethod.GET,
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
                                        .authenticated()
                                        .anyRequest()
                                        .denyAll());

        return http.build();
    }
}
