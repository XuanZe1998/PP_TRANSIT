# 2026-09-26 实施结果（以此节为准）

- 服务器为 Ubuntu 24.04。检查时 UFW 仅向公网开放 SSH 22、HTTP 80、HTTPS 443；数据库、Redis 和 Java 服务仅监听环回。7 天 SSH 日志脱敏计数为约 147,844 次失败、36 次成功（当时）；确有持续口令尝试。其后新增管理员登录会增加成功计数。最近 7 天有 5 次 root 密码成功登录，其中 2 次是本次操作，3 次发生在 9 月 24 日 01:27–01:31 UTC，来源与本次不同；需由所有者核对，不能直接判定为入侵。
- 已在服务器建立 `linknux-admin` 密钥管理员，密钥只保存在操作者 Windows 用户目录 `.ssh/linknux_admin_ed25519`（不在仓库），账号口令锁定、sudo 经密钥会话可用。在独立连接验证之后，安装 `/etc/ssh/sshd_config.d/00-linknux-ssh-hardening.conf`，`sshd -t` 通过且有效设置为 `PermitRootLogin no`、`PasswordAuthentication no`、`PubkeyAuthentication yes`。reload 后独立密钥登录及 sudo 正常，root 密码 SSH 被拒。设置并验证过定时回滚，成功后停止了回滚定时器。
- 已备份并安装仓库的 Nginx 配置，`nginx -t` 通过。首页 200、API 根路径 401、API actuator 403，两个域名的隐藏文件及 `wp-login.php` 404，首页返回短期 HSTS、nosniff、DENY frame 和 Referrer-Policy。站点配置备份在 `/etc/nginx/sites-available/linknux.pre-security-20260926`，并已停止定时回滚。
- 已安装 Fail2ban 1.0.2，启用 `sshd` 与 `linknux-probes` jail（10 分钟 5 次、封禁 1 小时）。自定义探测过滤器的合成测试中 3 行有 2 行命中、普通 404 不命中；Nginx 当前日志 167 行中 6 行命中。受控测试 IP 封禁在 nftables 集合中出现，随后已解封。**自动封禁及邮件告警均已启用并验收。**
- 所有者已通过 `deploy/security/configure-qq-alert-mail.ps1` 在本机交互配置 QQ SMTP，并确认收到独立 SMTP 测试邮件和受控 `[Fail2Ban] linknux-probes BAN` 邮件。2026-09-26 09:31 UTC 只读复核：`ssh`、`nginx`、`fail2ban`、`ufw` 均为 active，`fail2ban-client -t` 通过；`sshd` 与 `linknux-probes` 运行时动作均为 `nftables, sendmail-whois`。SMTP 配置、授权码文件及 Fail2ban 邮件配置均为 root:root、0600；未读取或输出授权码。受控测试 IP 已解封，root SSH 和密码 SSH 仍关闭。
- 服务器提示有待安装更新且需要重启；**本次没有重启、没有变更应用数据或业务接口**。已将聊天中暴露的 root 密码轮换为随机值，并验证账号密码状态为 P、root SSH 仍禁用；加密恢复副本仅保存在本机 Windows 用户目录 `.ssh/linknux_root_recovery.dpapi`，由当前 Windows 用户的 DPAPI 保护，未写入仓库。请将恢复材料转存可信密码管理器，并核对 9 月 24 日的三次登录；不要在聊天中发送密码。

- 2026-09-26 邮件首次配置测试收到 QQ SMTP `535 Login fail`。只读格式检查：发件人与认证用户名一致，主机/端口/STARTTLS 为 `smtp.qq.com:587`/on；服务器上保存的输入仅 4 字符，不像可用的客户端授权码。测试未启用 Fail2ban 邮件动作；已删除服务器上这次无效的 `/etc/msmtprc` 和私有 SMTP 密码文件，自动封禁仍 active。脚本现会提前拒绝短输入，并在邮件测试失败或投递未确认时清理未验证凭据。不要连续尝试登录；从 QQ 邮箱设置里获取实际 SMTP 授权码，短信验证码只用于生成它。

- 2026-09-26 第二次邮件测试：独立 SMTP 测试邮件已被使用者确认，但受控封禁邮件未收到，使用者正确输入 NO，脚本回滚邮件动作并删除 SMTP 私有配置。核查发现 `/etc/fail2ban/jail.d/linknux.local` 的 `action = %(action_)s` 覆盖了另一份 `[DEFAULT]` 邮件动作；运行时只有 nftables。已从基础 jail 删除该覆盖，备份原文件为 `.pre-mailfix-20260926`，测试 `fail2ban-client -t` 与 reload，当前保持 ban-only。临时 dummy 收件地址的静态 `fail2ban-client -d` 检查显示两个 jail 都会加载 sendmail-whois（4 处配置输出），临时文件随后删除。脚本现对启用后的两个 jail 都检查 sendmail-whois 动作；此处是当时的失败记录，后续已用真实收件箱完成投递验收。

- 2026-09-26 第三次交互：SMTP 测试邮件已确认，但脚本在邮件动作检查时再次回滚。已复现 `systemctl reload fail2ban` 不会为已运行 jail 增加新 action，静态配置虽正确，运行时仍只有 nftables；改为 `fail2ban-client reload --restart` 后，虚构地址的受控检查显示 sshd/linknux-probes 均加载 `nftables, sendmail-whois`，移除配置后再 `reload --restart` 恢复为仅 nftables。脚本已采用该命令并保留具体失败原因；此处为修复前记录，真实封禁邮件后续已验收。Fail2ban 自带 sendmail-whois 除 BAN 外还可能发送 jail 启停通知，不能把启停邮件当作封禁邮件。

## 后续维护

1. 邮件告警已验收；按 IP 在 10 分钟内 5 次命中阈值后封禁 1 小时并寄信。Fail2ban 可能另发 jail 启停通知；不是每个扫描请求都发，低频扫描和高流量 DDoS 不保证覆盖。不要将授权码输入聊天或提交至 Git。
2. 核对 9 月 24 日三次非本次 root 密码成功登录；维护窗口安装待更新安全包并重启，重启后复测密钥登录、Nginx、Fail2ban、UFW 和邮件投递；保留主机商带外恢复与异地备份，并验证备份可恢复。

---
# Linknux 生产安全排查与分阶段加固（2026-09-26）

## 变更前只读发现（历史）

- Ubuntu 24.04；Nginx、SSH、UFW、自动安全更新服务处于 active/enabled。公网监听 22/80/443；MySQL、Redis、Java 8089 仅监听环回地址。**尚未以管理员身份确认 UFW 规则、有效 SSH 配置、更新待重启状态或完整认证日志**。
- 可读的 SSH 配置行包含 `PermitRootLogin yes` 与 `PasswordAuthentication yes`。曾看到 `PubkeyAuthentication` 的多处设置；没有 sudo 权限，`sshd -T` 未能确认最终有效值。用户已将 root 密码发在对话中，**必须立即经主机商控制台或其他可信通道轮换**，不要在仓库、工单或聊天中重复发送新密码。
- 公开入口首页未返回 HSTS、nosniff、X-Frame-Options 或 Referrer-Policy 头；API 的应用响应已有部分头。前端 `/`、`/.env`、`/.git/config` 在服务器本机的 HEAD 均得到 `200 text/html`、`Content-Length: 1102`，高度符合 SPA fallback；**未读取隐藏路径内容，不能据此宣称配置泄露**；`/assets/.env` 为 404，API `/actuator/health` 为 403。对隐藏路径返回 200 不利于探测识别和告警。
- `api-deploy` 仅可无密码运行受限的发布命令，不能安装包或修改系统配置；现有 `deploy-production.ps1` 也不发布 Nginx、SSH、Fail2ban 配置。**这是变更前状态；实施结果以文首为准。**
- 未获权限读取完整 SSH/Nginx 安全日志，不对当前是否存在入侵作结论。扫描、登录失败、成功登录、提权、未知进程、出站连接需由管理员结合保留日志核查；不要把包含 Cookie、令牌或 URL 查询的日志发到聊天或邮件。

## 变更前上线计划（历史）

1. **立即处置泄露密码：**通过控制台轮换 root 密码，检查 root 最近成功登录、`authorized_keys`、sudo 用户和计划任务是否有未知变更。建立独立的具名 sudo 管理员与密钥；保持主机商带外控制台可用，在另一 SSH 会话验证新账号可登录与 sudo，才考虑关闭密码或 root SSH 登录。勿复用本次已公开密码。
2. **SSH/UFW：**先备份当前 SSH 配置并审查 `sudo ufw status verbose` 与现有允许规则，确保当前管理网络的 SSH 和 80/443 可达。将 `00-linknux-ssh-hardening.conf.example` 作为受控变更安装至 `/etc/ssh/sshd_config.d/`，运行 `sudo sshd -t`、`sudo sshd -T -C user=root,host=localhost,addr=127.0.0.1` 核对实际 root/password/pubkey 设置，**reload 而不是直接终止现有会话**；从第二个终端验证具名密钥登录，失败则借带外控制台回滚。不要在未验证可恢复通道前锁定 SSH。
3. **Nginx：**仓库 `deploy/nginx/linknux.conf` 增加了前端响应头、短期 HSTS（300 秒）、隐藏文件及常见探测路径的 404。由运维比较服务器有效配置与仓库示例，备份后合并，执行 `sudo nginx -t` 再 `sudo systemctl reload nginx`。验证首页及 `/market`、`/services`、真实 `/assets/` 仍 200，匿名 API 正常，隐藏文件和探测路径 404，`/actuator/` 403，证书更新路径正常；异常立即回滚。不要未经确认将 HSTS 扩展到子域或 preload，不要贸然加会破坏支付/OAuth 的 CSP。注意现有应用发布流程不会安装此配置。
4. **邮件与防暴力扫描告警：**提供真实告警收件邮箱和**专用** SMTP 凭据，由管理员配置 `msmtp-mta`（兼容 sendmail）与 `fail2ban`。只在服务器私有目录（root:root, 0600）保存 SMTP 密码，`msmtprc.example` 和 `linknux-jail.local.example` 中的占位符必须在安装时替换；不要把凭据提交仓库。先单独测试邮件发送及投递，再检查发行版安装的 `action_mw`、`nginx-botsearch` 滤器及日志路径是否存在，运行 `fail2ban-client -t`，启用 SSH 与 Nginx bot 探测 jail。默认 10 分钟内 5 次触发禁用 1 小时并在封禁事件寄信（另有 jail 启停通知），不是每个扫描请求都寄信；邮件不附原始日志或 URL。测试 jail 统计、模拟测试 IP 封禁/解禁和邮件投递后持续观察误报。现有 UFW 规则是否与 Fail2ban 默认封禁动作兼容必须由运维核验。
5. **调查与运维：**记录基线和变更时间，检查最近成功/失败 SSH 登录、异常 sudo、系统更新、Web 4xx/5xx 异常峰值和出站连接；设定日志保留、异地备份以及恢复演练。对高流量 DDoS 或所有扫描不承诺 Fail2ban 足够，必要时使用主机商入口防护/WAF 并单独验证。

## 原定验收标准

在两个独立终端确认具名密钥 SSH 可用、root/密码 SSH 被拒；UFW 未误阻断 SSH/网站；HTTPS 页面与 API 功能正常，隐藏路径 404，预期安全头存在；由一次受控测试触发封禁并收到一封邮件，解除测试封禁且没有误封正常用户；保留回滚路径和记录。**此段为原定标准；自动封禁与邮件送达已验收，实施结果以文首为准。**
