package io.github.moar0210.assetpulse.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class AlertStreamServiceTest {

    private static final UUID NORTHSTAR_ID = UUID.randomUUID();
    private static final UUID RIVERSIDE_ID = UUID.randomUUID();
    private static final UUID ALERT_ID = UUID.randomUUID();

    @Test
    void publishesTheExplicitInvalidationOnlyToTheMatchingOrganisation() {
        CapturingEmitter northstar = new CapturingEmitter();
        CapturingEmitter riverside = new CapturingEmitter();
        AlertStreamService service = serviceWith(northstar, riverside);

        service.subscribe(NORTHSTAR_ID);
        service.subscribe(RIVERSIDE_ID);
        service.publish(
                NORTHSTAR_ID, new AlertChangeEvent(ALERT_ID, AlertChangeType.OCCURRENCE_RECORDED));

        assertThat(northstar.eventNames()).containsExactly("event:ready", "event:alert-changed");
        assertThat(northstar.eventPayloads())
                .containsExactly(
                        new AlertChangeEvent(ALERT_ID, AlertChangeType.OCCURRENCE_RECORDED));
        assertThat(riverside.eventNames()).containsExactly("event:ready");
        assertThat(riverside.eventPayloads()).isEmpty();
        assertThat(service.subscriberCount(NORTHSTAR_ID)).isOne();
        assertThat(service.subscriberCount(RIVERSIDE_ID)).isOne();
    }

    @Test
    void aFailingSubscriberIsRemovedWithoutBlockingHealthySubscribers() {
        FailingEmitter failed = new FailingEmitter(2);
        CapturingEmitter healthy = new CapturingEmitter();
        AlertStreamService service = serviceWith(failed, healthy);
        service.subscribe(NORTHSTAR_ID);
        service.subscribe(NORTHSTAR_ID);
        AlertChangeEvent change = new AlertChangeEvent(ALERT_ID, AlertChangeType.STATUS_CHANGED);

        assertThatCode(() -> service.publish(NORTHSTAR_ID, change)).doesNotThrowAnyException();

        assertThat(healthy.eventPayloads()).containsExactly(change);
        assertThat(service.subscriberCount(NORTHSTAR_ID)).isOne();
        assertThat(failed.completed).isTrue();
    }

    @Test
    void anEmitterThatCannotOpenIsNotRetained() {
        FailingEmitter failed = new FailingEmitter(1);
        AlertStreamService service = serviceWith(failed);

        assertThatCode(() -> service.subscribe(NORTHSTAR_ID)).doesNotThrowAnyException();

        assertThat(service.subscriberCount(NORTHSTAR_ID)).isZero();
        assertThat(failed.completed).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void completionTimeoutAndErrorCallbacksEachRemoveTheirEmitter() throws Exception {
        SseEmitter completed = mock(SseEmitter.class);
        SseEmitter timedOut = mock(SseEmitter.class);
        SseEmitter failed = mock(SseEmitter.class);
        AlertStreamService service = serviceWith(completed, timedOut, failed);

        service.subscribe(NORTHSTAR_ID);
        service.subscribe(NORTHSTAR_ID);
        service.subscribe(NORTHSTAR_ID);
        assertThat(service.subscriberCount(NORTHSTAR_ID)).isEqualTo(3);

        ArgumentCaptor<Runnable> completion = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Runnable> timeout = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Consumer<Throwable>> error = ArgumentCaptor.forClass(Consumer.class);
        verify(completed).onCompletion(completion.capture());
        verify(timedOut).onTimeout(timeout.capture());
        verify(failed).onError(error.capture());

        completion.getValue().run();
        timeout.getValue().run();
        error.getValue().accept(new IOException("disconnected"));

        assertThat(service.subscriberCount(NORTHSTAR_ID)).isZero();
        verify(completed, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(timedOut, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(failed, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void aSlowSubscriberCannotDelayAHealthySubscriber() throws Exception {
        SlowEmitter slow = new SlowEmitter();
        CapturingEmitter healthy = new CapturingEmitter();
        Queue<SseEmitter> available = new ArrayDeque<>(List.of(slow, healthy));
        ExecutorService notifications = Executors.newVirtualThreadPerTaskExecutor();
        AlertStreamService service =
                new AlertStreamService(30_000, ignored -> available.remove(), notifications);
        service.subscribe(NORTHSTAR_ID);
        service.subscribe(NORTHSTAR_ID);

        try {
            service.publish(
                    NORTHSTAR_ID, new AlertChangeEvent(ALERT_ID, AlertChangeType.STATUS_CHANGED));

            assertThat(slow.sendStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(awaitPayloadCount(healthy, 1)).isTrue();
        } finally {
            slow.allowSend.countDown();
            notifications.shutdownNow();
        }
    }

    @Test
    void publicationQueuesSocketWorkInsteadOfSendingOnTheCallingThread() {
        CapturingEmitter emitter = new CapturingEmitter();
        Queue<Runnable> notifications = new ArrayDeque<>();
        AlertStreamService service =
                new AlertStreamService(30_000, ignored -> emitter, notifications::add);
        service.subscribe(NORTHSTAR_ID);
        AlertChangeEvent change = new AlertChangeEvent(ALERT_ID, AlertChangeType.STATUS_CHANGED);

        service.publish(NORTHSTAR_ID, change);

        assertThat(notifications).hasSize(1);
        assertThat(emitter.eventPayloads()).isEmpty();
        notifications.remove().run();
        assertThat(emitter.eventPayloads()).containsExactly(change);
    }

    @Test
    void rejectedNotificationWorkRemovesAndCompletesTheSubscriber() {
        FailingEmitter emitter = new FailingEmitter(Integer.MAX_VALUE);
        AlertStreamService service =
                new AlertStreamService(
                        30_000,
                        ignored -> emitter,
                        ignored -> {
                            throw new RejectedExecutionException("shutting down");
                        });
        service.subscribe(NORTHSTAR_ID);

        assertThatCode(
                        () ->
                                service.publish(
                                        NORTHSTAR_ID,
                                        new AlertChangeEvent(
                                                ALERT_ID, AlertChangeType.STATUS_CHANGED)))
                .doesNotThrowAnyException();

        assertThat(service.subscriberCount(NORTHSTAR_ID)).isZero();
        assertThat(emitter.completed).isTrue();
    }

    @Test
    void concurrentLastRemovalAndNewSubscriptionNeverOrphansTheNewEmitter() throws Exception {
        ExecutorService races = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < 500; iteration++) {
                CallbackEmitter departing = new CallbackEmitter();
                CapturingEmitter arriving = new CapturingEmitter();
                AlertStreamService service = serviceWith(departing, arriving);
                service.subscribe(NORTHSTAR_ID);
                CountDownLatch start = new CountDownLatch(1);

                Future<?> removal =
                        races.submit(
                                () -> {
                                    await(start);
                                    departing.disconnect();
                                });
                Future<?> subscription =
                        races.submit(
                                () -> {
                                    await(start);
                                    service.subscribe(NORTHSTAR_ID);
                                });
                start.countDown();
                removal.get(2, TimeUnit.SECONDS);
                subscription.get(2, TimeUnit.SECONDS);

                service.publish(
                        NORTHSTAR_ID,
                        new AlertChangeEvent(ALERT_ID, AlertChangeType.STATUS_CHANGED));

                assertThat(service.subscriberCount(NORTHSTAR_ID))
                        .as("subscriber count at iteration %s", iteration)
                        .isOne();
                assertThat(arriving.eventPayloads())
                        .as("new subscriber event at iteration %s", iteration)
                        .containsExactly(
                                new AlertChangeEvent(ALERT_ID, AlertChangeType.STATUS_CHANGED));
            }
        } finally {
            races.shutdownNow();
        }
    }

    private AlertStreamService serviceWith(SseEmitter... emitters) {
        Queue<SseEmitter> available = new ArrayDeque<>(List.of(emitters));
        return new AlertStreamService(30_000, ignored -> available.remove(), Runnable::run);
    }

    private static boolean awaitPayloadCount(CapturingEmitter emitter, int expected)
            throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            if (emitter.eventPayloads().size() == expected) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for subscriber race");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static class CapturingEmitter extends SseEmitter {

        private final List<List<ResponseBodyEmitter.DataWithMediaType>> events =
                new CopyOnWriteArrayList<>();

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            events.add(List.copyOf(builder.build()));
        }

        List<String> eventNames() {
            return events.stream()
                    .map(
                            event ->
                                    event.stream()
                                            .map(ResponseBodyEmitter.DataWithMediaType::getData)
                                            .filter(String.class::isInstance)
                                            .map(String.class::cast)
                                            .filter(part -> part.startsWith("event:"))
                                            .findFirst()
                                            .map(part -> part.substring(0, part.indexOf('\n')))
                                            .orElseThrow())
                    .toList();
        }

        List<AlertChangeEvent> eventPayloads() {
            return events.stream()
                    .flatMap(List::stream)
                    .map(ResponseBodyEmitter.DataWithMediaType::getData)
                    .filter(AlertChangeEvent.class::isInstance)
                    .map(AlertChangeEvent.class::cast)
                    .toList();
        }
    }

    private static final class SlowEmitter extends CapturingEmitter {

        private final CountDownLatch sendStarted = new CountDownLatch(1);
        private final CountDownLatch allowSend = new CountDownLatch(1);
        private int sends;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            sends++;
            if (sends == 2) {
                sendStarted.countDown();
                await(allowSend);
            }
            super.send(builder);
        }
    }

    private static final class CallbackEmitter extends CapturingEmitter {

        private Runnable completion;

        @Override
        public void onCompletion(Runnable callback) {
            completion = callback;
        }

        void disconnect() {
            completion.run();
        }
    }

    private static final class FailingEmitter extends CapturingEmitter {

        private final int failedSend;
        private int sends;
        private boolean completed;

        private FailingEmitter(int failedSend) {
            this.failedSend = failedSend;
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            sends++;
            if (sends == failedSend) {
                throw new IOException("subscriber failure");
            }
            super.send(builder);
        }

        @Override
        public void complete() {
            completed = true;
            super.complete();
        }
    }
}
