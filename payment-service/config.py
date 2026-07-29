#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""支付服务配置加载器

从 .env 文件和环境变量读取配置，提供统一的配置入口。
优先级: 环境变量 > .env 文件 > 默认值
"""

from __future__ import annotations

import os
from pathlib import Path
from typing import Any

# 尝试加载 python-dotenv
try:
    from dotenv import load_dotenv
    _dotenv_path = Path(__file__).resolve().parent / ".env"
    if _dotenv_path.exists():
        load_dotenv(_dotenv_path)
except ImportError:
    pass  # 没有 dotenv 也行，直接用环境变量


def _env(key: str, default: str = "") -> str:
    """读取环境变量，缺失时返回默认值"""
    return os.environ.get(key, default)


# ─── 代理配置 ─────────────────────────────────────────────────
PROXY: str = _env("PROXY", "http://127.0.0.1:7897")

# ─── Flask 服务配置 ───────────────────────────────────────────
HOST: str = _env("HOST", "127.0.0.1")
PORT: int = int(_env("PORT", "5000"))
DEBUG: bool = _env("DEBUG", "false").lower() in ("true", "1", "yes")

# ─── MySQL 配置 ──────────────────────────────────────────────
DB_CONFIG: dict[str, Any] = {
    "host": _env("DB_HOST", "localhost"),
    "port": int(_env("DB_PORT", "3306")),
    "user": _env("DB_USER", "root"),
    "password": _env("DB_PASSWORD", "123456"),
    "database": _env("DB_NAME", "gpt_payment"),
    "charset": "utf8mb4",
    "cursorclass": None,  # 运行时动态设置
}

# ─── 默认支付地区 ─────────────────────────────────────────────
DEFAULT_COUNTRY: str = _env("DEFAULT_COUNTRY", "US")
DEFAULT_CURRENCY: str = _env("DEFAULT_CURRENCY", "USD")
