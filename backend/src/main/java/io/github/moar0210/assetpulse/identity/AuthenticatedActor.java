package io.github.moar0210.assetpulse.identity;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public final class AuthenticatedActor implements UserDetails, CredentialsContainer, Serializable {

    @Serial private static final long serialVersionUID = 1L;

    private final UUID userId;
    private final String email;
    private final String displayName;
    private final UUID organisationId;
    private final String organisationSlug;
    private final String organisationName;
    private final String roleCode;
    private final String roleDisplayName;
    private final List<GrantedAuthority> authorities;
    private String passwordHash;

    public AuthenticatedActor(
            UUID userId,
            String email,
            String displayName,
            String passwordHash,
            UUID organisationId,
            String organisationSlug,
            String organisationName,
            String roleCode,
            String roleDisplayName) {
        this.userId = Objects.requireNonNull(userId);
        this.email = Objects.requireNonNull(email);
        this.displayName = Objects.requireNonNull(displayName);
        this.passwordHash = Objects.requireNonNull(passwordHash);
        this.organisationId = Objects.requireNonNull(organisationId);
        this.organisationSlug = Objects.requireNonNull(organisationSlug);
        this.organisationName = Objects.requireNonNull(organisationName);
        this.roleCode = Objects.requireNonNull(roleCode);
        this.roleDisplayName = Objects.requireNonNull(roleDisplayName);
        this.authorities = List.of(new SimpleGrantedAuthority("ROLE_" + roleCode));
    }

    public UUID userId() {
        return userId;
    }

    public String email() {
        return email;
    }

    public String displayName() {
        return displayName;
    }

    public UUID organisationId() {
        return organisationId;
    }

    public String organisationSlug() {
        return organisationSlug;
    }

    public String organisationName() {
        return organisationName;
    }

    public String roleCode() {
        return roleCode;
    }

    public String roleDisplayName() {
        return roleDisplayName;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public void eraseCredentials() {
        passwordHash = null;
    }

    @Override
    public boolean equals(Object candidate) {
        return this == candidate
                || candidate instanceof AuthenticatedActor actor && userId.equals(actor.userId);
    }

    @Override
    public int hashCode() {
        return userId.hashCode();
    }
}
