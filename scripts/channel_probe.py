#!/usr/bin/env python3
import argparse
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

OPENAI_COMPATIBLE = {
    "", "openai", "openai-compatible", "deepseek", "xai", "openrouter",
    "qwen", "kimi", "glm", "mistral", "meta", "nvidia", "ollama", "azure-openai"
}


def main():
    parser = argparse.ArgumentParser(description="Probe an upstream model endpoint.")
    parser.add_argument("--provider", default="")
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--api-key", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--prompt", default="Reply with OK.")
    parser.add_argument("--timeout", type=int, default=20)
    args = parser.parse_args()

    started = time.time()
    provider = (args.provider or "").lower()
    try:
        if provider == "anthropic":
            payload, headers, url = build_anthropic(args)
        elif provider in {"gemini", "google", "google-gemini"}:
            payload, headers, url = build_gemini(args)
        else:
            payload, headers, url = build_openai_compatible(args)

        if provider == "nvidia":
            status_code, parsed = post_streaming_json(url, headers, payload, args.timeout)
            usage = parsed.get("usage") or zero_usage()
            text = parsed.get("sampleText") or ""
        else:
            status_code, body = post_json(url, headers, payload, args.timeout)
            parsed = json.loads(body)
            usage = extract_usage(provider, parsed)
            text = extract_sample_text(provider, parsed)
        latency_ms = int((time.time() - started) * 1000)
        status = "SUCCESS" if 200 <= status_code < 300 else "FAILED"
        emit({
            "status": status,
            "latencyMs": latency_ms,
            "model": args.model,
            "usage": usage,
            "sampleText": text[:500],
            "error": None,
            "exitCode": 0,
        })
        return 0
    except urllib.error.HTTPError as exc:
        latency_ms = int((time.time() - started) * 1000)
        error_body = safe_read(exc)
        status = "AUTH_FAILED" if exc.code in (401, 403) else "FAILED"
        emit({
            "status": status,
            "latencyMs": latency_ms,
            "model": args.model,
            "usage": zero_usage(),
            "sampleText": "",
            "error": f"HTTP {exc.code}: {error_body[:1000]}",
            "exitCode": exc.code,
        })
        return 2
    except TimeoutError as exc:
        latency_ms = int((time.time() - started) * 1000)
        emit_failure("TIMEOUT", latency_ms, args.model, str(exc), 124)
        return 124
    except Exception as exc:
        latency_ms = int((time.time() - started) * 1000)
        emit_failure("FAILED", latency_ms, args.model, str(exc), 1)
        return 1


def build_openai_compatible(args):
    base = normalize_base(args.base_url)
    url = chat_completions_url(base, (args.provider or "").lower())
    payload = {
        "model": args.model,
        "messages": openai_messages(args.prompt),
        "stream": False,
        "max_tokens": 64,
    }
    apply_model_specific_options(payload, (args.provider or "").lower(), args.model)
    headers = {"Authorization": "Bearer " + args.api_key}
    return payload, headers, url


def build_anthropic(args):
    base = normalize_base(args.base_url)
    url = base if base.endswith("/messages") else base + "/v1/messages"
    payload = {
        "model": args.model,
        "max_tokens": 64,
        "messages": [{"role": "user", "content": args.prompt}],
    }
    headers = {
        "x-api-key": args.api_key,
        "anthropic-version": "2023-06-01",
    }
    return payload, headers, url


def build_gemini(args):
    base = normalize_base(args.base_url)
    encoded_model = urllib.parse.quote(args.model, safe="")
    if ":generateContent" in base:
        url = base
    else:
        url = f"{base}/v1beta/models/{encoded_model}:generateContent"
    separator = "&" if "?" in url else "?"
    url = f"{url}{separator}key={urllib.parse.quote(args.api_key)}"
    payload = {"contents": [{"parts": [{"text": args.prompt}]}]}
    return payload, {}, url


def post_json(url, headers, payload, timeout):
    data = json.dumps(payload).encode("utf-8")
    request_headers = {"Content-Type": "application/json", **headers}
    req = urllib.request.Request(url, data=data, headers=request_headers, method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.status, resp.read().decode("utf-8", errors="replace")


def post_streaming_json(url, headers, payload, timeout):
    payload = dict(payload)
    payload["stream"] = True
    payload.pop("stream_options", None)
    data = json.dumps(payload).encode("utf-8")
    request_headers = {
        "Content-Type": "application/json",
        "Accept": "text/event-stream",
        **headers,
    }
    req = urllib.request.Request(url, data=data, headers=request_headers, method="POST")
    text_parts = []
    usage = zero_usage()
    started = time.time()
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        status_code = resp.status
        for raw_line in resp:
            if time.time() - started > timeout:
                break
            line = raw_line.decode("utf-8", errors="replace").strip()
            if not line or line.startswith(":"):
                continue
            if line.startswith("data:"):
                line = line[5:].strip()
            if line == "[DONE]":
                break
            try:
                chunk = json.loads(line)
            except json.JSONDecodeError:
                continue
            if chunk.get("usage"):
                usage = usage_from_openai_chunk(chunk.get("usage") or {})
            choices = chunk.get("choices") or []
            if choices:
                delta = (choices[0] or {}).get("delta") or {}
                content = delta.get("content") or delta.get("reasoning_content")
                if content:
                    text_parts.append(str(content))
                    if len("".join(text_parts)) >= 300:
                        break
    return status_code, {"sampleText": "".join(text_parts), "usage": usage}


def usage_from_openai_chunk(usage):
    details = usage.get("prompt_tokens_details") or {}
    return {
        "promptTokens": int(usage.get("prompt_tokens") or 0),
        "completionTokens": int(usage.get("completion_tokens") or 0),
        "cachedTokens": int(details.get("cached_tokens") or 0),
    }


def extract_usage(provider, parsed):
    if provider == "anthropic":
        usage = parsed.get("usage") or {}
        return {
            "promptTokens": int(usage.get("input_tokens") or 0),
            "completionTokens": int(usage.get("output_tokens") or 0),
            "cachedTokens": int(usage.get("cache_read_input_tokens") or 0)
            + int(usage.get("cache_creation_input_tokens") or 0),
        }
    if provider in {"gemini", "google", "google-gemini"}:
        usage = parsed.get("usageMetadata") or {}
        return {
            "promptTokens": int(usage.get("promptTokenCount") or 0),
            "completionTokens": int(usage.get("candidatesTokenCount") or 0),
            "cachedTokens": int(usage.get("cachedContentTokenCount") or 0),
        }
    usage = parsed.get("usage") or {}
    details = usage.get("prompt_tokens_details") or {}
    return {
        "promptTokens": int(usage.get("prompt_tokens") or 0),
        "completionTokens": int(usage.get("completion_tokens") or 0),
        "cachedTokens": int(details.get("cached_tokens") or 0),
    }


def extract_sample_text(provider, parsed):
    if provider == "anthropic":
        chunks = parsed.get("content") or []
        return "\n".join(item.get("text", "") for item in chunks if isinstance(item, dict)).strip()
    if provider in {"gemini", "google", "google-gemini"}:
        candidates = parsed.get("candidates") or []
        parts = (((candidates[0] or {}).get("content") or {}).get("parts") or []) if candidates else []
        return "\n".join(item.get("text", "") for item in parts if isinstance(item, dict)).strip()
    choices = parsed.get("choices") or []
    if not choices:
        return ""
    message = (choices[0] or {}).get("message") or {}
    content = message.get("content")
    if isinstance(content, list):
        return "\n".join(str(item.get("text", "")) for item in content if isinstance(item, dict)).strip()
    return str(content or "")


def normalize_base(value):
    return (value or "").rstrip("/")


def chat_completions_url(base, provider):
    if base.endswith("/chat/completions"):
        return base
    if provider == "deepseek" or "api.deepseek.com" in base:
        return base + "/chat/completions"
    if base.endswith("/v1"):
        return base + "/chat/completions"
    return base + "/v1/chat/completions"


def openai_messages(prompt):
    content = prompt if prompt and prompt.strip() else "Hello"
    return [
        {"role": "system", "content": "You are a helpful assistant"},
        {"role": "user", "content": content},
    ]


def apply_model_specific_options(payload, provider, model):
    normalized_model = (model or "").lower()
    if provider == "deepseek" or normalized_model.startswith("deepseek-"):
        payload["reasoning_effort"] = "high"
        payload["thinking"] = {"type": "enabled"}
    if provider == "nvidia" or normalized_model.startswith(("z-ai/", "google/gemma-")):
        payload["temperature"] = 1
        payload["top_p"] = 0.95 if normalized_model.startswith("google/gemma-") else 1
        payload["max_tokens"] = 16384 if normalized_model.startswith("z-ai/glm-") else 512
    if normalized_model.startswith("google/gemma-"):
        payload["chat_template_kwargs"] = {"enable_thinking": True}
    if normalized_model.startswith("z-ai/glm-"):
        payload["seed"] = 42


def zero_usage():
    return {"promptTokens": 0, "completionTokens": 0, "cachedTokens": 0}


def safe_read(exc):
    try:
        return exc.read().decode("utf-8", errors="replace")
    except Exception:
        return str(exc)


def emit_failure(status, latency_ms, model, error, exit_code):
    emit({
        "status": status,
        "latencyMs": latency_ms,
        "model": model,
        "usage": zero_usage(),
        "sampleText": "",
        "error": error,
        "exitCode": exit_code,
    })


def emit(payload):
    print(json.dumps(payload, ensure_ascii=False))


if __name__ == "__main__":
    sys.exit(main())
