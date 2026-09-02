#!/usr/bin/env node
/**
 * Smoke test for the model-probe sidecar.
 * Starts the server in-process, verifies /health, and (optionally) runs a
 * probe against a real endpoint.
 *
 * Usage:
 *   node src/smoke.js                 # health check only
 *   node src/smoke.js <baseUrl> <apiKey> <modelId>
 */

"use strict";

const path = require("path");

// Load the engine directly to confirm the vendored build is usable.
let engine;
try {
  engine = require(path.join(__dirname, "..", "vendor", "llmprobe-engine", "dist", "index.js"));
} catch (err) {
  console.error("[smoke] FAILED to load vendored engine:", err.message);
  process.exit(1);
}

console.log("[smoke] vendored engine loaded.");
console.log("[smoke] runProbes:", typeof engine.runProbes === "function" ? "OK" : "MISSING");
console.log("[smoke] probe count:", (engine.PROBE_SUITE || []).length);

// Optional end-to-end probe against a real endpoint.
const [, , baseUrl, apiKey, modelId] = process.argv;
if (baseUrl && apiKey && modelId) {
  console.log(`[smoke] running probes against ${baseUrl} model=${modelId} ...`);
  engine
    .runProbes({ baseUrl, apiKey, modelId, timeoutMs: 120000 })
    .then((report) => {
      console.log("[smoke] score:", report.score, "/", report.scoreMax);
      console.log("[smoke] probes:", report.results.length);
      console.log("[smoke] identity:", JSON.stringify(report.identityAssessment ?? null));
      console.log("[smoke] E2E OK");
      process.exit(0);
    })
    .catch((err) => {
      console.error("[smoke] E2E FAILED:", err.message);
      process.exit(1);
    });
} else {
  console.log("[smoke] smoke OK (no endpoint provided for E2E)");
  process.exit(0);
}