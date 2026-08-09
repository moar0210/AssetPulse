package io.github.moar0210.assetpulse.identity;

import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private static final String FIND_USER_BY_EMAIL =
            """
            SELECT
                u.id AS user_id,
                u.email,
                u.display_name,
                u.password_hash,
                o.id AS organisation_id,
                o.slug AS organisation_slug,
                o.name AS organisation_name,
                r.code AS role_code,
                r.display_name AS role_display_name
            FROM app_user u
            JOIN organisation o ON o.id = u.organisation_id
            JOIN app_role r ON r.code = u.role_code
            WHERE u.email = :email
            """;

    private final JdbcClient jdbcClient;

    public DatabaseUserDetailsService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        String normalizedEmail = username == null ? "" : username.strip().toLowerCase(Locale.ROOT);

        return jdbcClient
                .sql(FIND_USER_BY_EMAIL)
                .param("email", normalizedEmail)
                .query(
                        (resultSet, rowNumber) ->
                                new AuthenticatedActor(
                                        resultSet.getObject("user_id", java.util.UUID.class),
                                        resultSet.getString("email"),
                                        resultSet.getString("display_name"),
                                        resultSet.getString("password_hash"),
                                        resultSet.getObject(
                                                "organisation_id", java.util.UUID.class),
                                        resultSet.getString("organisation_slug"),
                                        resultSet.getString("organisation_name"),
                                        resultSet.getString("role_code"),
                                        resultSet.getString("role_display_name")))
                .optional()
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }
}
