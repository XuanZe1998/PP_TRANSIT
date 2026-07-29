#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""GPT Checkout Link Generator - 整合脚本

通过 accessToken 或 session-token 生成 ChatGPT Plus 支付链接,
支持两种登录方式:
  1. 直接使用 accessToken (--mode token)
  2. 使用 session-token cookie (--mode session)

登录成功后自动生成 ChatGPT Plus 支付链接。
支付链接格式: https://chatgpt.com/checkout/{processor_entity}/{session_id}
在已登录 ChatGPT 的浏览器中打开即可显示 Stripe 支付页面。

用法:
  python gpt_checkout_link.py --mode token --access-token eyJ...
  python gpt_checkout_link.py --mode session --session-token eyJ...

默认使用本地 Clash 代理 http://127.0.0.1:7897
默认地区: JP/JPY
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

# ─── Windows UTF-8 修复 (Sentinel SDK Node.js 子进程) ──────────
# Windows 默认用 GBK 解码 subprocess 输出, Sentinel SDK 的 JS 含非 GBK 字符
# 会导致 UnicodeDecodeError -> NoneType rpartition 崩溃
if sys.platform == "win32":
    os.environ.setdefault("PYTHONIOENCODING", "utf-8")
    os.environ.setdefault("PYTHONUTF8", "1")
    # Monkey-patch subprocess.run to force UTF-8 encoding on text mode
    _original_subprocess_run = subprocess.run
    def _utf8_subprocess_run(*args, **kwargs):
        if kwargs.get("text", False) and "encoding" not in kwargs:
            kwargs["encoding"] = "utf-8"
            kwargs["errors"] = "replace"
        return _original_subprocess_run(*args, **kwargs)
    subprocess.run = _utf8_subprocess_run

# ─── 禁用 Sentinel httpx fallback (Windows 下 httpx SSL 兼容差) ──
os.environ.setdefault("SENTINEL_HTTPX_FALLBACK", "0")

# ─── 将 protocol 项目加入 import 路径 ───────────────────────────
PROTOCOL_SRC = Path(__file__).resolve().parent / "protocol" / "gpt_trial_protocol" / "src"
if not PROTOCOL_SRC.exists():
    print(f"[错误] 找不到协议项目 src: {PROTOCOL_SRC}", file=sys.stderr)
    sys.exit(1)
sys.path.insert(0, str(PROTOCOL_SRC))

from gpt_trial_protocol.chatgpt import ChatGPTProtocolClient
from gpt_trial_protocol.http_client import ProtocolHttpClient, json_or_empty, require_ok

# ─── Monkey-patch: curl SSL 失败时重试而非 fallback 到 httpx ──────
# Windows 下 httpx 通过代理的 SSL 总是失败, curl_cffi 偶尔因网络抖动失败
# 改为: curl 失败时重试最多 3 次, 每次间隔 2 秒, 不再 fallback 到 httpx
_OriginalProtocolHttpClient = ProtocolHttpClient

class _RetryProtocolHttpClient(_OriginalProtocolHttpClient):
    """ProtocolHttpClient 的重试封装: curl SSL 失败时重试而非 fallback 到 httpx

    Windows 下 httpx 通过代理的 SSL 总是失败, 所以:
    1) 禁用 httpx fallback (_httpx_fallback 直接抛异常)
    2) curl SSL 失败时重试最多 5 次 (无论 GET/POST)
    """

    def _httpx_fallback(self, *args: Any, **kwargs: Any) -> Any:
        """禁用 httpx fallback — Windows 下 httpx SSL 必定失败"""
        raise RuntimeError("httpx fallback 已禁用 (Windows 下 httpx SSL 不兼容)")

    def _curl_request(self, method: str, url: str, **kwargs: Any) -> Any:
        import time as _time
        from gpt_trial_protocol.http_client import _is_fast_curl_fallback_error

        max_retries = 5
        for attempt in range(1, max_retries + 1):
            try:
                return super()._curl_request(method, url, **kwargs)
            except RuntimeError as exc:
                # 如果是我们自己禁用的 httpx fallback 抛出的, 且是 SSL 错误, 重试
                if "httpx fallback 已禁用" in str(exc):
                    if attempt < max_retries:
                        wait = 2 * attempt
                        print(f"  [重试] curl SSL 错误 (第{attempt}/{max_retries}次), {wait}秒后重试...", file=sys.stderr, flush=True)
                        _time.sleep(wait)
                        continue
                    raise RuntimeError(
                        f"curl SSL 错误, 已重试{max_retries}次仍失败。请检查代理是否正常: {self.proxy}"
                    ) from exc
                raise
            except Exception as exc:
                is_ssl = _is_fast_curl_fallback_error(exc)
                if is_ssl and attempt < max_retries:
                    wait = 2 * attempt
                    print(f"  [重试] curl SSL 错误 (第{attempt}/{max_retries}次), {wait}秒后重试...", file=sys.stderr, flush=True)
                    _time.sleep(wait)
                    continue
                raise
        raise RuntimeError("unreachable")

ProtocolHttpClient = _RetryProtocolHttpClient
from gpt_trial_protocol.models import (
    BrowserProfile,
    CheckoutInput,
    ProtocolConfig,
    SessionInfo,
)
from gpt_trial_protocol.sentinel_http import SentinelHttpTokenProvider

# 注册 authorize_continue sentinel flow
from gpt_trial_protocol import sentinel_http as _sentinel_mod
_sentinel_mod.DEFAULT_FLOW_BY_PURPOSE.setdefault("authorize_continue", "authorize_continue")

# ─── 默认配置 (从 config 模块读取环境变量) ────────────────────
from config import PROXY as DEFAULT_PROXY, DEFAULT_COUNTRY, DEFAULT_CURRENCY
DEFAULT_PLAN_NAME = "chatgptplusplan"
DEFAULT_PROMO_CAMPAIGN_ID = "plus-1-month-free"

# Stripe init API 版本 (必须匹配 Stripe 前端 JS 使用的版本号)
STRIPE_INIT_VERSION = "2025-03-31.basil; checkout_server_update_beta=v1; checkout_manual_approval_preview=v1"
STRIPE_INIT_TIMEOUT = 15.0

# Codex OAuth 参数
DEFAULT_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
DEFAULT_ISSUER = "https://auth.openai.com"
DEFAULT_REDIRECT_URI = "http://localhost:1455/auth/callback"
DEFAULT_SCOPE = "openid email profile offline_access"


def log(msg: str) -> None:
    ts = datetime.now().strftime("%H:%M:%S")
    print(f"[{ts}] {msg}", flush=True)


# ═══════════════════════════════════════════════════════════════
#  通用辅助
# ═══════════════════════════════════════════════════════════════
def _json_or_empty(response: Any) -> dict[str, Any]:
    try:
        payload = response.json()
    except Exception:
        return {}
    return payload if isinstance(payload, dict) else {}


# ═══════════════════════════════════════════════════════════════
#  Stripe Init: 获取真实托管支付长链
# ═══════════════════════════════════════════════════════════════
def _stripe_init(
    *,
    checkout_session_id: str,
    publishable_key: str,
    proxy: str | None = None,
) -> dict[str, Any]:
    """调用 Stripe init API 获取真实的 stripe_hosted_url

    ChatGPT checkout API 返回的 client_secret 不能直接用来构造长链,
    必须调用 Stripe 的 init 接口才能拿到包含加密 fragment 的完整 URL。

    参考: https://api.stripe.com/v1/payment_pages/{cs_id}/init

    返回: Stripe init 响应 JSON (包含 stripe_hosted_url 字段)
    """
    import curl_cffi.requests as _curl

    body = {
        "key": publishable_key,
        "_stripe_version": STRIPE_INIT_VERSION,
        "elements_session_client[client_betas][0]": "custom_checkout_server_updates_1",
        "elements_session_client[client_betas][1]": "custom_checkout_manual_approval_1",
        "elements_session_client[elements_init_source]": "custom_checkout",
        "elements_session_client[referrer_host]": "chatgpt.com",
        "elements_session_client[stripe_js_id]": str(uuid.uuid4()),
        "elements_session_client[locale]": "en",
        "elements_session_client[is_aggregation_expected]": "false",
        "elements_options_client[saved_payment_method][enable_save]": "never",
        "elements_options_client[saved_payment_method][enable_redisplay]": "never",
        "browser_locale": "en-US",
        "browser_timezone": "Asia/Shanghai",
    }
    headers = {
        "Origin": "https://pay.openai.com",
        "Referer": "https://pay.openai.com/",
        "Accept": "application/json",
        "Content-Type": "application/x-www-form-urlencoded",
    }
    url = f"https://api.stripe.com/v1/payment_pages/{checkout_session_id}/init"

    max_retries = 3
    for attempt in range(1, max_retries + 1):
        try:
            with _curl.Session() as s:
                resp = s.post(
                    url,
                    data=body,
                    headers=headers,
                    timeout=STRIPE_INIT_TIMEOUT,
                    proxies={"https": proxy, "http": proxy} if proxy else None,
                    impersonate="chrome120",
                )
            try:
                data = resp.json()
            except Exception:
                data = {"raw": resp.text[:500]}

            if resp.status_code >= 400:
                log(f"  Stripe init 失败: HTTP {resp.status_code}")
                if attempt < max_retries:
                    time.sleep(2)
                    continue
                return data

            return data

        except Exception as exc:
            err = str(exc).lower()
            is_ssl = "ssl" in err or "handshake" in err or "eof" in err
            if is_ssl and attempt < max_retries:
                wait = 2 * attempt
                log(f"  Stripe init SSL 错误 (第{attempt}/{max_retries}次), {wait}秒后重试...")
                time.sleep(wait)
                continue
            raise

    return {"error": "max_retries_exceeded"}


def _open_hosted_checkout(checkout_url: str) -> None:
    """用系统浏览器打开 ChatGPT hosted checkout URL (脚本不阻塞)

    URL 格式: https://chatgpt.com/checkout/{processor_entity}/{session_id}
    需要在已登录 ChatGPT 的浏览器中打开, 否则会跳转到登录页。
    """
    import subprocess

    opened = False
    for cmd in [["msedge"], ["chrome"]]:
        try:
            subprocess.Popen(cmd + [checkout_url], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            log(f"  已用 {cmd[0]} 打开支付页面")
            opened = True
            break
        except FileNotFoundError:
            continue

    if not opened:
        import webbrowser
        webbrowser.open(checkout_url)
        log(f"  已在默认浏览器打开支付链接")

    log("  请在浏览器中完成支付")
    log("  注意: 浏览器需要已登录 ChatGPT 并开启代理/VPN")


# ═══════════════════════════════════════════════════════════════
#  核心: 生成支付链接
# ═══════════════════════════════════════════════════════════════
def generate_checkout_link(
    *,
    access_token: str,
    chatgpt: ChatGPTProtocolClient,
    country: str = DEFAULT_COUNTRY,
    currency: str = DEFAULT_CURRENCY,
    plan_name: str = DEFAULT_PLAN_NAME,
    promo_campaign_id: str = DEFAULT_PROMO_CAMPAIGN_ID,
    sentinel_provider: SentinelHttpTokenProvider | None = None,
    proxy: str | None = None,
) -> dict[str, Any]:
    """使用 accessToken 生成 ChatGPT Plus 支付链接

    流程:
      1) 调用 ChatGPT checkout API 获取 checkout_session_id + publishable_key
      2) 调用 Stripe init API 获取真实的 stripe_hosted_url (长链)
      3) 将 checkout.stripe.com 替换为 pay.openai.com
      4) 同时提供 chatgpt.com 短链作为备选

    返回 dict:
      mode: "hosted" | "already_paid" | "unknown"
      url:  优先返回长链 (Stripe 托管), 无长链时返回短链
      short_url: ChatGPT 短链 (需登录浏览器打开)
      long_url: Stripe 托管长链 (pay.openai.com 格式, 不依赖登录态)
      session_id: Stripe checkout session ID
      processor_entity: 处理方标识 (如 "openai_llc")
      raw: 原始 API 响应

    注意: checkout API 需要 openai-sentinel-token 头, 否则可能被拒绝
    """
    # 获取 sentinel token (如果有的话), SSL 错误自动重试
    sentinel_headers: dict[str, str] = {}
    if sentinel_provider is not None:
        max_sentinel_retries = 5
        for attempt in range(1, max_sentinel_retries + 1):
            try:
                bundle = sentinel_provider.get_openai_sentinel(purpose="authorize_continue")
                sentinel_headers = bundle.sentinel.as_headers()
                log("  checkout 请求附带 Sentinel Token")
                break
            except Exception as exc:
                err_str = str(exc).lower()
                is_ssl = "ssl" in err_str or "handshake" in err_str or "eof" in err_str or "connection" in err_str
                if is_ssl and attempt < max_sentinel_retries:
                    wait = 2 * attempt
                    log(f"  Sentinel Token SSL 错误 (第{attempt}/{max_sentinel_retries}次), {wait}秒后重试...")
                    time.sleep(wait)
                    continue
                log(f"  Sentinel Token 获取失败 (尝试{attempt}次): {exc}")
                if not is_ssl:
                    break  # 非 SSL 错误不重试

    checkout = CheckoutInput(
        country=country,
        currency=currency,
        plan_name=plan_name,
        promo_campaign_id=promo_campaign_id,
    )
    # 直接用原始请求, 带 sentinel token
    # (chatgpt.generate_checkout_link() 不支持 sentinel headers, 所以不走它)
    raw_payload = {
        "plan_name": checkout.plan_name,
        "billing_details": {"country": checkout.country, "currency": checkout.currency},
        "cancel_url": "https://chatgpt.com/#pricing",
        "promo_campaign": {
            "promo_campaign_id": checkout.promo_campaign_id,
            "is_coupon_from_query_param": False,
        },
        "checkout_ui_mode": "hosted",
    }
    raw_resp = chatgpt.http.post(
        chatgpt.chatgpt_url("/backend-api/payments/checkout"),
        headers=chatgpt.config.profile.api_headers(access_token)
        | {"content-type": "application/json"}
        | sentinel_headers,
        json=raw_payload,
    )
    raw_data = _json_or_empty(raw_resp)
    log(f"  checkout 响应 HTTP {raw_resp.status_code}: {json.dumps(raw_data, ensure_ascii=False)[:500]}")

    # 1. 尝试提取直接的 hosted URL
    url = raw_data.get("url") or raw_data.get("stripe_hosted_url") or raw_data.get("checkout_url")
    if url:
        log(f"  找到 hosted checkout URL")
        return {"mode": "hosted", "url": str(url), "raw": raw_data}

    # 2. 检查是否已经订阅
    if raw_resp.status_code == 400:
        detail = raw_data.get("detail", "")
        if "already paid" in detail.lower() or "already subscribed" in detail.lower():
            return {"mode": "already_paid", "url": "您已经是 ChatGPT Plus 订阅用户", "raw": raw_data}

    # 3. Custom/Embedded Checkout 模式 -> 获取真实托管长链
    session_id = raw_data.get("checkout_session_id")
    processor_entity = raw_data.get("processor_entity", "openai_llc")
    publishable_key = raw_data.get("publishable_key", "")
    client_secret = raw_data.get("client_secret", "")

    if session_id:
        checkout_ui_mode = raw_data.get("checkout_ui_mode", "unknown")
        log(f"  checkout 模式: {checkout_ui_mode}")
        log(f"  session_id: {session_id[:30]}...")
        log(f"  processor_entity: {processor_entity}")

        # ChatGPT 短链 (需登录 ChatGPT 浏览器打开)
        short_url = f"https://chatgpt.com/checkout/{processor_entity}/{session_id}"

        # ─── 调用 Stripe init API 获取真实托管长链 ───
        long_url = ""
        if publishable_key:
            log(f"  正在调用 Stripe init API 获取托管长链...")
            try:
                init_data = _stripe_init(
                    checkout_session_id=session_id,
                    publishable_key=publishable_key,
                    proxy=proxy,
                )
                # 从 init 响应提取 stripe_hosted_url
                stripe_url = str(
                    init_data.get("url")
                    or init_data.get("stripe_hosted_url")
                    or ""
                )
                if stripe_url and "checkout.stripe.com" in stripe_url:
                    # 将 checkout.stripe.com 替换为 pay.openai.com
                    long_url = stripe_url.replace("checkout.stripe.com", "pay.openai.com", 1)
                    log(f"  Stripe 托管长链: {long_url[:80]}...")
                elif stripe_url:
                    long_url = stripe_url
                    log(f"  Stripe 返回 URL (非标准域名): {long_url[:80]}...")
                else:
                    log(f"  Stripe init 未返回 stripe_hosted_url, 尝试从响应提取...")
                    # 尝试从其他字段提取
                    for _key in ("hosted_url", "redirect_url", "next_action"):
                        _val = init_data.get(_key, "")
                        if _val and isinstance(_val, str) and "http" in _val:
                            long_url = _val.replace("checkout.stripe.com", "pay.openai.com", 1)
                            log(f"  从 {_key} 提取到 URL: {long_url[:80]}...")
                            break
            except Exception as exc:
                log(f"  Stripe init 调用失败: {exc}")
                log(f"  将仅返回短链, 需登录后打开")
        else:
            log(f"  无 publishable_key, 无法调用 Stripe init, 仅返回短链")

        log(f"  ChatGPT 短链: {short_url}")

        return {
            "mode": "hosted",
            "url": long_url or short_url,  # 优先返回长链
            "short_url": short_url,
            "long_url": long_url,
            "session_id": session_id,
            "processor_entity": processor_entity,
            "publishable_key": publishable_key,
            "client_secret": client_secret,
            "raw": raw_data,
        }

    # 4. 检查错误
    if raw_resp.status_code >= 400:
        error_msg = raw_data.get("detail", "") or raw_data.get("error", {}).get("message", "")
        if raw_resp.status_code == 403:
            if not sentinel_headers:
                raise RuntimeError(
                    f"checkout API 返回 403 (Forbidden). "
                    f"这通常是因为缺少 openai-sentinel-token 头。"
                    f"请确保 sentinel_provider 参数已传入。"
                )
            else:
                raise RuntimeError(
                    f"checkout API 返回 403 (Forbidden). "
                    f"Sentinel token 已附带但仍被拒绝, 可能 token 已过期或账号无权限。"
                )
        raise RuntimeError(f"checkout API 失败: HTTP {raw_resp.status_code}: {error_msg[:300]}")

    # 5. 兜底: 返回原始 JSON
    return {"mode": "unknown", "raw": raw_data}


# ═══════════════════════════════════════════════════════════════
#  CLI 入口
# ═══════════════════════════════════════════════════════════════
def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="GPT 支付链接生成器 - 支持 token/session 模式",
    )
    parser.add_argument(
        "--mode",
        choices=["token", "session"],
        required=True,
        help="登录模式: token=直接用accessToken, session=用session-token换accessToken",
    )
    parser.add_argument("--access-token", help="已有的 accessToken (mode=token 时使用)")
    parser.add_argument("--session-token", help="浏览器 session-token cookie (mode=session 时使用)")
    parser.add_argument(
        "--proxy",
        default=DEFAULT_PROXY,
        help=f"HTTP 代理地址 (默认: {DEFAULT_PROXY})",
    )
    parser.add_argument("--no-proxy", action="store_true", help="不使用代理")
    parser.add_argument(
        "--country",
        default=DEFAULT_COUNTRY,
        help=f"支付国家代码 (默认: {DEFAULT_COUNTRY})",
    )
    parser.add_argument(
        "--currency",
        default=DEFAULT_CURRENCY,
        help=f"支付货币代码 (默认: {DEFAULT_CURRENCY})",
    )
    parser.add_argument("--plan-name", default=DEFAULT_PLAN_NAME, help="订阅计划名称")
    parser.add_argument("--promo-campaign-id", default=DEFAULT_PROMO_CAMPAIGN_ID, help="促销活动 ID")
    parser.add_argument("--timeout", type=float, default=30.0, help="HTTP 超时秒数")
    parser.add_argument("--code-timeout", type=float, default=90.0, help="验证码等待超时秒数")
    parser.add_argument("--backend", choices=["curl_cffi", "httpx"], default="curl_cffi", help="HTTP 后端")
    parser.add_argument("--impersonate", default="chrome120", help="curl_cffi TLS 指纹 (默认: chrome120)")
    parser.add_argument("--no-open", action="store_true", help="不自动在浏览器中打开支付链接")
    return parser.parse_args()


def main() -> int:
    args = parse_args()

    if args.mode == "token" and not args.access_token:
        print("[错误] token 模式需要 --access-token 参数", file=sys.stderr)
        return 1
    if args.mode == "session" and not args.session_token:
        print("[错误] session 模式需要 --session-token 参数", file=sys.stderr)
        return 1

    proxy = None if args.no_proxy else args.proxy

    config = ProtocolConfig(timeout=args.timeout, profile=BrowserProfile())

    # ── mode=token ──
    if args.mode == "token":
        log("使用已有 accessToken 生成支付链接...")
        with ProtocolHttpClient(timeout=args.timeout, proxy=proxy, backend=args.backend, impersonate=args.impersonate) as http:
            chatgpt = ChatGPTProtocolClient(config, http)
            # checkout API 需要 sentinel token, 必须初始化
            log("初始化 Sentinel Token 提供器...")
            sentinel_provider = SentinelHttpTokenProvider(config=config, proxy=proxy)
            checkout_result = generate_checkout_link(
                access_token=args.access_token, chatgpt=chatgpt,
                country=args.country, currency=args.currency,
                sentinel_provider=sentinel_provider,
                proxy=proxy,
            )
        _handle_checkout_result(email="", result=checkout_result, no_open=args.no_open)
        return 0

    # ── mode=session ──
    if args.mode == "session":
        log("使用 session-token 获取 accessToken ...")
        with ProtocolHttpClient(timeout=args.timeout, proxy=proxy, backend=args.backend, impersonate=args.impersonate) as http:
            # 用 session-token cookie 请求 /api/auth/session 拿 accessToken
            session_cookie = args.session_token
            headers = config.profile.browser_headers(referer="https://chatgpt.com/")
            headers["cookie"] = f"__Secure-next-auth.session-token={session_cookie}"
            resp = http.get("https://chatgpt.com/api/auth/session", headers=headers)
            if resp.status_code != 200:
                raise RuntimeError(f"获取 session 失败: HTTP {resp.status_code}")
            data = _json_or_empty(resp)
            access_token = data.get("accessToken", "")
            user = data.get("user", {})
            if not access_token:
                raise RuntimeError(f"session 中无 accessToken, 可能 session-token 已过期")
            email = user.get("email", "")
            log(f"  获取成功! 用户: {user.get('name', '')} ({email})")
            log(f"  Token 长度: {len(access_token)}")

            # checkout API 需要 sentinel token
            log("初始化 Sentinel Token 提供器...")
            sentinel_provider = SentinelHttpTokenProvider(config=config, proxy=proxy)

            # 生成支付信息
            chatgpt = ChatGPTProtocolClient(config, http)
            log("生成支付信息...")
            checkout_result = generate_checkout_link(
                access_token=access_token, chatgpt=chatgpt,
                country=args.country, currency=args.currency,
                sentinel_provider=sentinel_provider,
                proxy=proxy,
            )
        _handle_checkout_result(email=email, result=checkout_result, no_open=args.no_open)
        return 0


def _handle_checkout_result(*, email: str, result: dict[str, Any], no_open: bool = False) -> None:
    """处理 checkout API 返回结果, 打印信息并打开浏览器"""
    mode = result.get("mode", "unknown")
    log("=" * 60)

    if mode == "already_paid":
        log("您已经是 ChatGPT Plus 订阅用户!")
        if email:
            log(f"账号: {email}")
        log("=" * 60)
        output = {"ok": True, "email": email, "status": "already_paid",
                  "generatedAt": datetime.now(timezone.utc).isoformat()}
        print(json.dumps(output, ensure_ascii=False, indent=2))
        return

    if mode == "hosted":
        url = result["url"]
        short_url = result.get("short_url", "")
        long_url = result.get("long_url", "")
        log("支付链接生成成功!")
        if email:
            log(f"账号: {email}")
        if long_url:
            log(f"长链 (Stripe托管): {long_url}")
        if short_url:
            log(f"短链 (ChatGPT路由): {short_url}")
        processor_entity = result.get("processor_entity", "")
        session_id = result.get("session_id", "")
        if processor_entity:
            log(f"processor_entity: {processor_entity}")
        if session_id:
            log(f"session_id: {session_id[:30]}...")
        log("  正在打开浏览器...")
        log("=" * 60)
        if not no_open:
            _open_hosted_checkout(url)
        else:
            log("  (已禁用自动打开浏览器)")
        output = {"ok": True, "email": email, "mode": "hosted",
                  "checkoutUrl": url, "longUrl": long_url, "shortUrl": short_url,
                  "processorEntity": processor_entity, "sessionId": session_id,
                  "generatedAt": datetime.now(timezone.utc).isoformat()}
        print(json.dumps(output, ensure_ascii=False, indent=2))
        return

    # unknown mode
    raw = result.get("raw", {})
    log("Checkout 响应 (未知模式):")
    if email:
        log(f"账号: {email}")
    log(json.dumps(raw, ensure_ascii=False)[:500])
    log("=" * 60)
    output = {"ok": False, "email": email, "mode": mode, "raw": raw,
              "generatedAt": datetime.now(timezone.utc).isoformat()}
    print(json.dumps(output, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    raise SystemExit(main())
