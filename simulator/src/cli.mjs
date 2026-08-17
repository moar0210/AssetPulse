import { pathToFileURL } from "node:url";

import { createPublicApiClient } from "./client.mjs";
import { buildScenario, resolveAnchor } from "./scenarios.mjs";

const DEFAULT_BASE_URL = "http://localhost:8080";
const DEFAULT_EMAIL = "admin@northstar.example";
const DEFAULT_PASSWORD = "AssetPulse1!";

function parseArguments(argv) {
  if (
    !Array.isArray(argv) ||
    argv.length < 1 ||
    argv.length > 2 ||
    !argv.every((argument) => typeof argument === "string")
  ) {
    throw new Error("Usage: node src/cli.mjs <normal|overheating> [--at=<ISO instant>]");
  }

  const [scenarioName, option] = argv;
  if (scenarioName !== "normal" && scenarioName !== "overheating") {
    throw new Error("The scenario must be normal or overheating");
  }

  if (option === undefined) {
    return { scenarioName, rawAnchor: undefined };
  }

  if (!option.startsWith("--at=") || option.length === "--at=".length) {
    throw new Error("The optional argument must be --at=<ISO instant>");
  }

  return { scenarioName, rawAnchor: option.slice("--at=".length) };
}

function readEnvironment(environment, name, fallback) {
  if (typeof environment !== "object" || environment === null) {
    throw new Error("A process environment is required");
  }

  const value = environment[name];
  if (value === undefined) {
    return fallback;
  }

  if (typeof value !== "string") {
    throw new Error(`${name} must be text`);
  }

  return value;
}

export async function runCli({
  argv,
  environment,
  fetchImpl = globalThis.fetch,
  now = new Date(),
  write = console.log,
}) {
  const { scenarioName, rawAnchor } = parseArguments(argv);
  const anchor = resolveAnchor(rawAnchor, now);
  const scenario = buildScenario(scenarioName, anchor);
  const client = createPublicApiClient({
    baseUrl: readEnvironment(
      environment,
      "ASSETPULSE_BASE_URL",
      DEFAULT_BASE_URL,
    ),
    email: readEnvironment(environment, "ASSETPULSE_EMAIL", DEFAULT_EMAIL),
    password: readEnvironment(
      environment,
      "ASSETPULSE_PASSWORD",
      DEFAULT_PASSWORD,
    ),
    fetchImpl,
  });
  const accepted = await client.run(scenario.request);

  write(
    `Accepted ${scenario.name} scenario: batch ${accepted.batchId}, ${accepted.readingCount} readings, anchor ${scenario.anchor}`,
  );

  return accepted;
}

const isEntryPoint =
  process.argv[1] !== undefined &&
  import.meta.url === pathToFileURL(process.argv[1]).href;

if (isEntryPoint) {
  runCli({ argv: process.argv.slice(2), environment: process.env }).catch(
    (error) => {
      const message = error instanceof Error ? error.message : "Unexpected failure";
      console.error(`Simulator failed: ${message}`);
      process.exitCode = 1;
    },
  );
}
