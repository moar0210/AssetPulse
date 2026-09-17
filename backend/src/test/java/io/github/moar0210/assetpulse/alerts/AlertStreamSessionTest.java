package io.github.moar0210.assetpulse.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class AlertStreamSessionTest {

    private static final UUID NORTHSTAR_ID = UUID.randomUUID();
    private static final UUID RIVERSIDE_ID = UUID.randomUUID();
    private static final AlertChangeEvent FIRST_CHANGE =
            new AlertChangeEvent(UUID.randomUUID(), AlertChangeType.OCCURRENCE_RECORDED);
    private static final AlertChangeEvent SECOND_CHANGE =
            new AlertChangeEvent(UUID.randomUUID(), AlertChangeType.STATUS_CHANGED);

    @Test
    @DisplayName("AUTH-04, ALR-03: a missing or invalid session cannot register a stream")
    void rejectsMissingAndAlreadyInvalidSessionsBeforeCreatingAnEmitter() {
        AtomicInteger created = new AtomicInteger();
        AlertStreamService service =
                new AlertStreamService(
                        30_000,
                        ignored -> {
                            created.incrementAndGet();
                            return new CapturingEmitter();
                        });
        MockHttpSession invalid = new MockHttpSession();
        invalid.invalidate();

        assertThatThrownBy(() -> service.subscribe(NORTHSTAR_ID, null))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(() -> service.subscribe(NORTHSTAR_ID, invalid))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);

        assertThat(created).hasValue(0);
        assertThat(service.subscriberCount(NORTHSTAR_ID)).isZero();
    }

    @Test
    @DisplayName("AUTH-04, ALR-03: invalidation during stream creation prevents registration")
    void invalidationBetweenSessionLookupAndRegistrationLeavesNoSubscriber() {
        MockHttpSession session = new MockHttpSession();
        CapturingEmitter emitter = new CapturingEmitter();
        AlertStreamService service =
                new AlertStreamService(
                        30_000,
                        ignored -> {
                            session.invalidate();
                            return emitter;
                        });

        assertThatThrownBy(() -> service.subscribe(NORTHSTAR_ID, session))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);

        assertThat(service.subscriberCount(NORTHSTAR_ID)).isZero();
        assertThat(emitter.sendCount).hasValue(0);
        service.publish(NORTHSTAR_ID, FIRST_CHANGE);
        assertThat(emitter.changes).isEmpty();
    }

    @Test
    @DisplayName("AUTH-04, ALR-03: session ID rotation preserves subsequent stream revocation")
    void rotatedSessionIdStillOwnsAndRevokesItsSubscription() {
        MockHttpSession session = new MockHttpSession();
        String originalId = session.getId();
        CapturingEmitter emitter = new CapturingEmitter();
        AlertStreamService service = new AlertStreamService(30_000, ignored -> emitter);
        service.subscribe(NORTHSTAR_ID, session);

        session.changeSessionId();
        service.publish(NORTHSTAR_ID, FIRST_CHANGE);

        assertThat(session.getId()).isNotEqualTo(originalId);
        assertThat(emitter.changes).containsExactly(FIRST_CHANGE);
        assertThat(service.subscriberCount(NORTHSTAR_ID)).isOne();
        session.invalidate();
        service.publish(NORTHSTAR_ID, SECOND_CHANGE);
        assertThat(service.subscriberCount(NORTHSTAR_ID)).isZero();
        assertThat(emitter.changes).containsExactly(FIRST_CHANGE);
        assertThat(emitter.completions).hasValue(1);
    }

    @Test
    @DisplayName("AUTH-04, ALR-03: queued changes are discarded after session invalidation")
    void queuedChangesCannotReachTheRevokedSession() {
        MockHttpSession session = new MockHttpSession();
        CapturingEmitter emitter = new CapturingEmitter();
        Queue<Runnable> notifications = new ArrayDeque<>();
        AlertStreamService service =
                new AlertStreamService(30_000, ignored -> emitter, notifications::add);
        service.subscribe(NORTHSTAR_ID, session);
        service.publish(NORTHSTAR_ID, FIRST_CHANGE);
        service.publish(NORTHSTAR_ID, SECOND_CHANGE);

        session.invalidate();

        assertThat(service.subscriberCount(NORTHSTAR_ID)).isZero();
        assertThat(emitter.completions).hasValue(0);
        assertThat(notifications).hasSize(3);
        drain(notifications);
        assertThat(emitter.changes).isEmpty();
        assertThat(emitter.completions).hasValue(1);
        service.publish(NORTHSTAR_ID, FIRST_CHANGE);
        assertThat(notifications).isEmpty();
    }

    @Test
    @DisplayName("AUTH-04, ALR-03: a blocked socket write cannot delay session revocation")
    void revocationDoesNotWaitForWritesAndDropsAWaitingChange() throws Exception {
        MockHttpSession endingSession = new MockHttpSession();
        BlockingEmitter slow = new BlockingEmitter();
        CapturingEmitter healthy = new CapturingEmitter();
        Queue<SseEmitter> emitters = new ArrayDeque<>(List.of(slow, healthy));
        LinkedBlockingQueue<Runnable> notifications = new LinkedBlockingQueue<>();
        AlertStreamService service =
                new AlertStreamService(30_000, ignored -> emitters.remove(), notifications::add);
        ExecutorService workers = Executors.newFixedThreadPool(4);
        service.subscribe(NORTHSTAR_ID, endingSession);

        try {
            service.publish(NORTHSTAR_ID, FIRST_CHANGE);
            Future<?> firstWrite = workers.submit(notifications.remove());
            assertThat(slow.sendStarted.await(2, TimeUnit.SECONDS)).isTrue();

            service.publish(NORTHSTAR_ID, SECOND_CHANGE);
            Runnable secondNotification = notifications.remove();
            AtomicReference<Thread> secondWriter = new AtomicReference<>();
            Future<?> waitingWrite =
                    workers.submit(
                            () -> {
                                secondWriter.set(Thread.currentThread());
                                secondNotification.run();
                            });
            await().atMost(Duration.ofSeconds(2))
                    .until(
                            () ->
                                    secondWriter.get() != null
                                            && secondWriter.get().getState()
                                                    == Thread.State.BLOCKED);
            service.subscribe(NORTHSTAR_ID, new MockHttpSession());

            workers.submit(endingSession::invalidate).get(2, TimeUnit.SECONDS);

            assertThat(service.subscriberCount(NORTHSTAR_ID)).isOne();
            assertThat(firstWrite.isDone()).isFalse();
            assertThat(waitingWrite.isDone()).isFalse();
            Future<?> completion = workers.submit(notifications.remove());
            assertThat(slow.completionStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(completion.isDone()).isFalse();
            service.publish(NORTHSTAR_ID, SECOND_CHANGE);
            workers.submit(notifications.remove()).get(2, TimeUnit.SECONDS);
            assertThat(healthy.changes).containsExactly(SECOND_CHANGE);

            slow.allowSend.countDown();
            firstWrite.get(2, TimeUnit.SECONDS);
            waitingWrite.get(2, TimeUnit.SECONDS);
            completion.get(2, TimeUnit.SECONDS);
            assertThat(slow.changes).containsExactly(FIRST_CHANGE);
            assertThat(slow.completions).hasValue(1);
        } finally {
            slow.allowSend.countDown();
            service.closeAll();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
            drain(notifications);
        }
    }

    @Test
    @DisplayName("ALR-03: shutdown removes subscribers before queued changes or invalidation run")
    void shutdownDropsQueuedChangesAndLaterInvalidationDoesNotCompleteTwice() {
        MockHttpSession northstarSession = new MockHttpSession();
        MockHttpSession riversideSession = new MockHttpSession();
        CapturingEmitter northstar = new CapturingEmitter();
        CapturingEmitter riverside = new CapturingEmitter();
        Queue<SseEmitter> emitters = new ArrayDeque<>(List.of(northstar, riverside));
        Queue<Runnable> notifications = new ArrayDeque<>();
        AlertStreamService service =
                new AlertStreamService(30_000, ignored -> emitters.remove(), notifications::add);
        service.subscribe(NORTHSTAR_ID, northstarSession);
        service.subscribe(RIVERSIDE_ID, riversideSession);
        service.publish(NORTHSTAR_ID, FIRST_CHANGE);
        service.publish(RIVERSIDE_ID, SECOND_CHANGE);

        service.closeAll();
        northstarSession.invalidate();
        riversideSession.invalidate();
        service.closeAll();

        assertThat(service.subscriberCount(NORTHSTAR_ID)).isZero();
        assertThat(service.subscriberCount(RIVERSIDE_ID)).isZero();
        assertThat(notifications).hasSize(4);
        drain(notifications);
        assertThat(northstar.changes).isEmpty();
        assertThat(riverside.changes).isEmpty();
        assertThat(northstar.completions).hasValue(1);
        assertThat(riverside.completions).hasValue(1);
    }

    private static void drain(Queue<Runnable> tasks) {
        while (!tasks.isEmpty()) {
            tasks.remove().run();
        }
    }

    private static class CapturingEmitter extends SseEmitter {

        final List<AlertChangeEvent> changes = new CopyOnWriteArrayList<>();
        final AtomicInteger sendCount = new AtomicInteger();
        final AtomicInteger completions = new AtomicInteger();

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            sendCount.incrementAndGet();
            builder.build().stream()
                    .map(ResponseBodyEmitter.DataWithMediaType::getData)
                    .filter(AlertChangeEvent.class::isInstance)
                    .map(AlertChangeEvent.class::cast)
                    .forEach(changes::add);
        }

        @Override
        public void complete() {
            completions.incrementAndGet();
        }
    }

    private static final class BlockingEmitter extends CapturingEmitter {

        private final ReentrantLock transportLock = new ReentrantLock();
        private final CountDownLatch sendStarted = new CountDownLatch(1);
        private final CountDownLatch allowSend = new CountDownLatch(1);
        private final CountDownLatch completionStarted = new CountDownLatch(1);

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            transportLock.lock();
            try {
                if (sendCount.get() == 1) {
                    sendStarted.countDown();
                    try {
                        if (!allowSend.await(10, TimeUnit.SECONDS)) {
                            throw new IOException("Timed out waiting to release the socket write");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException(interrupted);
                    }
                }
                super.send(builder);
            } finally {
                transportLock.unlock();
            }
        }

        @Override
        public void complete() {
            completionStarted.countDown();
            transportLock.lock();
            try {
                super.complete();
            } finally {
                transportLock.unlock();
            }
        }
    }
}
