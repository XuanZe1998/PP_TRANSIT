#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""GPT 支付自动填写脚本

完整自动化流程:
  1. 生成 ChatGPT Plus 支付链接 (复用 gpt_checkout_link.py 逻辑)
  2. 启动 Playwright 浏览器, 注入 session cookie
  3. 打开支付页面, 自动填写银行卡和账单地址
  4. 支付完成后更新数据库中的卡片状态

用法:
  # 从 MySQL 数据库自动选取可用卡 (默认):
  python auto_fill_payment.py --mode session --session-token <TOKEN> --db

  # 指定数据库中的卡片 ID:
  python auto_fill_payment.py --mode session --session-token <TOKEN> --db --card-id 1

  # 手动指定卡片信息:
  python auto_fill_payment.py --mode session --session-token <TOKEN> \
    --card-number 4549241835825563 --card-expiry 05/2029 --card-cvc 254 \
    --name "RI LIU" --address "1295 Rollin Burg" --city Dover --state "New Hampshire" --zip 03216

  # JSON 配置文件 (作为数据库的 fallback):
  python auto_fill_payment.py --mode session --session-token <TOKEN> --config payment_config.json

  # 从 session.txt 自动提取:
  python auto_fill_payment.py --mode session --session-file session.txt --db
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import Any

# ─── MySQL 连接配置 (从 config 模块统一读取) ────────────────
from config import DB_CONFIG, PROXY

# ─── Windows UTF-8 修复 ─────────────────────────────────────────
if sys.platform == "win32":
    os.environ.setdefault("PYTHONIOENCODING", "utf-8")
    os.environ.setdefault("PYTHONUTF8", "1")
    _original_subprocess_run = subprocess.run
    def _utf8_subprocess_run(*args, **kwargs):
        if kwargs.get("text", False) and "encoding" not in kwargs:
            kwargs["encoding"] = "utf-8"
            kwargs["errors"] = "replace"
        return _original_subprocess_run(*args, **kwargs)
    subprocess.run = _utf8_subprocess_run

os.environ.setdefault("SENTINEL_HTTPX_FALLBACK", "0")

# ─── 将 protocol 项目加入 import 路径 ───────────────────────────
SCRIPT_DIR = Path(__file__).resolve().parent
PROTOCOL_SRC = SCRIPT_DIR / "protocol" / "gpt_trial_protocol" / "src"
if not PROTOCOL_SRC.exists():
    print(f"[错误] 找不到协议项目 src: {PROTOCOL_SRC}", file=sys.stderr)
    sys.exit(1)
sys.path.insert(0, str(PROTOCOL_SRC))


def log(msg: str) -> None:
    ts = datetime.now().strftime("%H:%M:%S")
    print(f"[{ts}] {msg}", flush=True)


# ═══════════════════════════════════════════════════════════════
#  Step 1: 生成支付链接 (调用 gpt_checkout_link.py)
# ═══════════════════════════════════════════════════════════════
def generate_checkout_link_via_subprocess(
    *,
    mode: str,
    session_token: str | None = None,
    access_token: str | None = None,
    proxy: str | None = "http://127.0.0.1:7897",
    country: str = "JP",
    currency: str = "JPY",
    timeout: float = 30.0,
) -> dict[str, Any]:
    """调用 gpt_checkout_link.py 生成支付链接, 返回结果 JSON"""

    cmd = [
        sys.executable,
        str(SCRIPT_DIR / "gpt_checkout_link.py"),
        "--mode", mode,
        "--country", country,
        "--currency", currency,
        "--timeout", str(timeout),
    ]

    if proxy:
        cmd += ["--proxy", proxy]
    else:
        cmd += ["--no-proxy"]

    if mode == "session" and session_token:
        cmd += ["--session-token", session_token]
    elif mode == "token" and access_token:
        cmd += ["--access-token", access_token]

    # 不让子脚本自动打开本机浏览器 (本脚本会自己启动 Playwright 浏览器)
    cmd += ["--no-open"]

    log(f"调用 gpt_checkout_link.py 生成支付链接...")
    log(f"  mode={mode}, country={country}, currency={currency}")

    result = subprocess.run(
        cmd, capture_output=True, text=True, encoding="utf-8", errors="replace",
        timeout=120,
    )

    stdout = result.stdout.strip()
    stderr = result.stderr.strip()

    # 从 stdout 中提取最后一个 JSON 块 (gpt_checkout_link.py 的输出)
    checkout_url = None
    session_id = None
    processor_entity = None

    # 尝试解析 stdout 最后一行 JSON
    if stdout:
        for line in reversed(stdout.splitlines()):
            line = line.strip()
            if line.startswith("{"):
                try:
                    data = json.loads(line)
                    checkout_url = data.get("checkoutUrl")
                    session_id = data.get("sessionId")
                    processor_entity = data.get("processorEntity")
                    if checkout_url:
                        log(f"支付链接提取成功: {checkout_url[:80]}...")
                        return {
                            "ok": True,
                            "url": checkout_url,
                            "session_id": session_id,
                            "processor_entity": processor_entity,
                            "raw": data,
                        }
                except json.JSONDecodeError:
                    continue

    # 如果 JSON 解析失败, 尝试从 stderr 中找 URL
    for stream in [stdout, stderr]:
        if stream and "chatgpt.com/checkout/" in stream:
            import re
            match = re.search(r'(https://chatgpt\.com/checkout/[^\s"\'<>]+)', stream)
            if match:
                checkout_url = match.group(1)
                log(f"从输出中提取到支付链接: {checkout_url[:80]}...")
                return {"ok": True, "url": checkout_url}

    log(f"gpt_checkout_link.py 执行完成, 但未提取到支付链接")
    if stderr:
        log(f"stderr (last 500 chars): {stderr[-500:]}")
    if stdout:
        log(f"stdout (last 500 chars): {stdout[-500:]}")

    return {"ok": False, "error": "未提取到支付链接", "stdout": stdout[-500:], "stderr": stderr[-500:]}


# ═══════════════════════════════════════════════════════════════
#  Step 2-3: Playwright 自动填写
# ═══════════════════════════════════════════════════════════════

def check_playwright_installed() -> bool:
    """检查 Playwright Python 库是否已安装"""
    try:
        import playwright
        return True
    except ImportError:
        return False


def install_playwright() -> None:
    """安装 Playwright Python 库和浏览器"""
    log("安装 Playwright Python 库...")
    subprocess.run([sys.executable, "-m", "pip", "install", "playwright"], check=True)
    log("安装 Playwright 浏览器 (chromium)...")
    subprocess.run([sys.executable, "-m", "playwright", "install", "chromium"], check=True)
    log("Playwright 安装完成")


def run_auto_fill_playwright(
    *,
    checkout_url: str,
    session_token: str = "",
    access_token: str = "",
    card_number: str,
    card_expiry: str,
    card_cvc: str,
    billing_name: str,
    billing_address: str,
    billing_city: str,
    billing_state: str,
    billing_zip: str,
    billing_country: str = "アメリカ合衆国",
    headless: bool = False,
    proxy: str | None = None,
    click_subscribe: bool = True,
) -> dict[str, Any]:
    """使用 Playwright Python 自动填写支付表单

    参数:
      checkout_url: ChatGPT checkout 页面 URL
      session_token: __Secure-next-auth.session-token cookie 值
      card_number/cvc/expiry: 卡片信息
      billing_*: 账单地址信息
      headless: 是否无头模式
      proxy: 代理地址 (如 http://127.0.0.1:7897)
      click_subscribe: 是否自动点击订阅按钮

    返回: 填写结果 dict
    """
    from playwright.sync_api import sync_playwright

    results = {"steps": [], "errors": [], "final_url": "", "success": False}

    def step(name, status, detail=""):
        results["steps"].append({"name": name, "status": status, "detail": detail, "time": datetime.now().isoformat()})
        log(f"  [{status}] {name}: {detail}")

    with sync_playwright() as pw:
        # 启动浏览器 - 不在启动参数设置代理，改用 context 级别
        launch_args = {
            "headless": headless,
            "args": [
                "--disable-blink-features=AutomationControlled",
                "--ignore-certificate-errors",
            ],
        }

        browser = pw.chromium.launch(**launch_args)

        # 浏览器上下文选项
        ctx_options = {
            "viewport": {"width": 1280, "height": 900},
            "locale": "ja-JP",
            "timezone_id": "Asia/Tokyo",
            "user_agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        }

        # 在 context 级别设置代理 (比启动参数更可靠)
        if proxy:
            ctx_options["proxy"] = {"server": proxy}

        context = browser.new_context(**ctx_options)

        # 忽略 SSL 错误 (代理可能导致 SSL 问题)
        context.on("pageerror", lambda exc: log(f"  [pageerror] {str(exc)[:200]}"))

        page = context.new_page()

        # 注入认证信息: 优先 session cookie, 否则用 access_token 拦截请求头
        if session_token and len(session_token) > 10:
            try:
                context.add_cookies([{
                    "name": "__Secure-next-auth.session-token",
                    "value": session_token,
                    "domain": ".chatgpt.com",
                    "path": "/",
                    "secure": True,
                    "httpOnly": True,
                    "sameSite": "Lax",
                }])
                step("set_cookie", "ok", f"session-token 长度={len(session_token)}")
            except Exception as e:
                results["errors"].append({"field": "cookie", "error": str(e)})
                step("set_cookie", "error", str(e))
        elif access_token and len(access_token) > 10:
            # 通过 route 拦截为所有 chatgpt.com 请求注入 Authorization 头
            def _auth_route(route):
                headers = {**route.request.headers, "Authorization": f"Bearer {access_token}"}
                route.continue_(headers=headers)
            try:
                page.route("**/chatgpt.com/**", _auth_route)
                step("set_auth_header", "ok", f"access_token route 拦截已设置, 长度={len(access_token)}")
            except Exception as e:
                results["errors"].append({"field": "auth_header", "error": str(e)})
                step("set_auth_header", "error", str(e))
        else:
            step("auth_warning", "info", "未提供 session_token 或 access_token, 页面可能需要登录")

        # 导航到 checkout 页面
        try:
            page.goto(checkout_url, wait_until="domcontentloaded", timeout=60000)
            step("navigate", "ok", checkout_url[:80])
        except Exception as e:
            step("navigate", "error", str(e))
            # 可能 domcontentloaded 超时, 但页面可能已加载
            page.wait_for_timeout(5000)

        # 等待页面完全加载 (Stripe JS 需要时间初始化)
        log("  等待 Stripe 支付表单加载...")
        page.wait_for_timeout(8000)

        # 检查是否跳到登录页
        current_url = page.url
        if "/auth/login" in current_url or "/auth/" in current_url:
            step("check_login", "error", f"跳转到登录页: {current_url}")
            results["errors"].append({"field": "login_redirect", "error": f"页面跳到登录页，cookie 可能已失效: {current_url}"})
            # 截图
            diag_path = str(SCRIPT_DIR / ".temp" / f"diag_login_{datetime.now().strftime('%Y%m%d_%H%M%S')}.png")
            try:
                SCRIPT_DIR.joinpath(".temp").mkdir(exist_ok=True)
                page.screenshot(path=diag_path)
                step("diag_login_screenshot", "ok", diag_path)
            except: pass
            results["final_url"] = current_url
            browser.close()
            return results

        # 等待 Stripe iframe 出现 - 多种模式匹配
        iframe_found = False
        iframe_selectors = [
            "iframe[name^='__privateStripeFrame']",
            "iframe[title*='Secure']",
            "iframe[title*='stripe']",
            "iframe[title*='Stripe']",
            "iframe[src*='stripe.com']",
            "iframe[src*='js.stripe.com']",
        ]
        for sel in iframe_selectors:
            if iframe_found:
                break
            try:
                page.wait_for_selector(sel, timeout=10000)
                step("wait_iframe", "ok", f"找到 iframe: {sel}")
                iframe_found = True
            except Exception:
                continue

        if not iframe_found:
            step("wait_iframe", "error", "所有 iframe 选择器均超时")
            # 截图诊断
            diag_path = str(SCRIPT_DIR / ".temp" / f"diag_navigate_{datetime.now().strftime('%Y%m%d_%H%M%S')}.png")
            try:
                SCRIPT_DIR.joinpath(".temp").mkdir(exist_ok=True)
                page.screenshot(path=diag_path)
                step("diag_screenshot", "ok", diag_path)
            except:
                pass
            step("diag_url", "info", page.url)
            try:
                title = page.title()
                step("diag_title", "info", title)
            except:
                pass
            # 打印页面上所有 iframe 信息
            try:
                all_iframes = page.evaluate("""() => Array.from(document.querySelectorAll('iframe')).map(f => ({name: f.name, src: f.src?.substring(0,100), title: f.title}))""")
                step("diag_all_iframes", "info", json.dumps(all_iframes, ensure_ascii=False)[:500])
            except:
                pass

        # 找到所有 Stripe 相关 iframe
        stripe_frames = [f for f in page.frames if f.name.startswith("__privateStripeFrame")]
        # 如果没有 __privateStripeFrame，尝试通过 src 识别
        if not stripe_frames:
            stripe_frames = [f for f in page.frames if "stripe.com" in (f.url or "")]
        # 如果还是没有，取所有非主 frame
        if not stripe_frames:
            all_non_main = [f for f in page.frames if f != page.main_frame]
            if all_non_main:
                stripe_frames = all_non_main
                step("find_iframes_fallback", "info", f"没有 __privateStripeFrame，使用所有非主frame: {[f.name for f in stripe_frames]}")
        step("find_iframes", "info", f"找到 {len(stripe_frames)} 个 Stripe iframe/fallback frame: {[f.name for f in stripe_frames]}")

        if not stripe_frames:
            results["errors"].append({"field": "iframe", "error": "未找到 Stripe iframe"})
            step("find_iframes", "error", "未找到 Stripe iframe, 中止")
            results["final_url"] = page.url
            browser.close()
            return results

        # 确定 card iframe 和 billing iframe
        card_iframe = stripe_frames[0]
        billing_iframe = stripe_frames[-1] if len(stripe_frames) > 1 else None
        step("card_iframe", "info", card_iframe.name)
        if billing_iframe:
            step("billing_iframe", "info", billing_iframe.name)

        # ── 调试: 仅在未找到 Stripe iframe 时输出诊断信息 ─────
        # (正常流程不输出 DOM dump, 出错时截图)
        try:
            SCRIPT_DIR.joinpath(".temp").mkdir(exist_ok=True)
        except Exception:
            pass

        # ── 确定哪个 iframe 包含卡号输入框 ──────────────────────
        # 等待 iframe 内容加载
        page.wait_for_timeout(3000)

        # 遍历 iframe，找到包含卡号字段的那个
        payment_iframe = None
        for fr in stripe_frames:
            try:
                fr.locator('input[name="number"], input#payment-numberInput').wait_for(timeout=5000)
                payment_iframe = fr
                step("payment_iframe", "info", f"卡号字段在 {fr.name}")
                break
            except Exception:
                continue

        if not payment_iframe:
            # fallback: 默认用最后一个 iframe
            payment_iframe = stripe_frames[-1] if len(stripe_frames) > 1 else stripe_frames[0]
            step("payment_iframe_fallback", "info", f"默认用: {payment_iframe.name}")

        # ── 用户提供的精确 XPath (在 iframe 内) ──────────────────
        XP_CARD_NUMBER = '/html/body/div[1]/div/div[1]/div/div/div/div/div/div/div/div/form/div/div[1]/div/div[1]/div/div[1]/div/div[1]/input'
        XP_CARD_EXPIRY = '/html/body/div[1]/div/div[1]/div/div/div/div/div/div/div/div/form/div/div[1]/div/div[2]/div/div[1]/div/div/input'
        XP_CARD_CVC    = '/html/body/div[1]/div/div[1]/div/div/div/div/div/div/div/div/form/div/div[1]/div/div[3]/div/div[1]/div/div[1]/input'

        # 主页面上的按钮
        XP_NEXT_BUTTON = '/html/body/div[2]/div[2]/div[1]/form/div/div[2]/div[1]/section/div[2]/div/button'

        # 点击"下一步"后，账单地址表单的 XPath (在 iframe 内)
        XP_BILLING_NAME    = '/html/body/div/div/div[1]/div/form/div/div/div[1]/div[1]/div[1]/div/div[1]/input'
        XP_BILLING_COUNTRY = '/html/body/div/div/div[1]/div/form/div/div/div[1]/div[1]/div[2]/div/div[1]/div/select/option[7]'
        XP_BILLING_ADDRESS = '/html/body/div/div/div[1]/div/form/div/div/div[1]/div[2]/div[1]/div/div/div[1]/div/input'
        XP_BILLING_CITY    = '/html/body/div/div/div[1]/div/form/div/div/div[1]/div[2]/div[3]/div/div[1]/input'
        XP_BILLING_STATE   = '/html/body/div/div/div[1]/div/form/div/div/div[1]/div[2]/div[4]/div/div[1]/input'
        XP_BILLING_ZIP     = '/html/body/div/div/div[1]/div/form/div/div/div[1]/div[2]/div[5]/div/div[1]/input'

        # 订阅按钮 (在主页面)
        XP_SUBSCRIBE_BTN  = '/html/body/div[2]/div[2]/div[2]/div[1]/div[3]/button'

        # ── Step 1: 填写银行卡信息 (在 payment_iframe 内) ──────

        # 卡号 - 用 type() 逐字符输入 (Stripe JS 需要逐字符事件进行格式化/验证)
        try:
            el = payment_iframe.locator(f"xpath={XP_CARD_NUMBER}")
            el.wait_for(timeout=10000)
            el.click()
            el.type(card_number, delay=50)
            step("card_number", "ok", f"type | {card_number[:4]}****{card_number[-4:]}")
        except Exception as e:
            results["errors"].append({"field": "card_number", "error": str(e)})
            step("card_number", "error", str(e))

        page.wait_for_timeout(500)

        # 有效期 - Stripe 需要 MMYY 格式 (如 0529), 不带斜杠和完整年份
        stripe_expiry = card_expiry
        try:
            # 将 05/2029 或 05/29 或 052029 转为 MMYY
            import re as _re
            m = _re.match(r"(\d{2})[/\-]?(\d{2,4})", card_expiry)
            if m:
                mm, yy = m.group(1), m.group(2)
                if len(yy) == 4:
                    yy = yy[2:]  # 2029 → 29
                stripe_expiry = f"{mm}{yy}"
        except Exception:
            pass

        try:
            el = payment_iframe.locator(f"xpath={XP_CARD_EXPIRY}")
            el.wait_for(timeout=5000)
            el.click()
            el.type(stripe_expiry, delay=50)
            step("card_expiry", "ok", f"type | {stripe_expiry} (from {card_expiry})")
        except Exception as e:
            results["errors"].append({"field": "card_expiry", "error": str(e)})
            step("card_expiry", "error", str(e))

        page.wait_for_timeout(500)

        # 安全码
        try:
            el = payment_iframe.locator(f"xpath={XP_CARD_CVC}")
            el.wait_for(timeout=5000)
            el.click()
            el.type(card_cvc, delay=50)
            step("card_cvc", "ok", "type | ***")
        except Exception as e:
            results["errors"].append({"field": "card_cvc", "error": str(e)})
            step("card_cvc", "error", str(e))

        page.wait_for_timeout(1000)

        # ── Step 2: 点击"下一步"按钮 (旧版两步流程) ───────────
        # 新版 Stripe checkout 是单页表单, 不需要点击"下一步"
        # 如果找到按钮就点击, 找不到就跳过 (单页模式)
        next_clicked = False
        for target in [page, payment_iframe]:
            if next_clicked:
                break
            try:
                btn = target.locator(f"xpath={XP_NEXT_BUTTON}")
                btn.wait_for(timeout=3000)
                btn.click()
                step("click_next", "ok", f"xpath | 在{'主页面' if target == page else payment_iframe.name}")
                next_clicked = True
            except Exception:
                continue

        if not next_clicked:
            # fallback: 用文本匹配
            for target in [page, payment_iframe]:
                if next_clicked:
                    break
                try:
                    btn = target.get_by_role("button", name="次へ")
                    btn.wait_for(timeout=3000)
                    btn.click()
                    step("click_next", "ok", "via role: 次へ")
                    next_clicked = True
                except Exception:
                    continue

        if not next_clicked:
            step("click_next", "info", "未找到下一步按钮 (单页模式, 跳过)")

        # 等待账单地址表单出现
        page.wait_for_timeout(2000)

        # ── Step 3: 填写账单地址 (在 iframe 内) ────────────────
        # 重新获取 iframe 列表 (可能有新的 iframe 出现)
        stripe_frames = [f for f in page.frames if f.name.startswith("__privateStripeFrame")]
        if not stripe_frames:
            stripe_frames = [f for f in page.frames if "stripe.com" in (f.url or "")]
        if not stripe_frames:
            stripe_frames = [f for f in page.frames if f != page.main_frame]

        # 确定账单地址 iframe: 遍历找到包含 name 字段的
        billing_iframe_target = None
        for fr in stripe_frames:
            try:
                fr.locator(f"xpath={XP_BILLING_NAME}").wait_for(timeout=3000)
                billing_iframe_target = fr
                step("billing_iframe", "info", f"账单字段在 {fr.name}")
                break
            except Exception:
                continue

        if not billing_iframe_target:
            # fallback: 用 payment_iframe
            billing_iframe_target = payment_iframe
            step("billing_iframe_fallback", "info", f"默认用: {payment_iframe.name}")

        # 姓氏 (非必需: 新版 Stripe 可能不需要账单地址)
        try:
            el = billing_iframe_target.locator(f"xpath={XP_BILLING_NAME}")
            el.wait_for(timeout=3000)
            el.click()
            el.type(billing_name, delay=30)
            step("billing_name", "ok", f"type | {billing_name}")
        except Exception as e:
            step("billing_name", "info", f"未找到姓名字段 (新版 Stripe 可能不需要): {str(e)[:80]}")

        page.wait_for_timeout(500)

        # 国家/地域 - 选择美国 (非必需)
        try:
            select_el = billing_iframe_target.locator("xpath=/html/body/div/div/div[1]/div/form/div/div/div[1]/div[1]/div[2]/div/div[1]/div/select")
            select_el.wait_for(timeout=3000)
            select_el.select_option(value="US")
            step("billing_country", "ok", "xpath | USA (value=US)")
        except Exception as e:
            try:
                el = billing_iframe_target.locator(f"xpath={XP_BILLING_COUNTRY}")
                el.click(force=True)
                step("billing_country", "ok", "xpath force | USA (option[7])")
            except Exception as e2:
                step("billing_country", "info", "未找到国家字段 (新版 Stripe 可能不需要)")

        page.wait_for_timeout(1500)  # 等待表单刷新

        # 地址1 (非必需)
        try:
            el = billing_iframe_target.locator(f"xpath={XP_BILLING_ADDRESS}")
            el.wait_for(timeout=2000)
            el.click()
            el.type(billing_address, delay=30)
            step("billing_address", "ok", f"type | {billing_address}")
        except Exception:
            addr_filled = False
            for sel in ['input[autocomplete="address-line1"]', 'input[name="addressLine1"]', 'input[name="address"]']:
                if addr_filled: break
                try:
                    el = billing_iframe_target.locator(sel)
                    el.wait_for(timeout=1500)
                    el.click()
                    el.type(billing_address, delay=30)
                    step("billing_address", "ok", f"css fallback: {sel} | {billing_address}")
                    addr_filled = True
                except Exception: continue
            if not addr_filled:
                step("billing_address", "info", "未找到地址字段 (新版 Stripe 可能不需要)")

        page.wait_for_timeout(500)

        # 城市名 (非必需)
        try:
            el = billing_iframe_target.locator(f"xpath={XP_BILLING_CITY}")
            el.wait_for(timeout=2000)
            el.click()
            el.type(billing_city, delay=30)
            step("billing_city", "ok", f"type | {billing_city}")
        except Exception:
            step("billing_city", "info", "未找到城市字段")

        page.wait_for_timeout(500)

        # 州 (非必需) - 是 select 下拉框
        state_filled = False
        for state_xp in [
            '/html/body/div/div/div[1]/div/form/div/div/div[1]/div[2]/div[4]/div/div[1]/select',
            '/html/body/div/div/div[1]/div/form/div/div/div[1]/div[2]/div[4]/div/div[1]/div/select',
        ]:
            if state_filled: break
            try:
                state_el = billing_iframe_target.locator(f"xpath={state_xp}")
                state_el.wait_for(timeout=2000)
                state_el.select_option(label=billing_state)
                step("billing_state", "ok", f"xpath select | {billing_state}")
                state_filled = True
            except Exception: continue

        if not state_filled:
            try:
                result = billing_iframe_target.evaluate("""(stateName) => {
                    const selects = document.querySelectorAll('select');
                    for (const sel of selects) {
                        for (const opt of sel.options) {
                            if (opt.text.includes(stateName) || opt.value === stateName) {
                                sel.value = opt.value;
                                sel.dispatchEvent(new Event('change', {bubbles: true}));
                                return {ok: true, value: opt.value, text: opt.text};
                            }
                        }
                    }
                    return {ok: false, selectCount: selects.length};
                }""", billing_state)
                if result.get("ok"):
                    step("billing_state", "ok", f"js select | {result['text']}")
                    state_filled = True
            except Exception:
                pass

        if not state_filled:
            step("billing_state", "info", "未找到州字段")

        page.wait_for_timeout(500)

        # 邮编 (非必需)
        try:
            el = billing_iframe_target.locator(f"xpath={XP_BILLING_ZIP}")
            el.wait_for(timeout=2000)
            el.click()
            el.type(billing_zip, delay=30)
            step("billing_zip", "ok", f"type | {billing_zip}")
        except Exception:
            step("billing_zip", "info", "未找到邮编字段")

        page.wait_for_timeout(1000)

        # ── Step 4: 点击"订阅"按钮 (提交支付) ─────────────────
        # 新版 Stripe checkout: 单页模式, 订阅按钮可能在主页面右侧
        if click_subscribe:
            sub_clicked = False

            # 优先用日文文本匹配 (最可靠)
            for target in [page, billing_iframe_target]:
                if sub_clicked:
                    break
                for btn_name in ["サブスクリプションを登録する", "Subscribe", "订阅"]:
                    if sub_clicked:
                        break
                    try:
                        btn = target.get_by_role("button", name=btn_name)
                        btn.wait_for(timeout=3000)
                        btn.click()
                        step("click_subscribe", "ok", f"role: {btn_name}")
                        sub_clicked = True
                    except Exception:
                        continue

            # fallback: XPath
            if not sub_clicked:
                for target in [page, billing_iframe_target]:
                    if sub_clicked:
                        break
                    try:
                        btn = target.locator(f"xpath={XP_SUBSCRIBE_BTN}")
                        btn.wait_for(timeout=3000)
                        btn.click()
                        step("click_subscribe", "ok", f"xpath | 在{'主页面' if target == page else billing_iframe_target.name}")
                        sub_clicked = True
                    except Exception:
                        continue

            if not sub_clicked:
                step("click_subscribe", "error", "未找到订阅按钮")

            page.wait_for_timeout(8000)

        # ── 检查结果 ──────────────────────────────────────────
        final_url = page.url
        results["final_url"] = final_url

        try:
            page_text = page.text_content("body") or ""
            if "付款未获批准" in page_text or "Your card was declined" in page_text:
                step("payment_result", "declined", "银行卡被拒绝")
                results["success"] = False
            elif "already" in page_text.lower() and "subscri" in page_text.lower():
                step("payment_result", "already_subscribed", "已是订阅用户")
                results["success"] = True
            elif "/checkout/" in final_url:
                step("payment_result", "still_on_checkout", "仍在支付页面")
                # 检查是否有错误提示
                try:
                    error_el = page.locator("text=付款未获批准")
                    if error_el.is_visible():
                        step("payment_result", "declined", "页面显示: 付款未获批准")
                        results["success"] = False
                    else:
                        results["success"] = True  # 表单填写成功, 只是支付结果未知
                except:
                    results["success"] = True
            else:
                step("payment_result", "navigated_away", final_url)
                results["success"] = True
        except Exception as e:
            step("payment_result", "error", str(e))

        # 截图保存
        screenshot_path = str(SCRIPT_DIR / ".temp" / f"auto_fill_result_{datetime.now().strftime('%Y%m%d_%H%M%S')}.png")
        try:
            SCRIPT_DIR.joinpath(".temp").mkdir(exist_ok=True)
            page.screenshot(path=screenshot_path)
            step("screenshot", "ok", screenshot_path)
            results["screenshot"] = screenshot_path
        except Exception as e:
            step("screenshot", "error", str(e))

        browser.close()

    return results


# ═══════════════════════════════════════════════════════════════
#  MySQL 数据库操作
# ═══════════════════════════════════════════════════════════════
def get_db_connection():
    """获取 MySQL 连接"""
    import pymysql
    cfg = dict(DB_CONFIG)
    cfg["cursorclass"] = pymysql.cursors.DictCursor
    return pymysql.connect(**cfg)


def pick_available_card(card_id: int | None = None) -> dict[str, Any] | None:
    """从数据库选取一张可用的卡

    选取条件: is_active=1 AND is_frozen=0
    如果指定 card_id, 则精确选取该卡 (仍需满足可用条件)
    如果不指定, 则按 id 顺序取第一张可用卡

    Returns: 卡数据 dict 或 None
    """
    import pymysql
    try:
        conn = get_db_connection()
        cur = conn.cursor()
        if card_id is not None:
            cur.execute(
                "SELECT * FROM cards WHERE id=%s AND is_active=1 AND is_frozen=0",
                (card_id,),
            )
        else:
            cur.execute(
                "SELECT * FROM cards WHERE is_active=1 AND is_frozen=0 ORDER BY id ASC LIMIT 1"
            )
        row = cur.fetchone()
        conn.close()
        return row
    except Exception as e:
        log(f"[DB] 选取卡片失败: {e}")
        return None


def update_card_status(
    card_id: int,
    *,
    success: bool,
    frozen: bool = False,
    remark: str = "",
) -> None:
    """支付尝试后更新卡片状态

    Args:
        card_id: 卡片 ID
        success: 支付是否成功 (被批准)
        frozen: 是否需要冻结此卡 (如被拒卡)
        remark: 备注信息
    """
    import pymysql
    try:
        conn = get_db_connection()
        cur = conn.cursor()
        now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

        # 更新使用次数和最后使用时间
        cur.execute(
            "UPDATE cards SET use_count=use_count+1, last_used_at=%s, updated_at=%s WHERE id=%s",
            (now, now, card_id),
        )

        # 如果需要冻结
        if frozen:
            cur.execute(
                "UPDATE cards SET is_frozen=1, updated_at=%s WHERE id=%s",
                (now, card_id),
            )
            log(f"[DB] 卡片 id={card_id} 已冻结")

        # 标记已使用 (支付成功)
        if success:
            cur.execute(
                "UPDATE cards SET is_used=1, updated_at=%s WHERE id=%s",
                (now, card_id),
            )

        # 更新备注
        if remark:
            cur.execute(
                "UPDATE cards SET remark=%s, updated_at=%s WHERE id=%s",
                (remark, now, card_id),
            )

        conn.commit()
        conn.close()
        log(f"[DB] 卡片 id={card_id} 状态已更新: success={success}, frozen={frozen}")
    except Exception as e:
        log(f"[DB] 更新卡片状态失败: {e}")


# ═══════════════════════════════════════════════════════════════
#  配置文件读取 (JSON fallback)
# ═══════════════════════════════════════════════════════════════
def load_config(config_path: str) -> dict[str, Any]:
    """从 JSON 文件加载卡片和账单地址配置"""
    with open(config_path, "r", encoding="utf-8") as f:
        return json.load(f)


def load_session_token(session_file: str) -> tuple[str, str]:
    """从 session.txt 提取 session-token 和 accessToken

    Returns: (session_token, access_token)
    """
    with open(session_file, "r", encoding="utf-8") as f:
        data = json.load(f)

    access_token = data.get("accessToken", "")
    # session.txt 可能包含 sessionToken 字段
    session_token = data.get("sessionToken") or data.get("session-token") or ""
    return session_token, access_token


# ═══════════════════════════════════════════════════════════════
#  CLI
# ═══════════════════════════════════════════════════════════════
def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="GPT 支付自动填写 - 生成支付链接后自动填写银行卡和账单地址",
    )

    # 登录模式 (与 gpt_checkout_link.py 一致)
    parser.add_argument("--mode", choices=["session", "token"], required=True,
                        help="登录模式")
    parser.add_argument("--session-token", help="session-token cookie 值")
    parser.add_argument("--access-token", help="accessToken JWT")
    parser.add_argument("--session-file", help="session.txt 文件路径 (提取 accessToken)")
    parser.add_argument("--token-file", help="session-token 文件路径 (纯文本, 一行)")

    # 卡片信息
    parser.add_argument("--card-number", help="银行卡号")
    parser.add_argument("--card-expiry", help="有效期 (MM/YYYY)")
    parser.add_argument("--card-cvc", help="安全码")

    # 账单地址
    parser.add_argument("--name", help="持卡人姓名")
    parser.add_argument("--address", help="地址行1")
    parser.add_argument("--city", help="城市")
    parser.add_argument("--state", help="州/省 (英文全称, 如 New Hampshire)")
    parser.add_argument("--zip", help="邮编")
    parser.add_argument("--country", default="アメリカ合衆国", help="国家 (日文名, 如 アメリカ合衆国)")

    # 配置文件 (替代单独参数) — JSON fallback
    parser.add_argument("--config", help="JSON 配置文件 (包含卡片和地址信息, 作为数据库的 fallback)")

    # 数据库模式
    parser.add_argument("--db", action="store_true", help="从 MySQL 数据库读取卡片信息 (默认模式)")
    parser.add_argument("--card-id", type=int, help="指定从数据库选取的卡片 ID (不指定则自动选第一张可用卡)")
    parser.add_argument("--db-host", default="localhost", help="MySQL 主机 (默认: localhost)")
    parser.add_argument("--db-user", default="root", help="MySQL 用户 (默认: root)")
    parser.add_argument("--db-password", default="123456", help="MySQL 密码 (默认: 123456)")
    parser.add_argument("--db-name", default="gpt_payment", help="MySQL 数据库名 (默认: gpt_payment)")

    # 其他
    parser.add_argument("--proxy", default="http://127.0.0.1:7897", help="代理地址")
    parser.add_argument("--no-proxy", action="store_true", help="不使用代理")
    parser.add_argument("--country-code", default="JP", help="支付国家代码 (默认: JP)")
    parser.add_argument("--currency", default="JPY", help="支付货币代码 (默认: JPY)")
    parser.add_argument("--headless", action="store_true", default=True, help="无头模式 (默认开启)")
    parser.add_argument("--visible", action="store_true", help="显示浏览器窗口 (关闭无头模式)")
    parser.add_argument("--no-submit", action="store_true", help="只填写不点击订阅按钮")
    parser.add_argument("--checkout-url", help="直接使用已有的支付链接 (跳过链接生成)")
    parser.add_argument("--install-playwright", action="store_true", help="安装 Playwright 后退出")

    return parser.parse_args()


def main() -> int:
    args = parse_args()

    # 安装 Playwright
    if args.install_playwright:
        install_playwright()
        return 0

    # 检查 Playwright
    if not check_playwright_installed():
        log("Playwright 未安装, 正在安装...")
        install_playwright()

    # ── 加载配置 ─────────────────────────────────────────────
    # 优先级: CLI 参数 > 数据库 > JSON 配置文件
    card_number = args.card_number or ""
    card_expiry = args.card_expiry or ""
    card_cvc = args.card_cvc or ""
    billing_name = args.name or ""
    billing_address = args.address or ""
    billing_city = args.city or ""
    billing_state = args.state or ""
    billing_zip = args.zip or ""
    billing_country = args.country
    used_card_id = None  # 记录使用了数据库中的哪张卡

    # 覆盖数据库连接配置
    DB_CONFIG["host"] = args.db_host
    DB_CONFIG["user"] = args.db_user
    DB_CONFIG["password"] = args.db_password
    DB_CONFIG["database"] = args.db_name

    # 尝试从数据库读取卡片 (默认行为, 除非明确用 --config)
    if args.db or (not args.config and not card_number):
        log("从 MySQL 数据库读取卡片信息...")
        card_row = pick_available_card(card_id=args.card_id)
        if card_row:
            log(f"  选中卡片: id={card_row['id']}, 卡号={card_row['card_number'][:4]}****{card_row['card_number'][-4:]}")
            used_card_id = card_row["id"]
            # CLI 参数优先, 数据库值作为补充
            card_number = card_number or card_row["card_number"]
            card_expiry = card_expiry or card_row["card_expiry"]
            card_cvc = card_cvc or card_row["card_cvc"]
            billing_name = billing_name or card_row["billing_name"]
            billing_address = billing_address or card_row["billing_address"]
            billing_city = billing_city or card_row["billing_city"]
            billing_state = billing_state or card_row["billing_state"]
            billing_zip = billing_zip or card_row["billing_zip"]
            # 数据库中 billing_country 存的是 "US", 转换为日文名
            db_country = card_row.get("billing_country", "US")
            if not args.country and db_country == "US":
                billing_country = "アメリカ合衆国"
            elif not args.country:
                billing_country = db_country
        else:
            log("  数据库中无可用卡片 (is_active=1, is_frozen=0)")

    # JSON 配置文件作为 fallback
    if args.config and not card_number:
        log(f"从配置文件加载: {args.config}")
        cfg = load_config(args.config)
        card_number = card_number or cfg.get("cardNumber") or cfg.get("card", {}).get("number", "")
        card_expiry = card_expiry or cfg.get("cardExpiry") or cfg.get("card", {}).get("expiry", "")
        card_cvc = card_cvc or cfg.get("cardCvc") or cfg.get("card", {}).get("cvc", "")
        billing_name = billing_name or cfg.get("name") or cfg.get("billing", {}).get("name", "")
        billing_address = billing_address or cfg.get("address") or cfg.get("billing", {}).get("address", "")
        billing_city = billing_city or cfg.get("city") or cfg.get("billing", {}).get("city", "")
        billing_state = billing_state or cfg.get("state") or cfg.get("billing", {}).get("state", "")
        billing_zip = billing_zip or cfg.get("zip") or cfg.get("billing", {}).get("zip", "")
        billing_country = billing_country or cfg.get("country") or cfg.get("billing", {}).get("country", "アメリカ合衆国")

    # 验证必填字段
    if not card_number:
        log("[错误] 缺少银行卡号 (--card-number 或配置文件)")
        return 1

    # ── 获取 session-token ───────────────────────────────────
    session_token = args.session_token or ""
    access_token = args.access_token or ""

    if args.session_file:
        log(f"从 session 文件提取: {args.session_file}")
        st, at = load_session_token(args.session_file)
        session_token = session_token or st
        access_token = access_token or at

    if args.token_file:
        log(f"从 token 文件读取: {args.token_file}")
        with open(args.token_file, "r", encoding="utf-8-sig") as f:
            session_token = session_token or f.read().strip()

    # ── Step 1: 生成支付链接 ─────────────────────────────────
    checkout_url = args.checkout_url

    if not checkout_url:
        proxy = None if args.no_proxy else args.proxy
        log("=" * 60)
        log("Step 1: 生成支付链接")
        log("=" * 60)

        link_result = generate_checkout_link_via_subprocess(
            mode=args.mode,
            session_token=session_token or None,
            access_token=access_token or None,
            proxy=proxy,
            country=args.country_code,
            currency=args.currency,
        )

        if not link_result.get("ok"):
            log(f"支付链接生成失败: {link_result.get('error', '未知错误')}")
            return 1

        checkout_url = link_result["url"]
        log(f"支付链接: {checkout_url}")
    else:
        log(f"使用已有支付链接: {checkout_url}")

    # ── Step 2-3: 自动填写 ───────────────────────────────────
    proxy = None if args.no_proxy else args.proxy
    log("=" * 60)
    log("Step 2: 启动浏览器并自动填写支付表单")
    log("=" * 60)

    fill_result = run_auto_fill_playwright(
        checkout_url=checkout_url,
        session_token=session_token,
        card_number=card_number,
        card_expiry=card_expiry,
        card_cvc=card_cvc,
        billing_name=billing_name,
        billing_address=billing_address,
        billing_city=billing_city,
        billing_state=billing_state,
        billing_zip=billing_zip,
        billing_country=billing_country,
        headless=not args.visible,
        proxy=proxy,
        click_subscribe=not args.no_submit,
    )

    # ── 输出结果 ─────────────────────────────────────────────
    log("=" * 60)
    log("自动填写完成!")
    log("=" * 60)

    for s in fill_result.get("steps", []):
        status_icon = "OK" if s["status"] == "ok" else "!!" if s["status"] == "error" else "--"
        log(f"  [{status_icon}] {s['name']}: {s.get('detail', '')}")

    if fill_result.get("errors"):
        log(f"  错误: {len(fill_result['errors'])} 个")
        for e in fill_result["errors"]:
            log(f"    - {e['field']}: {e['error'][:100]}")

    if fill_result.get("screenshot"):
        log(f"  截图: {fill_result['screenshot']}")

    # ── 更新数据库中的卡片状态 ─────────────────────────────────
    if used_card_id is not None:
        # 判断支付结果
        is_success = fill_result.get("success", False)
        is_frozen = False
        remark_text = ""

        # 检查是否被拒卡
        for s in fill_result.get("steps", []):
            if s["name"] == "payment_result" and s["status"] == "declined":
                is_frozen = True
                remark_text = f"银行卡被拒绝 - {datetime.now().strftime('%Y-%m-%d %H:%M')}"
                break
            if s["name"] == "payment_result" and s["status"] == "already_subscribed":
                remark_text = f"账号已订阅 - {datetime.now().strftime('%Y-%m-%d %H:%M')}"
                break

        update_card_status(
            used_card_id,
            success=is_success,
            frozen=is_frozen,
            remark=remark_text,
        )

    # 保存结果到 JSON
    result_path = str(SCRIPT_DIR / ".temp" / f"auto_fill_result_{datetime.now().strftime('%Y%m%d_%H%M%S')}.json")
    SCRIPT_DIR.joinpath(".temp").mkdir(exist_ok=True)
    with open(result_path, "w", encoding="utf-8") as f:
        json.dump(fill_result, f, ensure_ascii=False, indent=2, default=str)
    log(f"  结果已保存: {result_path}")

    return 0 if fill_result.get("success", False) else 1


if __name__ == "__main__":
    raise SystemExit(main())
