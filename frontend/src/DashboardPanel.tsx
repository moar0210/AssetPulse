import { useEffect, useId, useRef, useState } from "react";

import {
  DashboardForbiddenError,
  DashboardSessionExpiredError,
  getDashboard,
} from "./api/dashboard";
import type {
  DashboardActivityAction,
  DashboardActivitySubjectType,
  DashboardSummary,
} from "./api/dashboard";
import {
  DemoForbiddenError,
  DemoRequestVerificationError,
  DemoResetLimitExceededError,
  DemoSessionExpiredError,
  launchOverheatingScenario,
  resetDemo,
} from "./api/demo";
import type { CsrfToken, SessionIdentity } from "./api/session";

type DashboardState =
  | Readonly<{ kind: "loading" }>
  | Readonly<{
      kind: "ready";
      summary: DashboardSummary;
      refreshing: boolean;
      refreshFailed: boolean;
    }>
  | Readonly<{ kind: "forbidden" }>
  | Readonly<{ kind: "unavailable" }>
  | Readonly<{ kind: "session-expired" }>;

type ScenarioState = "idle" | "submitting" | "uncertain" | "forbidden";
type ResetState =
  | Readonly<{ kind: "idle" }>
  | Readonly<{ kind: "confirming" }>
  | Readonly<{ kind: "submitting" }>
  | Readonly<{
      kind: "succeeded";
      alertsResolved: number;
      workOrdersCompleted: number;
    }>
  | Readonly<{ kind: "limit-exceeded" | "uncertain" | "forbidden" }>;

const API_TIMEOUT_MS = 5_000;
const activityLabels: Readonly<Record<DashboardActivityAction, string>> = {
  ALERT_ACKNOWLEDGED: "Alert acknowledged",
  ALERT_RESOLVED: "Alert resolved",
  WORK_ORDER_CREATED: "Work order created",
  WORK_ORDER_ASSIGNED: "Work order assigned",
  WORK_ORDER_STARTED: "Work order started",
  WORK_ORDER_COMPLETED: "Work order completed",
};
const subjectLabels: Readonly<Record<DashboardActivitySubjectType, string>> = {
  ALERT: "Alert",
  WORK_ORDER: "Work order",
};

function formatTimestamp(value: string) {
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: "medium",
    timeStyle: "short",
    timeZone: "UTC",
  }).format(new Date(value));
}

export function DashboardPanel({
  identity,
  csrfToken,
  onSessionExpired,
  onOpenAlerts,
}: Readonly<{
  identity: SessionIdentity;
  csrfToken: CsrfToken;
  onSessionExpired: () => void;
  onOpenAlerts: () => void;
}>) {
  const sectionTitleId = useId();
  const scenarioFeedbackRef = useRef<HTMLParagraphElement>(null);
  const resetFeedbackRef = useRef<HTMLParagraphElement>(null);
  const prepareResetRef = useRef<HTMLButtonElement>(null);
  const confirmResetRef = useRef<HTMLButtonElement>(null);
  const resetFocusTarget = useRef<"prepare" | "confirm" | null>(null);
  const commandController = useRef<AbortController | null>(null);
  const [dashboardState, setDashboardState] = useState<DashboardState>({
    kind: "loading",
  });
  const [loadAttempt, setLoadAttempt] = useState(0);
  const [scenarioState, setScenarioState] = useState<ScenarioState>("idle");
  const [resetState, setResetState] = useState<ResetState>({ kind: "idle" });

  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    const timeoutId = window.setTimeout(
      () => controller.abort(),
      API_TIMEOUT_MS,
    );
    setDashboardState((current) =>
      current.kind === "ready"
        ? { ...current, refreshing: true, refreshFailed: false }
        : { kind: "loading" },
    );
    void getDashboard(controller.signal)
      .then((summary) => {
        if (active && !controller.signal.aborted) {
          setDashboardState({
            kind: "ready",
            summary,
            refreshing: false,
            refreshFailed: false,
          });
        }
      })
      .catch((error: unknown) => {
        if (!active) return;
        if (error instanceof DashboardSessionExpiredError) {
          setDashboardState({ kind: "session-expired" });
          onSessionExpired();
          return;
        }
        if (error instanceof DashboardForbiddenError) {
          setDashboardState({ kind: "forbidden" });
          return;
        }
        setDashboardState((current) =>
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

  useEffect(
    () => () => {
      commandController.current?.abort();
    },
    [],
  );

  useEffect(() => {
    if (
      resetState.kind === "confirming" &&
      resetFocusTarget.current === "confirm"
    ) {
      confirmResetRef.current?.focus();
      resetFocusTarget.current = null;
    } else if (
      resetState.kind === "idle" &&
      resetFocusTarget.current === "prepare"
    ) {
      prepareResetRef.current?.focus();
      resetFocusTarget.current = null;
    }

    if (
      ["succeeded", "limit-exceeded", "uncertain", "forbidden"].includes(
        resetState.kind,
      )
    ) {
      resetFeedbackRef.current?.focus();
    }
  }, [resetState]);

  useEffect(() => {
    if (scenarioState === "uncertain" || scenarioState === "forbidden") {
      scenarioFeedbackRef.current?.focus();
    }
  }, [scenarioState]);

  function startCommand() {
    commandController.current?.abort();
    const controller = new AbortController();
    commandController.current = controller;
    const timeoutId = window.setTimeout(
      () => controller.abort(),
      API_TIMEOUT_MS,
    );
    return {
      controller,
      finish: () => {
        window.clearTimeout(timeoutId);
        if (commandController.current === controller) {
          commandController.current = null;
        }
      },
    };
  }

  async function handleScenarioLaunch() {
    if (
      scenarioState === "submitting" ||
      resetState.kind === "confirming" ||
      resetState.kind === "submitting"
    ) {
      return;
    }
    setScenarioState("submitting");
    const command = startCommand();
    try {
      await launchOverheatingScenario(csrfToken, command.controller.signal);
      onOpenAlerts();
    } catch (error: unknown) {
      if (
        error instanceof DemoSessionExpiredError ||
        error instanceof DemoRequestVerificationError
      ) {
        onSessionExpired();
        return;
      }
      setScenarioState(
        error instanceof DemoForbiddenError ? "forbidden" : "uncertain",
      );
    } finally {
      command.finish();
    }
  }

  async function handleReset() {
    if (resetState.kind !== "confirming" || scenarioState === "submitting") {
      return;
    }
    setResetState({ kind: "submitting" });
    const command = startCommand();
    try {
      const result = await resetDemo(csrfToken, command.controller.signal);
      setResetState({
        kind: "succeeded",
        alertsResolved: result.alertsResolved,
        workOrdersCompleted: result.workOrdersCompleted,
      });
      setLoadAttempt((attempt) => attempt + 1);
    } catch (error: unknown) {
      if (
        error instanceof DemoSessionExpiredError ||
        error instanceof DemoRequestVerificationError
      ) {
        onSessionExpired();
        return;
      }
      if (error instanceof DemoForbiddenError) {
        setResetState({ kind: "forbidden" });
      } else if (error instanceof DemoResetLimitExceededError) {
        setResetState({ kind: "limit-exceeded" });
      } else {
        setResetState({ kind: "uncertain" });
      }
    } finally {
      command.finish();
    }
  }

  const refreshing =
    dashboardState.kind === "ready" && dashboardState.refreshing;
  const isAdmin = identity.role.code === "OPERATIONS_ADMIN";
  const canLaunchScenario =
    isAdmin && identity.organisation.slug === "northstar-operations";
  const resetIsPending =
    resetState.kind === "confirming" || resetState.kind === "submitting";

  return (
    <section
      className="dashboard-section"
      aria-labelledby={sectionTitleId}
      aria-busy={dashboardState.kind === "loading" || refreshing}
    >
      <div className="dashboard-section__heading">
        <div>
          <p className="eyebrow">Operational overview</p>
          <h2 id={sectionTitleId}>Dashboard</h2>
          <p className="dashboard-intro">
            Current organisation state and the latest safe workflow activity.
          </p>
        </div>
        <button
          className="secondary-button secondary-button--compact"
          type="button"
          disabled={
            dashboardState.kind === "loading" ||
            dashboardState.kind === "session-expired" ||
            refreshing
          }
          onClick={() => setLoadAttempt((attempt) => attempt + 1)}
        >
          Refresh dashboard
        </button>
      </div>

      {(dashboardState.kind === "loading" || refreshing) && (
        <p className="asset-message" role="status" aria-live="polite">
          {refreshing ? "Refreshing dashboard…" : "Loading dashboard…"}
        </p>
      )}

      {dashboardState.kind === "unavailable" && (
        <div className="dashboard-state-panel">
          <h3>Dashboard unavailable</h3>
          <p className="asset-message form-message--error" role="alert">
            We could not load the dashboard. Try again.
          </p>
          <button
            className="secondary-button secondary-button--compact"
            type="button"
            onClick={() => setLoadAttempt((attempt) => attempt + 1)}
          >
            Retry dashboard
          </button>
        </div>
      )}

      {dashboardState.kind === "forbidden" && (
        <div className="dashboard-state-panel">
          <h3>Dashboard access denied</h3>
          <p className="asset-message" role="alert">
            The server did not grant access to this organisation dashboard.
          </p>
        </div>
      )}

      {dashboardState.kind === "session-expired" && (
        <p className="asset-message" role="status">
          Your session expired. Returning to sign in…
        </p>
      )}

      {dashboardState.kind === "ready" && (
        <>
          {dashboardState.refreshFailed && (
            <p className="form-message form-message--error" role="alert">
              Refresh failed. The last confirmed dashboard is still shown.
            </p>
          )}
          <dl className="dashboard-metrics" aria-label="Dashboard counts">
            <div>
              <dt>Assets</dt>
              <dd>{dashboardState.summary.assetCount}</dd>
            </div>
            <div>
              <dt>Open alerts</dt>
              <dd>{dashboardState.summary.openAlertCount}</dd>
            </div>
            <div>
              <dt>Active work</dt>
              <dd>{dashboardState.summary.activeWorkOrderCount}</dd>
            </div>
          </dl>

          <section
            className="dashboard-activity"
            aria-labelledby="dashboard-activity-title"
          >
            <h3 id="dashboard-activity-title">Recent activity</h3>
            {dashboardState.summary.recentActivity.length === 0 ? (
              <p className="asset-message">
                No workflow activity has been recorded for this organisation.
              </p>
            ) : (
              <ol>
                {dashboardState.summary.recentActivity.map((activity) => (
                  <li
                    key={`${activity.action}:${activity.subjectId}:${activity.occurredAt}`}
                  >
                    <div>
                      <strong>{activityLabels[activity.action]}</strong>
                      <span>
                        {subjectLabels[activity.subjectType]} ·{" "}
                        <code>{activity.subjectId}</code>
                      </span>
                    </div>
                    <time dateTime={activity.occurredAt}>
                      {formatTimestamp(activity.occurredAt)} UTC
                    </time>
                  </li>
                ))}
              </ol>
            )}
          </section>
        </>
      )}

      <section
        className="dashboard-simulator"
        aria-labelledby="dashboard-simulator-title"
      >
        <div>
          <h3 id="dashboard-simulator-title">Overheating Pump scenario</h3>
          <p className="asset-message">
            Submit six bounded readings through the public API, then follow the
            live alert without refreshing.
          </p>
        </div>
        {canLaunchScenario ? (
          <button
            className="primary-button"
            type="button"
            disabled={scenarioState === "submitting" || resetIsPending}
            onClick={() => void handleScenarioLaunch()}
          >
            {scenarioState === "submitting"
              ? "Launching scenario…"
              : "Launch and open alerts"}
          </button>
        ) : (
          <p className="readonly-badge">
            {isAdmin ? "Northstar demo only" : "Operations Admin only"}
          </p>
        )}
      </section>

      {scenarioState === "uncertain" && (
        <p
          ref={scenarioFeedbackRef}
          className="dashboard-feedback dashboard-feedback--error"
          role="alert"
          tabIndex={-1}
        >
          The scenario result is uncertain. Check Alerts before launching it
          again.
        </p>
      )}
      {scenarioState === "forbidden" && (
        <p
          ref={scenarioFeedbackRef}
          className="dashboard-feedback dashboard-feedback--error"
          role="alert"
          tabIndex={-1}
        >
          The server denied this scenario launch.
        </p>
      )}

      {isAdmin && (
        <section
          className="dashboard-reset"
          aria-labelledby="dashboard-reset-title"
        >
          <div>
            <h3 id="dashboard-reset-title">Reset active demo state</h3>
            <p className="asset-message">
              Finish active alerts and work orders through their legal states.
              Immutable history and audit evidence remain intact.
            </p>
          </div>
          {resetState.kind === "idle" && (
            <button
              ref={prepareResetRef}
              className="secondary-button secondary-button--compact"
              type="button"
              disabled={scenarioState === "submitting"}
              onClick={() => {
                resetFocusTarget.current = "confirm";
                setResetState({ kind: "confirming" });
              }}
            >
              Prepare reset
            </button>
          )}
          {resetState.kind === "confirming" && (
            <div
              className="dashboard-reset__confirmation"
              role="group"
              aria-label="Confirm demo reset"
            >
              <p>This advances active workflow records to terminal states.</p>
              <button
                ref={confirmResetRef}
                className="primary-button"
                type="button"
                disabled={scenarioState === "submitting"}
                onClick={() => void handleReset()}
              >
                Confirm reset
              </button>
              <button
                className="secondary-button secondary-button--compact"
                type="button"
                disabled={scenarioState === "submitting"}
                onClick={() => {
                  resetFocusTarget.current = "prepare";
                  setResetState({ kind: "idle" });
                }}
              >
                Cancel
              </button>
            </div>
          )}
          {resetState.kind === "submitting" && (
            <p className="asset-message" role="status">
              Resetting active demo state…
            </p>
          )}
          {resetState.kind === "succeeded" && (
            <p
              ref={resetFeedbackRef}
              className="dashboard-feedback dashboard-feedback--success"
              role="status"
              tabIndex={-1}
            >
              Demo reset complete: {resetState.alertsResolved} alerts resolved
              and {resetState.workOrdersCompleted} work orders completed.
            </p>
          )}
          {resetState.kind === "limit-exceeded" && (
            <p
              ref={resetFeedbackRef}
              className="dashboard-feedback dashboard-feedback--error"
              role="alert"
              tabIndex={-1}
            >
              Reset stopped at its safety limit; no partial reset was committed.
            </p>
          )}
          {resetState.kind === "uncertain" && (
            <p
              ref={resetFeedbackRef}
              className="dashboard-feedback dashboard-feedback--error"
              role="alert"
              tabIndex={-1}
            >
              The reset result is uncertain. Refresh the dashboard before trying
              again.
            </p>
          )}
          {resetState.kind === "forbidden" && (
            <p
              ref={resetFeedbackRef}
              className="dashboard-feedback dashboard-feedback--error"
              role="alert"
              tabIndex={-1}
            >
              The server denied this demo reset.
            </p>
          )}
        </section>
      )}
    </section>
  );
}
