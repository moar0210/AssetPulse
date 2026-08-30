package io.github.moar0210.assetpulse.audit;

import io.github.moar0210.assetpulse.identity.AuthenticatedActor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditQueryService {

    private final AuditRepository repository;

    public AuditQueryService(AuditRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public AuditListResponse list(AuthenticatedActor actor, AuditListRequest request) {
        if (!"OPERATIONS_ADMIN".equals(actor.roleCode())) {
            throw new AccessDeniedException(
                    "Only operations administrators can inspect audit events");
        }
        return new AuditListResponse(
                repository.findByOrganisationId(actor.organisationId(), request.limit()),
                request.limit());
    }
}
