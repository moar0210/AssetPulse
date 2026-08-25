import { useCallback, useEffect, useId, useRef, useState } from "react";

import {
  DEFAULT_DEAD_PROCESSING_EVENT_LIMIT,
  getDeadProcessingEvents,
  ProcessingEventForbiddenError,
  ProcessingEventNotFoundError,
  ProcessingEventRequestVerificationError,
  ProcessingEventRetryUncertainError,
  ProcessingEventSessionExpiredError,
  ProcessingEventStateConflictError,
  retryDeadProcessingEvent,
} from "./api/processingEvents";
import type {
  DeadProcessingEvent,
  DeadProcessingEventList,
} from "./api/processingEvents";
import type { CsrfToken } from "./api/session";

type OperationsQueueState =
  | Readonly<{ kind: "loading" }>
  | Readonly<{
      kind: "ready";
      result: DeadProcessingEventList;
      refreshFailed: boolean;
    }>
  | Readonly<{ kind: "forbidden" }>
  | Readonly<{ kind: "unavailable" }>;

type SelectedEvent = Readonly<{
  event: DeadProcessingEvent;
  presence: "dead" | "no-longer-dead";
}>;

type ActionState =
  "idle" | "submitting" | "recovering" | "recovery-failed" | "blocked";

type RecoveryReason = "conflict" | "uncertain";

type ActionFeedback = Readonly<{
  kind: "success" | "conflict" | "uncertain" | "forbidden";
  message: string;
}>;

type QueueLoadOutcome =
  | Readonly<{ kind: "success"; result: DeadProcessingEventList }>
  | Readonly<{ kind: "failed" }>
  | Readonly<{ kind: "forbidden" }>
  | Readonly<{ kind: "session-expired" }>
  | Readonly<{ kind: "superseded" }>;

const API_TIMEOUT_MS = 5_000;

function formatTimestamp(value: string) {
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: "medium",
    timeStyle: "medium",
    timeZone: "UTC",
  }).format(new Date(value));
}

function eventTypeLabel(eventType: DeadProcessingEvent["eventType"]) {
  return eventType === "TELEMETRY_BATCH_ACCEPTED"
    ? "Telemetry batch accepted"
    : (eventType satisfies never);
}

function findEvent(
  result: DeadProcessingEventList,
  eventId: string,
): DeadProcessingEvent | undefined {
  const normalizedId = eventId.toLowerCase();
  return result.events.find((event) => event.id.toLowerCase() === normalizedId);
}

export function OperationsPanel({
  csrfToken,
  onSessionExpired,
}: Readonly<{
  csrfToken: CsrfToken;
  onSessionExpired: () => void;
}>) {
  const sectionTitleId = useId();
  const [queueState, setQueueState] = useState<OperationsQueueState>({
    kind: "loading",
  });
  const [selectedEvent, setSelectedEvent] = useState<SelectedEvent | null>(
    null,
  );
  const [actionState, setActionState] = useState<ActionState>("idle");
  const [actionFeedback, setActionFeedback] = useState<ActionFeedback | null>(
    null,
  );
  const selectedEventId = selectedEvent?.event.id ?? null;
  const queueController = useRef<AbortController | null>(null);
  const commandController = useRef<AbortController | null>(null);
  const queueRequestSequence = useRef(0);
  const mounted = useRef(true);
  const recoveryReason = useRef<RecoveryReason>("uncertain");
  const queueFocus = useRef<HTMLElement | null>(null);
  const detailFocus = useRef<HTMLElement | null>(null);
  const feedbackFocus = useRef<HTMLParagraphElement | null>(null);
  const focusFeedbackAfterUpdate = useRef(false);
  const originatingEventId = useRef<string | null>(null);
  const rowButtons = useRef(new Map<string, HTMLButtonElement>());

  const cancelQueueRead = useCallback(() => {
    queueRequestSequence.current += 1;
    queueController.current?.abort();
    queueController.current = null;
  }, []);

  const readQueue = useCallback(
    async function requestQueueRead(
      showLoading: boolean,
    ): Promise<QueueLoadOutcome> {
      cancelQueueRead();
      const requestSequence = queueRequestSequence.current + 1;
      queueRequestSequence.current = requestSequence;
      const controller = new AbortController();
      queueController.current = controller;
      const timeoutId = window.setTimeout(
        () => controller.abort(),
        API_TIMEOUT_MS,
      );

      if (showLoading) {
        setQueueState((current) =>
          current.kind === "ready" ? current : { kind: "loading" },
        );
      }

      try {
        const result = await getDeadProcessingEvents(
          DEFAULT_DEAD_PROCESSING_EVENT_LIMIT,
          controller.signal,
        );
        if (
          !mounted.current ||
          queueRequestSequence.current !== requestSequence ||
          controller.signal.aborted
        ) {
          return { kind: "superseded" };
        }

        setQueueState({ kind: "ready", result, refreshFailed: false });
        setSelectedEvent((current) => {
          if (current === null) {
            return null;
          }
          const latest = findEvent(result, current.event.id);
          return latest === undefined
            ? { ...current, presence: "no-longer-dead" }
            : { event: latest, presence: "dead" };
        });
        return { kind: "success", result };
      } catch (error: unknown) {
        if (
          !mounted.current ||
          queueRequestSequence.current !== requestSequence
        ) {
          return { kind: "superseded" };
        }
        if (error instanceof ProcessingEventSessionExpiredError) {
          onSessionExpired();
          return { kind: "session-expired" };
        }
        if (error instanceof ProcessingEventForbiddenError) {
          setQueueState({ kind: "forbidden" });
          setSelectedEvent(null);
          return { kind: "forbidden" };
        }

        setQueueState((current) =>
          current.kind === "ready"
            ? { ...current, refreshFailed: true }
            : { kind: "unavailable" },
        );
        return { kind: "failed" };
      } finally {
        window.clearTimeout(timeoutId);
        if (queueController.current === controller) {
          queueController.current = null;
        }
      }
    },
    [cancelQueueRead, onSessionExpired],
  );

  const recoverLatestState = useCallback(
    async (reason: RecoveryReason) => {
      recoveryReason.current = reason;
      setActionState("recovering");
      setActionFeedback({
        kind: reason,
        message:
          reason === "conflict"
            ? "This event changed before the retry was accepted. Loading the latest saved state before another retry."
            : "The retry result could not be confirmed. Loading the latest saved state before another retry.",
      });

      const currentEventId = selectedEventId;
      if (currentEventId === null) {
        return;
      }
      const outcome = await readQueue(false);
      if (!mounted.current || outcome.kind === "superseded") {
        return;
      }
      if (outcome.kind === "session-expired") {
        return;
      }
      if (outcome.kind === "forbidden") {
        setActionState("blocked");
        setActionFeedback({
          kind: "forbidden",
          message: "You no longer have permission to retry this event.",
        });
        return;
      }
      if (outcome.kind === "failed") {
        setActionState("recovery-failed");
        setActionFeedback({
          kind: reason,
          message:
            reason === "conflict"
              ? "This event changed, but the latest saved state could not be loaded. Retry the latest state before another retry request."
              : "The retry result and latest saved state could not be confirmed. Retry the latest state before another retry request.",
        });
        return;
      }

      const remainsDead =
        findEvent(outcome.result, currentEventId) !== undefined;
      if (!remainsDead) {
        focusFeedbackAfterUpdate.current = true;
      }
      setActionState("idle");
      setActionFeedback({
        kind: reason,
        message:
          reason === "conflict"
            ? remainsDead
              ? "This event changed before the retry was accepted. The latest saved state still shows it as dead."
              : "This event changed before the retry was accepted. The latest saved state confirms it is no longer dead."
            : remainsDead
              ? "The retry result could not be confirmed. The latest saved state still shows this event as dead."
              : "The retry result could not be confirmed. The latest saved state confirms this event is no longer dead.",
      });
    },
    [readQueue, selectedEventId],
  );

  useEffect(() => {
    mounted.current = true;
    void readQueue(true);
    return () => {
      mounted.current = false;
      cancelQueueRead();
      commandController.current?.abort();
    };
  }, [cancelQueueRead, readQueue]);

  useEffect(() => {
    if (selectedEvent !== null) {
      detailFocus.current?.focus();
      return;
    }
    if (originatingEventId.current !== null) {
      const originatingButton = rowButtons.current.get(
        originatingEventId.current,
      );
      (originatingButton ?? queueFocus.current)?.focus();
      originatingEventId.current = null;
    }
  }, [selectedEventId]);

  useEffect(() => {
    if (focusFeedbackAfterUpdate.current && actionFeedback !== null) {
      focusFeedbackAfterUpdate.current = false;
      feedbackFocus.current?.focus();
    }
  }, [actionFeedback]);

  async function handleRetry(event: DeadProcessingEvent) {
    if (actionState !== "idle" || selectedEvent?.presence !== "dead") {
      return;
    }

    setActionState("submitting");
    setActionFeedback(null);
    cancelQueueRead();
    const controller = new AbortController();
    commandController.current = controller;
    const timeoutId = window.setTimeout(
      () => controller.abort(),
      API_TIMEOUT_MS,
    );

    try {
      await retryDeadProcessingEvent(event.id, csrfToken, controller.signal);
      if (!mounted.current) {
        return;
      }
      setSelectedEvent((current) =>
        current === null ? null : { ...current, presence: "no-longer-dead" },
      );
      setQueueState((current) =>
        current.kind === "ready"
          ? {
              ...current,
              result: {
                ...current.result,
                events: current.result.events.filter(
                  (queuedEvent) => queuedEvent.id !== event.id,
                ),
              },
            }
          : current,
      );
      setActionState("idle");
      focusFeedbackAfterUpdate.current = true;
      setActionFeedback({
        kind: "success",
        message:
          "Retry accepted. Processing will resume asynchronously with a fresh attempt cycle.",
      });
      void readQueue(false);
    } catch (error: unknown) {
      if (!mounted.current) {
        return;
      }
      if (
        error instanceof ProcessingEventSessionExpiredError ||
        error instanceof ProcessingEventRequestVerificationError
      ) {
        onSessionExpired();
        return;
      }
      if (error instanceof ProcessingEventForbiddenError) {
        setActionState("blocked");
        setActionFeedback(null);
        setSelectedEvent(null);
        setQueueState({ kind: "forbidden" });
        return;
      }

      const reason: RecoveryReason =
        error instanceof ProcessingEventStateConflictError ||
        error instanceof ProcessingEventNotFoundError
          ? "conflict"
          : error instanceof ProcessingEventRetryUncertainError
            ? "uncertain"
            : "uncertain";
      await recoverLatestState(reason);
    } finally {
      window.clearTimeout(timeoutId);
      if (commandController.current === controller) {
        commandController.current = null;
      }
    }
  }

  function handleRecoveryRetry() {
    if (actionState !== "recovery-failed") {
      return;
    }
    focusFeedbackAfterUpdate.current = true;
    void recoverLatestState(recoveryReason.current);
  }

  if (selectedEvent !== null) {
    const { event, presence } = selectedEvent;
    return (
      <section
        ref={detailFocus}
        className="operations-section operations-detail"
        aria-labelledby="operations-detail-title"
        tabIndex={-1}
      >
        <button
          className="secondary-button secondary-button--compact operations-back"
          type="button"
          disabled={
            actionState === "submitting" ||
            actionState === "recovering" ||
            actionState === "recovery-failed"
          }
          onClick={() => {
            setSelectedEvent(null);
            setActionState("idle");
            setActionFeedback(null);
          }}
        >
          Back to operations
        </button>

        <div className="operations-detail__content">
          <header className="operations-detail__header">
            <div>
              <p className="eyebrow">Protected recovery control</p>
              <h2 id="operations-detail-title">Processing event</h2>
              <code>{event.id}</code>
            </div>
            <span
              className={`operations-status operations-status--${presence}`}
            >
              {presence === "dead" ? "Dead" : "No longer dead"}
            </span>
          </header>

          {queueState.kind === "ready" && queueState.refreshFailed && (
            <p className="form-message form-message--error" role="alert">
              The operations queue could not be refreshed. The last confirmed
              safe details remain shown.
            </p>
          )}

          <dl className="operations-facts">
            <div>
              <dt>Telemetry batch</dt>
              <dd>
                <code>{event.telemetryBatchId}</code>
              </dd>
            </div>
            <div>
              <dt>Event type</dt>
              <dd>{eventTypeLabel(event.eventType)}</dd>
            </div>
            <div>
              <dt>Attempts in cycle</dt>
              <dd>{event.attemptCount} of 5</dd>
            </div>
            <div>
              <dt>Created</dt>
              <dd>
                <time dateTime={event.createdAt}>
                  {formatTimestamp(event.createdAt)}
                </time>
              </dd>
            </div>
            <div>
              <dt>Marked dead</dt>
              <dd>
                <time dateTime={event.deadAt}>
                  {formatTimestamp(event.deadAt)}
                </time>
              </dd>
            </div>
            <div>
              <dt>Last updated</dt>
              <dd>
                <time dateTime={event.updatedAt}>
                  {formatTimestamp(event.updatedAt)}
                </time>
              </dd>
            </div>
          </dl>

          <section
            className="operations-failure"
            aria-labelledby="operations-failure-title"
          >
            <h3 id="operations-failure-title">Safe failure details</h3>
            <dl>
              <div>
                <dt>Error code</dt>
                <dd>
                  <code>{event.lastErrorCode}</code>
                </dd>
              </div>
              <div>
                <dt>Message</dt>
                <dd>{event.lastErrorMessage}</dd>
              </div>
            </dl>
          </section>

          {actionFeedback !== null && (
            <p
              ref={feedbackFocus}
              className={`form-message operations-action-feedback operations-action-feedback--${actionFeedback.kind}`}
              role={actionFeedback.kind === "success" ? "status" : "alert"}
              tabIndex={-1}
            >
              {actionFeedback.message}
            </p>
          )}

          {presence === "dead" && (
            <div className="operations-actions">
              <button
                className="primary-button"
                type="button"
                disabled={actionState !== "idle"}
                onClick={() => void handleRetry(event)}
              >
                {actionState === "submitting"
                  ? "Requesting retry…"
                  : actionState === "recovering"
                    ? "Recovering latest state…"
                    : actionState === "recovery-failed"
                      ? "Latest state required"
                      : actionState === "blocked"
                        ? "Retry unavailable"
                        : "Retry processing event"}
              </button>
              {actionState === "recovery-failed" && (
                <button
                  className="secondary-button secondary-button--compact"
                  type="button"
                  onClick={handleRecoveryRetry}
                >
                  Retry latest state
                </button>
              )}
            </div>
          )}
        </div>
      </section>
    );
  }

  return (
    <section
      ref={queueFocus}
      className="operations-section operations-queue"
      aria-labelledby={sectionTitleId}
      tabIndex={-1}
    >
      <div className="operations-section__heading">
        <div>
          <p className="eyebrow">Protected recovery control</p>
          <h2 id={sectionTitleId}>Processing operations</h2>
          <p className="operations-intro">
            Inspect bounded safe failure details and request an asynchronous
            retry for dead telemetry processing events.
          </p>
        </div>
        <div className="operations-heading-actions">
          {queueState.kind === "ready" && (
            <p className="asset-count" aria-label="Loaded dead event count">
              {queueState.result.events.length}
            </p>
          )}
          <button
            className="secondary-button secondary-button--compact"
            type="button"
            disabled={queueState.kind === "loading"}
            onClick={() => void readQueue(queueState.kind !== "ready")}
          >
            Refresh operations
          </button>
        </div>
      </div>

      {queueState.kind === "loading" && (
        <p className="asset-message" role="status" aria-live="polite">
          Loading dead processing events…
        </p>
      )}

      {queueState.kind === "unavailable" && (
        <div className="operations-state-panel">
          <p className="asset-message form-message--error" role="alert">
            We could not load processing operations. Try again.
          </p>
          <button
            className="secondary-button secondary-button--compact"
            type="button"
            onClick={() => void readQueue(true)}
          >
            Retry operations
          </button>
        </div>
      )}

      {queueState.kind === "forbidden" && (
        <div className="operations-state-panel">
          <h3>Operations access denied</h3>
          <p className="asset-message" role="alert">
            The server did not grant access to processing operations.
          </p>
        </div>
      )}

      {queueState.kind === "ready" && queueState.refreshFailed && (
        <p className="form-message form-message--error" role="alert">
          Refresh failed. The last confirmed operations queue is still shown.
        </p>
      )}

      {queueState.kind === "ready" && queueState.result.events.length === 0 && (
        <p className="asset-message">
          No dead processing events are awaiting review.
        </p>
      )}

      {queueState.kind === "ready" && queueState.result.events.length > 0 && (
        <ul className="operations-list">
          {queueState.result.events.map((event) => (
            <li key={event.id}>
              <button
                ref={(button) => {
                  if (button === null) {
                    rowButtons.current.delete(event.id);
                  } else {
                    rowButtons.current.set(event.id, button);
                  }
                }}
                className="operations-row-button"
                type="button"
                aria-label={`View dead processing event ${event.id}`}
                onClick={() => {
                  originatingEventId.current = event.id;
                  setActionState("idle");
                  setActionFeedback(null);
                  setSelectedEvent({ event, presence: "dead" });
                }}
              >
                <span className="operations-row-button__primary">
                  <span className="operations-row-button__title">
                    Telemetry processing failure
                  </span>
                  <span>
                    Batch <code>{event.telemetryBatchId}</code>
                  </span>
                </span>
                <span className="operations-row-button__facts">
                  <code>{event.lastErrorCode}</code>
                  <span>{event.attemptCount} attempts</span>
                  <time dateTime={event.deadAt}>
                    {formatTimestamp(event.deadAt)}
                  </time>
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
