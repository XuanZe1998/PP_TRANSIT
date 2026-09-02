#!/usr/bin/env node
/**
 * Model Probe Sidecar
 * -------------------
 * A zero-dependency Node.js HTTP sidecar that wraps the BazaarLink
 * LLMprobe-engine so the Java (Spring Boot) backend can run model
 * identity / quality / security probes on any OpenAI-compatible
 * endpoint.
 *
 * The sidecar runs as a separate process (deployed alongside the Java
 * app) and is reached only over the loopback interface. It keeps the
 * AGPL-licensed LLMprobe-engine isolated from the main application
 * process.
 *
 * Endpoints:
 *   GET  /health            -> { status, version, engine }
 *   POST /probe            -> runs runProbes() synchronously and
 *                             returns the full JSON report.
 *                             Body: { baseUrl, apiKey, modelId,
 *                               claimedModel?, includeOptional?,
 *                               timeoutMs?, judge? }
 *                             Judge is optional:
 *                               judge: { baseUrl, apiKey, modelId, threshold? }
 *
 * Config via env:
 *   MODEL_PROBE_PORT        (default 9891)
 *   MODEL_PROBE_MAX_BODY    bytes for request body (default 1 MiB)
 *   MODEL_PROBE_TIMEOUT_MS  hard cap for one probe run (default 15 min)
 *
 * Run:  node src/server.js
 */

"use strict";

const http = require("http");
const path = require("path");

// ── Load the vendored probe engine ──────────────────────────────────────────
let engine;
try {
  engine = require(path.join(__dirname, "..", "vendor", "llmprobe-engine", "dist", "index.js"));
} catch (err) {
  console.error("[model-probe] Failed to load vendored LLMprobe-engine:", err.message);
  process.exit(1);
}

const runProbes = engine.runProbes;
const PROBE_SUITE = engine.PROBE_SUITE || [];

const PORT = Number(process.env.MODEL_PROBE_PORT || 9891);
const HOST = process.env.MODEL_PROBE_HOST || "127.0.0.1";
const MAX_BODY = Number(process.env.MODEL_PROBE_MAX_BODY || 1024 * 1024);
const HARD_TIMEOUT_MS = Number(process.env.MODEL_PROBE_TIMEOUT_MS || 15 * 60 * 1000);

function json(res, status, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(body),
  });
  res.end(body);
}

function readBody(req, onDone, onError) {
  const chunks = [];
  let size = 0;
  req.on("data", (chunk) => {
    size += chunk.length;
    if (size > MAX_BODY) {
      onError(new Error("Request body too large"));
      req.destroy();
      return;
    }
    chunks.push(chunk);
  });
  req.on("end", () => {
    const raw = Buffer.concat(chunks).toString("utf-8");
    try {
      onDone(raw ? JSON.parse(raw) : {});
    } catch (err) {
      onError(new Error("Invalid JSON body: " + err.message));
    }
  });
  req.on("error", onError);
}

function requireField(obj, name) {
  const v = obj[name];
  if (typeof v !== "string" || !v.trim()) {
    throw new Error(`Missing required field: ${name}`);
  }
  return v.trim();
}

async function handleProbe(body) {
  const baseUrl = requireField(body, "baseUrl");
  const apiKey = requireField(body, "apiKey");
  const modelId = requireField(body, "modelId");

  const options = {
    baseUrl,
    apiKey,
    modelId,
    includeOptional: Boolean(body.includeOptional),
    timeoutMs: Math.min(
      Number(body.timeoutMs) || 180_000,
      HARD_TIMEOUT_MS
    ),
  };
  if (body.claimedModel) options.claimedModel = String(body.claimedModel);
  if (body.judge && body.judge.baseUrl && body.judge.apiKey && body.judge.modelId) {
    options.judge = {
      baseUrl: String(body.judge.baseUrl),
      apiKey: String(body.judge.apiKey),
      modelId: String(body.judge.modelId),
    };
    if (body.judge.threshold) options.judge.threshold = Number(body.judge.threshold);
  }

  const report = await runProbes(options);
  return { ok: true, report };
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || "localhost"}`);

  if (req.method === "GET" && url.pathname === "/health") {
    json(res, 200, {
      status: "ok",
      ok: true,
      engine: { probes: PROBE_SUITE.length },
      version: "1.0.0",
    });
    return;
  }

  if (req.method === "POST" && url.pathname === "/probe") {
    readBody(req, (body) => {
      handleProbe(body)
        .then((result) => json(res, 200, result))
        .catch((err) => json(res, 400, { ok: false, error: err.message }));
    }, (err) => json(res, 400, { ok: false, error: err.message }));
    return;
  }

  json(res, 404, { ok: false, error: "Not found" });
});

server.listen(PORT, HOST, () => {
  console.log(`[model-probe] sidecar listening on http://${HOST}:${PORT}`);
  console.log(`[model-probe] engine probes loaded: ${PROBE_SUITE.length}`);
});

server.on("error", (err) => {
  console.error("[model-probe] server error:", err.message);
  process.exit(1);
});

process.on("SIGINT", () => server.close(() => process.exit(0)));
process.on("SIGTERM", () => server.close(() => process.exit(0)));