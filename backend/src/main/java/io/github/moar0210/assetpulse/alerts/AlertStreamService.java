package io.github.moar0210.assetpulse.alerts;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionBindingListener;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongFunction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class AlertStreamService {

    private static final String READY_EVENT_NAME = "ready";
    private static final String ALERT_CHANGED_EVENT_NAME = "alert-changed";
    private static final String SESSION_SUBSCRIPTIONS = AlertStreamService.class.getName();

    private final long timeoutMillis;
    private final LongFunction<SseEmitter> emitterFactory;
    private final Executor notificationExecutor;
    private final Map<UUID, Set<Subscription>> subscriptionsByOrganisation =
            new ConcurrentHashMap<>();

    @Autowired
    public AlertStreamService(
            @Value("${assetpulse.alerts.stream.timeout-millis:1800000}") long timeoutMillis,
            @Qualifier(AlertStreamConfiguration.NOTIFICATION_EXECUTOR)
                    Executor notificationExecutor) {
        this(timeoutMillis, SseEmitter::new, notificationExecutor);
    }

    AlertStreamService(long timeoutMillis, LongFunction<SseEmitter> emitterFactory) {
        this(timeoutMillis, emitterFactory, Runnable::run);
    }

    AlertStreamService(
            long timeoutMillis,
            LongFunction<SseEmitter> emitterFactory,
            Executor notificationExecutor) {
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("Alert stream timeout must be positive");
        }
        this.timeoutMillis = timeoutMillis;
        this.emitterFactory = emitterFactory;
        this.notificationExecutor = notificationExecutor;
    }

    public SseEmitter subscribe(UUID organisationId, HttpSession session) {
        SessionSubscriptions sessionSubscriptions = subscriptionsFor(session);
        SseEmitter emitter = emitterFactory.apply(timeoutMillis);
        Subscription subscription = new Subscription(organisationId, emitter, sessionSubscriptions);
        emitter.onCompletion(subscription::remove);
        emitter.onTimeout(subscription::remove);
        emitter.onError(ignored -> subscription.remove());
        sessionSubscriptions.add(subscription);
        subscription.send(
                SseEmitter.event()
                        .name(READY_EVENT_NAME)
                        .data(Map.of(), MediaType.APPLICATION_JSON));
        return emitter;
    }

    public void publish(UUID organisationId, AlertChangeEvent change) {
        Set<Subscription> subscriptions = subscriptionsByOrganisation.get(organisationId);
        if (subscriptions == null) {
            return;
        }

        for (Subscription subscription : subscriptions) {
            try {
                notificationExecutor.execute(
                        () ->
                                subscription.send(
                                        SseEmitter.event()
                                                .name(ALERT_CHANGED_EVENT_NAME)
                                                .data(change, MediaType.APPLICATION_JSON)));
            } catch (RuntimeException schedulingFailure) {
                subscription.close();
            }
        }
    }

    int subscriberCount(UUID organisationId) {
        Set<Subscription> subscriptions = subscriptionsByOrganisation.get(organisationId);
        return subscriptions == null ? 0 : subscriptions.size();
    }

    @PreDestroy
    void closeAll() {
        subscriptionsByOrganisation.values().stream()
                .flatMap(Set::stream)
                .toList()
                .forEach(Subscription::close);
    }

    private SessionSubscriptions subscriptionsFor(HttpSession session) {
        if (session == null) {
            throw sessionRequired();
        }
        try {
            synchronized (session) {
                SessionSubscriptions subscriptions =
                        (SessionSubscriptions) session.getAttribute(SESSION_SUBSCRIPTIONS);
                if (subscriptions == null) {
                    subscriptions = new SessionSubscriptions();
                    session.setAttribute(SESSION_SUBSCRIPTIONS, subscriptions);
                }
                return subscriptions;
            }
        } catch (IllegalStateException expiredSession) {
            throw sessionRequired();
        }
    }

    private static AuthenticationCredentialsNotFoundException sessionRequired() {
        return new AuthenticationCredentialsNotFoundException("An active session is required");
    }

    private final class SessionSubscriptions implements HttpSessionBindingListener {

        private final Set<Subscription> subscriptions = new HashSet<>();
        private volatile boolean closed;

        synchronized void add(Subscription subscription) {
            if (closed) {
                throw sessionRequired();
            }
            subscriptions.add(subscription);
            subscriptionsByOrganisation.compute(
                    subscription.organisationId,
                    (ignored, existing) -> {
                        Set<Subscription> current =
                                existing == null ? ConcurrentHashMap.newKeySet() : existing;
                        current.add(subscription);
                        return current;
                    });
        }

        synchronized void remove(Subscription subscription) {
            subscriptions.remove(subscription);
        }

        @Override
        public void valueUnbound(HttpSessionBindingEvent event) {
            List<Subscription> ending;
            synchronized (this) {
                closed = true;
                ending = List.copyOf(subscriptions);
                subscriptions.clear();
            }
            ending.forEach(Subscription::close);
        }
    }

    private final class Subscription {

        private final UUID organisationId;
        private final SseEmitter emitter;
        private final SessionSubscriptions sessionSubscriptions;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final Object sendLock = new Object();

        private Subscription(
                UUID organisationId,
                SseEmitter emitter,
                SessionSubscriptions sessionSubscriptions) {
            this.organisationId = organisationId;
            this.emitter = emitter;
            this.sessionSubscriptions = sessionSubscriptions;
        }

        void send(SseEmitter.SseEventBuilder event) {
            try {
                synchronized (sendLock) {
                    // Check after waiting for earlier writes; logout never waits for this lock.
                    if (active.get() && !sessionSubscriptions.closed) {
                        emitter.send(event);
                    }
                }
            } catch (IOException | RuntimeException connectionFailure) {
                close();
            }
        }

        boolean remove() {
            if (!active.getAndSet(false)) {
                return false;
            }
            sessionSubscriptions.remove(this);
            subscriptionsByOrganisation.computeIfPresent(
                    organisationId,
                    (ignored, subscriptions) -> {
                        subscriptions.remove(this);
                        return subscriptions.isEmpty() ? null : subscriptions;
                    });
            return true;
        }

        void close() {
            if (!remove()) {
                return;
            }
            try {
                // SseEmitter completion shares its write lock with sends and may block.
                notificationExecutor.execute(
                        () -> {
                            try {
                                emitter.complete();
                            } catch (RuntimeException ignored) {
                                // The subscriber has already failed or disconnected.
                            }
                        });
            } catch (RuntimeException shuttingDown) {
                // Already revoked; container teardown or the stream timeout closes the transport.
            }
        }
    }
}
