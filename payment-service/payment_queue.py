#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""GPT 支付任务队列 - 并发执行多账号支付

功能:
  - 多账号并发支付 (线程池)
  - MySQL 持久化任务队列 (崩溃恢复)
  - 原子化卡片选取 (SELECT FOR UPDATE, 防止重复分配)
  - 无头浏览器模式 (默认)
  - 超时锁自动释放
  - 实时进度报告

用法:
  # 从文件读取多个 session token, 并发执行:
  python payment_queue.py --session-file tokens.txt --workers 3

  # 指定多个 session token:
  python payment_queue.py --tokens "token1,token2,token3" --workers 3

  # 恢复上次中断的任务:
  python payment_queue.py --resume --workers 3

  # 查看任务状态:
  python payment_queue.py --status
"""

from __future__ import annotations

import argparse
import enum
import hashlib
import json
import os
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any

# ─── Windows UTF-8 修复 ─────────────────────────────────────────
if sys.platform == "win32":
    os.environ.setdefault("PYTHONIOENCODING", "utf-8")
    os.environ.setdefault("PYTHONUTF8", "1")

SCRIPT_DIR = Path(__file__).resolve().parent

# 导入已有模块的函数
sys.path.insert(0, str(SCRIPT_DIR))
from auto_fill_payment import (
    generate_checkout_link_via_subprocess,
    run_auto_fill_playwright,
    update_card_status,
    get_db_connection,
    DB_CONFIG,
    log,
)


# ═══════════════════════════════════════════════════════════════
#  Constants
# ═══════════════════════════════════════════════════════════════
DEFAULT_WORKERS = 3
MAX_WORKERS = 10
STALE_LOCK_MINUTES = 10  # 锁超过此时间视为超时


class TaskStatus(str, enum.Enum):
    PENDING = "pending"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


# ═══════════════════════════════════════════════════════════════
#  CardLocker - 原子化卡片选取与行级锁
# ═══════════════════════════════════════════════════════════════
class CardLocker:
    """保证并发安全的卡片选取与行级锁

    两阶段锁策略:
      提交阶段: 通过 tasks 表 NOT IN 约束防止重复分配, 不设 locked_at
      执行阶段: Worker 加锁 (locked_at), 防止两个 worker 同时操作同一卡
    """

    @staticmethod
    def pick_for_task() -> dict | None:
        """提交阶段: 原子选取一张可分配的卡 (不设锁)

        注意: 此方法仅用于查询, 真正的原子分配请用 submit_single_task

        Returns: 卡片 dict 或 None
        """
        import pymysql
        conn = get_db_connection()
        try:
            cur = conn.cursor()
            cur.execute("""
                SELECT * FROM cards
                WHERE is_active = 1
                  AND is_frozen = 0
                  AND id NOT IN (
                      SELECT card_id FROM tasks
                      WHERE status IN ('pending', 'running')
                  )
                ORDER BY id ASC
                LIMIT 1
            """)
            card = cur.fetchone()
            conn.close()
            return card
        except Exception as e:
            log(f"[CardLocker] pick_for_task 异常: {e}")
            conn.close()
            return None

    @staticmethod
    def submit_single_task(session_token: str, token_hash: str) -> int | None:
        """原子操作: 选卡 + 创建任务 (同一事务)

        流程 (单事务内):
          1. SELECT ... FOR UPDATE 锁定候选卡行
          2. INSERT INTO tasks 记录任务
          3. COMMIT 释放行锁 (此时卡片 id 已在 tasks 表中, NOT IN 生效)

        Returns: task_id 或 None (无可用卡)
        """
        import pymysql
        conn = get_db_connection()
        try:
            cur = conn.cursor()
            conn.begin()

            # 行级锁: 锁定候选行
            cur.execute("""
                SELECT * FROM cards
                WHERE is_active = 1
                  AND is_frozen = 0
                  AND id NOT IN (
                      SELECT card_id FROM tasks
                      WHERE status IN ('pending', 'running')
                  )
                ORDER BY id ASC
                LIMIT 1
                FOR UPDATE
            """)
            card = cur.fetchone()

            if not card:
                conn.commit()
                return None

            # 同一事务内创建任务 (卡片分配立即生效)
            cur.execute("""
                INSERT INTO tasks (session_token_hash, session_token, card_id, status)
                VALUES (%s, %s, %s, %s)
            """, (token_hash, session_token, card["id"], TaskStatus.PENDING))
            task_id = cur.lastrowid

            conn.commit()
            log(f"[CardLocker] 原子分配: task_id={task_id}, card_id={card['id']}, hash={token_hash}")
            return task_id

        except Exception as e:
            conn.rollback()
            log(f"[CardLocker] submit_single_task 异常: {e}")
            raise
        finally:
            conn.close()

    @staticmethod
    def lock_for_execution(card_id: int, worker_id: str):
        """执行阶段: Worker 加锁, 标记卡片正在使用中

        Returns: True 成功, False 卡片已被锁或不可用
        """
        conn = get_db_connection()
        try:
            cur = conn.cursor()
            conn.begin()

            # 先检查卡片是否仍然可用
            cur.execute("""
                SELECT id FROM cards
                WHERE id = %s AND is_active = 1 AND is_frozen = 0 AND locked_at IS NULL
                FOR UPDATE
            """, (card_id,))
            row = cur.fetchone()

            if not row:
                conn.commit()
                log(f"[CardLocker] 卡片 id={card_id} 不可用 (已冻结/已锁定)")
                return False

            now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
            cur.execute("""
                UPDATE cards SET locked_at = %s, locked_by = %s, updated_at = %s
                WHERE id = %s
            """, (now, worker_id, now, card_id))
            conn.commit()
            log(f"[CardLocker] 卡片 id={card_id} 执行锁已加 by {worker_id}")
            return True

        except Exception as e:
            conn.rollback()
            log(f"[CardLocker] lock_for_execution 异常: {e}")
            raise
        finally:
            conn.close()

    @staticmethod
    def release_lock(card_id: int):
        """释放卡片锁"""
        conn = get_db_connection()
        try:
            cur = conn.cursor()
            now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
            cur.execute("""
                UPDATE cards SET locked_at = NULL, locked_by = NULL, updated_at = %s
                WHERE id = %s
            """, (now, card_id))
            conn.commit()
            log(f"[CardLocker] 卡片 id={card_id} 锁已释放")
        finally:
            conn.close()

    @staticmethod
    def cleanup_stale_locks(timeout_minutes: int = STALE_LOCK_MINUTES):
        """清理超时锁 (worker 崩溃后遗留)"""
        conn = get_db_connection()
        try:
            cur = conn.cursor()
            cutoff = (datetime.now() - timedelta(minutes=timeout_minutes)).strftime("%Y-%m-%d %H:%M:%S")
            cur.execute("""
                UPDATE cards SET locked_at = NULL, locked_by = NULL
                WHERE locked_at IS NOT NULL AND locked_at < %s
            """, (cutoff,))
            released = cur.rowcount
            conn.commit()
            if released > 0:
                log(f"[CardLocker] 清理了 {released} 个超时锁 (>{timeout_minutes}min)")
        finally:
            conn.close()


# ═══════════════════════════════════════════════════════════════
#  TaskManager - MySQL 持久化任务队列
# ═══════════════════════════════════════════════════════════════
class TaskManager:
    """任务生命周期管理, 所有状态变更落盘到 MySQL"""

    @staticmethod
    def _token_hash(token: str) -> str:
        """session-token 短哈希, 用于去重和日志"""
        return hashlib.sha256(token.encode()).hexdigest()[:16]

    @staticmethod
    def create_task(session_token: str, card_id: int) -> int:
        """创建支付任务, 返回 task_id"""
        conn = get_db_connection()
        try:
            cur = conn.cursor()
            token_hash = TaskManager._token_hash(session_token)
            cur.execute("""
                INSERT INTO tasks (session_token_hash, session_token, card_id, status)
                VALUES (%s, %s, %s, %s)
            """, (token_hash, session_token, card_id, TaskStatus.PENDING))
            task_id = cur.lastrowid
            conn.commit()
            log(f"[TaskManager] 任务已创建: id={task_id}, card_id={card_id}, hash={token_hash}")
            return task_id
        finally:
            conn.close()

    @staticmethod
    def start_task(task_id: int, worker_id: str):
        """标记任务开始执行 (pending -> running)"""
        conn = get_db_connection()
        try:
            cur = conn.cursor()
            now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
            cur.execute("""
                UPDATE tasks SET status=%s, worker_id=%s, started_at=%s
                WHERE id=%s AND status=%s
            """, (TaskStatus.RUNNING, worker_id, now, task_id, TaskStatus.PENDING))
            conn.commit()
        finally:
            conn.close()

    @staticmethod
    def complete_task(task_id: int, result: dict, success: bool):
        """标记任务完成 (running -> completed/failed)"""
        conn = get_db_connection()
        try:
            cur = conn.cursor()
            now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
            status = TaskStatus.COMPLETED if success else TaskStatus.FAILED
            cur.execute("""
                UPDATE tasks SET status=%s, result=%s, completed_at=%s
                WHERE id=%s
            """, (status, json.dumps(result, default=str, ensure_ascii=False), now, task_id))
            conn.commit()
            log(f"[TaskManager] 任务 id={task_id} 完成: {status}")
        finally:
            conn.close()

    @staticmethod
    def fail_task(task_id: int, error: str):
        """标记任务失败"""
        conn = get_db_connection()
        try:
            cur = conn.cursor()
            now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
            cur.execute("""
                UPDATE tasks SET status=%s, error=%s, completed_at=%s
                WHERE id=%s
            """, (TaskStatus.FAILED, error[:1000], now, task_id))
            conn.commit()
        finally:
            conn.close()

    @staticmethod
    def cancel_task(task_id: int, reason: str = ""):
        """取消任务"""
        conn = get_db_connection()
        try:
            cur = conn.cursor()
            now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
            cur.execute("""
                UPDATE tasks SET status=%s, error=%s, completed_at=%s
                WHERE id=%s
            """, (TaskStatus.CANCELLED, reason[:500], now, task_id))
            conn.commit()
        finally:
            conn.close()

    @staticmethod
    def get_pending_tasks() -> list[dict]:
        """获取待执行任务 (含卡片信息)"""
        conn = get_db_connection()
        try:
            cur = conn.cursor()
            cur.execute("""
                SELECT t.*, c.card_number, c.card_expiry, c.card_cvc,
                       c.billing_name, c.billing_address, c.billing_city,
                       c.billing_state, c.billing_zip, c.billing_country
                FROM tasks t
                JOIN cards c ON t.card_id = c.id
                WHERE t.status = %s
                ORDER BY t.id ASC
            """, (TaskStatus.PENDING,))
            return cur.fetchall()
        finally:
            conn.close()

    @staticmethod
    def get_status_summary() -> dict[str, int]:
        """各状态的任务计数"""
        conn = get_db_connection()
        try:
            cur = conn.cursor()
            cur.execute("SELECT status, COUNT(*) as cnt FROM tasks GROUP BY status")
            result = {row["status"]: row["cnt"] for row in cur.fetchall()}
            for s in TaskStatus:
                result.setdefault(s, 0)
            return result
        finally:
            conn.close()

    @staticmethod
    def is_token_processed(token_hash: str) -> bool:
        """检查该 token 是否已有完成/运行中的任务 (去重)"""
        conn = get_db_connection()
        try:
            cur = conn.cursor()
            cur.execute("""
                SELECT COUNT(*) as cnt FROM tasks
                WHERE session_token_hash = %s AND status IN (%s, %s)
            """, (token_hash, TaskStatus.COMPLETED, TaskStatus.RUNNING))
            return cur.fetchone()["cnt"] > 0
        finally:
            conn.close()

    @staticmethod
    def reset_stale_running(timeout_minutes: int = STALE_LOCK_MINUTES) -> int:
        """将超时的 running 任务重置为 pending (崩溃恢复)"""
        conn = get_db_connection()
        try:
            cur = conn.cursor()
            cutoff = (datetime.now() - timedelta(minutes=timeout_minutes)).strftime("%Y-%m-%d %H:%M:%S")
            cur.execute("""
                UPDATE tasks SET status=%s, worker_id=NULL, started_at=NULL
                WHERE status=%s AND started_at < %s
            """, (TaskStatus.PENDING, TaskStatus.RUNNING, cutoff))
            reset = cur.rowcount
            conn.commit()
            if reset > 0:
                log(f"[TaskManager] 重置了 {reset} 个超时 running 任务")
            return reset
        finally:
            conn.close()


# ═══════════════════════════════════════════════════════════════
#  PaymentWorker - 单任务执行逻辑
# ═══════════════════════════════════════════════════════════════
def run_single_payment(
    task_id: int,
    session_token: str,
    card: dict,
    proxy: str | None = "http://127.0.0.1:7897",
    worker_id: str = "w0",
) -> dict:
    """执行单个支付任务的完整流程

    流程:
      1. 生成支付链接
      2. 无头浏览器自动填写
      3. 判断支付结果, 更新卡片状态
      4. 更新任务状态
      5. 释放卡片锁

    Returns: 结果 dict
    """
    card_id = card["id"]
    result = {"task_id": task_id, "card_id": card_id, "success": False, "steps": [], "errors": []}

    def _step(name, status, detail=""):
        result["steps"].append({"name": name, "status": status, "detail": detail})
        log(f"  [Worker-{worker_id}] [{status}] {name}: {detail}")

    try:
        # ── Step 1: 生成支付链接 ──────────────────────
        _step("generate_link", "ok", "开始生成支付链接...")

        link_result = generate_checkout_link_via_subprocess(
            mode="session",
            session_token=session_token,
            proxy=proxy,
            country="JP",
            currency="JPY",
        )

        if not link_result.get("ok"):
            err_msg = link_result.get("error", "未知错误")
            _step("generate_link", "error", err_msg)
            result["errors"].append({"field": "checkout_link", "error": err_msg})
            # 链接生成失败不冻结卡 (不是卡的问题)
            TaskManager.fail_task(task_id, f"支付链接生成失败: {err_msg}")
            return result

        checkout_url = link_result["url"]
        _step("generate_link", "ok", f"链接: {checkout_url[:60]}...")

        # 记录 checkout_url 到任务
        conn = get_db_connection()
        try:
            cur = conn.cursor()
            cur.execute("UPDATE tasks SET checkout_url=%s WHERE id=%s", (checkout_url, task_id))
            conn.commit()
        finally:
            conn.close()

        # ── Step 2: 无头浏览器自动填写 ──────────────────
        db_country = card.get("billing_country", "US")
        billing_country_ja = "アメリカ合衆国" if db_country == "US" else db_country

        _step("auto_fill", "ok", "启动无头浏览器...")

        fill_result = run_auto_fill_playwright(
            checkout_url=checkout_url,
            session_token=session_token,
            card_number=card["card_number"],
            card_expiry=card["card_expiry"],
            card_cvc=card["card_cvc"],
            billing_name=card["billing_name"],
            billing_address=card["billing_address"],
            billing_city=card["billing_city"],
            billing_state=card["billing_state"],
            billing_zip=card["billing_zip"],
            billing_country=billing_country_ja,
            headless=True,   # 强制无头
            proxy=proxy,
            click_subscribe=True,
        )

        result["fill_result"] = fill_result
        result["success"] = fill_result.get("success", False)
        result["errors"] = fill_result.get("errors", [])

        # ── Step 3: 根据支付结果更新卡片状态 ──────────
        is_success = fill_result.get("success", False)
        is_frozen = False
        remark_text = ""

        for s in fill_result.get("steps", []):
            if s["name"] == "payment_result":
                if s["status"] == "declined":
                    is_frozen = True
                    remark_text = f"被拒绝 - {datetime.now().strftime('%Y-%m-%d %H:%M')}"
                elif s["status"] == "already_subscribed":
                    remark_text = f"已订阅 - {datetime.now().strftime('%Y-%m-%d %H:%M')}"
                break

        update_card_status(card_id, success=is_success, frozen=is_frozen, remark=remark_text)

        # ── Step 4: 更新任务状态 ──────────────────────
        TaskManager.complete_task(task_id, fill_result, is_success)
        _step("done", "ok", f"支付结果: {'成功' if is_success else '失败'}")

    except Exception as e:
        _step("exception", "error", str(e)[:200])
        result["errors"].append({"field": "exception", "error": str(e)})
        TaskManager.fail_task(task_id, str(e)[:500])
    finally:
        # 无论成功失败, 释放卡片锁
        CardLocker.release_lock(card_id)

    return result


# ═══════════════════════════════════════════════════════════════
#  PaymentOrchestrator - 任务总调度
# ═══════════════════════════════════════════════════════════════
class PaymentOrchestrator:
    """支付任务总调度器

    负责:
      - 接收 session tokens, 原子分配卡片, 创建任务
      - 启动线程池并发执行任务
      - 崩溃恢复 (清理超时锁 + 重置超时任务)
      - 实时进度报告
    """

    def __init__(
        self,
        max_workers: int = DEFAULT_WORKERS,
        proxy: str | None = "http://127.0.0.1:7897",
        lock_timeout: int = STALE_LOCK_MINUTES,
    ):
        self.max_workers = min(max_workers, MAX_WORKERS)
        self.proxy = proxy
        self.lock_timeout = lock_timeout

    # ── 提交任务 ──────────────────────────────────────
    def submit_from_tokens(self, tokens: list[str]) -> int:
        """为每个 session token 创建任务, 原子分配卡片

        通过 CardLocker.submit_single_task 保证:
          - 同一事务内选卡+创建任务, 无并发窗口
          - tasks 表 NOT IN 约束防止重复分配
          - token_hash 去重防止重复提交

        返回: 成功提交的任务数
        """
        submitted = 0
        skipped = 0
        no_card = 0

        for i, token in enumerate(tokens):
            token_hash = TaskManager._token_hash(token)

            # 去重: 已完成或运行中的任务跳过
            if TaskManager.is_token_processed(token_hash):
                log(f"[Orchestrator] 跳过已处理 token #{i+1}: hash={token_hash}")
                skipped += 1
                continue

            # 原子操作: 选卡 + 创建任务 (同一事务)
            task_id = CardLocker.submit_single_task(token, token_hash)

            if task_id is None:
                log(f"[Orchestrator] 无可用卡片, token #{i+1} hash={token_hash}")
                no_card += 1
                continue

            submitted += 1

        log(f"[Orchestrator] 提交完成: 新建={submitted}, 跳过={skipped}, 无卡={no_card}")
        return submitted

    # ── 执行任务 ──────────────────────────────────────
    def run(self):
        """启动线程池, 执行所有 pending 任务"""
        # 先清理超时锁和超时任务
        CardLocker.cleanup_stale_locks(self.lock_timeout)
        TaskManager.reset_stale_running(self.lock_timeout)

        pending = TaskManager.get_pending_tasks()
        if not pending:
            log("[Orchestrator] 没有待执行的任务")
            return

        log(f"[Orchestrator] 启动: {len(pending)} 个任务, 并发度={self.max_workers}")
        self._print_status()

        completed_count = 0
        failed_count = 0

        with ThreadPoolExecutor(max_workers=self.max_workers) as executor:
            futures = {}

            for task in pending:
                worker_id = f"w{task['id']}"

                # 执行阶段: 锁定卡片
                locked = CardLocker.lock_for_execution(task["card_id"], worker_id)
                if not locked:
                    log(f"[Orchestrator] 任务 id={task['id']}: 卡片不可用, 标记失败")
                    TaskManager.fail_task(task["id"], "卡片不可用 (已冻结或已锁定)")
                    continue

                TaskManager.start_task(task["id"], worker_id)

                # 从 join 结果构建卡片 dict
                card = {
                    "id": task["card_id"],
                    "card_number": task["card_number"],
                    "card_expiry": task["card_expiry"],
                    "card_cvc": task["card_cvc"],
                    "billing_name": task["billing_name"],
                    "billing_address": task["billing_address"],
                    "billing_city": task["billing_city"],
                    "billing_state": task["billing_state"],
                    "billing_zip": task["billing_zip"],
                    "billing_country": task["billing_country"],
                }

                future = executor.submit(
                    run_single_payment,
                    task_id=task["id"],
                    session_token=task["session_token"],
                    card=card,
                    proxy=self.proxy,
                    worker_id=worker_id,
                )
                futures[future] = task["id"]

            # 等待所有任务完成
            for future in as_completed(futures):
                task_id = futures[future]
                try:
                    result = future.result()
                    if result.get("success"):
                        completed_count += 1
                    else:
                        failed_count += 1
                except Exception as e:
                    log(f"[Orchestrator] 任务 id={task_id} 执行异常: {e}")
                    failed_count += 1
                    # 异常时确保释放卡片锁
                    try:
                        conn = get_db_connection()
                        cur = conn.cursor()
                        cur.execute("SELECT card_id FROM tasks WHERE id=%s", (task_id,))
                        row = cur.fetchone()
                        if row:
                            CardLocker.release_lock(row["card_id"])
                        conn.close()
                    except Exception:
                        pass

                self._print_status()

        log(f"[Orchestrator] 全部完成: 成功={completed_count}, 失败={failed_count}")
        self.show_status()

    # ── 恢复任务 ──────────────────────────────────────
    def resume(self):
        """恢复中断的任务: 清理超时锁/任务 + 重新执行 pending"""
        log("[Orchestrator] 恢复模式...")
        self.run()

    # ── 状态查看 ──────────────────────────────────────
    def show_status(self):
        """打印详细的任务和卡片状态"""
        summary = TaskManager.get_status_summary()

        print("\n" + "=" * 55)
        print("  GPT 支付任务队列 - 状态概览")
        print("=" * 55)

        labels = {
            "pending": "等待中", "running": "执行中",
            "completed": "已完成", "failed": "已失败", "cancelled": "已取消",
        }
        for status, label in labels.items():
            cnt = summary.get(status, 0)
            if cnt > 0:
                print(f"  {label:>6s}: {cnt}")

        total = sum(summary.values())
        print(f"  {'总计':>6s}: {total}")

        # 卡片状态
        conn = get_db_connection()
        try:
            cur = conn.cursor()
            cur.execute("SELECT COUNT(*) as cnt FROM cards WHERE is_active=1 AND is_frozen=0 AND locked_at IS NULL")
            available = cur.fetchone()["cnt"]
            cur.execute("SELECT COUNT(*) as cnt FROM cards WHERE is_frozen=1")
            frozen = cur.fetchone()["cnt"]
            cur.execute("SELECT COUNT(*) as cnt FROM cards WHERE locked_at IS NOT NULL")
            locked = cur.fetchone()["cnt"]
            cur.execute("SELECT COUNT(*) as cnt FROM cards")
            total_cards = cur.fetchone()["cnt"]
            print(f"\n  卡片总数: {total_cards}")
            print(f"  可用: {available} | 已冻结: {frozen} | 锁定中: {locked}")
        finally:
            conn.close()

        print("=" * 55 + "\n")

    def _print_status(self):
        """打印简短状态行"""
        summary = TaskManager.get_status_summary()
        parts = []
        for s, label in [
            (TaskStatus.PENDING, "待执行"),
            (TaskStatus.RUNNING, "执行中"),
            (TaskStatus.COMPLETED, "已完成"),
            (TaskStatus.FAILED, "失败"),
        ]:
            cnt = summary.get(s, 0)
            if cnt > 0:
                parts.append(f"{label}={cnt}")
        log(f"[状态] {', '.join(parts) if parts else '无任务'}")


# ═══════════════════════════════════════════════════════════════
#  CLI
# ═══════════════════════════════════════════════════════════════
def parse_args():
    parser = argparse.ArgumentParser(
        description="GPT 支付任务队列 - 并发执行多账号支付",
    )

    # 任务来源
    parser.add_argument("--session-file", help="包含多个 session-token 的文件 (每行一个)")
    parser.add_argument("--tokens", help="逗号分隔的多个 session-token")

    # 执行控制
    parser.add_argument("--workers", type=int, default=DEFAULT_WORKERS,
                        help=f"并发 worker 数 (默认: {DEFAULT_WORKERS}, 最大: {MAX_WORKERS})")
    parser.add_argument("--resume", action="store_true", help="恢复上次中断的任务")
    parser.add_argument("--status", action="store_true", help="查看任务状态")

    # 代理
    parser.add_argument("--proxy", default="http://127.0.0.1:7897", help="代理地址")
    parser.add_argument("--no-proxy", action="store_true", help="不使用代理")

    # 数据库
    parser.add_argument("--db-host", default="localhost", help="MySQL 主机")
    parser.add_argument("--db-user", default="root", help="MySQL 用户")
    parser.add_argument("--db-password", default="123456", help="MySQL 密码")
    parser.add_argument("--db-name", default="gpt_payment", help="MySQL 数据库名")

    # 锁超时
    parser.add_argument("--lock-timeout", type=int, default=STALE_LOCK_MINUTES,
                        help=f"卡片锁超时时间(分钟) (默认: {STALE_LOCK_MINUTES})")

    return parser.parse_args()


def load_tokens_from_file(filepath: str) -> list[str]:
    """从文件读取 session token (每行一个, 忽略空行和注释)"""
    tokens = []
    with open(filepath, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#"):
                tokens.append(line)
    return tokens


def main() -> int:
    args = parse_args()

    # 更新数据库配置
    DB_CONFIG["host"] = args.db_host
    DB_CONFIG["user"] = args.db_user
    DB_CONFIG["password"] = args.db_password
    DB_CONFIG["database"] = args.db_name

    proxy = None if args.no_proxy else args.proxy

    orchestrator = PaymentOrchestrator(
        max_workers=args.workers,
        proxy=proxy,
        lock_timeout=args.lock_timeout,
    )

    # 查看状态
    if args.status:
        orchestrator.show_status()
        return 0

    # 恢复模式
    if args.resume:
        orchestrator.resume()
        return 0

    # 从文件或参数读取 token
    tokens = []
    if args.session_file:
        log(f"从文件读取 session token: {args.session_file}")
        tokens = load_tokens_from_file(args.session_file)
        log(f"  读取到 {len(tokens)} 个 token")
    elif args.tokens:
        tokens = [t.strip() for t in args.tokens.split(",") if t.strip()]
        log(f"从参数读取 {len(tokens)} 个 token")

    if not tokens:
        log("[错误] 请提供 session token (--session-file 或 --tokens), 或使用 --resume / --status")
        return 1

    # 提交任务
    submitted = orchestrator.submit_from_tokens(tokens)
    if submitted == 0:
        log("[错误] 没有成功提交任何任务 (可能没有可用卡片, 或所有 token 已处理)")
        orchestrator.show_status()
        return 1

    # 执行任务
    orchestrator.run()

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
