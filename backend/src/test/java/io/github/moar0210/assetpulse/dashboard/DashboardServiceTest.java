package io.github.moar0210.assetpulse.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.moar0210.assetpulse.identity.AuthenticatedActor;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.access.AccessDeniedException;

class DashboardServiceTest {

    private static final UUID ORGANISATION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

    private final DashboardRepository repository = mock(DashboardRepository.class);
    private final DashboardService service = new DashboardService(repository);

    @ParameterizedTest
    @CsvSource({"OPERATIONS_ADMIN,false", "TECHNICIAN,true", "VIEWER,false"})
    void scopesOnlyTechnicianWorkWhileKeepingTheSharedOrganisationSummary(
            String roleCode, boolean technicianScoped) {
        when(repository.countSummary(ORGANISATION_ID, USER_ID, technicianScoped))
                .thenReturn(new DashboardRepository.DashboardCounts(2, 3, 4));
        when(repository.findRecentActivity(ORGANISATION_ID, USER_ID, technicianScoped, 5))
                .thenReturn(List.of());

        assertThat(service.get(actor(roleCode)))
                .isEqualTo(new DashboardResponse(2, 3, 4, List.of()));
        verify(repository).countSummary(ORGANISATION_ID, USER_ID, technicianScoped);
        verify(repository).findRecentActivity(ORGANISATION_ID, USER_ID, technicianScoped, 5);
    }

    @ParameterizedTest
    @CsvSource({"UNSUPPORTED", "''"})
    void rejectsUnadmittedRolesBeforeQueryingDashboardData(String roleCode) {
        assertThatThrownBy(() -> service.get(actor(roleCode)))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(repository);
    }

    private AuthenticatedActor actor(String roleCode) {
        return new AuthenticatedActor(
                USER_ID,
                "user@example.test",
                "Test User",
                "unused-password",
                ORGANISATION_ID,
                "test-organisation",
                "Test Organisation",
                roleCode,
                roleCode);
    }
}
