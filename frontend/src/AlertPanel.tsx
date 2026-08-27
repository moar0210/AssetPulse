import { useCallback, useEffect, useId, useRef, useState } from "react";

import {
  acknowledgeAlert,
  AlertCommandUncertainError,
  AlertForbiddenError,
  AlertNotFoundError,
  AlertRequestVerificationError,
  AlertSessionExpiredError,
  AlertStateConflictError,
  DEFAULT_ALERT_LIMIT,
  getAlertDetail,
  getAlerts,
  resolveAlert,
  subscribeToAlertChanges,
} from "./api/alerts";
import type {
  AlertDetail,
  AlertEventSourceFactory,
  AlertList,
  AlertStatus,
} from "./api/alerts";
import type { CsrfToken, RoleCode } from "./api/session";
import {
  createWorkOrder,
  DEFAULT_WORK_ORDER_LIMIT,
  getWorkOrders,
  WorkOrderAlreadyExistsError,
  WorkOrderCommandUncertainError,
  WorkOrderForbiddenError,
  WorkOrderRequestVerificationError,
  WorkOrderSessionExpiredError,
  WorkOrderSourceAlertNotFoundError,
} from "./api/workOrders";

type AlertQueueState =
  | Readonly<{ kind: "loading" }>
  | Readonly<{
      kind: "ready";
      result: AlertList;
      refreshFailed: boolean;
    }>
  | Readonly<{ kind: "forbidden" }>
  | Readonly<{ kind: "unavailable" }>;

type AlertDetailState =
  | Readonly<{ kind: "loading" }>
  | Readonly<{
      kind: "ready";
      detail: AlertDetail;
      refreshFailed: boolean;
    }>
  | Readonly<{ kind: "not-found" }>
  | Readonly<{ kind: "forbidden" }>
  | Readonly<{ kind: "unavailable" }>;

type StreamState = "connecting" | "connected" | "reconnecting" | "degraded";
type ActionState = "idle" | "submitting" | "recovering" | "recovery-failed";
type ActionFeedback = Readonly<{
  kind: "success" | "conflict" | "uncertain" | "forbidden";
  message: string;
}>;

type CreateActionState =
  | "idle"
  | "submitting"
  | "recovering"
  | "recovery-failed"
  | "created"
  | "existing"
  | "blocked";
type CreateRecoveryReason = "duplicate" | "uncertain";
type CreateFeedback = Readonly<{
  kind: "success" | "duplicate" | "uncertain" | "forbidden" | "not-found";
  message: string;
}>;

const API_TIMEOUT_MS = 5_000;

const statusLabels: Record<AlertStatus, string> = {
  OPEN: "Open",
  ACKNOWLEDGED: "Acknowledged",
  RESOLVED: "Resolved",
};

const streamLabels: Record<StreamState, string> = {
  connecting: "Live updates connecting…",
  connected: "Live updates connected",
  reconnecting:
    "Live updates reconnecting. Saved alert data remains available.",
  degraded:
    "Live updates received invalid data. An authoritative refresh was requested; manual refresh remains available.",
};

function formatTimestamp(value: string) {
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: "medium",
    timeStyle: "medium",
    timeZone: "UTC",
  }).format(new Date(value));
}

function formatValue(value: number) {
  return new Intl.NumberFormat(undefined, {
    maximumFractionDigits: 6,
  }).format(value);
}

function StatusBadge({ status }: Readonly<{ status: AlertStatus }>) {
  return (
    <span className={`alert-status alert-status--${status.toLowerCase()}`}>
      {statusLabels[status]}
    </span>
  );
}

function AlertDetailPanel({
  alertId,
  roleCode,
  csrfToken,
  refreshVersion,
  onBack,
  onSessionExpired,
  onRefreshQueue,
}: Readonly<{
  alertId: string;
  roleCode: RoleCode;
  csrfToken: CsrfToken;
  refreshVersion: number;
  onBack: () => void;
  onSessionExpired: () => void;
  onRefreshQueue: () => void;
}>) {
  const [detailState, setDetailState] = useState<AlertDetailState>({
    kind: "loading",
  });
  const [actionState, setActionState] = useState<ActionState>("idle");
  const [actionFeedback, setActionFeedback] = useState<ActionFeedback | null>(
    null,
  );
  const [createActionState, setCreateActionState] =
    useState<CreateActionState>("idle");
  const [createFeedback, setCreateFeedback] = useState<CreateFeedback | null>(
    null,
  );
  const commandController = useRef<AbortController | null>(null);
  const createController = useRef<AbortController | null>(null);
  const createRecoveryController = useRef<AbortController | null>(null);
  const createRecoveryReason = useRef<CreateRecoveryReason>("uncertain");
  const detailController = useRef<AbortController | null>(null);
  const detailRequestSequence = useRef(0);
  const detailRequestActive = useRef(false);
  const detailTrailingRefresh = useRef(false);
  const recoveryPending = useRef(false);
  const detailFocus = useRef<HTMLElement | null>(null);
  const feedbackFocus = useRef<HTMLParagraphElement | null>(null);
  const focusFeedbackAfterUpdate = useRef(false);
  const observedRefreshVersion = useRef(refreshVersion);
  const mounted = useRef(true);

  const finishRecovery = useCallback((succeeded: boolean) => {
    if (!recoveryPending.current) {
      return;
    }
    recoveryPending.current = false;
    setActionState(succeeded ? "idle" : "recovery-failed");
    setActionFeedback((current) => {
      if (current?.kind === "conflict") {
        return {
          ...current,
          message: succeeded
            ? "This alert changed in another client. The latest saved state is now loaded."
            : "This alert changed in another client, but the latest saved state could not be loaded. Retry the latest state before using another command.",
        };
      }
      if (current?.kind === "uncertain") {
        return {
          ...current,
          message: succeeded
            ? "The command result could not be confirmed. The latest saved state is now loaded."
            : "The command result could not be confirmed, and the latest saved state could not be loaded. Retry the latest state before using another command.",
        };
      }
      return current;
    });
  }, []);

  const cancelDetailRefresh = useCallback(() => {
    detailRequestSequence.current += 1;
    detailRequestActive.current = false;
    detailTrailingRefresh.current = false;
    detailController.current?.abort();
    detailController.current = null;
  }, []);

  const focusStableDetailTarget = useCallback(() => {
    (feedbackFocus.current ?? detailFocus.current)?.focus();
  }, []);

  const refreshDetail = useCallback(
    function requestDetailRefresh(showLoading: boolean) {
      if (!mounted.current) {
        return;
      }
      if (detailRequestActive.current) {
        detailTrailingRefresh.current = true;
        return;
      }

      detailRequestActive.current = true;
      detailTrailingRefresh.current = false;
      const requestSequence = detailRequestSequence.current + 1;
      detailRequestSequence.current = requestSequence;
      const controller = new AbortController();
      detailController.current = controller;
      const timeoutId = window.setTimeout(
        () => controller.abort(),
        API_TIMEOUT_MS,
      );

      if (showLoading) {
        setDetailState((current) =>
          current.kind === "ready" ? current : { kind: "loading" },
        );
      }

      void getAlertDetail(alertId, controller.signal)
        .then((detail) => {
          if (
            mounted.current &&
            detailRequestSequence.current === requestSequence &&
            !controller.signal.aborted
          ) {
            const focusedElement = document.activeElement;
            const focusedActionWillBeRemoved =
              detail.status === "RESOLVED" &&
              focusedElement !== null &&
              detailFocus.current
                ?.querySelector(".alert-actions")
                ?.contains(focusedElement);
            if (
              detail.status === "RESOLVED" &&
              (recoveryPending.current || focusedActionWillBeRemoved)
            ) {
              focusStableDetailTarget();
            }
            setDetailState({ kind: "ready", detail, refreshFailed: false });
            finishRecovery(true);
          }
        })
        .catch((error: unknown) => {
          if (
            !mounted.current ||
            detailRequestSequence.current !== requestSequence
          ) {
            return;
          }
          if (error instanceof AlertSessionExpiredError) {
            finishRecovery(false);
            onSessionExpired();
            return;
          }
          if (error instanceof AlertNotFoundError) {
            setDetailState({ kind: "not-found" });
            if (!detailTrailingRefresh.current) {
              finishRecovery(false);
            }
            return;
          }
          if (error instanceof AlertForbiddenError) {
            setDetailState({ kind: "forbidden" });
            if (!detailTrailingRefresh.current) {
              finishRecovery(false);
            }
            return;
          }
          setDetailState((current) =>
            current.kind === "ready"
              ? { ...current, refreshFailed: true }
              : { kind: "unavailable" },
          );
          if (!detailTrailingRefresh.current) {
            finishRecovery(false);
          }
        })
        .finally(() => {
          window.clearTimeout(timeoutId);
          if (detailRequestSequence.current !== requestSequence) {
            return;
          }
          detailRequestActive.current = false;
          if (detailController.current === controller) {
            detailController.current = null;
          }
          if (mounted.current && detailTrailingRefresh.current) {
            detailTrailingRefresh.current = false;
            requestDetailRefresh(false);
          }
        });
    },
    [alertId, finishRecovery, focusStableDetailTarget, onSessionExpired],
  );

  useEffect(() => {
    mounted.current = true;
    detailFocus.current?.focus();
    refreshDetail(true);
    return () => {
      mounted.current = false;
      cancelDetailRefresh();
      commandController.current?.abort();
      createController.current?.abort();
      createRecoveryController.current?.abort();
    };
  }, [cancelDetailRefresh, refreshDetail]);

  useEffect(() => {
    if (observedRefreshVersion.current !== refreshVersion) {
      observedRefreshVersion.current = refreshVersion;
      refreshDetail(false);
    }
  }, [refreshDetail, refreshVersion]);

  useEffect(() => {
    if (focusFeedbackAfterUpdate.current && actionFeedback !== null) {
      focusFeedbackAfterUpdate.current = false;
      feedbackFocus.current?.focus();
    }
  }, [actionFeedback]);

  function handleRecoveryRetry() {
    if (actionState !== "recovery-failed") {
      return;
    }
    focusStableDetailTarget();
    cancelDetailRefresh();
    recoveryPending.current = true;
    setActionState("recovering");
    setActionFeedback((current) => ({
      kind: current?.kind === "conflict" ? "conflict" : "uncertain",
      message: "Retrying the latest saved alert state…",
    }));
    refreshDetail(false);
  }

  async function handleCommand(detail: AlertDetail) {
    if (actionState !== "idle") {
      return;
    }

    const command =
      detail.status === "OPEN"
        ? acknowledgeAlert
        : detail.status === "ACKNOWLEDGED"
          ? resolveAlert
          : null;
    if (command === null) {
      return;
    }

    setActionState("submitting");
    setActionFeedback(null);
    cancelDetailRefresh();
    const controller = new AbortController();
    commandController.current = controller;
    const timeoutId = window.setTimeout(
      () => controller.abort(),
      API_TIMEOUT_MS,
    );

    try {
      const updated = await command(alertId, csrfToken, controller.signal);
      if (!mounted.current) {
        return;
      }
      cancelDetailRefresh();
      setDetailState({
        kind: "ready",
        detail: updated,
        refreshFailed: false,
      });
      focusFeedbackAfterUpdate.current = updated.status === "RESOLVED";
      setActionFeedback({
        kind: "success",
        message:
          updated.status === "ACKNOWLEDGED"
            ? "Alert acknowledged."
            : "Alert resolved.",
      });
      onRefreshQueue();
    } catch (error: unknown) {
      if (!mounted.current) {
        return;
      }
      if (
        error instanceof AlertSessionExpiredError ||
        error instanceof AlertRequestVerificationError
      ) {
        onSessionExpired();
        return;
      }
      if (error instanceof AlertNotFoundError) {
        setDetailState({ kind: "not-found" });
        onRefreshQueue();
        return;
      }
      if (error instanceof AlertForbiddenError) {
        setActionFeedback({
          kind: "forbidden",
          message: "You no longer have permission to change this alert.",
        });
        return;
      }
      if (error instanceof AlertStateConflictError) {
        recoveryPending.current = true;
        setActionState("recovering");
        setActionFeedback({
          kind: "conflict",
          message:
            "This alert changed in another client. Recovering the latest saved state before another command.",
        });
      } else if (error instanceof AlertCommandUncertainError) {
        recoveryPending.current = true;
        setActionState("recovering");
        setActionFeedback({
          kind: "uncertain",
          message:
            "The command result could not be confirmed. Recovering the latest saved state before another command.",
        });
      } else {
        recoveryPending.current = true;
        setActionState("recovering");
        setActionFeedback({
          kind: "uncertain",
          message:
            "The alert could not be changed. Recovering the latest saved state before another command.",
        });
      }
      cancelDetailRefresh();
      refreshDetail(false);
      onRefreshQueue();
    } finally {
      window.clearTimeout(timeoutId);
      if (mounted.current && !recoveryPending.current) {
        setActionState("idle");
      }
      if (commandController.current === controller) {
        commandController.current = null;
      }
    }
  }

  const recoverWorkOrderCreation = useCallback(
    async (reason: CreateRecoveryReason) => {
      createRecoveryReason.current = reason;
      setCreateActionState("recovering");
      setCreateFeedback({
        kind: reason,
        message:
          reason === "duplicate"
            ? "A work order already exists for this alert. Confirming it from the saved work-order queue."
            : "The create result could not be confirmed. Checking the saved work-order queue before another action.",
      });

      createRecoveryController.current?.abort();
      const controller = new AbortController();
      createRecoveryController.current = controller;
      const timeoutId = window.setTimeout(
        () => controller.abort(),
        API_TIMEOUT_MS,
      );
      try {
        const result = await getWorkOrders(
          DEFAULT_WORK_ORDER_LIMIT,
          controller.signal,
        );
        if (!mounted.current || controller.signal.aborted) {
          return;
        }
        const recovered = result.workOrders.find(
          (workOrder) =>
            workOrder.alertId.toLowerCase() === alertId.toLowerCase(),
        );
        if (recovered !== undefined) {
          setCreateActionState(reason === "duplicate" ? "existing" : "created");
          setCreateFeedback({
            kind: reason === "duplicate" ? "duplicate" : "success",
            message:
              reason === "duplicate"
                ? "This alert already has a work order. Open Work orders to inspect it."
                : "The saved work-order queue confirms that the work order was created.",
          });
          return;
        }
        if (reason === "duplicate") {
          // The conflict is authoritative even when the record is outside the
          // bounded first page. Do not offer another create mutation.
          setCreateActionState("existing");
          setCreateFeedback({
            kind: "duplicate",
            message:
              "This alert already has a work order. It is outside the current bounded queue; open Work orders to refresh and inspect it.",
          });
          return;
        }
        setCreateActionState("recovery-failed");
        setCreateFeedback({
          kind: "uncertain",
          message:
            "The create result is still uncertain because no matching work order was visible in the bounded queue. Retry authoritative recovery before creating again.",
        });
      } catch (error: unknown) {
        if (!mounted.current) {
          return;
        }
        if (error instanceof WorkOrderSessionExpiredError) {
          onSessionExpired();
          return;
        }
        if (error instanceof WorkOrderForbiddenError) {
          setCreateActionState("blocked");
          setCreateFeedback({
            kind: "forbidden",
            message:
              "The server did not grant access to recover this work-order result.",
          });
          return;
        }
        setCreateActionState("recovery-failed");
        setCreateFeedback({
          kind: reason === "duplicate" ? "duplicate" : "uncertain",
          message:
            reason === "duplicate"
              ? "A work order already exists, but the saved work-order queue could not be loaded. Open Work orders and retry there."
              : "The create result and saved work-order queue could not be confirmed. Retry authoritative recovery before creating again.",
        });
      } finally {
        window.clearTimeout(timeoutId);
        if (createRecoveryController.current === controller) {
          createRecoveryController.current = null;
        }
      }
    },
    [alertId, onSessionExpired],
  );

  async function handleCreateWorkOrder() {
    if (createActionState !== "idle") {
      return;
    }
    setCreateActionState("submitting");
    setCreateFeedback(null);
    const controller = new AbortController();
    createController.current = controller;
    const timeoutId = window.setTimeout(
      () => controller.abort(),
      API_TIMEOUT_MS,
    );
    try {
      const created = await createWorkOrder(
        alertId,
        csrfToken,
        controller.signal,
      );
      if (!mounted.current) {
        return;
      }
      setCreateActionState("created");
      setCreateFeedback({
        kind: "success",
        message: `Work order created. Open Work orders to inspect ${created.context.assetName}.`,
      });
    } catch (error: unknown) {
      if (!mounted.current) {
        return;
      }
      if (
        error instanceof WorkOrderSessionExpiredError ||
        error instanceof WorkOrderRequestVerificationError
      ) {
        onSessionExpired();
        return;
      }
      if (error instanceof WorkOrderSourceAlertNotFoundError) {
        setCreateActionState("blocked");
        setCreateFeedback({
          kind: "not-found",
          message:
            "The source alert is no longer available, so a work order cannot be created.",
        });
        onRefreshQueue();
        return;
      }
      if (error instanceof WorkOrderForbiddenError) {
        setCreateActionState("blocked");
        setCreateFeedback({
          kind: "forbidden",
          message:
            "The server did not grant permission to create a work order.",
        });
        return;
      }
      await recoverWorkOrderCreation(
        error instanceof WorkOrderAlreadyExistsError
          ? "duplicate"
          : error instanceof WorkOrderCommandUncertainError
            ? "uncertain"
            : "uncertain",
      );
    } finally {
      window.clearTimeout(timeoutId);
      if (createController.current === controller) {
        createController.current = null;
      }
    }
  }

  return (
    <section
      ref={detailFocus}
      className="alert-section alert-detail"
      aria-labelledby="alert-detail-title"
      tabIndex={-1}
    >
      <button
        className="secondary-button secondary-button--compact alert-back"
        type="button"
        onClick={onBack}
      >
        Back to alerts
      </button>

      {detailState.kind === "loading" && (
        <div className="alert-state-panel">
          <p className="eyebrow">Live incident</p>
          <h2 id="alert-detail-title">Alert details</h2>
          <p className="asset-message" role="status" aria-live="polite">
            Loading alert details…
          </p>
        </div>
      )}

      {detailState.kind === "not-found" && (
        <div className="alert-state-panel">
          <p className="eyebrow">Live incident</p>
          <h2 id="alert-detail-title">Alert not found</h2>
          <p className="asset-message" role="status">
            The requested alert is not available.
          </p>
        </div>
      )}

      {detailState.kind === "forbidden" && (
        <div className="alert-state-panel">
          <p className="eyebrow">Live incident</p>
          <h2 id="alert-detail-title">Alert access denied</h2>
          <p className="asset-message" role="alert">
            You do not have permission to view this alert.
          </p>
        </div>
      )}

      {detailState.kind === "unavailable" && (
        <div className="alert-state-panel">
          <p className="eyebrow">Live incident</p>
          <h2 id="alert-detail-title">Alert details unavailable</h2>
          <p className="asset-message form-message--error" role="alert">
            We could not load this alert. Try again.
          </p>
          <button
            className="secondary-button secondary-button--compact"
            type="button"
            onClick={() => refreshDetail(true)}
          >
            Retry alert details
          </button>
        </div>
      )}

      {detailState.kind === "ready" && (
        <div className="alert-detail__content">
          <header className="alert-detail__header">
            <div>
              <p className="eyebrow">Live incident</p>
              <h2 id="alert-detail-title">
                {detailState.detail.context.thresholdRule.name}
              </h2>
              <p className="alert-detail__asset">
                {detailState.detail.context.asset.name} ·{" "}
                <code>{detailState.detail.context.asset.assetCode}</code>
              </p>
            </div>
            <StatusBadge status={detailState.detail.status} />
          </header>

          {detailState.refreshFailed && (
            <p className="form-message form-message--error" role="alert">
              Live refresh failed. The last confirmed alert detail is still
              shown.
            </p>
          )}

          <dl className="alert-facts">
            <div>
              <dt>Occurrences</dt>
              <dd>{detailState.detail.occurrenceCount}</dd>
            </div>
            <div>
              <dt>First observed</dt>
              <dd>
                <time dateTime={detailState.detail.firstOccurredAt}>
                  {formatTimestamp(detailState.detail.firstOccurredAt)}
                </time>
              </dd>
            </div>
            <div>
              <dt>Last observed</dt>
              <dd>
                <time dateTime={detailState.detail.lastOccurredAt}>
                  {formatTimestamp(detailState.detail.lastOccurredAt)}
                </time>
              </dd>
            </div>
            <div>
              <dt>Cooldown until</dt>
              <dd>
                <time dateTime={detailState.detail.cooldownUntil}>
                  {formatTimestamp(detailState.detail.cooldownUntil)}
                </time>
              </dd>
            </div>
            <div>
              <dt>Recorded</dt>
              <dd>
                <time dateTime={detailState.detail.createdAt}>
                  {formatTimestamp(detailState.detail.createdAt)}
                </time>
              </dd>
            </div>
            <div>
              <dt>Last updated</dt>
              <dd>
                <time dateTime={detailState.detail.updatedAt}>
                  {formatTimestamp(detailState.detail.updatedAt)}
                </time>
              </dd>
            </div>
          </dl>

          <section
            className="alert-context-section"
            aria-labelledby="alert-context-title"
          >
            <h3 id="alert-context-title">Threshold context</h3>
            <dl className="alert-context-grid">
              <div>
                <dt>Sensor</dt>
                <dd>{detailState.detail.context.sensor.name}</dd>
              </div>
              <div>
                <dt>Sensor key</dt>
                <dd>
                  <code>{detailState.detail.context.sensor.sensorKey}</code>
                </dd>
              </div>
              <div>
                <dt>Rule</dt>
                <dd>
                  <code>
                    {detailState.detail.context.thresholdRule.ruleCode}
                  </code>
                </dd>
              </div>
              <div>
                <dt>Condition</dt>
                <dd>
                  At or above{" "}
                  <data
                    value={String(
                      detailState.detail.context.thresholdRule.thresholdValue,
                    )}
                  >
                    {formatValue(
                      detailState.detail.context.thresholdRule.thresholdValue,
                    )}{" "}
                    °C
                  </data>
                </dd>
              </div>
            </dl>
          </section>

          <section
            className="alert-history-section"
            aria-labelledby="alert-history-title"
          >
            <h3 id="alert-history-title">Status history</h3>
            {detailState.detail.history.length === 0 ? (
              <p className="asset-message">
                No status transitions have been recorded.
              </p>
            ) : (
              <ol className="alert-history-list">
                {detailState.detail.history.map((entry) => (
                  <li key={entry.sequenceNumber}>
                    <div>
                      <strong>
                        {statusLabels[entry.fromStatus]} →{" "}
                        {statusLabels[entry.toStatus]}
                      </strong>
                      <span>{entry.actor.displayName}</span>
                    </div>
                    <time dateTime={entry.transitionedAt}>
                      {formatTimestamp(entry.transitionedAt)}
                    </time>
                  </li>
                ))}
              </ol>
            )}
          </section>

          {actionFeedback !== null && (
            <p
              ref={feedbackFocus}
              className={`form-message alert-action-feedback alert-action-feedback--${actionFeedback.kind}`}
              role={actionFeedback.kind === "success" ? "status" : "alert"}
              tabIndex={-1}
            >
              {actionFeedback.message}
            </p>
          )}

          {roleCode === "OPERATIONS_ADMIN" &&
            detailState.detail.status !== "RESOLVED" && (
              <div className="alert-actions">
                <button
                  className="primary-button"
                  type="button"
                  disabled={actionState !== "idle"}
                  onClick={() => void handleCommand(detailState.detail)}
                >
                  {actionState === "submitting"
                    ? "Saving alert…"
                    : actionState === "recovering"
                      ? "Recovering latest state…"
                      : actionState === "recovery-failed"
                        ? "Latest state required"
                        : detailState.detail.status === "OPEN"
                          ? "Acknowledge alert"
                          : "Resolve alert"}
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

          {roleCode === "OPERATIONS_ADMIN" && (
            <section
              className="work-order-create"
              aria-labelledby="work-order-create-title"
            >
              <div>
                <h3 id="work-order-create-title">Maintenance work order</h3>
                <p className="asset-message">
                  Create the single work order linked to this alert.
                </p>
              </div>

              {createFeedback !== null && (
                <p
                  className={`form-message work-order-create__feedback work-order-create__feedback--${createFeedback.kind}`}
                  role={createFeedback.kind === "success" ? "status" : "alert"}
                >
                  {createFeedback.message}
                </p>
              )}

              <div className="work-order-create__actions">
                <button
                  className="primary-button"
                  type="button"
                  disabled={createActionState !== "idle"}
                  onClick={() => void handleCreateWorkOrder()}
                >
                  {createActionState === "submitting"
                    ? "Creating work order…"
                    : createActionState === "recovering"
                      ? "Checking saved work orders…"
                      : createActionState === "recovery-failed"
                        ? "Authoritative check required"
                        : createActionState === "created"
                          ? "Work order created"
                          : createActionState === "existing"
                            ? "Work order already exists"
                            : createActionState === "blocked"
                              ? "Create unavailable"
                              : "Create work order"}
                </button>
                {createActionState === "recovery-failed" && (
                  <button
                    className="secondary-button secondary-button--compact"
                    type="button"
                    onClick={() =>
                      void recoverWorkOrderCreation(
                        createRecoveryReason.current,
                      )
                    }
                  >
                    Retry authoritative check
                  </button>
                )}
              </div>
            </section>
          )}
        </div>
      )}
    </section>
  );
}

export function AlertPanel({
  roleCode,
  csrfToken,
  onSessionExpired,
  createEventSource,
}: Readonly<{
  roleCode: RoleCode;
  csrfToken: CsrfToken;
  onSessionExpired: () => void;
  createEventSource?: AlertEventSourceFactory;
}>) {
  const sectionTitleId = useId();
  const [queueState, setQueueState] = useState<AlertQueueState>({
    kind: "loading",
  });
  const [selectedAlertId, setSelectedAlertId] = useState<string | null>(null);
  const [streamState, setStreamState] = useState<StreamState>("connecting");
  const [detailRefreshVersion, setDetailRefreshVersion] = useState(0);
  const queueRequestSequence = useRef(0);
  const queueController = useRef<AbortController | null>(null);
  const queueRequestActive = useRef(false);
  const queueTrailingRefresh = useRef(false);
  const queueTrailingShowLoading = useRef(false);
  const queueMounted = useRef(true);
  const selectedAlertIdRef = useRef<string | null>(null);
  const originatingAlertId = useRef<string | null>(null);
  const rowButtons = useRef(new Map<string, HTMLButtonElement>());

  useEffect(() => {
    selectedAlertIdRef.current = selectedAlertId;
    if (selectedAlertId === null && originatingAlertId.current !== null) {
      rowButtons.current.get(originatingAlertId.current)?.focus();
      originatingAlertId.current = null;
    }
  }, [selectedAlertId]);

  const refreshQueue = useCallback(
    function requestQueueRefresh(showLoading: boolean) {
      if (!queueMounted.current) {
        return;
      }
      if (queueRequestActive.current) {
        queueTrailingRefresh.current = true;
        queueTrailingShowLoading.current =
          queueTrailingShowLoading.current || showLoading;
        return;
      }

      queueRequestActive.current = true;
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

      void getAlerts(DEFAULT_ALERT_LIMIT, controller.signal)
        .then((result) => {
          if (
            queueRequestSequence.current === requestSequence &&
            !controller.signal.aborted
          ) {
            setQueueState({ kind: "ready", result, refreshFailed: false });
          }
        })
        .catch((error: unknown) => {
          if (queueRequestSequence.current !== requestSequence) {
            return;
          }
          if (error instanceof AlertSessionExpiredError) {
            onSessionExpired();
            return;
          }
          if (error instanceof AlertForbiddenError) {
            setQueueState({ kind: "forbidden" });
            return;
          }
          setQueueState((current) =>
            current.kind === "ready"
              ? { ...current, refreshFailed: true }
              : { kind: "unavailable" },
          );
        })
        .finally(() => {
          window.clearTimeout(timeoutId);
          if (queueRequestSequence.current !== requestSequence) {
            return;
          }
          queueRequestActive.current = false;
          if (queueController.current === controller) {
            queueController.current = null;
          }
          if (queueMounted.current && queueTrailingRefresh.current) {
            const trailingShowLoading = queueTrailingShowLoading.current;
            queueTrailingRefresh.current = false;
            queueTrailingShowLoading.current = false;
            requestQueueRefresh(trailingShowLoading);
          }
        });
    },
    [onSessionExpired],
  );

  useEffect(() => {
    queueMounted.current = true;
    refreshQueue(true);
    return () => {
      queueMounted.current = false;
      queueRequestSequence.current += 1;
      queueRequestActive.current = false;
      queueTrailingRefresh.current = false;
      queueTrailingShowLoading.current = false;
      queueController.current?.abort();
    };
  }, [refreshQueue]);

  useEffect(() => {
    let active = true;
    try {
      const unsubscribe = subscribeToAlertChanges(
        {
          onOpen: () => {
            if (!active) {
              return;
            }
            setStreamState("connected");
            refreshQueue(false);
            if (selectedAlertIdRef.current !== null) {
              setDetailRefreshVersion((version) => version + 1);
            }
          },
          onChange: (change) => {
            if (!active) {
              return;
            }
            setStreamState("connected");
            refreshQueue(false);
            if (selectedAlertIdRef.current === change.alertId) {
              setDetailRefreshVersion((version) => version + 1);
            }
          },
          onError: () => {
            if (!active) {
              return;
            }
            setStreamState("reconnecting");
            refreshQueue(false);
          },
          onProtocolError: () => {
            if (!active) {
              return;
            }
            setStreamState("degraded");
            refreshQueue(false);
            if (selectedAlertIdRef.current !== null) {
              setDetailRefreshVersion((version) => version + 1);
            }
          },
        },
        createEventSource,
      );
      return () => {
        active = false;
        unsubscribe();
      };
    } catch {
      setStreamState("reconnecting");
      return undefined;
    }
  }, [createEventSource, refreshQueue]);

  if (selectedAlertId !== null) {
    return (
      <AlertDetailPanel
        alertId={selectedAlertId}
        roleCode={roleCode}
        csrfToken={csrfToken}
        refreshVersion={detailRefreshVersion}
        onBack={() => setSelectedAlertId(null)}
        onSessionExpired={onSessionExpired}
        onRefreshQueue={() => refreshQueue(false)}
      />
    );
  }

  return (
    <section className="alert-section" aria-labelledby={sectionTitleId}>
      <div className="alert-section__heading">
        <div>
          <p className="eyebrow">Live incident queue</p>
          <h2 id={sectionTitleId}>Alerts</h2>
          <p
            className={`alert-stream-state alert-stream-state--${streamState}`}
            role="status"
            aria-live="polite"
          >
            <span aria-hidden="true" />
            {streamLabels[streamState]}
          </p>
        </div>
        <div className="alert-heading-actions">
          {queueState.kind === "ready" && (
            <p className="asset-count" aria-label="Loaded alert count">
              {queueState.result.alerts.length}
            </p>
          )}
          <button
            className="secondary-button secondary-button--compact"
            type="button"
            disabled={queueState.kind === "loading"}
            onClick={() => refreshQueue(queueState.kind !== "ready")}
          >
            Refresh alerts
          </button>
        </div>
      </div>

      {queueState.kind === "loading" && (
        <p className="asset-message" role="status" aria-live="polite">
          Loading alerts…
        </p>
      )}

      {queueState.kind === "unavailable" && (
        <div className="alert-unavailable">
          <p className="asset-message form-message--error" role="alert">
            We could not load alerts. Try again.
          </p>
          <button
            className="secondary-button secondary-button--compact"
            type="button"
            onClick={() => refreshQueue(true)}
          >
            Retry alerts
          </button>
        </div>
      )}

      {queueState.kind === "forbidden" && (
        <div className="alert-state-panel">
          <h3>Alert access denied</h3>
          <p className="asset-message" role="alert">
            You do not have permission to view this alert queue.
          </p>
        </div>
      )}

      {queueState.kind === "ready" && queueState.refreshFailed && (
        <p className="form-message form-message--error" role="alert">
          Live refresh failed. The last confirmed alert queue is still shown.
        </p>
      )}

      {queueState.kind === "ready" && queueState.result.alerts.length === 0 && (
        <p className="asset-message">
          No alerts are available for this organisation.
        </p>
      )}

      {queueState.kind === "ready" && queueState.result.alerts.length > 0 && (
        <ul className="alert-list">
          {queueState.result.alerts.map((alert) => (
            <li key={alert.id}>
              <button
                ref={(button) => {
                  if (button === null) {
                    rowButtons.current.delete(alert.id);
                  } else {
                    rowButtons.current.set(alert.id, button);
                  }
                }}
                className="alert-row-button"
                type="button"
                aria-label={`View ${statusLabels[alert.status].toLowerCase()} alert for ${alert.context.asset.name}: ${alert.context.thresholdRule.name}`}
                onClick={() => {
                  originatingAlertId.current = alert.id;
                  setSelectedAlertId(alert.id);
                }}
              >
                <span className="alert-row-button__primary">
                  <span className="alert-row-button__title">
                    {alert.context.thresholdRule.name}
                  </span>
                  <span className="alert-row-button__asset">
                    {alert.context.asset.name} ·{" "}
                    <code>{alert.context.asset.assetCode}</code>
                  </span>
                </span>
                <span className="alert-row-button__facts">
                  <StatusBadge status={alert.status} />
                  <span>
                    {alert.occurrenceCount}{" "}
                    {alert.occurrenceCount === 1 ? "occurrence" : "occurrences"}
                  </span>
                  <time dateTime={alert.lastOccurredAt}>
                    {formatTimestamp(alert.lastOccurredAt)}
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
