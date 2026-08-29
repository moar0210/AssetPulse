import { useEffect, useId, useState } from "react";

import {
  AuditForbiddenError,
  AuditSessionExpiredError,
  DEFAULT_AUDIT_EVENT_LIMIT,
  getAuditEvents,
} from "./api/auditEvents";
import type {
  AuditAction,
  AuditEventList,
  AuditSubjectType,
} from "./api/auditEvents";

type AuditState =
  | Readonly<{ kind: "loading" }>
  | Readonly<{
      kind: "ready";
      result: AuditEventList;
      refreshing: boolean;
      refreshFailed: boolean;
    }>
  | Readonly<{ kind: "forbidden" }>
  | Readonly<{ kind: "unavailable" }>
  | Readonly<{ kind: "session-expired" }>;

const API_TIMEOUT_MS = 5_000;
const actionLabels: Readonly<Record<AuditAction, string>> = {
  AUTHENTICATION_SUCCEEDED: "Signed in",
  SESSION_ENDED: "Signed out",
  ALERT_ACKNOWLEDGED: "Alert acknowledged",
  ALERT_RESOLVED: "Alert resolved",
  WORK_ORDER_CREATED: "Work order created",
  WORK_ORDER_ASSIGNED: "Work order assigned",
  WORK_ORDER_STARTED: "Work order started",
  WORK_ORDER_COMPLETED: "Work order completed",
  PROCESSING_EVENT_RETRIED: "Processing event retry requested",
};
const subjectLabels: Readonly<Record<AuditSubjectType, string>> = {
  USER: "User",
  ALERT: "Alert",
  WORK_ORDER: "Work order",
  PROCESSING_EVENT: "Processing event",
};

function formatTimestamp(value: string) {
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: "medium",
    timeStyle: "medium",
    timeZone: "UTC",
  }).format(new Date(value));
}

export function AuditPanel({
  onSessionExpired,
}: Readonly<{ onSessionExpired: () => void }>) {
  const sectionTitleId = useId();
  const tableCaptionId = useId();
  const [auditState, setAuditState] = useState<AuditState>({ kind: "loading" });
  const [loadAttempt, setLoadAttempt] = useState(0);

  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    const timeoutId = window.setTimeout(
      () => controller.abort(),
      API_TIMEOUT_MS,
    );

    setAuditState((current) =>
      current.kind === "ready"
        ? { ...current, refreshing: true, refreshFailed: false }
        : { kind: "loading" },
    );

    void getAuditEvents(DEFAULT_AUDIT_EVENT_LIMIT, controller.signal)
      .then((result) => {
        if (active && !controller.signal.aborted) {
          setAuditState({
            kind: "ready",
            result,
            refreshing: false,
            refreshFailed: false,
          });
        }
      })
      .catch((error: unknown) => {
        if (!active) {
          return;
        }
        if (error instanceof AuditSessionExpiredError) {
          setAuditState({ kind: "session-expired" });
          onSessionExpired();
          return;
        }
        if (error instanceof AuditForbiddenError) {
          setAuditState({ kind: "forbidden" });
          return;
        }
        setAuditState((current) =>
          current.kind === "ready"
            ? { ...current, refreshing: false, refreshFailed: true }
            : { kind: "unavailable" },
        );
      })
      .finally(() => window.clearTimeout(timeoutId));

    return () => {
      active = false;
      window.clearTimeout(timeoutId);
      controller.abort();
    };
  }, [loadAttempt, onSessionExpired]);

  const refreshing = auditState.kind === "ready" && auditState.refreshing;

  return (
    <section
      className="operations-section audit-section"
      aria-labelledby={sectionTitleId}
    >
      <div className="operations-section__heading">
        <div>
          <p className="eyebrow">Organisation activity</p>
          <h2 id={sectionTitleId}>Audit trail</h2>
          <p className="operations-intro">
            Inspect the latest {DEFAULT_AUDIT_EVENT_LIMIT} recorded actions in
            your organisation, newest first. This view is read-only.
          </p>
        </div>
        <div className="operations-heading-actions">
          {auditState.kind === "ready" && (
            <p className="asset-count" aria-label="Loaded audit event count">
              {auditState.result.events.length}
            </p>
          )}
          <button
            className="secondary-button secondary-button--compact"
            type="button"
            disabled={
              auditState.kind === "loading" ||
              auditState.kind === "session-expired" ||
              refreshing
            }
            onClick={() => setLoadAttempt((attempt) => attempt + 1)}
          >
            Refresh audit trail
          </button>
        </div>
      </div>

      {(auditState.kind === "loading" || refreshing) && (
        <p className="asset-message" role="status" aria-live="polite">
          {refreshing ? "Refreshing audit trail…" : "Loading audit trail…"}
        </p>
      )}

      {auditState.kind === "unavailable" && (
        <div className="operations-state-panel">
          <p className="asset-message form-message--error" role="alert">
            We could not load the audit trail. Try again.
          </p>
          <button
            className="secondary-button secondary-button--compact"
            type="button"
            onClick={() => setLoadAttempt((attempt) => attempt + 1)}
          >
            Retry audit trail
          </button>
        </div>
      )}

      {auditState.kind === "forbidden" && (
        <div className="operations-state-panel">
          <h3>Audit access denied</h3>
          <p className="asset-message" role="alert">
            The server did not grant access to the organisation audit trail.
          </p>
        </div>
      )}

      {auditState.kind === "session-expired" && (
        <p className="asset-message" role="status">
          Your session expired. Returning to sign in…
        </p>
      )}

      {auditState.kind === "ready" && auditState.refreshFailed && (
        <p className="form-message form-message--error" role="alert">
          Refresh failed. The last confirmed audit trail is still shown.
        </p>
      )}

      {auditState.kind === "ready" && auditState.result.events.length === 0 && (
        <p className="asset-message">
          No audit events have been recorded for your organisation.
        </p>
      )}

      {auditState.kind === "ready" && auditState.result.events.length > 0 && (
        <div
          className="audit-table-scroll"
          role="region"
          aria-labelledby={tableCaptionId}
          tabIndex={0}
        >
          <table className="audit-table">
            <caption id={tableCaptionId}>
              Latest organisation audit events (up to {auditState.result.limit})
            </caption>
            <thead>
              <tr>
                <th scope="col">Time (UTC)</th>
                <th scope="col">Actor</th>
                <th scope="col">Action</th>
                <th scope="col">Subject</th>
                <th scope="col">Correlation ID</th>
              </tr>
            </thead>
            <tbody>
              {auditState.result.events.map((event) => (
                <tr key={event.id}>
                  <td>
                    <time dateTime={event.occurredAt}>
                      {formatTimestamp(event.occurredAt)}
                    </time>
                  </td>
                  <td>
                    <span>{event.actor.displayName}</span>
                    <code>{event.actor.id}</code>
                  </td>
                  <td>{actionLabels[event.action]}</td>
                  <td>
                    <span>{subjectLabels[event.subject.type]}</span>
                    <code>{event.subject.id}</code>
                  </td>
                  <td>
                    <code>{event.correlationId}</code>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
