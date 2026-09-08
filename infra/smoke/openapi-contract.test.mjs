import assert from "node:assert/strict";
import { readdir, readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { test } from "node:test";

import {
  EXPECTED_OPENAPI_OPERATIONS,
  OpenApiContractError,
  parseAndValidateOpenApiDocument,
} from "./openapi-contract.mjs";

const REPOSITORY_ROOT = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../..",
);
const OPENAPI_PATH = path.join(
  REPOSITORY_ROOT,
  "frontend",
  "public",
  "openapi.json",
);
const JAVA_ROOT = path.join(REPOSITORY_ROOT, "backend", "src", "main", "java");
const SECURITY_CONFIGURATION_PATH = path.join(
  JAVA_ROOT,
  "io",
  "github",
  "moar0210",
  "assetpulse",
  "security",
  "SecurityConfiguration.java",
);

function key(method, route) {
  return `${method.toUpperCase()} ${route}`;
}

function sorted(values) {
  return [...values].sort((left, right) => left.localeCompare(right));
}

async function javaFiles(directory) {
  const files = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const entryPath = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      files.push(...(await javaFiles(entryPath)));
    } else if (entry.isFile() && entry.name.endsWith(".java")) {
      files.push(entryPath);
    }
  }
  return files;
}

function mappingPath(argumentsText) {
  if (argumentsText === undefined) {
    return "";
  }
  const explicit = argumentsText.match(
    /(?:^|,)\s*(?:path|value)\s*=\s*"([^"]*)"/u,
  );
  if (explicit) {
    return explicit[1];
  }
  return argumentsText.match(/^\s*"([^"]*)"/u)?.[1] ?? "";
}

function joinRoute(base, suffix) {
  if (!suffix) {
    return base;
  }
  return `${base.replace(/\/$/u, "")}/${suffix.replace(/^\//u, "")}`;
}

async function deriveControllerOperations() {
  const operations = [];
  const annotation =
    /@(Get|Put|Post|Delete|Options|Head|Patch)Mapping(?:\s*\(([\s\S]*?)\))?/gu;

  for (const file of await javaFiles(JAVA_ROOT)) {
    const source = await readFile(file, "utf8");
    if (!/@RestController(?:\s|$)/u.test(source)) {
      continue;
    }
    const base = source.match(
      /@RequestMapping\s*\(\s*(?:(?:path|value)\s*=\s*)?"([^"]+)"/u,
    )?.[1];
    assert.ok(
      base,
      `${path.relative(REPOSITORY_ROOT, file)} needs a class route`,
    );

    for (const match of source.matchAll(annotation)) {
      operations.push(
        key(match[1].toLowerCase(), joinRoute(base, mappingPath(match[2]))),
      );
    }
  }

  return operations;
}

async function deriveFilterOwnedOperations() {
  const source = await readFile(SECURITY_CONFIGURATION_PATH, "utf8");
  const operations = [];
  const logoutMatcher =
    /logoutRequestMatcher\s*\(\s*paths\.matcher\s*\(\s*HttpMethod\.([A-Z]+)\s*,\s*"([^"]+)"\s*\)/gu;

  for (const match of source.matchAll(logoutMatcher)) {
    operations.push(key(match[1], match[2]));
  }

  assert.deepEqual(operations, ["DELETE /api/v1/session"]);
  return operations;
}

async function deriveAuthorizationRoles() {
  const source = await readFile(SECURITY_CONFIGURATION_PATH, "utf8");
  const authorizationStart = source.indexOf(".authorizeHttpRequests(");
  assert.notEqual(authorizationStart, -1);
  const authorization = source.slice(authorizationStart);
  const rules = [];
  const rulePattern =
    /\.requestMatchers\(([\s\S]*?)\)\s*\.(permitAll|authenticated|hasRole|hasAnyRole)\(([\s\S]*?)\)/gu;

  for (const match of authorization.matchAll(rulePattern)) {
    const routes = [...match[1].matchAll(/"(\/api\/[^"\\]+)"/gu)].map(
      (route) => route[1],
    );
    if (routes.length === 0) {
      continue;
    }
    const method = match[1].match(/HttpMethod\.([A-Z]+)/u)?.[1];
    const access = match[2];
    const roles =
      access === "permitAll"
        ? []
        : access === "authenticated"
          ? ["OPERATIONS_ADMIN", "TECHNICIAN", "VIEWER"]
          : [...match[3].matchAll(/"([A-Z_]+)"/gu)].map((role) => role[1]);
    rules.push({ method, routes, roles });
  }

  const matrix = new Map();
  for (const expected of EXPECTED_OPENAPI_OPERATIONS) {
    const rule = rules.find(
      (candidate) =>
        candidate.routes.includes(expected.path) &&
        (candidate.method === undefined ||
          candidate.method === expected.method.toUpperCase()),
    );
    assert.ok(
      rule,
      `SecurityConfiguration has no authorization rule for ${key(
        expected.method,
        expected.path,
      )}`,
    );
    matrix.set(key(expected.method, expected.path), rule.roles);
  }
  return matrix;
}

function resolveReference(document, value) {
  let current = value;
  const seen = new Set();
  while (current?.$ref) {
    assert.equal(current.$ref.startsWith("#/"), true);
    assert.equal(seen.has(current.$ref), false);
    seen.add(current.$ref);
    current = current.$ref
      .slice(2)
      .split("/")
      .map((token) => token.replaceAll("~1", "/").replaceAll("~0", "~"))
      .reduce((container, token) => container[token], document);
  }
  return current;
}

function operationAt(document, method, route) {
  return document.paths[route][method];
}

function responseAt(document, method, route, status) {
  return resolveReference(
    document,
    operationAt(document, method, route).responses[status],
  );
}

function requestSchemaAt(document, method, route) {
  const requestBody = resolveReference(
    document,
    operationAt(document, method, route).requestBody,
  );
  return resolveReference(
    document,
    requestBody.content["application/json"].schema,
  );
}

function parameterAt(document, method, route, name, location) {
  const operation = operationAt(document, method, route);
  const parameters = [
    ...(document.paths[route].parameters ?? []),
    ...(operation.parameters ?? []),
  ].map((parameter) => resolveReference(document, parameter));
  return parameters.find(
    (parameter) => parameter.name === name && parameter.in === location,
  );
}

function firstReferenceSlot(value) {
  if (value === null || typeof value !== "object") {
    return undefined;
  }
  if (Object.hasOwn(value, "$ref")) {
    return value;
  }
  for (const child of Object.values(value)) {
    const found = firstReferenceSlot(child);
    if (found) {
      return found;
    }
  }
  return undefined;
}

function assertInvalid(validDocument, mutate, message) {
  const changed = structuredClone(validDocument);
  mutate(changed);
  assert.throws(
    () => parseAndValidateOpenApiDocument(JSON.stringify(changed)),
    (error) =>
      error instanceof OpenApiContractError && message.test(error.message),
  );
}

const validRaw = await readFile(OPENAPI_PATH, "utf8");
const validDocument = JSON.parse(validRaw);

test("the published OpenAPI document satisfies the dependency-free contract check", () => {
  const parsed = parseAndValidateOpenApiDocument(validRaw);
  assert.deepEqual(parsed, validDocument);
});

test("the fixed operation registry has exact controller and filter-owned parity", async () => {
  const sourceOperations = [
    ...(await deriveControllerOperations()),
    ...(await deriveFilterOwnedOperations()),
  ];
  const expectedOperations = EXPECTED_OPENAPI_OPERATIONS.map(
    ({ method, path }) => key(method, path),
  );

  assert.equal(sourceOperations.length, 26);
  assert.equal(new Set(sourceOperations).size, sourceOperations.length);
  assert.deepEqual(sorted(expectedOperations), sorted(sourceOperations));
});

test("the declared role matrix has exact SecurityConfiguration parity", async () => {
  const sourceMatrix = await deriveAuthorizationRoles();
  for (const expected of EXPECTED_OPENAPI_OPERATIONS) {
    assert.deepEqual(
      sorted(expected.roles),
      sorted(sourceMatrix.get(key(expected.method, expected.path))),
      key(expected.method, expected.path),
    );
  }
});

test("malformed JSON and non-OpenAPI 3.1 documents are rejected", () => {
  assert.throws(
    () => parseAndValidateOpenApiDocument("{"),
    (error) =>
      error instanceof OpenApiContractError &&
      /valid JSON/u.test(error.message),
  );
  assertInvalid(
    validDocument,
    (document) => {
      document.openapi = "3.0.3";
    },
    /version 3\.1/u,
  );
});

test("missing and stale operations are rejected", () => {
  assertInvalid(
    validDocument,
    (document) => {
      delete document.paths["/api/v1/dashboard"].get;
    },
    /missing GET \/api\/v1\/dashboard/u,
  );
  assertInvalid(
    validDocument,
    (document) => {
      document.paths["/api/v1/stale"] = {
        get: {
          ...structuredClone(document.paths["/api/v1/status"].get),
          operationId: "getStaleOperation",
        },
      };
    },
    /undocumented\/stale GET \/api\/v1\/stale/u,
  );
});

test("duplicate operation IDs and unresolved or external references are rejected", () => {
  assertInvalid(
    validDocument,
    (document) => {
      operationAt(document, "get", "/api/v1/dashboard").operationId =
        operationAt(document, "get", "/api/v1/status").operationId;
    },
    /operationId .* duplicated/u,
  );
  assertInvalid(
    validDocument,
    (document) => {
      firstReferenceSlot(document).$ref = "#/components/schemas/DoesNotExist";
    },
    /does not resolve/u,
  );
  assertInvalid(
    validDocument,
    (document) => {
      firstReferenceSlot(document).$ref = "https://example.test/schema.json";
    },
    /local JSON Pointer/u,
  );
});

test("operation summaries and declared tags cannot drift", () => {
  assertInvalid(
    validDocument,
    (document) => {
      operationAt(document, "get", "/api/v1/dashboard").summary = "";
    },
    /non-empty summary/u,
  );
  assertInvalid(
    validDocument,
    (document) => {
      operationAt(document, "get", "/api/v1/dashboard").tags = ["Undeclared"];
    },
    /exactly one declared tag/u,
  );
});

test("security, role, CSRF, and trusted tenant declarations cannot drift", () => {
  assertInvalid(
    validDocument,
    (document) => {
      operationAt(document, "get", "/api/v1/assets").security = [];
    },
    /incorrect security requirement/u,
  );
  assertInvalid(
    validDocument,
    (document) => {
      operationAt(document, "post", "/api/v1/demo/reset")[
        "x-assetpulse-roles"
      ] = ["VIEWER"];
    },
    /incorrect role matrix/u,
  );
  assertInvalid(
    validDocument,
    (document) => {
      delete operationAt(document, "get", "/api/v1/dashboard")[
        "x-assetpulse-tenant-scope"
      ];
    },
    /trusted session organisation/u,
  );
  assertInvalid(
    validDocument,
    (document) => {
      operationAt(document, "post", "/api/v1/session").parameters = [];
    },
    /required X-CSRF-TOKEN/u,
  );
  assertInvalid(
    validDocument,
    (document) => {
      operationAt(document, "post", "/api/v1/session").security = [
        { csrfToken: [] },
      ];
    },
    /incorrect security requirement/u,
  );
});

test("tenant and role scope cannot be accepted from untrusted request input", () => {
  assertInvalid(
    validDocument,
    (document) => {
      operationAt(document, "get", "/api/v1/assets").parameters = [
        {
          name: "organisationId",
          in: "query",
          schema: { type: "string", format: "uuid" },
        },
      ];
    },
    /untrusted scope parameter organisationId/u,
  );
  assertInvalid(
    validDocument,
    (document) => {
      const login = requestSchemaAt(document, "post", "/api/v1/session");
      login.properties.roleCode = { type: "string" };
    },
    /untrusted scope property roleCode/u,
  );
  assertInvalid(
    validDocument,
    (document) => {
      const request = requestSchemaAt(
        document,
        "post",
        "/api/v1/work-orders/{workOrderId}/start",
      );
      request.properties.actorUserId = { type: "string", format: "uuid" };
    },
    /untrusted scope property actorUserId/u,
  );
});

test("list, range, ingestion, and optimistic-version bounds cannot drift", () => {
  assertInvalid(
    validDocument,
    (document) => {
      parameterAt(
        document,
        "get",
        "/api/v1/alerts",
        "limit",
        "query",
      ).schema.maximum = 101;
    },
    /bounded 1-100/u,
  );
  assertInvalid(
    validDocument,
    (document) => {
      parameterAt(
        document,
        "get",
        "/api/v1/sensors/{sensorId}/telemetry-readings",
        "limit",
        "query",
      ).schema.maximum = 501;
    },
    /bounded 1-500/u,
  );
  assertInvalid(
    validDocument,
    (document) => {
      const batch = requestSchemaAt(
        document,
        "post",
        "/api/v1/telemetry-batches",
      );
      resolveReference(document, batch.properties.readings).maxItems = 101;
    },
    /1 to 100 items/u,
  );
  assertInvalid(
    validDocument,
    (document) => {
      const batch = requestSchemaAt(
        document,
        "post",
        "/api/v1/telemetry-batches",
      );
      const reading = resolveReference(
        document,
        resolveReference(document, batch.properties.readings).items,
      );
      reading.properties.value.type = "string";
    },
    /numeric bounds/u,
  );
  assertInvalid(
    validDocument,
    (document) => {
      const request = requestSchemaAt(
        document,
        "post",
        "/api/v1/work-orders/{workOrderId}/start",
      );
      resolveReference(document, request.properties.expectedVersion).minimum =
        -1;
    },
    /non-negative int64/u,
  );
  assertInvalid(
    validDocument,
    (document) => {
      const request = requestSchemaAt(
        document,
        "post",
        "/api/v1/work-orders/{workOrderId}/assign",
      );
      request.required = ["expectedVersion"];
    },
    /incorrect required-property set/u,
  );
});

test("request schemas remain closed and exact", () => {
  assertInvalid(
    validDocument,
    (document) => {
      requestSchemaAt(
        document,
        "post",
        "/api/v1/session",
      ).additionalProperties = true;
    },
    /login request must be a closed object schema/u,
  );
  assertInvalid(
    validDocument,
    (document) => {
      requestSchemaAt(
        document,
        "post",
        "/api/v1/work-orders",
      ).properties.actorUserId = { type: "string", format: "uuid" };
    },
    /untrusted scope property actorUserId/u,
  );
});

test("success statuses, media types, and response schemas remain exact", () => {
  assertInvalid(
    validDocument,
    (document) => {
      delete operationAt(document, "get", "/api/v1/assets").responses["200"];
    },
    /implemented 200 success response/u,
  );
  assertInvalid(
    validDocument,
    (document) => {
      responseAt(document, "get", "/api/v1/assets", "200").content[
        "application/json"
      ].schema = { $ref: "#/components/responses/Problem400" };
    },
    /must use AssetListResponse/u,
  );
});

test("bounded dashboard and work-order response shapes cannot drift", () => {
  assertInvalid(
    validDocument,
    (document) => {
      document.components.schemas.DashboardResponse.properties.recentActivity.maxItems = 10;
    },
    /bounded to five events/u,
  );
  assertInvalid(
    validDocument,
    (document) => {
      document.components.schemas.WorkOrderDetailResponse = {
        allOf: [
          { $ref: "#/components/schemas/WorkOrderResponse" },
          {
            type: "object",
            additionalProperties: false,
            required: ["history"],
            properties: {
              history: {
                type: "array",
                maxItems: 3,
                items: {
                  $ref: "#/components/schemas/WorkOrderHistoryResponse",
                },
              },
            },
          },
        ],
      };
    },
    /closed object schema/u,
  );
  assertInvalid(
    validDocument,
    (document) => {
      document.components.schemas.WorkOrderHistoryResponse.properties.actor = {
        $ref: "#/components/schemas/ActorResponse",
      };
    },
    /legacy null value/u,
  );
  assertInvalid(
    validDocument,
    (document) => {
      document.components.schemas.DashboardActivityAction.enum.push(
        "AUTHENTICATION_FAILED",
      );
    },
    /only dashboard query actions/u,
  );
  assertInvalid(
    validDocument,
    (document) => {
      document.components.schemas.ThresholdRuleResponse.properties.cooldownSeconds.minimum = 1;
    },
    /zero to seven days/u,
  );
  assertInvalid(
    validDocument,
    (document) => {
      document.components.schemas.DeadProcessingEventResponse.properties.lastErrorMessage.maxLength = 500;
    },
    /bounded to 256 characters/u,
  );
  assertInvalid(
    validDocument,
    (document) => {
      document.components.schemas.DeadProcessingEventResponse.properties.attemptCount.const = 4;
    },
    /terminal fifth attempt/u,
  );
});

test("SSE framing, problem details, and no-store responses cannot drift", () => {
  assertInvalid(
    validDocument,
    (document) => {
      delete responseAt(document, "get", "/api/v1/alerts/stream", "200")
        .content["text/event-stream"];
    },
    /must use only text\/event-stream/u,
  );
  assertInvalid(
    validDocument,
    (document) => {
      document.components.schemas.Problem.properties.status.maximum = 600;
    },
    /100 to 599/u,
  );
  assertInvalid(
    validDocument,
    (document) => {
      const response = responseAt(document, "get", "/api/v1/assets", "200");
      delete response.headers["Cache-Control"];
    },
    /Cache-Control: no-store/u,
  );
  assertInvalid(
    validDocument,
    (document) => {
      delete document.components.headers.CacheControlNoStore.schema.const;
    },
    /exact no-store value/u,
  );
  assertInvalid(
    validDocument,
    (document) => {
      document.components.schemas.AlertStreamChangedEvent.properties.data = {
        type: "string",
      };
    },
    /data must use AlertChangeEvent/u,
  );
});

test("implemented correlation, creation, and streaming headers cannot drift", () => {
  assertInvalid(
    validDocument,
    (document) => {
      delete responseAt(document, "get", "/api/v1/assets", "200").headers[
        "X-Correlation-ID"
      ];
    },
    /must document X-Correlation-ID/u,
  );
  assertInvalid(
    validDocument,
    (document) => {
      delete responseAt(document, "post", "/api/v1/work-orders", "201").headers
        .Location;
    },
    /must document its Location header/u,
  );
  assertInvalid(
    validDocument,
    (document) => {
      delete responseAt(document, "get", "/api/v1/alerts/stream", "200")
        .headers["X-Accel-Buffering"];
    },
    /must document X-Accel-Buffering: no/u,
  );
});
