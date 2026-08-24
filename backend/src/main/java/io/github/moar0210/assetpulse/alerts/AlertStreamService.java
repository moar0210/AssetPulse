package io.github.moar0210.assetpulse.alerts;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.LongFunction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class AlertStreamService {

    private static final String READY_EVENT_NAME = "ready";
    private static final String ALERT_CHANGED_EVENT_NAME = "alert-changed";

    private final long timeoutMillis;
    private final LongFunction<SseEmitter> emitterFactory;
    private final Executor notificationExecutor;
    private final Map<UUID, Set<SseEmitter>> emittersByOrganisation = new ConcurrentHashMap<>();

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

    public SseEmitter subscribe(UUID organisationId) {
        SseEmitter emitter = emitterFactory.apply(timeoutMillis);
        emittersByOrganisation.compute(
                organisationId,
                (ignored, organisationEmitters) -> {
                    Set<SseEmitter> current =
                            organisationEmitters == null
                                    ? ConcurrentHashMap.newKeySet()
                                    : organisationEmitters;
                    current.add(emitter);
                    return current;
                });

        Runnable remove = () -> remove(organisationId, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(ignored -> remove.run());

        try {
            emitter.send(
                    SseEmitter.event()
                            .name(READY_EVENT_NAME)
                            .data(Map.of(), MediaType.APPLICATION_JSON));
        } catch (IOException | RuntimeException connectionFailure) {
            remove.run();
            completeQuietly(emitter);
        }
        return emitter;
    }

    public void publish(UUID organisationId, AlertChangeEvent change) {
        Set<SseEmitter> organisationEmitters = emittersByOrganisation.get(organisationId);
        if (organisationEmitters == null) {
            return;
        }

        for (SseEmitter emitter : organisationEmitters) {
            try {
                notificationExecutor.execute(() -> send(organisationId, emitter, change));
            } catch (RuntimeException schedulingFailure) {
                remove(organisationId, emitter);
                completeQuietly(emitter);
            }
        }
    }

    int subscriberCount(UUID organisationId) {
        Set<SseEmitter> organisationEmitters = emittersByOrganisation.get(organisationId);
        return organisationEmitters == null ? 0 : organisationEmitters.size();
    }

    @PreDestroy
    void closeAll() {
        emittersByOrganisation.values().stream()
                .flatMap(Set::stream)
                .forEach(AlertStreamService::completeQuietly);
        emittersByOrganisation.clear();
    }

    private void remove(UUID organisationId, SseEmitter emitter) {
        emittersByOrganisation.compute(
                organisationId,
                (ignored, organisationEmitters) -> {
                    if (organisationEmitters == null) {
                        return null;
                    }
                    organisationEmitters.remove(emitter);
                    return organisationEmitters.isEmpty() ? null : organisationEmitters;
                });
    }

    private void send(UUID organisationId, SseEmitter emitter, AlertChangeEvent change) {
        try {
            emitter.send(
                    SseEmitter.event()
                            .name(ALERT_CHANGED_EVENT_NAME)
                            .data(change, MediaType.APPLICATION_JSON));
        } catch (IOException | RuntimeException notificationFailure) {
            remove(organisationId, emitter);
            completeQuietly(emitter);
        }
    }

    private static void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (RuntimeException ignored) {
            // The subscriber has already failed or disconnected.
        }
    }
}
