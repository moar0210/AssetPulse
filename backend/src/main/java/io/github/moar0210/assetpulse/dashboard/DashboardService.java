package io.github.moar0210.assetpulse.dashboard;

import io.github.moar0210.assetpulse.identity.AuthenticatedActor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardService {

    private static final int RECENT_ACTIVITY_LIMIT = 5;

    private final DashboardRepository repository;

    public DashboardService(DashboardRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DashboardResponse get(AuthenticatedActor actor) {
        boolean technicianScoped = technicianScoped(actor);
        DashboardRepository.DashboardCounts counts =
                repository.countSummary(actor.organisationId(), actor.userId(), technicianScoped);
        return new DashboardResponse(
                counts.assetCount(),
                counts.openAlertCount(),
                counts.activeWorkOrderCount(),
                repository.findRecentActivity(
                        actor.organisationId(),
                        actor.userId(),
                        technicianScoped,
                        RECENT_ACTIVITY_LIMIT));
    }

    private static boolean technicianScoped(AuthenticatedActor actor) {
        return switch (actor.roleCode()) {
            case "OPERATIONS_ADMIN", "VIEWER" -> false;
            case "TECHNICIAN" -> true;
            default -> throw new AccessDeniedException("The role cannot access the dashboard");
        };
    }
}
