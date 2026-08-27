import { useCallback, useEffect, useId, useRef, useState } from "react";

import {
  assignWorkOrder,
  completeWorkOrder,
  DEFAULT_WORK_ORDER_LIMIT,
  getEligibleTechnicians,
  getWorkOrderDetail,
  getWorkOrders,
  InvalidWorkOrderAssigneeError,
  startWorkOrder,
  WorkOrderCommandUncertainError,
  WorkOrderForbiddenError,
  WorkOrderNotFoundError,
  WorkOrderRequestVerificationError,
  WorkOrderSessionExpiredError,
  WorkOrderStateConflictError,
} from "./api/workOrders";
import type {
  EligibleTechnicianList,
  WorkOrder,
  WorkOrderDetail,
  WorkOrderList,
  WorkOrderStatus,
} from "./api/workOrders";
import type { CsrfToken, SessionIdentity } from "./api/session";

type QueueState =
  | Readonly<{ kind: "loading" }>
  | Readonly<{
      kind: "ready";
      result: WorkOrderList;
      refreshFailed: boolean;
    }>
  | Readonly<{ kind: "forbidden" }>
  | Readonly<{ kind: "unavailable" }>;

type DetailState =
  | Readonly<{ kind: "loading" }>
  | Readonly<{
      kind: "ready";
      workOrder: WorkOrderDetail;
      refreshFailed: boolean;
    }>
  | Readonly<{ kind: "not-found" }>
  | Readonly<{ kind: "forbidden" }>
  | Readonly<{ kind: "unavailable" }>;

type TechnicianState =
  | Readonly<{ kind: "idle" | "loading" }>
  | Readonly<{ kind: "ready"; result: EligibleTechnicianList }>
  | Readonly<{ kind: "forbidden" }>
  | Readonly<{ kind: "unavailable" }>;

type ActionState = "idle" | "submitting" | "recovering" | "recovery-failed";
type RecoveryReason = "conflict" | "uncertain";
type WorkOrderCommand = "assign" | "start" | "complete";
type Feedback = Readonly<{
  kind: "success" | "conflict" | "uncertain" | "invalid";
  message: string;
}>;

const API_TIMEOUT_MS = 5_000;

const statusLabels: Record<WorkOrderStatus, string> = {
  OPEN: "Open",
  ASSIGNED: "Assigned",
  IN_PROGRESS: "In progress",
  DONE: "Done",
};

function isAssignedTechnician(workOrder: WorkOrder, identity: SessionIdentity) {
  return (
    identity.role.code === "TECHNICIAN" &&
    workOrder.assignedTechnician?.id.toLowerCase() ===
      identity.userId.toLowerCase()
  );
}

function formatTimestamp(value: string) {
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: "medium",
    timeStyle: "medium",
    timeZone: "UTC",
  }).format(new Date(value));
}

function StatusBadge({ status }: Readonly<{ status: WorkOrderStatus }>) {
  return (
    <span
      className={`work-order-status work-order-status--${status.toLowerCase()}`}
    >
      {statusLabels[status]}
    </span>
  );
}

function WorkOrderDetailPanel({
  workOrderId,
  identity,
  csrfToken,
  onBack,
  onSessionExpired,
  onRefreshQueue,
}: Readonly<{
  workOrderId: string;
  identity: SessionIdentity;
  csrfToken: CsrfToken;
  onBack: () => void;
  onSessionExpired: () => void;
  onRefreshQueue: () => void;
}>) {
  const [detailState, setDetailState] = useState<DetailState>({
    kind: "loading",
  });
  const [technicianState, setTechnicianState] = useState<TechnicianState>({
    kind: "idle",
  });
  const [selectedTechnicianId, setSelectedTechnicianId] = useState("");
  const [actionState, setActionState] = useState<ActionState>("idle");
  const [feedback, setFeedback] = useState<Feedback | null>(null);
  const detailController = useRef<AbortController | null>(null);
  const technicianController = useRef<AbortController | null>(null);
  const commandController = useRef<AbortController | null>(null);
  const requestSequence = useRef(0);
  const mounted = useRef(true);
  const recoveryReason = useRef<RecoveryReason>("uncertain");
  const recoveryInFlight = useRef(false);
  const detailFocus = useRef<HTMLElement | null>(null);
  const feedbackFocus = useRef<HTMLParagraphElement | null>(null);

  const cancelDetailRead = useCallback(() => {
    requestSequence.current += 1;
    detailController.current?.abort();
    detailController.current = null;
  }, []);

  const readTechnicians = useCallback(async () => {
    if (identity.role.code !== "OPERATIONS_ADMIN") {
      return;
    }
    technicianController.current?.abort();
    const controller = new AbortController();
    technicianController.current = controller;
    const timeoutId = window.setTimeout(
      () => controller.abort(),
      API_TIMEOUT_MS,
    );
    setTechnicianState({ kind: "loading" });
    try {
      const result = await getEligibleTechnicians(controller.signal);
      if (!mounted.current || controller.signal.aborted) {
        return;
      }
      setTechnicianState({ kind: "ready", result });
      setSelectedTechnicianId((current) =>
        result.technicians.some((technician) => technician.id === current)
          ? current
          : (result.technicians[0]?.id ?? ""),
      );
    } catch (error: unknown) {
      if (!mounted.current) {
        return;
      }
      if (error instanceof WorkOrderSessionExpiredError) {
        onSessionExpired();
        return;
      }
      setTechnicianState(
        error instanceof WorkOrderForbiddenError
          ? { kind: "forbidden" }
          : { kind: "unavailable" },
      );
    } finally {
      window.clearTimeout(timeoutId);
      if (technicianController.current === controller) {
        technicianController.current = null;
      }
    }
  }, [identity.role.code, onSessionExpired]);

  const readDetail = useCallback(
    async function requestDetail(
      showLoading: boolean,
    ): Promise<"success" | "failed" | "terminal" | "expired" | "superseded"> {
      cancelDetailRead();
      const sequence = requestSequence.current + 1;
      requestSequence.current = sequence;
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
      try {
        const workOrder = await getWorkOrderDetail(
          workOrderId,
          controller.signal,
        );
        if (!mounted.current || requestSequence.current !== sequence) {
          return "superseded";
        }
        if (controller.signal.aborted) {
          throw new Error("The work-order detail request timed out");
        }
        setDetailState({ kind: "ready", workOrder, refreshFailed: false });
        return "success";
      } catch (error: unknown) {
        if (!mounted.current || requestSequence.current !== sequence) {
          return "superseded";
        }
        if (error instanceof WorkOrderSessionExpiredError) {
          onSessionExpired();
          return "expired";
        }
        if (error instanceof WorkOrderNotFoundError) {
          setDetailState({ kind: "not-found" });
          return "terminal";
        }
        if (error instanceof WorkOrderForbiddenError) {
          setDetailState({ kind: "forbidden" });
          return "terminal";
        }
        setDetailState((current) =>
          current.kind === "ready"
            ? { ...current, refreshFailed: true }
            : { kind: "unavailable" },
        );
        return "failed";
      } finally {
        window.clearTimeout(timeoutId);
        if (detailController.current === controller) {
          detailController.current = null;
        }
      }
    },
    [cancelDetailRead, onSessionExpired, workOrderId],
  );

  useEffect(() => {
    mounted.current = true;
    detailFocus.current?.focus();
    void readDetail(true);
    return () => {
      mounted.current = false;
      cancelDetailRead();
      technicianController.current?.abort();
      commandController.current?.abort();
    };
  }, [cancelDetailRead, readDetail]);

  useEffect(() => {
    if (
      identity.role.code === "OPERATIONS_ADMIN" &&
      detailState.kind === "ready" &&
      detailState.workOrder.status === "OPEN" &&
      technicianState.kind === "idle"
    ) {
      void readTechnicians();
    }
  }, [detailState, identity.role.code, readTechnicians, technicianState.kind]);

  useEffect(() => {
    if (detailState.kind === "not-found" || detailState.kind === "forbidden") {
      detailFocus.current?.focus();
    } else if (feedback !== null) {
      feedbackFocus.current?.focus();
    }
  }, [detailState.kind, feedback]);

  const recoverLatest = useCallback(
    async (reason: RecoveryReason) => {
      if (recoveryInFlight.current) {
        return;
      }
      recoveryInFlight.current = true;
      recoveryReason.current = reason;
      setActionState("recovering");
      setFeedback({
        kind: reason,
        message:
          reason === "conflict"
            ? "This work order changed before the update was accepted. Loading the latest saved state."
            : "The work-order update result could not be confirmed. Loading the latest saved state.",
      });
      const outcome = await readDetail(false);
      recoveryInFlight.current = false;
      if (
        !mounted.current ||
        outcome === "superseded" ||
        outcome === "expired"
      ) {
        return;
      }
      if (outcome === "terminal") {
        setActionState("idle");
        return;
      }
      if (outcome === "failed") {
        setActionState("recovery-failed");
        setFeedback({
          kind: reason,
          message:
            reason === "conflict"
              ? "This work order changed, but its latest saved state could not be loaded. Retry latest state before making another change."
              : "The work-order update result and latest saved state could not be confirmed. Retry latest state before making another change.",
        });
        return;
      }
      setActionState("idle");
      setFeedback({
        kind: reason,
        message:
          reason === "conflict"
            ? "This work order changed in another client. The latest saved state is now loaded."
            : "The work-order update result could not be confirmed. The latest saved state is now loaded.",
      });
      onRefreshQueue();
    },
    [onRefreshQueue, readDetail],
  );

  async function handleCommand(
    workOrder: WorkOrderDetail,
    command: WorkOrderCommand,
  ) {
    const permitted =
      command === "assign"
        ? identity.role.code === "OPERATIONS_ADMIN" &&
          workOrder.status === "OPEN" &&
          selectedTechnicianId !== ""
        : isAssignedTechnician(workOrder, identity) &&
          workOrder.status ===
            (command === "start" ? "ASSIGNED" : "IN_PROGRESS");
    if (
      actionState !== "idle" ||
      commandController.current !== null ||
      !permitted
    ) {
      return;
    }
    setActionState("submitting");
    setFeedback(null);
    cancelDetailRead();
    const controller = new AbortController();
    commandController.current = controller;
    const timeoutId = window.setTimeout(
      () => controller.abort(),
      API_TIMEOUT_MS,
    );
    try {
      const updated = await (command === "assign"
        ? assignWorkOrder(
            workOrder.id,
            selectedTechnicianId,
            workOrder.version,
            csrfToken,
            controller.signal,
          )
        : (command === "start" ? startWorkOrder : completeWorkOrder)(
            workOrder.id,
            workOrder.version,
            csrfToken,
            controller.signal,
          ));
      if (!mounted.current) {
        return;
      }
      if (controller.signal.aborted) {
        throw new WorkOrderCommandUncertainError();
      }
      setDetailState({
        kind: "ready",
        workOrder: updated,
        refreshFailed: false,
      });
      setActionState("idle");
      setFeedback({
        kind: "success",
        message:
          command === "assign"
            ? `Work order assigned to ${updated.assignedTechnician!.displayName}.`
            : command === "start"
              ? "Work started. The work order is now in progress."
              : "Work completed. The work order is now done.",
      });
      onRefreshQueue();
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
      if (error instanceof WorkOrderNotFoundError) {
        setActionState("idle");
        setDetailState({ kind: "not-found" });
        onRefreshQueue();
        return;
      }
      if (error instanceof WorkOrderForbiddenError) {
        setActionState("idle");
        setDetailState({ kind: "forbidden" });
        return;
      }
      if (error instanceof InvalidWorkOrderAssigneeError) {
        setActionState("idle");
        setSelectedTechnicianId("");
        setFeedback({
          kind: "invalid",
          message:
            "That technician is no longer eligible. The eligible list is being refreshed.",
        });
        void readTechnicians();
        return;
      }
      await recoverLatest(
        error instanceof WorkOrderStateConflictError ? "conflict" : "uncertain",
      );
    } finally {
      window.clearTimeout(timeoutId);
      if (commandController.current === controller) {
        commandController.current = null;
      }
    }
  }

  const readyWorkOrder =
    detailState.kind === "ready" ? detailState.workOrder : null;
  const ownedAction =
    readyWorkOrder !== null && isAssignedTechnician(readyWorkOrder, identity)
      ? readyWorkOrder.status === "ASSIGNED"
        ? "start"
        : readyWorkOrder.status === "IN_PROGRESS"
          ? "complete"
          : null
      : null;
  const canAssign =
    identity.role.code === "OPERATIONS_ADMIN" &&
    readyWorkOrder?.status === "OPEN";

  return (
    <section
      ref={detailFocus}
      className="work-order-section work-order-detail"
      aria-labelledby="work-order-detail-title"
      tabIndex={-1}
    >
      <button
        className="secondary-button secondary-button--compact work-order-back"
        type="button"
        disabled={actionState === "submitting" || actionState === "recovering"}
        onClick={onBack}
      >
        Back to work orders
      </button>

      {detailState.kind === "loading" && (
        <div className="work-order-state-panel">
          <p className="eyebrow">Maintenance coordination</p>
          <h2 id="work-order-detail-title">Work order details</h2>
          <p className="asset-message" role="status" aria-live="polite">
            Loading work order details…
          </p>
        </div>
      )}

      {detailState.kind === "not-found" && (
        <div className="work-order-state-panel">
          <p className="eyebrow">Maintenance coordination</p>
          <h2 id="work-order-detail-title">Work order not found</h2>
          <p className="asset-message" role="status">
            The requested work order is not available.
          </p>
        </div>
      )}

      {detailState.kind === "forbidden" && (
        <div className="work-order-state-panel">
          <p className="eyebrow">Maintenance coordination</p>
          <h2 id="work-order-detail-title">Work order access denied</h2>
          <p className="asset-message" role="alert">
            The server did not grant access to this work order.
          </p>
        </div>
      )}

      {detailState.kind === "unavailable" && (
        <div className="work-order-state-panel">
          <p className="eyebrow">Maintenance coordination</p>
          <h2 id="work-order-detail-title">Work order unavailable</h2>
          <p className="asset-message form-message--error" role="alert">
            We could not load this work order. Try again.
          </p>
          <button
            className="secondary-button secondary-button--compact"
            type="button"
            onClick={() => void readDetail(true)}
          >
            Retry work order details
          </button>
        </div>
      )}

      {readyWorkOrder !== null && (
        <div className="work-order-detail__content">
          <header className="work-order-detail__header">
            <div>
              <p className="eyebrow">Maintenance coordination</p>
              <h2 id="work-order-detail-title">
                {readyWorkOrder.context.ruleName}
              </h2>
              <p className="work-order-detail__asset">
                {readyWorkOrder.context.assetName} ·{" "}
                <code>{readyWorkOrder.context.assetCode}</code>
              </p>
            </div>
            <div className="work-order-detail__badges">
              <StatusBadge status={readyWorkOrder.status} />
              {!canAssign && ownedAction === null && (
                <span className="readonly-badge">Read-only</span>
              )}
            </div>
          </header>

          {detailState.kind === "ready" && detailState.refreshFailed && (
            <p className="form-message form-message--error" role="alert">
              Refresh failed. The last confirmed work order remains shown.
            </p>
          )}

          <dl className="work-order-facts">
            <div>
              <dt>Work order</dt>
              <dd>
                <code>{readyWorkOrder.id}</code>
              </dd>
            </div>
            <div>
              <dt>Source alert</dt>
              <dd>
                <code>{readyWorkOrder.alertId}</code>
              </dd>
            </div>
            <div>
              <dt>Version</dt>
              <dd>{readyWorkOrder.version}</dd>
            </div>
            <div>
              <dt>Assigned technician</dt>
              <dd>
                {readyWorkOrder.assignedTechnician?.displayName ??
                  "Not assigned"}
              </dd>
            </div>
            <div>
              <dt>Created</dt>
              <dd>
                <time dateTime={readyWorkOrder.createdAt}>
                  {formatTimestamp(readyWorkOrder.createdAt)}
                </time>
              </dd>
            </div>
            <div>
              <dt>Last updated</dt>
              <dd>
                <time dateTime={readyWorkOrder.updatedAt}>
                  {formatTimestamp(readyWorkOrder.updatedAt)}
                </time>
              </dd>
            </div>
          </dl>

          <section
            className="work-order-history-section"
            aria-labelledby="work-order-history-title"
          >
            <h3 id="work-order-history-title">Status history</h3>
            {readyWorkOrder.history.length === 0 ? (
              <p className="asset-message">
                No status transitions have been recorded.
              </p>
            ) : (
              <>
                <p className="asset-message">
                  Transitions are shown oldest first. Times are UTC.
                </p>
                <ol
                  className="work-order-history-list"
                  aria-label="Work-order status history"
                >
                  {readyWorkOrder.history.map((entry) => (
                    <li key={entry.sequenceNumber}>
                      <div>
                        <strong>
                          {statusLabels[entry.fromStatus]} →{" "}
                          {statusLabels[entry.toStatus]}
                        </strong>
                        <span>
                          {entry.actor?.displayName ??
                            "Unknown actor (assignment predates history tracking)"}
                        </span>
                      </div>
                      <time dateTime={entry.transitionedAt}>
                        {formatTimestamp(entry.transitionedAt)}
                      </time>
                    </li>
                  ))}
                </ol>
              </>
            )}
          </section>

          {feedback !== null && (
            <p
              ref={feedbackFocus}
              className={`form-message work-order-action-feedback work-order-action-feedback--${feedback.kind}`}
              role={feedback.kind === "success" ? "status" : "alert"}
              tabIndex={-1}
            >
              {feedback.message}
            </p>
          )}

          {actionState === "recovery-failed" && (
            <button
              className="secondary-button secondary-button--compact"
              type="button"
              onClick={() => void recoverLatest(recoveryReason.current)}
            >
              Retry latest state
            </button>
          )}

          {canAssign && (
            <section
              className="work-order-assignment"
              aria-labelledby="work-order-assignment-title"
            >
              <h3 id="work-order-assignment-title">Assign technician</h3>
              {technicianState.kind === "loading" && (
                <p className="asset-message" role="status">
                  Loading eligible technicians…
                </p>
              )}
              {technicianState.kind === "forbidden" && (
                <p className="asset-message" role="alert">
                  Eligible-technician access was denied.
                </p>
              )}
              {technicianState.kind === "unavailable" && (
                <div className="work-order-state-panel">
                  <p className="asset-message form-message--error" role="alert">
                    Eligible technicians are unavailable.
                  </p>
                  <button
                    className="secondary-button secondary-button--compact"
                    type="button"
                    onClick={() => void readTechnicians()}
                  >
                    Retry eligible technicians
                  </button>
                </div>
              )}
              {technicianState.kind === "ready" &&
                technicianState.result.technicians.length === 0 && (
                  <p className="asset-message">
                    No eligible technicians are available.
                  </p>
                )}
              {technicianState.kind === "ready" &&
                technicianState.result.technicians.length > 0 && (
                  <div className="work-order-assignment__controls">
                    <label>
                      <span>Eligible technician</span>
                      <select
                        value={selectedTechnicianId}
                        disabled={actionState !== "idle"}
                        onChange={(event) =>
                          setSelectedTechnicianId(event.target.value)
                        }
                      >
                        {technicianState.result.technicians.map(
                          (technician) => (
                            <option key={technician.id} value={technician.id}>
                              {technician.displayName}
                            </option>
                          ),
                        )}
                      </select>
                    </label>
                    <button
                      className="primary-button"
                      type="button"
                      disabled={
                        actionState !== "idle" || selectedTechnicianId === ""
                      }
                      onClick={() =>
                        void handleCommand(readyWorkOrder, "assign")
                      }
                    >
                      {actionState === "submitting"
                        ? "Assigning work order…"
                        : actionState === "recovering"
                          ? "Recovering latest state…"
                          : actionState === "recovery-failed"
                            ? "Latest state required"
                            : "Assign work order"}
                    </button>
                  </div>
                )}
            </section>
          )}

          {ownedAction !== null && (
            <section
              className="work-order-lifecycle"
              aria-labelledby="work-order-lifecycle-title"
            >
              <h3 id="work-order-lifecycle-title">Update your work</h3>
              <p className="asset-message">You are the assigned technician.</p>
              <button
                className="primary-button"
                type="button"
                disabled={actionState !== "idle"}
                onClick={() => void handleCommand(readyWorkOrder, ownedAction)}
              >
                {actionState === "submitting"
                  ? ownedAction === "start"
                    ? "Starting work…"
                    : "Completing work…"
                  : actionState === "recovering"
                    ? "Recovering latest state…"
                    : actionState === "recovery-failed"
                      ? "Latest state required"
                      : ownedAction === "start"
                        ? "Start work"
                        : "Complete work"}
              </button>
            </section>
          )}
          {ownedAction === null &&
            (readyWorkOrder.status === "ASSIGNED" ||
              readyWorkOrder.status === "IN_PROGRESS") && (
              <p className="asset-message">
                Only the assigned technician can update this work order.
              </p>
            )}
        </div>
      )}
    </section>
  );
}

export function WorkOrderPanel({
  identity,
  csrfToken,
  onSessionExpired,
}: Readonly<{
  identity: SessionIdentity;
  csrfToken: CsrfToken;
  onSessionExpired: () => void;
}>) {
  const titleId = useId();
  const [queueState, setQueueState] = useState<QueueState>({ kind: "loading" });
  const [selectedWorkOrderId, setSelectedWorkOrderId] = useState<string | null>(
    null,
  );
  const controller = useRef<AbortController | null>(null);
  const requestSequence = useRef(0);
  const mounted = useRef(true);
  const originatingId = useRef<string | null>(null);
  const rowButtons = useRef(new Map<string, HTMLButtonElement>());

  const readQueue = useCallback(
    async (showLoading: boolean) => {
      requestSequence.current += 1;
      controller.current?.abort();
      const sequence = requestSequence.current;
      const nextController = new AbortController();
      controller.current = nextController;
      const timeoutId = window.setTimeout(
        () => nextController.abort(),
        API_TIMEOUT_MS,
      );
      if (showLoading) {
        setQueueState((current) =>
          current.kind === "ready" ? current : { kind: "loading" },
        );
      }
      try {
        const result = await getWorkOrders(
          DEFAULT_WORK_ORDER_LIMIT,
          nextController.signal,
        );
        if (
          !mounted.current ||
          requestSequence.current !== sequence ||
          nextController.signal.aborted
        ) {
          return;
        }
        setQueueState({ kind: "ready", result, refreshFailed: false });
      } catch (error: unknown) {
        if (!mounted.current || requestSequence.current !== sequence) {
          return;
        }
        if (error instanceof WorkOrderSessionExpiredError) {
          onSessionExpired();
          return;
        }
        if (error instanceof WorkOrderForbiddenError) {
          setQueueState({ kind: "forbidden" });
          return;
        }
        setQueueState((current) =>
          current.kind === "ready"
            ? { ...current, refreshFailed: true }
            : { kind: "unavailable" },
        );
      } finally {
        window.clearTimeout(timeoutId);
        if (controller.current === nextController) {
          controller.current = null;
        }
      }
    },
    [onSessionExpired],
  );

  useEffect(() => {
    mounted.current = true;
    void readQueue(true);
    return () => {
      mounted.current = false;
      requestSequence.current += 1;
      controller.current?.abort();
    };
  }, [readQueue]);

  useEffect(() => {
    if (selectedWorkOrderId === null && originatingId.current !== null) {
      rowButtons.current.get(originatingId.current)?.focus();
      originatingId.current = null;
    }
  }, [selectedWorkOrderId]);

  if (selectedWorkOrderId !== null) {
    return (
      <WorkOrderDetailPanel
        workOrderId={selectedWorkOrderId}
        identity={identity}
        csrfToken={csrfToken}
        onBack={() => setSelectedWorkOrderId(null)}
        onSessionExpired={onSessionExpired}
        onRefreshQueue={() => void readQueue(false)}
      />
    );
  }

  return (
    <section className="work-order-section" aria-labelledby={titleId}>
      <div className="work-order-section__heading">
        <div>
          <p className="eyebrow">Maintenance coordination</p>
          <h2 id={titleId}>Work orders</h2>
          <p className="work-order-intro">
            {identity.role.code === "TECHNICIAN"
              ? "Only work orders assigned to your trusted session are shown."
              : "Inspect the bounded work-order queue for your organisation."}
          </p>
        </div>
        <div className="work-order-heading-actions">
          {queueState.kind === "ready" && (
            <p className="asset-count" aria-label="Loaded work order count">
              {queueState.result.workOrders.length}
            </p>
          )}
          <button
            className="secondary-button secondary-button--compact"
            type="button"
            disabled={queueState.kind === "loading"}
            onClick={() => void readQueue(queueState.kind !== "ready")}
          >
            Refresh work orders
          </button>
        </div>
      </div>

      {queueState.kind === "loading" && (
        <p className="asset-message" role="status" aria-live="polite">
          Loading work orders…
        </p>
      )}
      {queueState.kind === "unavailable" && (
        <div className="work-order-state-panel">
          <p className="asset-message form-message--error" role="alert">
            We could not load work orders. Try again.
          </p>
          <button
            className="secondary-button secondary-button--compact"
            type="button"
            onClick={() => void readQueue(true)}
          >
            Retry work orders
          </button>
        </div>
      )}
      {queueState.kind === "forbidden" && (
        <div className="work-order-state-panel">
          <h3>Work-order access denied</h3>
          <p className="asset-message" role="alert">
            The server did not grant access to work orders.
          </p>
        </div>
      )}
      {queueState.kind === "ready" && queueState.refreshFailed && (
        <p className="form-message form-message--error" role="alert">
          Refresh failed. The last confirmed work-order queue remains shown.
        </p>
      )}
      {queueState.kind === "ready" &&
        queueState.result.workOrders.length === 0 && (
          <p className="asset-message">
            {identity.role.code === "TECHNICIAN"
              ? "No work orders are assigned to you."
              : "No work orders are available for this organisation."}
          </p>
        )}
      {queueState.kind === "ready" &&
        queueState.result.workOrders.length > 0 && (
          <ul className="work-order-list">
            {queueState.result.workOrders.map((workOrder) => (
              <li key={workOrder.id}>
                <button
                  ref={(button) => {
                    if (button === null) {
                      rowButtons.current.delete(workOrder.id);
                    } else {
                      rowButtons.current.set(workOrder.id, button);
                    }
                  }}
                  className="work-order-row-button"
                  type="button"
                  aria-label={`View ${statusLabels[workOrder.status].toLowerCase()} work order for ${workOrder.context.assetName}: ${workOrder.context.ruleName}`}
                  onClick={() => {
                    originatingId.current = workOrder.id;
                    setSelectedWorkOrderId(workOrder.id);
                  }}
                >
                  <span className="work-order-row-button__primary">
                    <span className="work-order-row-button__title">
                      {workOrder.context.ruleName}
                    </span>
                    <span>
                      {workOrder.context.assetName} ·{" "}
                      <code>{workOrder.context.assetCode}</code>
                    </span>
                  </span>
                  <span className="work-order-row-button__facts">
                    <StatusBadge status={workOrder.status} />
                    <span>
                      {workOrder.assignedTechnician?.displayName ??
                        "Unassigned"}
                    </span>
                    <time dateTime={workOrder.updatedAt}>
                      {formatTimestamp(workOrder.updatedAt)}
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
