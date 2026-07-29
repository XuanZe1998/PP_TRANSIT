#!/bin/bash
cd "$(dirname "$0")"

echo "========================================"
echo "  ChatGPT Payment Link Service"
echo "========================================"
echo ""

# 检查 .env 文件
if [ ! -f .env ]; then
    echo "[WARN] .env 文件不存在，将使用默认配置"
    echo "       你可以复制 .env.example 为 .env 并修改配置"
    echo ""
fi

# 检查虚拟环境
if [ ! -d "venv" ]; then
    echo "[INFO] 正在创建 Python 虚拟环境..."
    python3 -m venv venv
    echo "[INFO] 安装依赖..."
    source venv/bin/activate
    pip install -r requirements.txt -q
    echo "[INFO] 安装 Playwright 浏览器..."
    playwright install chromium
    echo ""
else
    source venv/bin/activate
fi

echo "[INFO] 启动 Payment Service..."
echo "[INFO] 访问 http://localhost:5000"
echo ""
python payment_web.py
