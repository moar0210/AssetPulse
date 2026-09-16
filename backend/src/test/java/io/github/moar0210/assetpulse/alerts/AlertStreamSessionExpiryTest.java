package io.github.moar0210.assetpulse.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import org.apache.catalina.Context;
import org.apache.catalina.Manager;
import org.apache.catalina.session.StandardSession;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class AlertStreamSessionExpiryTest {

    private static final UUID ORGANISATION_ID = UUID.randomUUID();

    @Test
    void idleSessionExpiryRevokesItsStreamAndQueuedChangesWithoutAffectingAnotherSession()
            throws Exception {
        StandardSession expiringSession = session();
        StandardSession activeSession = session();
        SseEmitter expiringEmitter = mock(SseEmitter.class);
        SseEmitter activeEmitter = mock(SseEmitter.class);
        Queue<SseEmitter> emitters = new ArrayDeque<>(List.of(expiringEmitter, activeEmitter));
        Queue<Runnable> notifications = new ArrayDeque<>();
        AlertStreamService service =
                new AlertStreamService(30_000, ignored -> emitters.remove(), notifications::add);
        service.subscribe(ORGANISATION_ID, expiringSession.getSession());
        service.subscribe(ORGANISATION_ID, activeSession.getSession());
        AlertChangeEvent change =
                new AlertChangeEvent(UUID.randomUUID(), AlertChangeType.STATUS_CHANGED);
        service.publish(ORGANISATION_ID, change);

        expiringSession.setCreationTime(System.currentTimeMillis() - 120_000);

        assertThat(expiringSession.isValid()).isFalse();
        assertThat(activeSession.isValid()).isTrue();
        assertThat(service.subscriberCount(ORGANISATION_ID)).isOne();
        drain(notifications);
        verify(expiringEmitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(expiringEmitter).complete();
        verify(activeEmitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(activeEmitter, never()).complete();

        service.publish(ORGANISATION_ID, change);
        drain(notifications);

        verify(expiringEmitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(activeEmitter, times(3)).send(any(SseEmitter.SseEventBuilder.class));
        service.closeAll();
        drain(notifications);
    }

    @Test
    void expiryDuringTheFirstSessionAttributeBindingCannotLeaveASubscription() throws Exception {
        Context context = mock(Context.class);
        StandardSession expiringSession = session(context);
        when(context.getDistributable())
                .thenAnswer(
                        ignored -> {
                            expiringSession.setCreationTime(System.currentTimeMillis() - 120_000);
                            assertThat(expiringSession.isValid()).isFalse();
                            return false;
                        });
        SseEmitter emitter = mock(SseEmitter.class);
        AlertStreamService service = new AlertStreamService(30_000, ignored -> emitter);

        assertThatThrownBy(() -> service.subscribe(ORGANISATION_ID, expiringSession.getSession()))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);

        assertThat(service.subscriberCount(ORGANISATION_ID)).isZero();
        service.publish(
                ORGANISATION_ID,
                new AlertChangeEvent(UUID.randomUUID(), AlertChangeType.STATUS_CHANGED));
        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    private static StandardSession session() {
        return session(mock(Context.class));
    }

    private static StandardSession session(Context context) {
        Manager manager = mock(Manager.class);
        when(manager.getContext()).thenReturn(context);
        StandardSession session = new StandardSession(manager);
        session.setId(UUID.randomUUID().toString(), false);
        session.setCreationTime(System.currentTimeMillis());
        session.setMaxInactiveInterval(60);
        session.setValid(true);
        return session;
    }

    private static void drain(Queue<Runnable> notifications) {
        while (!notifications.isEmpty()) {
            notifications.remove().run();
        }
    }
}
