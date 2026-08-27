# 验证报告

日期：2026-07-13

## 自动化结果

| 检查 | 结果 |
|---|---|
| `mvnw verify` | 69 项测试通过，0 失败；JAR 打包、JaCoCo 报告和覆盖率门禁通过 |
| 后端覆盖率 | 行 44.71%，分支 26.88%；门禁下限分别为 40% 和 20% |
| `npm test` | 4 个测试文件、18 项测试通过 |
| `npm run build` | TypeScript/Vue 类型检查和 Vite 生产构建通过 |
| `npm audit` | 0 个已知漏洞 |
| 跟踪文件秘密扫描 | 未发现常见 API Key、私钥或明文配置模式 |

后端测试覆盖注册/登录/注销、会话过期、OAuth state 与 Provider 表单交换、权限矩阵、API Key 一次性 Secret、渠道密钥加密、余额/额度预留与退款、路由回退、Plus 收据安全和 ShopGPT 关闭策略。

## 实机本地启动结果

应用能够完成 Spring/Tomcat 初始化，但连接本地 MySQL 时退出。根因不是代码异常：Windows 服务 `MySQL80` 当前为 `Stopped`，当前进程没有启动系统服务的管理员权限，连接 `localhost:3306` 被拒绝。

继续真实本地验收前需要以管理员身份启动 MySQL：

```powershell
Start-Service MySQL80
```

外部 YAML 已检测到 Google 和 GitHub 配置，但还没有以下配置段：

- `security.data-encryption-key`
- `security.bootstrap-admin`（仅首次没有管理员时需要）
- 显式 `features.shopgpt.enabled`（代码默认已安全关闭）

完整占位模板见 `config/application-local.example.yaml`。

## 尚未完成的外部验收

- 真实 Google/GitHub 账号交互登录（自动化使用本地 Provider Stub，未读取或输出你的 Secret）。
- 真实上游 Provider 的调用、计费对账和故障切换。
- 真实支付、退款、拒付、收据法定信息和 ShopGPT 供应链。
- MySQL 生产数据迁移、多实例、Redis、峰值压测、备份恢复和灾难演练。

## 已知工程债务

- `web/node_modules` 历史上已有 14,016 个文件被 Git 跟踪；`.gitignore` 虽已正确忽略，但仍应在单独提交中执行 `git rm -r --cached web/node_modules`。
- Element Plus 产物约 1.09 MB（gzip 约 342 KB），生产构建通过但仍应继续按页面/组件拆包。
- 覆盖率已建立防回退门禁，但商用核心模块建议逐步提高到行覆盖率 80%、分支覆盖率 60% 以上。
- 当前浏览器 Bearer Token 使用 `localStorage`；正式上线应迁移到 HttpOnly Cookie 并增加 CSRF 防护。
