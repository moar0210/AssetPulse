const HTTP_METHODS = Object.freeze([
  "get",
  "put",
  "post",
  "delete",
  "options",
  "head",
  "patch",
  "trace",
]);

const ALL_ROLES = Object.freeze(["OPERATIONS_ADMIN", "TECHNICIAN", "VIEWER"]);
const ADMIN_ROLE = Object.freeze(["OPERATIONS_ADMIN"]);
const TECHNICIAN_ROLE = Object.freeze(["TECHNICIAN"]);
const NO_ROLES = Object.freeze([]);
const TENANT_SCOPE = "trusted-session-organisation";
const MAX_DOCUMENT_CHARACTERS = 2_000_000;

function operation(
  method,
  path,
  roles,
  security,
  { tenantScoped = false } = {},
) {
  return Object.freeze({
    method,
    path,
    roles,
    security,
    tenantScoped,
  });
}

function success(method, path, status, mediaType, schemaReference) {
  return Object.freeze({ method, path, status, mediaType, schemaReference });
}

export const EXPECTED_OPENAPI_OPERATIONS = Object.freeze([
  operation("get", "/api/v1/status", NO_ROLES, "public"),
  operation("get", "/api/v1/session/csrf", NO_ROLES, "public"),
  operation("get", "/api/v1/session", ALL_ROLES, "session"),
  operation("post", "/api/v1/session", NO_ROLES, "session-csrf"),
  operation("delete", "/api/v1/session", NO_ROLES, "session-csrf"),
  operation("get", "/api/v1/dashboard", ALL_ROLES, "session", {
    tenantScoped: true,
  }),
  operation("post", "/api/v1/demo/reset", ADMIN_ROLE, "session-csrf", {
    tenantScoped: true,
  }),
  operation("get", "/api/v1/audit-events", ADMIN_ROLE, "session", {
    tenantScoped: true,
  }),
  operation("get", "/api/v1/assets", ALL_ROLES, "session", {
    tenantScoped: true,
  }),
  operation("get", "/api/v1/assets/{assetId}", ALL_ROLES, "session", {
    tenantScoped: true,
  }),
  operation(
    "get",
    "/api/v1/sensors/{sensorId}/telemetry-readings",
    ALL_ROLES,
    "session",
    { tenantScoped: true },
  ),
  operation("get", "/api/v1/alerts/stream", ALL_ROLES, "session", {
    tenantScoped: true,
  }),
  operation("get", "/api/v1/alerts", ALL_ROLES, "session", {
    tenantScoped: true,
  }),
  operation("get", "/api/v1/alerts/{alertId}", ALL_ROLES, "session", {
    tenantScoped: true,
  }),
  operation(
    "post",
    "/api/v1/alerts/{alertId}/acknowledge",
    ADMIN_ROLE,
    "session-csrf",
    { tenantScoped: true },
  ),
  operation(
    "post",
    "/api/v1/alerts/{alertId}/resolve",
    ADMIN_ROLE,
    "session-csrf",
    { tenantScoped: true },
  ),
  operation("get", "/api/v1/work-orders", ALL_ROLES, "session", {
    tenantScoped: true,
  }),
  operation(
    "get",
    "/api/v1/work-orders/eligible-technicians",
    ADMIN_ROLE,
    "session",
    { tenantScoped: true },
  ),
  operation("get", "/api/v1/work-orders/{workOrderId}", ALL_ROLES, "session", {
    tenantScoped: true,
  }),
  operation("post", "/api/v1/work-orders", ADMIN_ROLE, "session-csrf", {
    tenantScoped: true,
  }),
  operation(
    "post",
    "/api/v1/work-orders/{workOrderId}/assign",
    ADMIN_ROLE,
    "session-csrf",
    { tenantScoped: true },
  ),
  operation(
    "post",
    "/api/v1/work-orders/{workOrderId}/start",
    TECHNICIAN_ROLE,
    "session-csrf",
    { tenantScoped: true },
  ),
  operation(
    "post",
    "/api/v1/work-orders/{workOrderId}/complete",
    TECHNICIAN_ROLE,
    "session-csrf",
    { tenantScoped: true },
  ),
  operation("get", "/api/v1/processing-events/dead", ADMIN_ROLE, "session", {
    tenantScoped: true,
  }),
  operation(
    "post",
    "/api/v1/processing-events/{eventId}/retry",
    ADMIN_ROLE,
    "session-csrf",
    { tenantScoped: true },
  ),
  operation("post", "/api/v1/telemetry-batches", ADMIN_ROLE, "session-csrf", {
    tenantScoped: true,
  }),
]);

const EXPECTED_SUCCESS_RESPONSES = Object.freeze([
  success("get", "/api/v1/status", "200", "application/json", "StatusResponse"),
  success(
    "get",
    "/api/v1/session/csrf",
    "200",
    "application/json",
    "CsrfTokenResponse",
  ),
  success(
    "get",
    "/api/v1/session",
    "200",
    "application/json",
    "SessionResponse",
  ),
  success(
    "post",
    "/api/v1/session",
    "200",
    "application/json",
    "SessionResponse",
  ),
  success("delete", "/api/v1/session", "204"),
  success(
    "get",
    "/api/v1/dashboard",
    "200",
    "application/json",
    "DashboardResponse",
  ),
  success(
    "post",
    "/api/v1/demo/reset",
    "200",
    "application/json",
    "DemoResetResponse",
  ),
  success(
    "get",
    "/api/v1/audit-events",
    "200",
    "application/json",
    "AuditListResponse",
  ),
  success(
    "get",
    "/api/v1/assets",
    "200",
    "application/json",
    "AssetListResponse",
  ),
  success(
    "get",
    "/api/v1/assets/{assetId}",
    "200",
    "application/json",
    "AssetDetailResponse",
  ),
  success(
    "get",
    "/api/v1/sensors/{sensorId}/telemetry-readings",
    "200",
    "application/json",
    "TelemetryReadingRangeResponse",
  ),
  success("get", "/api/v1/alerts/stream", "200", "text/event-stream"),
  success(
    "get",
    "/api/v1/alerts",
    "200",
    "application/json",
    "AlertListResponse",
  ),
  success(
    "get",
    "/api/v1/alerts/{alertId}",
    "200",
    "application/json",
    "AlertDetailResponse",
  ),
  success(
    "post",
    "/api/v1/alerts/{alertId}/acknowledge",
    "200",
    "application/json",
    "AlertDetailResponse",
  ),
  success(
    "post",
    "/api/v1/alerts/{alertId}/resolve",
    "200",
    "application/json",
    "AlertDetailResponse",
  ),
  success(
    "get",
    "/api/v1/work-orders",
    "200",
    "application/json",
    "WorkOrderListResponse",
  ),
  success(
    "get",
    "/api/v1/work-orders/eligible-technicians",
    "200",
    "application/json",
    "EligibleTechnicianListResponse",
  ),
  success(
    "get",
    "/api/v1/work-orders/{workOrderId}",
    "200",
    "application/json",
    "WorkOrderDetailResponse",
  ),
  success(
    "post",
    "/api/v1/work-orders",
    "201",
    "application/json",
    "WorkOrderDetailResponse",
  ),
  success(
    "post",
    "/api/v1/work-orders/{workOrderId}/assign",
    "200",
    "application/json",
    "WorkOrderDetailResponse",
  ),
  success(
    "post",
    "/api/v1/work-orders/{workOrderId}/start",
    "200",
    "application/json",
    "WorkOrderDetailResponse",
  ),
  success(
    "post",
    "/api/v1/work-orders/{workOrderId}/complete",
    "200",
    "application/json",
    "WorkOrderDetailResponse",
  ),
  success(
    "get",
    "/api/v1/processing-events/dead",
    "200",
    "application/json",
    "DeadProcessingEventListResponse",
  ),
  success("post", "/api/v1/processing-events/{eventId}/retry", "204"),
  success(
    "post",
    "/api/v1/telemetry-batches",
    "200",
    "application/json",
    "TelemetryBatchResponse",
  ),
]);

export class OpenApiContractError extends Error {
  constructor(message, options) {
    super(`OpenAPI contract: ${message}`, options);
    this.name = "OpenApiContractError";
  }
}

function fail(message, options) {
  throw new OpenApiContractError(message, options);
}

function assert(condition, message) {
  if (!condition) {
    fail(message);
  }
}

function isObject(value) {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function hasOwn(value, key) {
  return Object.prototype.hasOwnProperty.call(value, key);
}

function operationKey(method, path) {
  return `${method.toUpperCase()} ${path}`;
}

function decodePointerToken(token, reference) {
  let decoded;
  try {
    decoded = decodeURIComponent(token);
  } catch (cause) {
    fail(`reference ${reference} contains invalid URI escaping`, { cause });
  }

  if (/~(?:[^01]|$)/u.test(decoded)) {
    fail(`reference ${reference} contains invalid JSON Pointer escaping`);
  }
  return decoded.replaceAll("~1", "/").replaceAll("~0", "~");
}

function resolveLocalReference(document, reference) {
  assert(
    typeof reference === "string" && reference.startsWith("#/"),
    `reference ${String(reference)} must be a local JSON Pointer`,
  );

  let current = document;
  for (const token of reference.slice(2).split("/")) {
    const key = decodePointerToken(token, reference);
    if (!isObject(current) && !Array.isArray(current)) {
      fail(`reference ${reference} does not resolve`);
    }
    if (!hasOwn(current, key)) {
      fail(`reference ${reference} does not resolve`);
    }
    current = current[key];
  }
  return current;
}

function dereference(document, value, label) {
  const seen = new Set();
  let current = value;

  while (isObject(current) && hasOwn(current, "$ref")) {
    const reference = current.$ref;
    assert(typeof reference === "string", `${label} has a non-string $ref`);
    if (seen.has(reference)) {
      fail(`${label} contains a cyclic reference through ${reference}`);
    }
    seen.add(reference);
    current = resolveLocalReference(document, reference);
  }

  return current;
}

function validateAllLocalReferences(document) {
  const visit = (value) => {
    if (!isObject(value) && !Array.isArray(value)) {
      return;
    }

    if (isObject(value) && hasOwn(value, "$ref")) {
      assert(typeof value.$ref === "string", "$ref values must be strings");
      resolveLocalReference(document, value.$ref);
    }

    for (const child of Object.values(value)) {
      visit(child);
    }
  };

  visit(document);
}

function collectOperations(document) {
  assert(isObject(document.paths), "paths must be an object");
  const operations = new Map();

  for (const [path, rawPathItem] of Object.entries(document.paths)) {
    assert(path.startsWith("/"), `path ${path} must start with /`);
    const pathItem = dereference(document, rawPathItem, `path item ${path}`);
    assert(isObject(pathItem), `path item ${path} must be an object`);

    for (const method of HTTP_METHODS) {
      if (!hasOwn(pathItem, method)) {
        continue;
      }
      const value = pathItem[method];
      assert(
        isObject(value),
        `${operationKey(method, path)} must be an object`,
      );
      operations.set(operationKey(method, path), {
        method,
        path,
        pathItem,
        operation: value,
      });
    }
  }

  return operations;
}

function validateOperationParity(operations) {
  const expectedKeys = new Set(
    EXPECTED_OPENAPI_OPERATIONS.map(({ method, path }) =>
      operationKey(method, path),
    ),
  );
  const actualKeys = new Set(operations.keys());
  const missing = [...expectedKeys].filter((key) => !actualKeys.has(key));
  const stale = [...actualKeys].filter((key) => !expectedKeys.has(key));

  assert(
    missing.length === 0 && stale.length === 0,
    `operation parity mismatch${
      missing.length > 0 ? `; missing ${missing.join(", ")}` : ""
    }${stale.length > 0 ? `; undocumented/stale ${stale.join(", ")}` : ""}`,
  );
  assert(
    actualKeys.size === 26,
    `expected exactly 26 semantic operations, found ${actualKeys.size}`,
  );
}

function validateOperationIds(operations) {
  const owners = new Map();

  for (const [key, { operation: value }] of operations) {
    assert(
      typeof value.operationId === "string" &&
        /^[A-Za-z][A-Za-z0-9._-]*$/u.test(value.operationId),
      `${key} must define a non-empty stable operationId`,
    );
    if (owners.has(value.operationId)) {
      fail(
        `operationId ${value.operationId} is duplicated by ${owners.get(
          value.operationId,
        )} and ${key}`,
      );
    }
    owners.set(value.operationId, key);
  }
}

function validateOperationMetadata(document, operations) {
  const declaredTags = document.tags;
  assert(Array.isArray(declaredTags), "tags must be an array");
  const tagNames = declaredTags.map((tag, index) => {
    assert(
      isObject(tag) && typeof tag.name === "string" && tag.name.length > 0,
      `tag ${index} must define a non-empty name`,
    );
    return tag.name;
  });
  assert(
    new Set(tagNames).size === tagNames.length,
    "top-level tag names must be unique",
  );

  for (const [key, { operation: value }] of operations) {
    assert(
      typeof value.summary === "string" && value.summary.length > 0,
      `${key} must define a non-empty summary`,
    );
    assert(
      Array.isArray(value.tags) &&
        value.tags.length === 1 &&
        typeof value.tags[0] === "string" &&
        tagNames.includes(value.tags[0]),
      `${key} must use exactly one declared tag`,
    );
  }
}

function expectedSecurity(kind) {
  switch (kind) {
    case "public":
      return [];
    case "session":
      return [{ sessionCookie: [] }];
    case "session-csrf":
      return [{ sessionCookie: [], csrfToken: [] }];
    default:
      fail(`unknown expected security kind ${kind}`);
  }
}

function canonicalSecurity(security, label) {
  assert(Array.isArray(security), `${label} security must be an array`);
  return security
    .map((requirement) => {
      assert(
        isObject(requirement),
        `${label} security entries must be objects`,
      );
      return Object.entries(requirement)
        .sort(([left], [right]) => left.localeCompare(right))
        .map(([name, scopes]) => {
          assert(
            Array.isArray(scopes) && scopes.length === 0,
            `${label} ${name} security scopes must be empty`,
          );
          return name;
        })
        .join("+");
    })
    .sort()
    .join("|");
}

function sameStringSet(actual, expected) {
  return (
    actual.length === expected.length &&
    new Set(actual).size === actual.length &&
    expected.every((value) => actual.includes(value))
  );
}

function validateSecuritySchemes(document) {
  const schemes = document.components?.securitySchemes;
  assert(isObject(schemes), "components.securitySchemes must be an object");

  const sessionCookie = dereference(
    document,
    schemes.sessionCookie,
    "sessionCookie security scheme",
  );
  assert(
    isObject(sessionCookie) &&
      sessionCookie.type === "apiKey" &&
      sessionCookie.in === "cookie" &&
      sessionCookie.name === "ASSETPULSE_SESSION",
    "sessionCookie must describe the ASSETPULSE_SESSION cookie",
  );

  const csrfToken = dereference(
    document,
    schemes.csrfToken,
    "csrfToken security scheme",
  );
  assert(
    isObject(csrfToken) &&
      csrfToken.type === "apiKey" &&
      csrfToken.in === "header" &&
      csrfToken.name === "X-CSRF-TOKEN",
    "csrfToken must describe the X-CSRF-TOKEN header",
  );
}

function validateSecurityAndRoles(operations) {
  for (const expected of EXPECTED_OPENAPI_OPERATIONS) {
    const key = operationKey(expected.method, expected.path);
    const value = operations.get(key).operation;
    const wantedSecurity = expectedSecurity(expected.security);

    assert(
      hasOwn(value, "security"),
      `${key} must declare security explicitly`,
    );
    assert(
      canonicalSecurity(value.security, key) ===
        canonicalSecurity(wantedSecurity, key),
      `${key} has an incorrect security requirement`,
    );

    const roles = value["x-assetpulse-roles"];
    assert(Array.isArray(roles), `${key} must declare x-assetpulse-roles`);
    assert(
      roles.every((role) => typeof role === "string") &&
        sameStringSet(roles, expected.roles),
      `${key} has an incorrect role matrix`,
    );

    if (expected.tenantScoped) {
      assert(
        value["x-assetpulse-tenant-scope"] === TENANT_SCOPE,
        `${key} must use the trusted session organisation tenant scope`,
      );
    } else {
      assert(
        !hasOwn(value, "x-assetpulse-tenant-scope"),
        `${key} must not declare a tenant scope`,
      );
    }
  }
}

function combinedParameters(document, entry) {
  const rawParameters = [
    ...(Array.isArray(entry.pathItem.parameters)
      ? entry.pathItem.parameters
      : []),
    ...(Array.isArray(entry.operation.parameters)
      ? entry.operation.parameters
      : []),
  ];

  return rawParameters.map((rawParameter, index) => {
    const parameter = dereference(
      document,
      rawParameter,
      `${operationKey(entry.method, entry.path)} parameter ${index}`,
    );
    assert(
      isObject(parameter) &&
        typeof parameter.name === "string" &&
        typeof parameter.in === "string",
      `${operationKey(entry.method, entry.path)} has an invalid parameter`,
    );
    return { raw: rawParameter, parameter };
  });
}

function looksLikeUntrustedScopeName(name) {
  const normalized = name.toLowerCase().replaceAll(/[^a-z0-9]/gu, "");
  if (normalized === "technicianuserid") {
    return false;
  }
  return (
    /(organisation|organization|tenant|role|actor|principal)/u.test(
      normalized,
    ) || normalized.endsWith("userid")
  );
}

function visitRequestSchema(document, rawSchema, visitProperty, visitedRefs) {
  if (!isObject(rawSchema)) {
    return;
  }

  if (hasOwn(rawSchema, "$ref")) {
    const reference = rawSchema.$ref;
    if (!visitedRefs.has(reference)) {
      visitedRefs.add(reference);
      visitRequestSchema(
        document,
        resolveLocalReference(document, reference),
        visitProperty,
        visitedRefs,
      );
    }
  }

  if (isObject(rawSchema.properties)) {
    for (const [name, propertySchema] of Object.entries(rawSchema.properties)) {
      visitProperty(name);
      visitRequestSchema(document, propertySchema, visitProperty, visitedRefs);
    }
  }

  for (const keyword of ["allOf", "anyOf", "oneOf", "prefixItems"]) {
    if (Array.isArray(rawSchema[keyword])) {
      for (const nestedSchema of rawSchema[keyword]) {
        visitRequestSchema(document, nestedSchema, visitProperty, visitedRefs);
      }
    }
  }
  visitRequestSchema(document, rawSchema.items, visitProperty, visitedRefs);
  if (isObject(rawSchema.additionalProperties)) {
    visitRequestSchema(
      document,
      rawSchema.additionalProperties,
      visitProperty,
      visitedRefs,
    );
  }
}

function validateNoUntrustedScopeInputs(document, operations) {
  const componentParameters = document.components?.parameters;
  if (componentParameters !== undefined) {
    assert(
      isObject(componentParameters),
      "components.parameters must be an object",
    );
    for (const [name, rawParameter] of Object.entries(componentParameters)) {
      const parameter = dereference(
        document,
        rawParameter,
        `component parameter ${name}`,
      );
      assert(isObject(parameter), `component parameter ${name} is invalid`);
      assert(
        !looksLikeUntrustedScopeName(parameter.name ?? name),
        `untrusted tenant or role scope parameter ${parameter.name ?? name} is forbidden`,
      );
    }
  }

  for (const [key, entry] of operations) {
    for (const { parameter } of combinedParameters(document, entry)) {
      assert(
        !looksLikeUntrustedScopeName(parameter.name),
        `${key} exposes forbidden untrusted scope parameter ${parameter.name}`,
      );
    }

    const rawRequestBody = entry.operation.requestBody;
    if (rawRequestBody === undefined) {
      continue;
    }
    const requestBody = dereference(
      document,
      rawRequestBody,
      `${key} request body`,
    );
    assert(isObject(requestBody), `${key} request body must be an object`);
    assert(
      isObject(requestBody.content),
      `${key} request body must define content`,
    );

    for (const media of Object.values(requestBody.content)) {
      if (isObject(media) && isObject(media.schema)) {
        visitRequestSchema(
          document,
          media.schema,
          (propertyName) => {
            assert(
              !looksLikeUntrustedScopeName(propertyName),
              `${key} request body exposes forbidden untrusted scope property ${propertyName}`,
            );
          },
          new Set(),
        );
      }
    }
  }
}

function findParameter(document, entry, name, location) {
  return combinedParameters(document, entry).find(
    ({ parameter }) =>
      parameter.name.toLowerCase() === name.toLowerCase() &&
      parameter.in === location,
  )?.parameter;
}

function resolvedSchema(document, rawSchema, label) {
  const schema = dereference(document, rawSchema, label);
  assert(isObject(schema), `${label} must be a schema object`);
  return schema;
}

function validatePathParameters(document, operations) {
  for (const [key, entry] of operations) {
    const names = [...entry.path.matchAll(/\{([^{}]+)\}/gu)].map(
      (match) => match[1],
    );
    for (const name of names) {
      const parameter = findParameter(document, entry, name, "path");
      assert(
        parameter !== undefined,
        `${key} must declare path parameter ${name}`,
      );
      const schema = resolvedSchema(
        document,
        parameter.schema,
        `${key} path parameter ${name}`,
      );
      assert(
        parameter.required === true &&
          schema.type === "string" &&
          schema.format === "uuid",
        `${key} path parameter ${name} must be a required UUID`,
      );
    }
  }
}

function validateCsrfParameters(document, operations) {
  for (const expected of EXPECTED_OPENAPI_OPERATIONS) {
    if (expected.security !== "csrf" && expected.security !== "session-csrf") {
      continue;
    }
    const key = operationKey(expected.method, expected.path);
    const entry = operations.get(key);
    const parameter = findParameter(document, entry, "X-CSRF-TOKEN", "header");
    assert(
      parameter !== undefined && parameter.required === true,
      `${key} must declare the required X-CSRF-TOKEN header`,
    );
    const schema = resolvedSchema(
      document,
      parameter.schema,
      `${key} CSRF header`,
    );
    assert(schema.type === "string", `${key} CSRF header must be a string`);
  }
}

function validateIntegerBound(
  document,
  entry,
  name,
  { minimum, maximum, defaultValue },
) {
  const key = operationKey(entry.method, entry.path);
  const parameter = findParameter(document, entry, name, "query");
  assert(
    parameter !== undefined,
    `${key} must declare query parameter ${name}`,
  );
  const schema = resolvedSchema(document, parameter.schema, `${key} ${name}`);
  assert(
    schema.type === "integer" &&
      schema.minimum === minimum &&
      schema.maximum === maximum &&
      schema.default === defaultValue,
    `${key} ${name} must be an integer bounded ${minimum}-${maximum} with default ${defaultValue}`,
  );
}

function requestJsonSchema(document, entry) {
  const key = operationKey(entry.method, entry.path);
  const requestBody = dereference(
    document,
    entry.operation.requestBody,
    `${key} request body`,
  );
  assert(
    isObject(requestBody) && requestBody.required === true,
    `${key} must define a required request body`,
  );
  const media = requestBody.content?.["application/json"];
  assert(isObject(media), `${key} request body must use application/json`);
  return resolvedSchema(document, media.schema, `${key} JSON request body`);
}

function requiredProperties(schema, names, label) {
  assert(
    Array.isArray(schema.required),
    `${label} must declare required properties`,
  );
  for (const name of names) {
    assert(schema.required.includes(name), `${label} must require ${name}`);
  }
}

function validateClosedObjectSchema(schema, properties, required, label) {
  assert(
    schema.type === "object" &&
      schema.additionalProperties === false &&
      isObject(schema.properties),
    `${label} must be a closed object schema`,
  );
  assert(
    sameStringSet(Object.keys(schema.properties), properties),
    `${label} must define exactly ${properties.join(", ")}`,
  );
  assert(
    Array.isArray(schema.required) && sameStringSet(schema.required, required),
    `${label} has an incorrect required-property set`,
  );
}

function validateCriticalBounds(document, operations) {
  for (const path of [
    "/api/v1/alerts",
    "/api/v1/audit-events",
    "/api/v1/processing-events/dead",
    "/api/v1/work-orders",
  ]) {
    validateIntegerBound(
      document,
      operations.get(operationKey("get", path)),
      "limit",
      {
        minimum: 1,
        maximum: 100,
        defaultValue: 50,
      },
    );
  }

  const range = operations.get(
    operationKey("get", "/api/v1/sensors/{sensorId}/telemetry-readings"),
  );
  validateIntegerBound(document, range, "limit", {
    minimum: 1,
    maximum: 500,
    defaultValue: 100,
  });
  for (const name of ["from", "to"]) {
    const parameter = findParameter(document, range, name, "query");
    assert(
      parameter !== undefined && parameter.required === true,
      `GET ${range.path} must require ${name}`,
    );
    const schema = resolvedSchema(
      document,
      parameter.schema,
      `telemetry range ${name}`,
    );
    assert(
      schema.type === "string" && schema.format === "date-time",
      `telemetry range ${name} must be a date-time`,
    );
  }

  const login = requestJsonSchema(
    document,
    operations.get(operationKey("post", "/api/v1/session")),
  );
  validateClosedObjectSchema(
    login,
    ["email", "password"],
    ["email", "password"],
    "login request",
  );
  const email = resolvedSchema(
    document,
    login.properties?.email,
    "login email",
  );
  const password = resolvedSchema(
    document,
    login.properties?.password,
    "login password",
  );
  assert(
    email.type === "string" &&
      email.format === "email" &&
      email.minLength === 1 &&
      email.maxLength === 254,
    "login email must be an email bounded to 254 characters",
  );
  assert(
    password.type === "string" && password.maxLength === 128,
    "login password must be bounded to 128 characters",
  );

  const batch = requestJsonSchema(
    document,
    operations.get(operationKey("post", "/api/v1/telemetry-batches")),
  );
  validateClosedObjectSchema(
    batch,
    ["idempotencyKey", "readings"],
    ["idempotencyKey", "readings"],
    "telemetry batch request",
  );
  const idempotencyKey = resolvedSchema(
    document,
    batch.properties?.idempotencyKey,
    "telemetry idempotency key",
  );
  assert(
    idempotencyKey.type === "string" &&
      idempotencyKey.maxLength === 100 &&
      idempotencyKey.pattern === "^[A-Za-z0-9][A-Za-z0-9._:-]{0,99}$",
    "telemetry idempotency key must use the implemented 100-character bound and pattern",
  );
  const readings = resolvedSchema(
    document,
    batch.properties?.readings,
    "telemetry readings",
  );
  assert(
    readings.type === "array" &&
      readings.minItems === 1 &&
      readings.maxItems === 100,
    "telemetry readings must be bounded from 1 to 100 items",
  );
  const reading = resolvedSchema(document, readings.items, "telemetry reading");
  validateClosedObjectSchema(
    reading,
    ["sensorId", "value", "observedAt"],
    ["sensorId", "value", "observedAt"],
    "telemetry reading",
  );
  const sensorId = resolvedSchema(
    document,
    reading.properties?.sensorId,
    "telemetry reading sensorId",
  );
  assert(
    sensorId.type === "string" && sensorId.format === "uuid",
    "telemetry reading sensorId must be a UUID",
  );
  const value = resolvedSchema(
    document,
    reading.properties?.value,
    "telemetry reading value",
  );
  assert(
    value.type === "number" &&
      Number(value.minimum) === -1_000_000_000_000 &&
      Number(value.maximum) === 1_000_000_000_000 &&
      value.multipleOf === 0.000001,
    "telemetry reading value must retain its implemented numeric bounds",
  );
  const observedAt = resolvedSchema(
    document,
    reading.properties?.observedAt,
    "telemetry reading observedAt",
  );
  assert(
    observedAt.type === "string" && observedAt.format === "date-time",
    "telemetry reading observedAt must be a date-time",
  );

  const createWorkOrder = requestJsonSchema(
    document,
    operations.get(operationKey("post", "/api/v1/work-orders")),
  );
  validateClosedObjectSchema(
    createWorkOrder,
    ["alertId"],
    ["alertId"],
    "create-work-order request",
  );
  const sourceAlertId = resolvedSchema(
    document,
    createWorkOrder.properties?.alertId,
    "create-work-order alertId",
  );
  assert(
    sourceAlertId.type === "string" && sourceAlertId.format === "uuid",
    "create-work-order alertId must be a UUID",
  );

  for (const path of [
    "/api/v1/work-orders/{workOrderId}/assign",
    "/api/v1/work-orders/{workOrderId}/start",
    "/api/v1/work-orders/{workOrderId}/complete",
  ]) {
    const schema = requestJsonSchema(
      document,
      operations.get(operationKey("post", path)),
    );
    const expectedProperties = path.endsWith("/assign")
      ? ["technicianUserId", "expectedVersion"]
      : ["expectedVersion"];
    validateClosedObjectSchema(
      schema,
      expectedProperties,
      expectedProperties,
      `${path} request`,
    );
    const version = resolvedSchema(
      document,
      schema.properties?.expectedVersion,
      `${path} expectedVersion`,
    );
    assert(
      version.type === "integer" &&
        version.format === "int64" &&
        version.minimum === 0,
      `${path} expectedVersion must be a non-negative int64`,
    );
  }

  const assignment = requestJsonSchema(
    document,
    operations.get(
      operationKey("post", "/api/v1/work-orders/{workOrderId}/assign"),
    ),
  );
  const technicianUserId = resolvedSchema(
    document,
    assignment.properties?.technicianUserId,
    "work-order assignment technicianUserId",
  );
  assert(
    technicianUserId.type === "string" && technicianUserId.format === "uuid",
    "work-order assignment technicianUserId must be a UUID",
  );
}

function validateCriticalResponseSchemas(document) {
  const workOrderDetail = resolvedSchema(
    document,
    document.components?.schemas?.WorkOrderDetailResponse,
    "WorkOrderDetailResponse",
  );
  validateClosedObjectSchema(
    workOrderDetail,
    [
      "id",
      "alertId",
      "status",
      "version",
      "assignedTechnician",
      "createdAt",
      "updatedAt",
      "context",
      "history",
    ],
    [
      "id",
      "alertId",
      "status",
      "version",
      "assignedTechnician",
      "createdAt",
      "updatedAt",
      "context",
      "history",
    ],
    "WorkOrderDetailResponse",
  );
  const history = resolvedSchema(
    document,
    workOrderDetail.properties?.history,
    "WorkOrderDetailResponse.history",
  );
  assert(
    history.type === "array" && history.maxItems === 3,
    "WorkOrderDetailResponse.history must be bounded to three transitions",
  );
  const historyItem = resolvedSchema(
    document,
    history.items,
    "WorkOrderDetailResponse.history item",
  );
  validateClosedObjectSchema(
    historyItem,
    ["sequenceNumber", "fromStatus", "toStatus", "actor", "transitionedAt"],
    ["sequenceNumber", "fromStatus", "toStatus", "actor", "transitionedAt"],
    "WorkOrderHistoryResponse",
  );
  const actor = historyItem.properties?.actor;
  assert(
    isObject(actor) &&
      Array.isArray(actor.oneOf) &&
      actor.oneOf.length === 2 &&
      actor.oneOf.some(
        (candidate) => candidate?.$ref === "#/components/schemas/ActorResponse",
      ) &&
      actor.oneOf.some((candidate) => candidate?.type === "null"),
    "WorkOrderHistoryResponse.actor must allow only an actor or the legacy null value",
  );

  const dashboard = resolvedSchema(
    document,
    document.components?.schemas?.DashboardResponse,
    "DashboardResponse",
  );
  const recentActivity = resolvedSchema(
    document,
    dashboard.properties?.recentActivity,
    "DashboardResponse.recentActivity",
  );
  assert(
    recentActivity.type === "array" && recentActivity.maxItems === 5,
    "DashboardResponse.recentActivity must be bounded to five events",
  );

  const dashboardActions = resolvedSchema(
    document,
    document.components?.schemas?.DashboardActivityAction,
    "DashboardActivityAction",
  );
  assert(
    dashboardActions.type === "string" &&
      sameStringSet(dashboardActions.enum ?? [], [
        "ALERT_ACKNOWLEDGED",
        "ALERT_RESOLVED",
        "WORK_ORDER_CREATED",
        "WORK_ORDER_ASSIGNED",
        "WORK_ORDER_STARTED",
        "WORK_ORDER_COMPLETED",
      ]),
    "DashboardActivityAction must define only dashboard query actions",
  );
  const dashboardSubjectTypes = resolvedSchema(
    document,
    document.components?.schemas?.DashboardSubjectType,
    "DashboardSubjectType",
  );
  assert(
    dashboardSubjectTypes.type === "string" &&
      sameStringSet(dashboardSubjectTypes.enum ?? [], ["ALERT", "WORK_ORDER"]),
    "DashboardSubjectType must define only dashboard subject types",
  );

  for (const [schemaName, propertyName] of [
    ["ThresholdRuleResponse", "cooldownSeconds"],
    ["AlertRuleContext", "cooldownSeconds"],
  ]) {
    const parent = resolvedSchema(
      document,
      document.components?.schemas?.[schemaName],
      schemaName,
    );
    const cooldown = resolvedSchema(
      document,
      parent.properties?.[propertyName],
      `${schemaName}.${propertyName}`,
    );
    assert(
      cooldown.type === "integer" &&
        cooldown.minimum === 0 &&
        cooldown.maximum === 604800,
      `${schemaName}.${propertyName} must be bounded from zero to seven days`,
    );
  }

  const deadEvent = resolvedSchema(
    document,
    document.components?.schemas?.DeadProcessingEventResponse,
    "DeadProcessingEventResponse",
  );
  const lastErrorMessage = resolvedSchema(
    document,
    deadEvent.properties?.lastErrorMessage,
    "DeadProcessingEventResponse.lastErrorMessage",
  );
  assert(
    lastErrorMessage.type === "string" && lastErrorMessage.maxLength === 256,
    "DeadProcessingEventResponse.lastErrorMessage must be bounded to 256 characters",
  );
  const eventType = resolvedSchema(
    document,
    deadEvent.properties?.eventType,
    "DeadProcessingEventResponse.eventType",
  );
  const attemptCount = resolvedSchema(
    document,
    deadEvent.properties?.attemptCount,
    "DeadProcessingEventResponse.attemptCount",
  );
  const lastErrorCode = resolvedSchema(
    document,
    deadEvent.properties?.lastErrorCode,
    "DeadProcessingEventResponse.lastErrorCode",
  );
  assert(
    eventType.type === "string" &&
      eventType.const === "TELEMETRY_BATCH_ACCEPTED",
    "DeadProcessingEventResponse.eventType must be the implemented event type",
  );
  assert(
    attemptCount.type === "integer" && attemptCount.const === 5,
    "DeadProcessingEventResponse.attemptCount must be the terminal fifth attempt",
  );
  assert(
    lastErrorCode.type === "string" &&
      lastErrorCode.maxLength === 64 &&
      sameStringSet(lastErrorCode.enum ?? [], [
        "PROCESSING_FAILED",
        "LEASE_EXPIRED",
      ]),
    "DeadProcessingEventResponse.lastErrorCode must define the safe terminal codes",
  );
}

function validateSuccessResponses(document, operations) {
  for (const expected of EXPECTED_SUCCESS_RESPONSES) {
    const key = operationKey(expected.method, expected.path);
    const entry = operations.get(key);
    const responses = entry.operation.responses;
    assert(isObject(responses), `${key} must define responses`);
    const successStatuses = Object.keys(responses).filter((status) =>
      /^2[0-9]{2}$/u.test(status),
    );
    assert(
      sameStringSet(successStatuses, [expected.status]),
      `${key} must define exactly the implemented ${expected.status} success response`,
    );

    const response = dereference(
      document,
      responses[expected.status],
      `${key} response ${expected.status}`,
    );
    assert(
      isObject(response),
      `${key} response ${expected.status} must be an object`,
    );

    if (expected.mediaType === undefined) {
      assert(
        response.content === undefined ||
          (isObject(response.content) &&
            Object.keys(response.content).length === 0),
        `${key} response ${expected.status} must not define a body`,
      );
      continue;
    }

    assert(
      isObject(response.content) &&
        Object.keys(response.content).length === 1 &&
        isObject(response.content[expected.mediaType]),
      `${key} response ${expected.status} must use only ${expected.mediaType}`,
    );
    const rawSchema = response.content[expected.mediaType].schema;
    assert(
      isObject(rawSchema),
      `${key} response ${expected.status} must define a response schema`,
    );
    if (expected.schemaReference !== undefined) {
      assert(
        rawSchema.$ref === `#/components/schemas/${expected.schemaReference}`,
        `${key} response ${expected.status} must use ${expected.schemaReference}`,
      );
    } else {
      const schema = resolvedSchema(
        document,
        rawSchema,
        `${key} response ${expected.status} schema`,
      );
      assert(
        schema.type === "string",
        `${key} response ${expected.status} must describe the event-stream body`,
      );
    }
  }
}

function validateProblemSchema(document) {
  const rawProblem = document.components?.schemas?.Problem;
  assert(rawProblem !== undefined, "components.schemas.Problem is required");
  const problem = resolvedSchema(document, rawProblem, "Problem schema");
  assert(
    problem.type === "object" && problem.additionalProperties === true,
    "Problem must be an extensible object schema",
  );
  requiredProperties(
    problem,
    ["type", "title", "status", "detail", "instance", "code", "correlationId"],
    "Problem schema",
  );

  for (const name of ["type", "instance"]) {
    const property = resolvedSchema(
      document,
      problem.properties?.[name],
      `Problem.${name}`,
    );
    assert(
      property.type === "string" && property.format === "uri-reference",
      `Problem.${name} must be a URI reference`,
    );
  }
  for (const name of ["title", "detail", "code"]) {
    const property = resolvedSchema(
      document,
      problem.properties?.[name],
      `Problem.${name}`,
    );
    assert(property.type === "string", `Problem.${name} must be a string`);
  }
  const status = resolvedSchema(
    document,
    problem.properties?.status,
    "Problem.status",
  );
  assert(
    status.type === "integer" &&
      status.minimum === 100 &&
      status.maximum === 599,
    "Problem.status must be an HTTP status from 100 to 599",
  );
  const correlationId = resolvedSchema(
    document,
    problem.properties?.correlationId,
    "Problem.correlationId",
  );
  assert(
    correlationId.type === "string" && correlationId.format === "uuid",
    "Problem.correlationId must be a UUID",
  );
}

function validateProblemResponses(document, operations) {
  for (const [key, entry] of operations) {
    assert(isObject(entry.operation.responses), `${key} must define responses`);
    assert(
      Object.keys(entry.operation.responses).length > 0,
      `${key} must define at least one response`,
    );
    let problemCount = 0;

    for (const [status, rawResponse] of Object.entries(
      entry.operation.responses,
    )) {
      const response = dereference(
        document,
        rawResponse,
        `${key} response ${status}`,
      );
      assert(isObject(response), `${key} response ${status} must be an object`);
      assert(
        typeof response.description === "string" &&
          response.description.length > 0,
        `${key} response ${status} must have a description`,
      );
      if (!/^(?:[45][0-9X]{2}|default)$/u.test(status)) {
        continue;
      }

      const problemMedia = response.content?.["application/problem+json"];
      assert(
        isObject(problemMedia),
        `${key} error response ${status} must use application/problem+json`,
      );
      assert(
        problemMedia.schema?.$ref === "#/components/schemas/Problem",
        `${key} error response ${status} must use the Problem schema`,
      );
      problemCount += 1;
    }

    if (key !== "GET /api/v1/status" && key !== "GET /api/v1/session/csrf") {
      assert(
        problemCount > 0,
        `${key} must document at least one problem response`,
      );
    }
  }
}

function validateNoStoreResponses(document, operations) {
  const reusableHeader = dereference(
    document,
    document.components?.headers?.CacheControlNoStore,
    "CacheControlNoStore header",
  );
  assert(
    isObject(reusableHeader),
    "components.headers.CacheControlNoStore is required",
  );
  const reusableSchema = resolvedSchema(
    document,
    reusableHeader.schema,
    "CacheControlNoStore schema",
  );
  assert(
    reusableSchema.type === "string" && reusableSchema.const === "no-store",
    "CacheControlNoStore must describe the exact no-store value",
  );

  for (const [key, entry] of operations) {
    if (key === "GET /api/v1/status") {
      continue;
    }
    for (const [status, rawResponse] of Object.entries(
      entry.operation.responses,
    )) {
      const response = dereference(
        document,
        rawResponse,
        `${key} response ${status}`,
      );
      const cacheHeaderEntry = Object.entries(response.headers ?? {}).find(
        ([name]) => name.toLowerCase() === "cache-control",
      );
      assert(
        cacheHeaderEntry !== undefined,
        `${key} response ${status} must document Cache-Control: no-store`,
      );
      const header = dereference(
        document,
        cacheHeaderEntry[1],
        `${key} response ${status} Cache-Control header`,
      );
      const schema = resolvedSchema(
        document,
        header.schema,
        `${key} response ${status} Cache-Control schema`,
      );
      assert(
        schema.type === "string" && schema.const === "no-store",
        `${key} response ${status} must describe the exact no-store value`,
      );
    }
  }
}

function validateImplementedResponseHeaders(document, operations) {
  const reusableCorrelationId = dereference(
    document,
    document.components?.headers?.CorrelationId,
    "CorrelationId header",
  );
  const reusableCorrelationSchema = resolvedSchema(
    document,
    reusableCorrelationId?.schema,
    "CorrelationId header schema",
  );
  assert(
    reusableCorrelationSchema.type === "string" &&
      reusableCorrelationSchema.format === "uuid",
    "CorrelationId header must describe a server-generated UUID",
  );

  for (const [key, entry] of operations) {
    for (const [status, rawResponse] of Object.entries(
      entry.operation.responses,
    )) {
      const response = dereference(
        document,
        rawResponse,
        `${key} response ${status}`,
      );
      const correlationEntry = Object.entries(response.headers ?? {}).find(
        ([name]) => name.toLowerCase() === "x-correlation-id",
      );
      assert(
        correlationEntry !== undefined,
        `${key} response ${status} must document X-Correlation-ID`,
      );
      const correlationHeader = dereference(
        document,
        correlationEntry[1],
        `${key} response ${status} X-Correlation-ID header`,
      );
      const correlationSchema = resolvedSchema(
        document,
        correlationHeader.schema,
        `${key} response ${status} X-Correlation-ID schema`,
      );
      assert(
        correlationSchema.type === "string" &&
          correlationSchema.format === "uuid",
        `${key} response ${status} X-Correlation-ID must be a UUID`,
      );
    }
  }

  const created = dereference(
    document,
    operations.get(operationKey("post", "/api/v1/work-orders")).operation
      .responses["201"],
    "POST /api/v1/work-orders response 201",
  );
  assert(
    created.headers?.Location !== undefined,
    "POST /api/v1/work-orders must document its Location header",
  );
  const location = dereference(
    document,
    created.headers?.Location,
    "POST /api/v1/work-orders Location header",
  );
  const locationSchema = resolvedSchema(
    document,
    location?.schema,
    "POST /api/v1/work-orders Location schema",
  );
  assert(
    locationSchema.type === "string" &&
      locationSchema.format === "uri-reference",
    "POST /api/v1/work-orders must document its Location header",
  );
}

function validateSseContract(document, operations) {
  const key = "GET /api/v1/alerts/stream";
  const stream = operations.get(key).operation;
  const response = dereference(
    document,
    stream.responses?.["200"],
    `${key} response 200`,
  );
  assert(isObject(response), `${key} must define a 200 response`);
  const media = response.content?.["text/event-stream"];
  assert(isObject(media), `${key} must produce text/event-stream`);
  assert(
    response.headers?.["X-Accel-Buffering"] !== undefined,
    `${key} must document X-Accel-Buffering: no`,
  );
  const bufferingHeader = dereference(
    document,
    response.headers?.["X-Accel-Buffering"],
    `${key} X-Accel-Buffering header`,
  );
  const bufferingSchema = resolvedSchema(
    document,
    bufferingHeader?.schema,
    `${key} X-Accel-Buffering schema`,
  );
  assert(
    bufferingSchema.type === "string" && bufferingSchema.const === "no",
    `${key} must document X-Accel-Buffering: no`,
  );
  const streamSchema = resolvedSchema(
    document,
    media.schema,
    `${key} stream schema`,
  );
  assert(
    streamSchema.type === "string",
    `${key} stream body must describe SSE framing`,
  );

  const events = stream["x-assetpulse-sse-events"];
  assert(isObject(events), `${key} must declare x-assetpulse-sse-events`);
  assert(
    Object.keys(events).length === 2 &&
      events.ready === "#/components/schemas/AlertStreamReadyEvent" &&
      events["alert-changed"] ===
        "#/components/schemas/AlertStreamChangedEvent",
    `${key} must declare exactly the ready and alert-changed events`,
  );
  resolveLocalReference(document, events.ready);
  resolveLocalReference(document, events["alert-changed"]);

  const ready = resolvedSchema(
    document,
    document.components?.schemas?.AlertStreamReadyEvent,
    "AlertStreamReadyEvent",
  );
  const changed = resolvedSchema(
    document,
    document.components?.schemas?.AlertStreamChangedEvent,
    "AlertStreamChangedEvent",
  );
  assert(
    ready.type === "object" &&
      ready.additionalProperties === false &&
      sameStringSet(ready.required ?? [], ["event", "data"]) &&
      ready.properties?.event?.const === "ready",
    "AlertStreamReadyEvent must describe the ready event",
  );
  const readyData = resolvedSchema(
    document,
    ready.properties?.data,
    "AlertStreamReadyEvent.data",
  );
  assert(
    readyData.type === "object" &&
      readyData.additionalProperties === false &&
      readyData.maxProperties === 0,
    "AlertStreamReadyEvent.data must be the implemented empty object",
  );
  assert(
    changed.type === "object" &&
      changed.additionalProperties === false &&
      sameStringSet(changed.required ?? [], ["event", "data"]) &&
      changed.properties?.event?.const === "alert-changed",
    "AlertStreamChangedEvent must describe the alert-changed event",
  );
  assert(
    changed.properties?.data?.$ref === "#/components/schemas/AlertChangeEvent",
    "AlertStreamChangedEvent.data must use AlertChangeEvent",
  );

  const payload = resolvedSchema(
    document,
    document.components?.schemas?.AlertChangeEvent,
    "AlertChangeEvent",
  );
  assert(payload.type === "object", "AlertChangeEvent must be an object");
  requiredProperties(payload, ["alertId", "changeType"], "AlertChangeEvent");
  const alertId = resolvedSchema(
    document,
    payload.properties?.alertId,
    "AlertChangeEvent.alertId",
  );
  const changeType = resolvedSchema(
    document,
    payload.properties?.changeType,
    "AlertChangeEvent.changeType",
  );
  assert(
    alertId.type === "string" && alertId.format === "uuid",
    "AlertChangeEvent.alertId must be a UUID",
  );
  assert(
    changeType.type === "string" &&
      sameStringSet(changeType.enum ?? [], [
        "OCCURRENCE_RECORDED",
        "STATUS_CHANGED",
      ]),
    "AlertChangeEvent.changeType must define the implemented change types",
  );
}

function validateRoot(document) {
  assert(isObject(document), "document root must be an object");
  assert(
    typeof document.openapi === "string" &&
      /^3\.1(?:\.[0-9]+)?$/u.test(document.openapi),
    "openapi must declare version 3.1",
  );
  assert(
    isObject(document.info) &&
      typeof document.info.title === "string" &&
      document.info.title.length > 0 &&
      typeof document.info.version === "string" &&
      document.info.version.length > 0,
    "info must define a title and version",
  );
  assert(isObject(document.components), "components must be an object");
  assert(
    isObject(document.components.schemas),
    "components.schemas must be an object",
  );
}

export function parseAndValidateOpenApiDocument(raw) {
  assert(typeof raw === "string", "document must be supplied as JSON text");
  assert(raw.length > 0, "document must not be empty");
  assert(
    raw.length <= MAX_DOCUMENT_CHARACTERS,
    `document exceeds ${MAX_DOCUMENT_CHARACTERS} characters`,
  );

  let document;
  try {
    document = JSON.parse(raw);
  } catch (cause) {
    fail("document is not valid JSON", { cause });
  }

  validateRoot(document);
  validateAllLocalReferences(document);
  const operations = collectOperations(document);
  validateOperationParity(operations);
  validateOperationIds(operations);
  validateOperationMetadata(document, operations);
  validateSecuritySchemes(document);
  validateSecurityAndRoles(operations);
  validateNoUntrustedScopeInputs(document, operations);
  validatePathParameters(document, operations);
  validateCsrfParameters(document, operations);
  validateCriticalBounds(document, operations);
  validateCriticalResponseSchemas(document);
  validateSuccessResponses(document, operations);
  validateProblemSchema(document);
  validateProblemResponses(document, operations);
  validateNoStoreResponses(document, operations);
  validateImplementedResponseHeaders(document, operations);
  validateSseContract(document, operations);

  return document;
}
