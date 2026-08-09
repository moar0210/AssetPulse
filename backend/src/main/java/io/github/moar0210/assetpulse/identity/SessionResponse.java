package io.github.moar0210.assetpulse.identity;

import java.util.UUID;

public record SessionResponse(
        UUID userId,
        String displayName,
        String email,
        OrganisationResponse organisation,
        RoleResponse role) {

    public static SessionResponse from(AuthenticatedActor actor) {
        return new SessionResponse(
                actor.userId(),
                actor.displayName(),
                actor.email(),
                new OrganisationResponse(
                        actor.organisationId(), actor.organisationSlug(), actor.organisationName()),
                new RoleResponse(actor.roleCode(), actor.roleDisplayName()));
    }

    public record OrganisationResponse(UUID id, String slug, String name) {}

    public record RoleResponse(String code, String displayName) {}
}
