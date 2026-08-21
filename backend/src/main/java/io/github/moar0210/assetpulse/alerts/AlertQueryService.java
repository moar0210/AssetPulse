package io.github.moar0210.assetpulse.alerts;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlertQueryService {

    private final AlertQueryRepository repository;

    public AlertQueryService(AlertQueryRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public AlertListResponse listForOrganisation(UUID organisationId, AlertListRequest request) {
        return new AlertListResponse(
                repository.findByOrganisationId(organisationId, request.limit()), request.limit());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AlertDetailResponse detailForOrganisation(UUID organisationId, UUID alertId) {
        return repository
                .findByOrganisationIdAndId(organisationId, alertId)
                .orElseThrow(AlertNotFoundException::new);
    }
}
