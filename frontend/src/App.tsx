import { useCallback, useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";

import {
  AssetNotFoundError,
  AssetSessionExpiredError,
  getAssetDetail,
  getAssets,
} from "./api/assets";
import type {
  AssetDetail,
  AssetSummary,
  MeasurementType,
  MeasurementUnit,
  ThresholdComparison,
} from "./api/assets";
import {
  AuthenticationFailedError,
  getCsrfToken,
  getCurrentSession,
  login,
  LoginRateLimitedError,
  logout,
} from "./api/session";
import type { CsrfToken, LoginRequest, SessionIdentity } from "./api/session";
import { getApiStatus } from "./api/status";
import { AlertPanel } from "./AlertPanel";
import { AuditPanel } from "./AuditPanel";
import { DashboardPanel } from "./DashboardPanel";
import { OperationsPanel } from "./OperationsPanel";
import { TelemetryPanel } from "./TelemetryPanel";
import { WorkOrderPanel } from "./WorkOrderPanel";
import "./App.css";

type ApiConnectionState = "checking" | "available" | "unavailable";

type ApplicationState =
  | Readonly<{ kind: "loading" }>
  | Readonly<{ kind: "anonymous"; csrfToken: CsrfToken }>
  | Readonly<{
      kind: "authenticated";
      csrfToken: CsrfToken;
      identity: SessionIdentity;
    }>
  | Readonly<{ kind: "unavailable" }>;

type LoginOutcome =
  | "authenticated"
  | "invalid-credentials"
  | "unavailable"
  | Readonly<{
      kind: "rate-limited";
      retryAfterSeconds: number | null;
    }>;
type LogoutOutcome = "logged-out" | "unavailable";
type WorkspaceView =
  "dashboard" | "assets" | "alerts" | "work-orders" | "operations";

type AssetListState =
  | Readonly<{ kind: "loading" }>
  | Readonly<{ kind: "ready"; assets: readonly AssetSummary[] }>
  | Readonly<{ kind: "unavailable" }>;

type AssetDetailState =
  | Readonly<{ kind: "loading" }>
  | Readonly<{ kind: "ready"; asset: AssetDetail }>
  | Readonly<{ kind: "not-found" }>
  | Readonly<{ kind: "unavailable" }>;

const API_TIMEOUT_MS = 5_000;

type DiscoveredSession = Readonly<{
  csrfToken: CsrfToken;
  identity: SessionIdentity | null;
}>;

async function withApiTimeout<T>(
  operation: (signal: AbortSignal) => Promise<T>,
): Promise<T> {
  const controller = new AbortController();
  const timeoutId = window.setTimeout(() => controller.abort(), API_TIMEOUT_MS);

  try {
    return await operation(controller.signal);
  } finally {
    window.clearTimeout(timeoutId);
  }
}

async function discoverSession(
  signal: AbortSignal,
): Promise<DiscoveredSession> {
  const csrfToken = await getCsrfToken(signal);
  const identity = await getCurrentSession(signal);
  return { csrfToken, identity };
}

async function createSessionWithRecovery(
  request: LoginRequest,
  initialCsrfToken: CsrfToken,
  signal: AbortSignal,
): Promise<Readonly<{ csrfToken: CsrfToken; identity: SessionIdentity }>> {
  let csrfToken = initialCsrfToken;

  for (let attempt = 0; attempt < 2; attempt += 1) {
    try {
      await login(request, csrfToken, signal);
    } catch (error: unknown) {
      if (
        error instanceof AuthenticationFailedError ||
        error instanceof LoginRateLimitedError
      ) {
        throw error;
      }
      if (signal.aborted) {
        throw error;
      }
    }

    const discoveredSession = await discoverSession(signal);
    if (discoveredSession.identity !== null) {
      return {
        csrfToken: discoveredSession.csrfToken,
        identity: discoveredSession.identity,
      };
    }
    csrfToken = discoveredSession.csrfToken;
  }

  throw new Error("The authenticated session could not be confirmed");
}

async function destroySessionWithRecovery(
  initialCsrfToken: CsrfToken,
  signal: AbortSignal,
): Promise<CsrfToken> {
  let csrfToken = initialCsrfToken;

  for (let attempt = 0; attempt < 2; attempt += 1) {
    try {
      await logout(csrfToken, signal);
    } catch (error: unknown) {
      if (signal.aborted) {
        throw error;
      }
      // Session discovery below resolves whether the mutation completed.
    }

    const discoveredSession = await discoverSession(signal);
    if (discoveredSession.identity === null) {
      return discoveredSession.csrfToken;
    }
    csrfToken = discoveredSession.csrfToken;
  }

  throw new Error("The logged-out session could not be confirmed");
}

const statusLabels: Record<ApiConnectionState, string> = {
  checking: "checking",
  available: "available",
  unavailable: "unavailable",
};

const measurementTypeLabels: Record<MeasurementType, string> = {
  TEMPERATURE: "Temperature",
};

const measurementUnitLabels: Record<MeasurementUnit, string> = {
  CELSIUS: "Celsius (°C)",
};

const comparisonLabels: Record<ThresholdComparison, string> = {
  GREATER_THAN_OR_EQUAL_TO: "At or above",
};

const seededAccounts = [
  ["admin@northstar.example", "Northstar · Operations Admin"],
  ["technician@northstar.example", "Northstar · Technician"],
  ["viewer@northstar.example", "Northstar · Viewer"],
  ["admin@riverside.example", "Riverside · Operations Admin"],
] as const;

function BrandMark() {
  return (
    <div className="brand-mark" aria-hidden="true">
      <span />
      <span />
      <span />
    </div>
  );
}

function ServiceStatus({
  apiState,
}: Readonly<{ apiState: ApiConnectionState }>) {
  return (
    <dl className="status-list" aria-label="Application status">
      <div>
        <dt>Frontend</dt>
        <dd>
          <span className="status-dot status-dot--ready" aria-hidden="true" />
          ready
        </dd>
      </div>
      <div>
        <dt>API</dt>
        <dd aria-live="polite">
          <span
            className={`status-dot status-dot--${apiState}`}
            aria-hidden="true"
          />
          {statusLabels[apiState]}
        </dd>
      </div>
    </dl>
  );
}

function LoginPanel({
  apiState,
  onLogin,
}: Readonly<{
  apiState: ApiConnectionState;
  onLogin: (request: LoginRequest) => Promise<LoginOutcome>;
}>) {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [submissionState, setSubmissionState] = useState<
    | "idle"
    | "submitting"
    | "invalid-credentials"
    | "unavailable"
    | Readonly<{
        kind: "rate-limited";
        retryAfterSeconds: number | null;
      }>
  >("idle");

  const isSubmitting = submissionState === "submitting";

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();

    if (isSubmitting) {
      return;
    }

    setSubmissionState("submitting");
    const outcome = await onLogin({ email: email.trim(), password });

    if (outcome === "invalid-credentials") {
      setPassword("");
      setSubmissionState("invalid-credentials");
    } else if (outcome === "unavailable") {
      setSubmissionState("unavailable");
    } else if (typeof outcome === "object") {
      setPassword("");
      setSubmissionState(outcome);
    }
  }

  return (
    <section className="session-card" aria-labelledby="page-title">
      <BrandMark />
      <p className="eyebrow">Secure access</p>
      <h1 id="page-title">AssetPulse Lite</h1>
      <p className="intro">
        Sign in with a seeded demo account. Your organisation and role are
        always resolved by the server.
      </p>

      <form
        className="login-form"
        onSubmit={(event) => void handleSubmit(event)}
        aria-busy={isSubmitting}
      >
        <label>
          <span>Email</span>
          <input
            type="email"
            name="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            autoComplete="username"
            maxLength={254}
            required
            disabled={isSubmitting}
          />
        </label>
        <label>
          <span>Password</span>
          <input
            type="password"
            name="password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            autoComplete="current-password"
            maxLength={128}
            required
            disabled={isSubmitting}
          />
        </label>

        {submissionState === "invalid-credentials" && (
          <p className="form-message form-message--error" role="alert">
            Email or password is incorrect.
          </p>
        )}
        {submissionState === "unavailable" && (
          <p className="form-message form-message--error" role="alert">
            AssetPulse is temporarily unavailable. Try again.
          </p>
        )}
        {typeof submissionState === "object" && (
          <p className="form-message form-message--error" role="alert">
            Too many sign-in attempts.{" "}
            {submissionState.retryAfterSeconds === null
              ? "Try again later."
              : `Try again in ${submissionState.retryAfterSeconds} seconds.`}
          </p>
        )}

        <button
          className="primary-button"
          type="submit"
          disabled={isSubmitting}
        >
          {isSubmitting ? "Signing in…" : "Sign in"}
        </button>
      </form>

      <section className="demo-access" aria-labelledby="demo-access-title">
        <h2 id="demo-access-title">Seeded demo accounts</h2>
        <p>
          Every account uses the password <code>AssetPulse1!</code>
        </p>
        <ul>
          {seededAccounts.map(([accountEmail, description]) => (
            <li key={accountEmail}>
              <code>{accountEmail}</code>
              <span>{description}</span>
            </li>
          ))}
        </ul>
      </section>

      <ServiceStatus apiState={apiState} />
    </section>
  );
}

function AssetDetailPanel({
  assetId,
  onBack,
  onSessionExpired,
}: Readonly<{
  assetId: string;
  onBack: () => void;
  onSessionExpired: () => void;
}>) {
  const [detailState, setDetailState] = useState<AssetDetailState>({
    kind: "loading",
  });
  const [loadAttempt, setLoadAttempt] = useState(0);

  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    const timeoutId = window.setTimeout(
      () => controller.abort(),
      API_TIMEOUT_MS,
    );

    setDetailState({ kind: "loading" });

    void getAssetDetail(assetId, controller.signal)
      .then((asset) => {
        if (active && !controller.signal.aborted) {
          setDetailState({ kind: "ready", asset });
        }
      })
      .catch((error: unknown) => {
        if (!active) {
          return;
        }

        if (error instanceof AssetSessionExpiredError) {
          onSessionExpired();
          return;
        }

        setDetailState(
          error instanceof AssetNotFoundError
            ? { kind: "not-found" }
            : { kind: "unavailable" },
        );
      })
      .finally(() => window.clearTimeout(timeoutId));

    return () => {
      active = false;
      window.clearTimeout(timeoutId);
      controller.abort();
    };
  }, [assetId, loadAttempt, onSessionExpired]);

  return (
    <section
      className="asset-section asset-detail"
      aria-labelledby="asset-detail-title"
    >
      <button
        className="secondary-button secondary-button--compact asset-back"
        type="button"
        onClick={onBack}
      >
        Back to assets
      </button>

      {detailState.kind === "loading" && (
        <div className="asset-detail__state">
          <p className="eyebrow">Protected inventory</p>
          <h2 id="asset-detail-title">Asset details</h2>
          <p className="asset-message" role="status">
            Loading asset details…
          </p>
        </div>
      )}

      {detailState.kind === "unavailable" && (
        <div className="asset-detail__state">
          <p className="eyebrow">Protected inventory</p>
          <h2 id="asset-detail-title">Asset details unavailable</h2>
          <p className="asset-message form-message--error" role="alert">
            We could not load this asset. Try again.
          </p>
          <button
            className="secondary-button secondary-button--compact"
            type="button"
            onClick={() => setLoadAttempt((attempt) => attempt + 1)}
          >
            Retry asset details
          </button>
        </div>
      )}

      {detailState.kind === "not-found" && (
        <div className="asset-detail__state">
          <p className="eyebrow">Protected inventory</p>
          <h2 id="asset-detail-title">Asset not found</h2>
          <p className="asset-message" role="status">
            The requested asset is not available.
          </p>
        </div>
      )}

      {detailState.kind === "ready" && (
        <div className="asset-detail__content">
          <header className="asset-detail__header">
            <div>
              <p className="eyebrow">Protected inventory</p>
              <h2 id="asset-detail-title">{detailState.asset.name}</h2>
              <code>{detailState.asset.assetCode}</code>
            </div>
            <p className="readonly-badge">Read-only</p>
          </header>

          <section
            className="sensor-section"
            aria-labelledby="sensor-configuration-title"
          >
            <div className="configuration-heading">
              <h3 id="sensor-configuration-title">Sensor configuration</h3>
              <p aria-label="Sensor count">
                {detailState.asset.sensors.length}
              </p>
            </div>

            {detailState.asset.sensors.length === 0 && (
              <p className="asset-message">No sensors are configured.</p>
            )}

            {detailState.asset.sensors.length > 0 && (
              <div className="sensor-list">
                {detailState.asset.sensors.map((sensor) => (
                  <article
                    className="sensor-card"
                    key={sensor.id}
                    aria-labelledby={`sensor-${sensor.id}`}
                  >
                    <h4 id={`sensor-${sensor.id}`}>{sensor.name}</h4>
                    <dl className="configuration-list">
                      <div>
                        <dt>Sensor key</dt>
                        <dd>
                          <code>{sensor.sensorKey}</code>
                        </dd>
                      </div>
                      <div>
                        <dt>Measurement</dt>
                        <dd>{measurementTypeLabels[sensor.measurementType]}</dd>
                      </div>
                      <div>
                        <dt>Unit</dt>
                        <dd>{measurementUnitLabels[sensor.unit]}</dd>
                      </div>
                    </dl>

                    <section
                      className="rule-section"
                      aria-labelledby={`rules-${sensor.id}`}
                    >
                      <h5 id={`rules-${sensor.id}`}>Threshold rules</h5>
                      {sensor.thresholdRules.length === 0 && (
                        <p className="asset-message">
                          No threshold rules are configured.
                        </p>
                      )}
                      {sensor.thresholdRules.length > 0 && (
                        <ul className="rule-list">
                          {sensor.thresholdRules.map((rule) => (
                            <li key={rule.id}>
                              <div className="rule-heading">
                                <strong>{rule.name}</strong>
                                <code>{rule.ruleCode}</code>
                              </div>
                              <dl className="configuration-list configuration-list--rule">
                                <div>
                                  <dt>Condition</dt>
                                  <dd>
                                    {comparisonLabels[rule.comparison]}{" "}
                                    <data value={String(rule.thresholdValue)}>
                                      {rule.thresholdValue} °C
                                    </data>
                                  </dd>
                                </div>
                                <div>
                                  <dt>Cooldown</dt>
                                  <dd>{rule.cooldownSeconds} seconds</dd>
                                </div>
                                <div>
                                  <dt>Status</dt>
                                  <dd>
                                    {rule.enabled ? "Enabled" : "Disabled"}
                                  </dd>
                                </div>
                              </dl>
                            </li>
                          ))}
                        </ul>
                      )}
                    </section>
                  </article>
                ))}
              </div>
            )}
          </section>

          <TelemetryPanel
            sensors={detailState.asset.sensors}
            onSessionExpired={onSessionExpired}
          />
        </div>
      )}
    </section>
  );
}

function AuthenticatedPanel({
  identity,
  csrfToken,
  onLogout,
  onSessionExpired,
}: Readonly<{
  identity: SessionIdentity;
  csrfToken: CsrfToken;
  onLogout: () => Promise<LogoutOutcome>;
  onSessionExpired: () => void;
}>) {
  const [logoutState, setLogoutState] = useState<
    "idle" | "submitting" | "error"
  >("idle");
  const [assetState, setAssetState] = useState<AssetListState>({
    kind: "loading",
  });
  const [assetLoadAttempt, setAssetLoadAttempt] = useState(0);
  const [selectedAssetId, setSelectedAssetId] = useState<string | null>(null);
  const [workspaceView, setWorkspaceView] =
    useState<WorkspaceView>("dashboard");
  const alertsNavigationRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (workspaceView !== "assets") {
      return;
    }

    let active = true;
    const controller = new AbortController();
    const timeoutId = window.setTimeout(
      () => controller.abort(),
      API_TIMEOUT_MS,
    );

    setAssetState({ kind: "loading" });

    void getAssets(controller.signal)
      .then((assets) => {
        if (active && !controller.signal.aborted) {
          setAssetState({ kind: "ready", assets });
        }
      })
      .catch((error: unknown) => {
        if (!active) {
          return;
        }

        if (error instanceof AssetSessionExpiredError) {
          onSessionExpired();
          return;
        }

        setAssetState({ kind: "unavailable" });
      })
      .finally(() => window.clearTimeout(timeoutId));

    return () => {
      active = false;
      window.clearTimeout(timeoutId);
      controller.abort();
    };
  }, [assetLoadAttempt, onSessionExpired, workspaceView]);

  async function handleLogout() {
    if (logoutState === "submitting") {
      return;
    }

    setLogoutState("submitting");
    const outcome = await onLogout();

    if (outcome === "unavailable") {
      setLogoutState("error");
    }
  }

  return (
    <section
      className="session-card session-card--workspace"
      aria-labelledby="page-title"
    >
      <BrandMark />
      <p className="eyebrow">Authenticated session</p>
      <h1 id="page-title" className="welcome-title">
        Welcome, {identity.displayName}
      </h1>
      <p className="intro">
        Your identity, organisation, and role come from the trusted server
        session.
      </p>

      <dl className="identity-list">
        <div>
          <dt>Email</dt>
          <dd>{identity.email}</dd>
        </div>
        <div>
          <dt>Organisation</dt>
          <dd>{identity.organisation.name}</dd>
        </div>
        <div>
          <dt>Role</dt>
          <dd>{identity.role.displayName}</dd>
        </div>
      </dl>

      <p className="session-ready">Your trusted session is ready.</p>

      <nav className="workspace-navigation" aria-label="Product sections">
        <button
          type="button"
          aria-current={workspaceView === "dashboard" ? "page" : undefined}
          onClick={() => setWorkspaceView("dashboard")}
        >
          Dashboard
        </button>
        <button
          type="button"
          aria-current={workspaceView === "assets" ? "page" : undefined}
          onClick={() => setWorkspaceView("assets")}
        >
          Assets
        </button>
        <button
          ref={alertsNavigationRef}
          type="button"
          aria-current={workspaceView === "alerts" ? "page" : undefined}
          onClick={() => setWorkspaceView("alerts")}
        >
          Alerts
        </button>
        <button
          type="button"
          aria-current={workspaceView === "work-orders" ? "page" : undefined}
          onClick={() => setWorkspaceView("work-orders")}
        >
          Work orders
        </button>
        {identity.role.code === "OPERATIONS_ADMIN" && (
          <button
            type="button"
            aria-current={workspaceView === "operations" ? "page" : undefined}
            onClick={() => setWorkspaceView("operations")}
          >
            Operations
          </button>
        )}
      </nav>

      {workspaceView === "dashboard" && (
        <DashboardPanel
          identity={identity}
          csrfToken={csrfToken}
          onSessionExpired={onSessionExpired}
          onOpenAlerts={() => {
            setWorkspaceView("alerts");
            alertsNavigationRef.current?.focus();
          }}
        />
      )}

      {workspaceView === "assets" &&
        (selectedAssetId === null ? (
          <section className="asset-section" aria-labelledby="assets-title">
            <div className="asset-section__heading">
              <div>
                <p className="eyebrow">Protected inventory</p>
                <h2 id="assets-title">Assets</h2>
              </div>
              {assetState.kind === "ready" && (
                <p className="asset-count" aria-label="Asset count">
                  {assetState.assets.length}
                </p>
              )}
            </div>

            {assetState.kind === "loading" && (
              <p className="asset-message" role="status" aria-live="polite">
                Loading assets…
              </p>
            )}

            {assetState.kind === "ready" && assetState.assets.length === 0 && (
              <p className="asset-message">No assets are available.</p>
            )}

            {assetState.kind === "ready" && assetState.assets.length > 0 && (
              <ul className="asset-list">
                {assetState.assets.map((asset) => (
                  <li key={asset.id}>
                    <button
                      className="asset-row-button"
                      type="button"
                      aria-label={`View details for ${asset.name} (${asset.assetCode})`}
                      onClick={() => setSelectedAssetId(asset.id)}
                    >
                      <span className="asset-name">{asset.name}</span>
                      <span className="asset-row-button__meta">
                        <code>{asset.assetCode}</code>
                        <span aria-hidden="true">→</span>
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            )}

            {assetState.kind === "unavailable" && (
              <div className="asset-unavailable">
                <p className="asset-message form-message--error" role="alert">
                  We could not load assets. Try again.
                </p>
                <button
                  className="secondary-button secondary-button--compact"
                  type="button"
                  onClick={() => setAssetLoadAttempt((attempt) => attempt + 1)}
                >
                  Retry assets
                </button>
              </div>
            )}
          </section>
        ) : (
          <AssetDetailPanel
            assetId={selectedAssetId}
            onBack={() => setSelectedAssetId(null)}
            onSessionExpired={onSessionExpired}
          />
        ))}

      {workspaceView === "alerts" && (
        <AlertPanel
          roleCode={identity.role.code}
          csrfToken={csrfToken}
          onSessionExpired={onSessionExpired}
        />
      )}

      {workspaceView === "work-orders" && (
        <WorkOrderPanel
          identity={identity}
          csrfToken={csrfToken}
          onSessionExpired={onSessionExpired}
        />
      )}

      {workspaceView === "operations" &&
        identity.role.code === "OPERATIONS_ADMIN" && (
          <>
            <OperationsPanel
              csrfToken={csrfToken}
              onSessionExpired={onSessionExpired}
            />
            <AuditPanel onSessionExpired={onSessionExpired} />
          </>
        )}

      {logoutState === "error" && (
        <p className="form-message form-message--error" role="alert">
          We could not sign you out. Your current view has been preserved; try
          again.
        </p>
      )}

      <button
        className="secondary-button"
        type="button"
        onClick={() => void handleLogout()}
        disabled={logoutState === "submitting"}
      >
        {logoutState === "submitting" ? "Signing out…" : "Sign out"}
      </button>
    </section>
  );
}

function LoadingPanel({
  apiState,
}: Readonly<{ apiState: ApiConnectionState }>) {
  return (
    <section className="session-card" aria-labelledby="page-title">
      <BrandMark />
      <p className="eyebrow">Secure access</p>
      <h1 id="page-title">AssetPulse Lite</h1>
      <p className="intro" role="status" aria-live="polite">
        Checking your session…
      </p>
      <ServiceStatus apiState={apiState} />
    </section>
  );
}

function UnavailablePanel({
  apiState,
  onRetry,
}: Readonly<{
  apiState: ApiConnectionState;
  onRetry: () => void;
}>) {
  return (
    <section className="session-card" aria-labelledby="page-title">
      <BrandMark />
      <p className="eyebrow">Secure access</p>
      <h1 id="page-title">AssetPulse Lite</h1>
      <p className="intro form-message--error" role="alert">
        We could not load your session. Check the service and try again.
      </p>
      <button className="primary-button" type="button" onClick={onRetry}>
        Retry
      </button>
      <ServiceStatus apiState={apiState} />
    </section>
  );
}

function App() {
  const [apiState, setApiState] = useState<ApiConnectionState>("checking");
  const [applicationState, setApplicationState] = useState<ApplicationState>({
    kind: "loading",
  });
  const [bootstrapAttempt, setBootstrapAttempt] = useState(0);

  const handleSessionExpired = useCallback(() => {
    setApplicationState({ kind: "loading" });
    setApiState("checking");
    setBootstrapAttempt((attempt) => attempt + 1);
  }, []);

  useEffect(() => {
    let active = true;
    const statusController = new AbortController();
    const sessionController = new AbortController();

    setApiState("checking");
    setApplicationState({ kind: "loading" });

    const statusTimeoutId = window.setTimeout(() => {
      statusController.abort();
      if (active) {
        setApiState("unavailable");
      }
    }, API_TIMEOUT_MS);
    const sessionTimeoutId = window.setTimeout(() => {
      sessionController.abort();
      if (active) {
        setApplicationState({ kind: "unavailable" });
      }
    }, API_TIMEOUT_MS);

    void getApiStatus(statusController.signal)
      .then(() => {
        if (active && !statusController.signal.aborted) {
          setApiState("available");
        }
      })
      .catch(() => {
        if (active && !statusController.signal.aborted) {
          setApiState("unavailable");
        }
      })
      .finally(() => window.clearTimeout(statusTimeoutId));

    void getCsrfToken(sessionController.signal)
      .then(async (csrfToken) => {
        const identity = await getCurrentSession(sessionController.signal);

        if (active && !sessionController.signal.aborted) {
          setApplicationState(
            identity === null
              ? { kind: "anonymous", csrfToken }
              : { kind: "authenticated", csrfToken, identity },
          );
        }
      })
      .catch(() => {
        if (active && !sessionController.signal.aborted) {
          setApplicationState({ kind: "unavailable" });
        }
      })
      .finally(() => window.clearTimeout(sessionTimeoutId));

    return () => {
      active = false;
      window.clearTimeout(statusTimeoutId);
      window.clearTimeout(sessionTimeoutId);
      statusController.abort();
      sessionController.abort();
    };
  }, [bootstrapAttempt]);

  async function handleLogin(
    request: LoginRequest,
    csrfToken: CsrfToken,
  ): Promise<LoginOutcome> {
    try {
      const authenticatedSession = await withApiTimeout((signal) =>
        createSessionWithRecovery(request, csrfToken, signal),
      );
      setApplicationState({
        kind: "authenticated",
        csrfToken: authenticatedSession.csrfToken,
        identity: authenticatedSession.identity,
      });
      setApiState("available");
      return "authenticated";
    } catch (error: unknown) {
      if (error instanceof AuthenticationFailedError) {
        return "invalid-credentials";
      }
      if (error instanceof LoginRateLimitedError) {
        return {
          kind: "rate-limited",
          retryAfterSeconds: error.retryAfterSeconds,
        };
      }

      setApplicationState({ kind: "unavailable" });
      setApiState("unavailable");
      return "unavailable";
    }
  }

  async function handleLogout(csrfToken: CsrfToken): Promise<LogoutOutcome> {
    try {
      const refreshedCsrfToken = await withApiTimeout((signal) =>
        destroySessionWithRecovery(csrfToken, signal),
      );
      setApplicationState({ kind: "anonymous", csrfToken: refreshedCsrfToken });
      setApiState("available");
      return "logged-out";
    } catch {
      setApplicationState({ kind: "unavailable" });
      setApiState("unavailable");
      return "unavailable";
    }
  }

  let content;

  if (applicationState.kind === "loading") {
    content = <LoadingPanel apiState={apiState} />;
  } else if (applicationState.kind === "unavailable") {
    content = (
      <UnavailablePanel
        apiState={apiState}
        onRetry={() => setBootstrapAttempt((attempt) => attempt + 1)}
      />
    );
  } else if (applicationState.kind === "anonymous") {
    content = (
      <LoginPanel
        apiState={apiState}
        onLogin={(request) => handleLogin(request, applicationState.csrfToken)}
      />
    );
  } else {
    content = (
      <AuthenticatedPanel
        identity={applicationState.identity}
        csrfToken={applicationState.csrfToken}
        onLogout={() => handleLogout(applicationState.csrfToken)}
        onSessionExpired={handleSessionExpired}
      />
    );
  }

  return <main className="app-shell">{content}</main>;
}

export default App;
