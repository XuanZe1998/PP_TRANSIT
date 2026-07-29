#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""GPT 支付引导界面 - Web UI

启动:
  python payment_web.py
  浏览器打开 http://localhost:5000

流程:
  1. 引导用户打开 session 页面, 粘贴 token
  2. 点击"订阅" → 后端自动验证 → 生成支付链接 → 无头填表 → 订阅
  3. 实时进度 + 结果展示
"""

from __future__ import annotations

import json
import hmac
import os
import queue
import subprocess
import sys
import threading
import time
import uuid
from datetime import datetime
from pathlib import Path
from typing import Any

# ─── Windows UTF-8 ────────────────────────────────────────────
if sys.platform == "win32":
    os.environ.setdefault("PYTHONIOENCODING", "utf-8")
    os.environ.setdefault("PYTHONUTF8", "1")

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

from config import PROXY, DEFAULT_COUNTRY, DB_CONFIG, HOST, PORT, DEBUG
from auto_fill_payment import (
    generate_checkout_link_via_subprocess,
    run_auto_fill_playwright,
    pick_available_card,
    update_card_status,
    get_db_connection,
)

# ─── 支付地区映射 (country_code → currency_code, label) ─────
# 列出 ChatGPT 官方支持的主流支付地区, 默认美国
SUPPORTED_REGIONS: dict[str, dict[str, str]] = {
    "US": {"currency": "USD", "label": "美国 United States"},
    "JP": {"currency": "JPY", "label": "日本 Japan"},
    "HK": {"currency": "HKD", "label": "香港 Hong Kong"},
    "TW": {"currency": "TWD", "label": "台湾 Taiwan"},
    "SG": {"currency": "SGD", "label": "新加坡 Singapore"},
    "KR": {"currency": "KRW", "label": "韩国 Korea"},
    "IN": {"currency": "INR", "label": "印度 India"},
    "AU": {"currency": "AUD", "label": "澳大利亚 Australia"},
    "NZ": {"currency": "NZD", "label": "新西兰 New Zealand"},
    "GB": {"currency": "GBP", "label": "英国 United Kingdom"},
    "DE": {"currency": "EUR", "label": "德国 Germany"},
    "FR": {"currency": "EUR", "label": "法国 France"},
    "IT": {"currency": "EUR", "label": "意大利 Italy"},
    "ES": {"currency": "EUR", "label": "西班牙 Spain"},
    "NL": {"currency": "EUR", "label": "荷兰 Netherlands"},
    "BE": {"currency": "EUR", "label": "比利时 Belgium"},
    "AT": {"currency": "EUR", "label": "奥地利 Austria"},
    "IE": {"currency": "EUR", "label": "爱尔兰 Ireland"},
    "PT": {"currency": "EUR", "label": "葡萄牙 Portugal"},
    "FI": {"currency": "EUR", "label": "芬兰 Finland"},
    "GR": {"currency": "EUR", "label": "希腊 Greece"},
    "LU": {"currency": "EUR", "label": "卢森堡 Luxembourg"},
    "CA": {"currency": "CAD", "label": "加拿大 Canada"},
    "CH": {"currency": "CHF", "label": "瑞士 Switzerland"},
    "SE": {"currency": "SEK", "label": "瑞典 Sweden"},
    "NO": {"currency": "NOK", "label": "挪威 Norway"},
    "DK": {"currency": "DKK", "label": "丹麦 Denmark"},
    "PL": {"currency": "PLN", "label": "波兰 Poland"},
    "CZ": {"currency": "CZK", "label": "捷克 Czechia"},
    "HU": {"currency": "HUF", "label": "匈牙利 Hungary"},
    "RO": {"currency": "RON", "label": "罗马尼亚 Romania"},
    "BG": {"currency": "BGN", "label": "保加利亚 Bulgaria"},
    "HR": {"currency": "EUR", "label": "克罗地亚 Croatia"},
    "SK": {"currency": "EUR", "label": "斯洛伐克 Slovakia"},
    "SI": {"currency": "EUR", "label": "斯洛文尼亚 Slovenia"},
    "LT": {"currency": "EUR", "label": "立陶宛 Lithuania"},
    "LV": {"currency": "EUR", "label": "拉脱维亚 Latvia"},
    "EE": {"currency": "EUR", "label": "爱沙尼亚 Estonia"},
    "MX": {"currency": "MXN", "label": "墨西哥 Mexico"},
    "BR": {"currency": "BRL", "label": "巴西 Brazil"},
    "AR": {"currency": "ARS", "label": "阿根廷 Argentina"},
    "CL": {"currency": "CLP", "label": "智利 Chile"},
    "CO": {"currency": "COP", "label": "哥伦比亚 Colombia"},
    "PE": {"currency": "PEN", "label": "秘鲁 Peru"},
    "ZA": {"currency": "ZAR", "label": "南非 South Africa"},
    "AE": {"currency": "AED", "label": "阿联酋 UAE"},
    "SA": {"currency": "SAR", "label": "沙特阿拉伯 Saudi Arabia"},
    "IL": {"currency": "ILS", "label": "以色列 Israel"},
    "TR": {"currency": "TRY", "label": "土耳其 Turkey"},
    "TH": {"currency": "THB", "label": "泰国 Thailand"},
    "MY": {"currency": "MYR", "label": "马来西亚 Malaysia"},
    "PH": {"currency": "PHP", "label": "菲律宾 Philippines"},
    "ID": {"currency": "IDR", "label": "印度尼西亚 Indonesia"},
    "VN": {"currency": "VND", "label": "越南 Vietnam"},
}
# DEFAULT_COUNTRY 已从 config 模块导入

# ═══════════════════════════════════════════════════════════════
#  全局状态: SSE 事件队列
# ═══════════════════════════════════════════════════════════════
task_queues: dict[str, queue.Queue] = {}
INTERNAL_API_TOKEN = os.getenv("PAYMENT_SERVICE_INTERNAL_TOKEN", "").strip()


def push_event(task_id: str, event: str, data: dict):
    if task_id in task_queues:
        task_queues[task_id].put({"event": event, "data": data})


# ═══════════════════════════════════════════════════════════════
#  Token 解析
# ═══════════════════════════════════════════════════════════════
def extract_token(raw_input: str) -> tuple[str, str]:
    """返回 (token_type, token_value)

    优先级: session-token > access-token (因为支付流程需要 session-token cookie)
    """
    raw = raw_input.strip()

    # JSON (整个 /api/auth/session 响应)
    try:
        data = json.loads(raw)
        # 优先返回 session-token (支付流程必需)
        st = data.get("__Secure-next-auth.session-token", "") or data.get("sessionToken", "")
        if st and len(st) > 50:
            return "session_token", st
        at = data.get("accessToken", "")
        if at and len(at) > 50:
            return "access_token", at
    except (json.JSONDecodeError, TypeError):
        pass

    # JWT (eyJ...) - 判断是 session-token 还是 access_token
    # session-token 是 Next-Auth 加密 JWT (通常 3000+ 字符, alg=dir)
    # access_token 是普通 JWT (通常 ~1700 字符, alg=RS256)
    if raw.startswith("eyJ") and "." in raw and len(raw) > 100:
        if len(raw) > 2000:
            return "session_token", raw
        return "access_token", raw

    # 其他长字符串默认当 session-token
    if len(raw) > 50:
        return "session_token", raw

    return "", raw


# ═══════════════════════════════════════════════════════════════
#  Token 验证
# ═══════════════════════════════════════════════════════════════
def validate_token(token_type: str, token_value: str) -> dict:
    """验证 token, 返回 {"ok", "user", "access_token", "error"}"""
    import httpx

    try:
        if token_type == "access_token":
            headers = {"Authorization": f"Bearer {token_value}"}
            with httpx.Client(timeout=15, proxy=PROXY, verify=False) as c:
                resp = c.get("https://chatgpt.com/api/auth/session", headers=headers)
        else:
            cookies = {"__Secure-next-auth.session-token": token_value}
            with httpx.Client(timeout=15, proxy=PROXY, verify=False, cookies=cookies) as c:
                resp = c.get("https://chatgpt.com/api/auth/session")

        if resp.status_code == 200:
            data = resp.json()
            email = data.get("user", {}).get("email", "未知")
            at = data.get("accessToken", "")
            return {"ok": True, "user": email, "access_token": at}
        elif resp.status_code in (401, 403):
            return {"ok": False, "error": "Token 已失效"}
        else:
            return {"ok": False, "error": f"返回状态码 {resp.status_code}"}
    except Exception as e:
        return {"ok": False, "error": f"验证异常: {str(e)[:150]}"}


def _required_internal_text(value: Any, field: str, max_length: int = 500) -> str:
    text = str(value or "").strip()
    if not text or len(text) > max_length:
        raise ValueError(f"{field} 格式无效")
    return text


def _internal_session(payload: dict[str, Any]) -> str:
    raw_session = _required_internal_text(payload.get("session"), "session", 12000)
    token_type, token_value = extract_token(raw_session)
    if token_type != "session_token":
        raise ValueError("必须提供有效的 session-token")
    return token_value


def generate_internal_service07_checkout(payload: dict[str, Any]) -> dict[str, Any]:
    """Generate the checkout URL while Java creates the VMCard in parallel."""
    token_value = _internal_session(payload)
    country = _required_internal_text(
        payload.get("country") or DEFAULT_COUNTRY, "country", 2
    ).upper()
    if country not in SUPPORTED_REGIONS:
        country = DEFAULT_COUNTRY
    currency = SUPPORTED_REGIONS[country]["currency"]
    link_result = generate_checkout_link_via_subprocess(
        mode="session",
        session_token=token_value,
        proxy=PROXY,
        country=country,
        currency=currency,
    )
    if not link_result.get("ok"):
        raise RuntimeError(str(link_result.get("error") or "生成支付链接失败")[:300])
    checkout_url = _required_internal_text(link_result.get("url"), "checkout_url", 4000)
    if not checkout_url.startswith("https://"):
        raise RuntimeError("支付链接协议无效")
    return {
        "ok": True,
        "url": checkout_url,
        "country": country,
        "currency": currency,
    }


def run_internal_service07_autofill(payload: dict[str, Any]) -> dict[str, Any]:
    """Open the generated URL and fill it from the VMCard detail using XPath."""
    token_value = _internal_session(payload)
    checkout_url = _required_internal_text(payload.get("checkout_url"), "checkout_url", 4000)
    if not checkout_url.startswith("https://"):
        raise ValueError("checkout_url 格式无效")

    card = payload.get("card")
    if not isinstance(card, dict):
        raise ValueError("card 格式无效")
    number = _required_internal_text(card.get("number"), "card.number", 32)
    expiry = _required_internal_text(card.get("expiry"), "card.expiry", 20)
    cvc = _required_internal_text(card.get("cvc"), "card.cvc", 8)
    name = _required_internal_text(card.get("name"), "card.name", 160)
    address = _required_internal_text(card.get("address"), "card.address", 300)
    city = _required_internal_text(card.get("city"), "card.city", 120)
    state = _required_internal_text(card.get("state"), "card.state", 120)
    postal_code = _required_internal_text(card.get("zip"), "card.zip", 32)
    country = _required_internal_text(card.get("country") or DEFAULT_COUNTRY, "card.country", 2).upper()
    if country not in SUPPORTED_REGIONS:
        country = DEFAULT_COUNTRY
    billing_country = "アメリカ合衆国" if country == "US" else country
    fill_result = run_auto_fill_playwright(
        checkout_url=checkout_url,
        session_token=token_value,
        access_token="",
        card_number=number,
        card_expiry=expiry,
        card_cvc=cvc,
        billing_name=name,
        billing_address=address,
        billing_city=city,
        billing_state=state,
        billing_zip=postal_code,
        billing_country=billing_country,
        headless=True,
        proxy=PROXY,
        click_subscribe=True,
    )

    success = bool(fill_result.get("success"))
    result_label = "订阅流程已完成" if success else "支付页面未确认成功"
    for step in fill_result.get("steps", []):
        if step.get("name") != "payment_result":
            continue
        status = step.get("status")
        if status == "already_subscribed":
            success = True
            result_label = "账号已订阅"
        elif status == "declined":
            result_label = "银行卡被拒绝"
        elif status == "navigated_away":
            result_label = "已离开支付页"
        elif status == "still_on_checkout":
            result_label = "仍停留在支付页"
        break
    return {"ok": success, "result_label": result_label}


# ═══════════════════════════════════════════════════════════════
#  后台支付执行
# ═══════════════════════════════════════════════════════════════
def run_payment_task(task_id: str, token_type: str, token_value: str, country: str = DEFAULT_COUNTRY):
    """后台线程: 选卡 → 生成链接 → 无头填表 → 订阅

    country: 两字母国家代码 (US/JP/HK/...), 不在白名单则回退到 US
    """

    region = SUPPORTED_REGIONS.get(country.upper(), SUPPORTED_REGIONS[DEFAULT_COUNTRY])
    country_code = country.upper() if country.upper() in SUPPORTED_REGIONS else DEFAULT_COUNTRY
    currency_code = region["currency"]
    region_label = region["label"]

    def emit(step, status, detail="", **kw):
        push_event(task_id, "progress", {
            "step": step, "status": status, "detail": detail,
            "time": datetime.now().isoformat(), **kw,
        })

    try:
        # ── 1. 选取卡片 ────────────────────────────
        emit("select_card", "running", "正在选取银行卡...")
        card = pick_available_card()

        if not card:
            emit("select_card", "error", "没有可用的银行卡")
            push_event(task_id, "done", {"success": False, "error": "没有可用的银行卡"})
            return

        card_mask = card["card_number"][:4] + "****" + card["card_number"][-4:]
        emit("select_card", "ok", f"选中卡片: {card_mask}")

        # ── 2. 生成支付链接 ────────────────────────
        emit("generate_link", "running", f"正在生成支付链接 (地区: {region_label})...")

        # session-token 可以直接用于 mode=session (子脚本会自动换取 access_token)
        # access_token 只能用于 mode=token
        if token_type == "session_token":
            mode = "session"
            link_result = generate_checkout_link_via_subprocess(
                mode="session",
                session_token=token_value,
                proxy=PROXY,
                country=country_code,
                currency=currency_code,
            )
        else:
            mode = "token"
            link_result = generate_checkout_link_via_subprocess(
                mode="token",
                access_token=token_value,
                proxy=PROXY,
                country=country_code,
                currency=currency_code,
            )

        if not link_result.get("ok"):
            err = link_result.get("error", "未知错误")
            emit("generate_link", "error", f"生成失败: {err}")
            push_event(task_id, "done", {"success": False, "error": err})
            return

        checkout_url = link_result["url"]
        emit(
            "generate_link",
            "ok",
            "支付链接已生成",
            hosted_url=checkout_url,
            country=country_code,
            currency=currency_code,
        )

        # ── 4. 无头浏览器自动填写 ──────────────────
        emit("auto_fill", "running", "无头浏览器启动中, 自动填写支付表单...")

        db_country = card.get("billing_country", "US")
        billing_country_ja = "アメリカ合衆国" if db_country == "US" else db_country

        # 认证策略: session-token 用 cookie, access_token 用 route 拦截 (可能被重定向到登录页)
        fill_session_token = token_value if token_type == "session_token" else ""
        fill_access_token = token_value if token_type == "access_token" else ""

        if not fill_session_token and fill_access_token:
            emit("auth_warning", "running",
                 "access_token 无法设置浏览器 cookie, 页面可能跳转到登录页。建议提供 session-token cookie。")

        fill_result = run_auto_fill_playwright(
            checkout_url=checkout_url,
            session_token=fill_session_token,
            access_token=fill_access_token,
            card_number=card["card_number"],
            card_expiry=card["card_expiry"],
            card_cvc=card["card_cvc"],
            billing_name=card["billing_name"],
            billing_address=card["billing_address"],
            billing_city=card["billing_city"],
            billing_state=card["billing_state"],
            billing_zip=card["billing_zip"],
            billing_country=billing_country_ja,
            headless=True,
            proxy=PROXY,
            click_subscribe=True,
        )

        # 推送每个步骤到前端
        for s in fill_result.get("steps", []):
            emit(s["name"], s["status"], s.get("detail", ""))

        # ── 5. 判断结果 ────────────────────────────
        is_success = fill_result.get("success", False)
        is_frozen = False
        remark_text = ""
        result_label = "未知"

        for s in fill_result.get("steps", []):
            if s["name"] == "payment_result":
                if s["status"] == "declined":
                    is_frozen = True
                    remark_text = f"银行卡被拒绝 - {datetime.now().strftime('%Y-%m-%d %H:%M')}"
                    result_label = "银行卡被拒绝"
                elif s["status"] == "already_subscribed":
                    remark_text = f"账号已订阅 - {datetime.now().strftime('%Y-%m-%d %H:%M')}"
                    result_label = "账号已订阅"
                    is_success = True
                elif s["status"] == "still_on_checkout":
                    result_label = "仍在支付页面"
                elif s["status"] == "navigated_away":
                    result_label = "已跳转, 可能成功"
                break

        update_card_status(card["id"], success=is_success, frozen=is_frozen, remark=remark_text)

        push_event(task_id, "done", {
            "success": is_success,
            "result_label": result_label,
            "hosted_url": checkout_url,
            "country": country_code,
            "currency": currency_code,
        })

    except Exception as e:
        emit("exception", "error", str(e)[:300])
        push_event(task_id, "done", {"success": False, "error": str(e)[:500]})
    finally:
        def cleanup():
            time.sleep(5)
            task_queues.pop(task_id, None)
        threading.Thread(target=cleanup, daemon=True).start()


# ═══════════════════════════════════════════════════════════════
#  Flask
# ═══════════════════════════════════════════════════════════════
from flask import Flask, request, Response, jsonify

app = Flask(__name__)


def _internal_request_authorized() -> bool:
    if not INTERNAL_API_TOKEN:
        return False
    header = request.headers.get("Authorization", "")
    expected = f"Bearer {INTERNAL_API_TOKEN}"
    return hmac.compare_digest(header.encode("utf-8"), expected.encode("utf-8"))


@app.route("/")
def index():
    return Response(HTML_PAGE, mimetype="text/html; charset=utf-8")


@app.route("/api/regions")
def api_regions():
    """返回支持的地区列表 (供前端下拉框渲染)"""
    regions = [
        {"code": code, "currency": info["currency"], "label": info["label"]}
        for code, info in SUPPORTED_REGIONS.items()
    ]
    return jsonify({"ok": True, "default": DEFAULT_COUNTRY, "regions": regions})


@app.route("/api/subscribe", methods=["POST"])
def api_subscribe():
    """一键订阅: 验证 + 生成链接 + 填表 + 支付"""
    data = request.json or {}
    raw = data.get("token", "").strip()
    country = (data.get("country") or DEFAULT_COUNTRY).upper()
    if country not in SUPPORTED_REGIONS:
        country = DEFAULT_COUNTRY

    if not raw:
        return jsonify({"ok": False, "error": "请输入 Token"}), 400

    token_type, token_value = extract_token(raw)
    if not token_type:
        return jsonify({"ok": False, "error": "无法识别 Token 格式"}), 400

    task_id = uuid.uuid4().hex[:12]
    task_queues[task_id] = queue.Queue(maxsize=200)

    t = threading.Thread(
        target=run_payment_task,
        args=(task_id, token_type, token_value, country),
        daemon=True,
    )
    t.start()

    return jsonify({"ok": True, "task_id": task_id})


def _run_internal_endpoint(action):
    if not INTERNAL_API_TOKEN:
        return jsonify({"ok": False, "error": "内部接口未配置"}), 503
    if not _internal_request_authorized():
        return jsonify({"ok": False, "error": "未授权"}), 401
    try:
        return jsonify(action(request.json or {}))
    except ValueError as exc:
        return jsonify({"ok": False, "error": str(exc)[:300]}), 400
    except Exception:
        # Do not echo exception details: they can contain checkout data.
        return jsonify({"ok": False, "error": "自动订阅执行失败"}), 502


@app.route("/api/internal/service-07/checkout-link", methods=["POST"])
def api_internal_service07_checkout_link():
    return _run_internal_endpoint(generate_internal_service07_checkout)


@app.route("/api/internal/service-07/autofill", methods=["POST"])
def api_internal_service07_autofill():
    return _run_internal_endpoint(run_internal_service07_autofill)


@app.route("/api/events/<task_id>")
def api_events(task_id):
    """SSE 实时进度"""
    q = task_queues.get(task_id)
    if not q:
        return Response("event: error\ndata: {}\n\n", mimetype="text/event-stream")

    def generate():
        while True:
            try:
                item = q.get(timeout=30)
                yield f"event: {item['event']}\ndata: {json.dumps(item['data'], ensure_ascii=False)}\n\n"
                if item["event"] == "done":
                    break
            except queue.Empty:
                yield "event: ping\ndata: {}\n\n"

    return Response(generate(), mimetype="text/event-stream", headers={
        "Cache-Control": "no-cache",
        "X-Accel-Buffering": "no",
    })


# ═══════════════════════════════════════════════════════════════
#  前端页面
# ═══════════════════════════════════════════════════════════════
HTML_PAGE = r"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>GPT Plus 订阅</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
:root{--bg:#0c0d12;--sf:#16181f;--bd:#252830;--tx:#e2e2e5;--t2:#8b8f9a;--ac:#6366f1;--ad:#4f46e5;--ok:#22c55e;--er:#ef4444;--wn:#f59e0b;--bl:#3b82f6}
body{font-family:-apple-system,'Segoe UI',sans-serif;background:var(--bg);color:var(--tx);min-height:100vh;display:flex;flex-direction:column;align-items:center;padding:40px 16px}
.box{width:100%;max-width:520px}
h1{font-size:24px;font-weight:700;background:linear-gradient(135deg,var(--ac),var(--bl));-webkit-background-clip:text;-webkit-text-fill-color:transparent;text-align:center;margin-bottom:6px}
.sub{text-align:center;color:var(--t2);font-size:13px;margin-bottom:28px}
.card{background:var(--sf);border:1px solid var(--bd);border-radius:12px;padding:20px;margin-bottom:14px}
.card h2{font-size:15px;font-weight:600;margin-bottom:10px;display:flex;align-items:center;gap:8px}
.card h2 .num{width:24px;height:24px;border-radius:50%;background:var(--ad);color:#fff;display:inline-flex;align-items:center;justify-content:center;font-size:12px;font-weight:700;flex-shrink:0}
.card h2 .num.ok{background:var(--ok)}
.card h2 .num.run{background:var(--wn);animation:pulse 1.5s infinite}
@keyframes pulse{0%,100%{box-shadow:0 0 0 0 rgba(245,158,11,.4)}50%{box-shadow:0 0 0 6px rgba(245,158,11,0)}}
.hint{font-size:12.5px;color:var(--t2);line-height:1.7;margin-bottom:12px}
.hint a{color:var(--bl);text-decoration:underline}
textarea{width:100%;min-height:90px;background:var(--bg);border:1px solid var(--bd);border-radius:8px;color:var(--tx);padding:10px 12px;font-size:12.5px;font-family:Consolas,'Courier New',monospace;resize:vertical;outline:none;transition:border .2s}
textarea:focus{border-color:var(--ac)}
textarea::placeholder{color:#444}
select{width:100%;background:var(--bg);border:1px solid var(--bd);border-radius:8px;color:var(--tx);padding:10px 12px;font-size:13px;font-family:inherit;outline:none;transition:border .2s;cursor:pointer;appearance:none;-webkit-appearance:none;background-image:url("data:image/svg+xml;charset=UTF-8,%3Csvg xmlns='http://www.w3.org/2000/svg' width='12' height='12' viewBox='0 0 24 24' fill='none' stroke='%238b8f9a' stroke-width='3'%3E%3Cpath d='M6 9l6 6 6-6'/%3E%3C/svg%3E");background-repeat:no-repeat;background-position:right 12px center;padding-right:32px}
select:focus{border-color:var(--ac)}
select option{background:var(--bg);color:var(--tx)}
.region-label{font-size:12.5px;color:var(--t2);margin:12px 0 6px;display:flex;align-items:center;gap:6px}
.btn{display:block;width:100%;padding:12px;border-radius:8px;font-size:15px;font-weight:700;cursor:pointer;border:none;transition:all .2s;color:#fff}
.btn-go{background:linear-gradient(135deg,var(--ac),var(--bl))}
.btn-go:hover{opacity:.9}
.btn-go:disabled{opacity:.4;cursor:not-allowed}
.log{background:var(--bg);border:1px solid var(--bd);border-radius:8px;padding:10px 12px;max-height:300px;overflow-y:auto;font-family:Consolas,'Courier New',monospace;font-size:11.5px;line-height:1.9}
.log-l{display:flex;gap:6px}
.log-t{color:var(--t2);flex-shrink:0}
.log-ok{color:var(--ok)}.log-er{color:var(--er)}.log-run{color:var(--wn)}.log-def{color:var(--t2)}
.result{text-align:center;padding:20px 0}
.result .icon{font-size:44px;margin-bottom:10px}
.result .title{font-size:18px;font-weight:700;margin-bottom:6px}
.result .detail{font-size:13px;color:var(--t2)}
.hidden{display:none}
.fade{animation:fi .25s ease-in}
@keyframes fi{from{opacity:0;transform:translateY(6px)}to{opacity:1;transform:translateY(0)}}
</style>
</head>
<body>
<div class="box">
  <h1>GPT Plus 订阅</h1>
  <p class="sub">粘贴 Session Token，一键完成订阅支付</p>

  <!-- Step 1: 获取 Token -->
  <div class="card">
    <h2><span class="num" id="n1">1</span> 获取认证信息</h2>
    <div class="hint">
      <b>方式 A (推荐):</b> 在浏览器中按 F12 打开 DevTools → Application → Cookies → chatgpt.com<br>
      复制 <code>__Secure-next-auth.session-token</code> 的值，粘贴到下方输入框。<br><br>
      <b>方式 B:</b> 在已登录 ChatGPT 的浏览器中打开
      <a href="https://chatgpt.com/api/auth/session" target="_blank" rel="noopener">
        /api/auth/session
      </a>，全选复制页面内容粘贴到下方。
    </div>
    <textarea id="tokenInput" placeholder="粘贴 session-token cookie 值 或 /api/auth/session 的完整 JSON"></textarea>
    <div class="region-label">💳 支付地区 / Billing Region</div>
    <select id="regionSelect" title="选择支付地区, 默认美国">
      <option value="US" data-currency="USD">加载中...</option>
    </select>
  </div>

  <!-- Step 2: 订阅 -->
  <div class="card">
    <h2><span class="num" id="n2">2</span> 订阅</h2>
    <button class="btn btn-go" id="subBtn" onclick="doSubscribe()">订阅</button>
  </div>

  <!-- Progress -->
  <div class="card hidden" id="logCard">
    <h2><span class="num run" id="n3">3</span> 执行进度</h2>
    <div class="log" id="logBox"></div>
  </div>

  <!-- Result -->
  <div class="card hidden" id="resultCard">
    <div class="result" id="resultBox"></div>
  </div>
</div>

<script>
function esc(s){const d=document.createElement('div');d.textContent=s;return d.innerHTML}
function now(){return new Date().toTimeString().slice(0,8)}

// ─── 加载支付地区列表 ───
(async function loadRegions(){
  try{
    const resp=await fetch('/api/regions');
    const data=await resp.json();
    const sel=document.getElementById('regionSelect');
    sel.innerHTML='';
    (data.regions||[]).forEach(r=>{
      const opt=document.createElement('option');
      opt.value=r.code;
      opt.dataset.currency=r.currency;
      opt.textContent=r.code+' · '+r.label+' ('+r.currency+')';
      if(r.code===data.default)opt.selected=true;
      sel.appendChild(opt);
    });
  }catch(e){
    console.error('加载地区列表失败',e);
  }
})();

function addLog(step,status,detail){
  const b=document.getElementById('logBox');
  const c=status==='ok'?'log-ok':status==='error'?'log-er':status==='running'?'log-run':'log-def';
  const i=status==='ok'?'+':status==='error'?'x':status==='running'?'~':'-';
  const l=document.createElement('div');
  l.className='log-l fade';
  l.innerHTML='<span class="log-t">'+esc(now())+'</span><span class="'+c+'">['+esc(i)+'] '+esc(step)+'</span> <span class="log-def">'+esc(detail)+'</span>';
  b.appendChild(l);b.scrollTop=b.scrollHeight;
}

async function doSubscribe(){
  const token=document.getElementById('tokenInput').value.trim();
  if(!token){alert('请先粘贴 Session Token');return}
  const region=document.getElementById('regionSelect').value||'US';

  const btn=document.getElementById('subBtn');
  btn.disabled=true;btn.textContent='执行中...';
  document.getElementById('n1').className='num ok';
  document.getElementById('n2').className='num run';
  document.getElementById('logCard').classList.remove('hidden');
  addLog('region','running','支付地区: '+region);

  try{
    const resp=await fetch('/api/subscribe',{
      method:'POST',headers:{'Content-Type':'application/json'},
      body:JSON.stringify({token,country:region})
    });
    const data=await resp.json();
    if(!data.ok){
      addLog('subscribe','error',data.error||'启动失败');
      document.getElementById('n2').className='num';
      btn.disabled=false;btn.textContent='订阅';return;
    }
    document.getElementById('n2').className='num ok';
    listenEvents(data.task_id);
  }catch(e){
    addLog('subscribe','error','请求异常: '+e.message);
    document.getElementById('n2').className='num';
    btn.disabled=false;btn.textContent='订阅';
  }
}

function listenEvents(tid){
  const es=new EventSource('/api/events/'+tid);
  es.addEventListener('progress',function(e){
    const d=JSON.parse(e.data);
    addLog(d.step,d.status,d.detail);
  });
  es.addEventListener('done',function(e){
    es.close();
    const d=JSON.parse(e.data);
    document.getElementById('n3').className='num ok';
    document.getElementById('resultCard').classList.remove('hidden');
    const box=document.getElementById('resultBox');
    if(d.success){
      box.innerHTML='<div class="icon" style="color:var(--ok)">&#10003;</div>'+
        '<div class="title" style="color:var(--ok)">订阅成功</div>'+
        '<div class="detail">'+esc(d.result_label||'支付流程完成')+'</div>';
    }else{
      box.innerHTML='<div class="icon" style="color:var(--er)">&#10007;</div>'+
        '<div class="title" style="color:var(--er)">订阅失败</div>'+
        '<div class="detail">'+esc(d.error||d.result_label||'支付未成功')+'</div>';
    }
    const btn=document.getElementById('subBtn');
    btn.disabled=false;btn.textContent='订阅';
    document.getElementById('n2').className='num ok';
  });
  es.addEventListener('ping',function(){});
  es.onerror=function(){};
}
</script>
</body>
</html>
"""


# ═══════════════════════════════════════════════════════════════
#  Main
# ═══════════════════════════════════════════════════════════════
def main():
    import argparse
    parser = argparse.ArgumentParser(description="GPT Plus 订阅引导界面")
    parser.add_argument("--host", default=HOST)
    parser.add_argument("--port", type=int, default=PORT)
    parser.add_argument("--debug", action="store_true", default=DEBUG)
    args = parser.parse_args()

    print(f"\n  GPT Plus 订阅 - 引导界面")
    print(f"  http://localhost:{args.port}\n")

    app.run(host=args.host, port=args.port, debug=args.debug, threaded=True)


if __name__ == "__main__":
    main()
