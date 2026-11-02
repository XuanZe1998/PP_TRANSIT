# 验证码邮件配置

当前本地环境和 server-207 生产环境通过 **Resend SMTP**（`smtp.resend.com:587`）发送验证码邮件，发件人为 `Linknux <linknux@linknux.com>`。SMTP 密码只保存在本地或生产私有配置中，不应提交到仓库。

## 修改邮件外观和文案

直接编辑 `src/main/resources/templates/verification-email.html`。模板使用内联 CSS，以兼容常见邮箱客户端。可使用的占位符：

- `{{brandName}}`：品牌名
- `{{verificationCode}}`：6 位验证码
- `{{websiteUrl}}`：官网地址
- `{{supportEmail}}`：支持邮箱

修改模板后需要重新构建或重启后端。邮件同时包含纯文本备选内容，在不支持 HTML 的邮箱中仍可阅读。

## 可配置项

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| `VERIFICATION_EMAIL_SENDER_ADDRESS` | `linknux@linknux.com` | 发件地址，必须是 Resend 中已验证的域名 |
| `VERIFICATION_EMAIL_SUBJECT` | `Linknux 安全验证码` | 邮件主题 |
| `VERIFICATION_EMAIL_BRAND_NAME` | `Linknux` | 品牌名 |
| `VERIFICATION_EMAIL_WEBSITE_URL` | `https://linknux.com` | 邮件底部官网链接 |
| `VERIFICATION_EMAIL_SUPPORT_EMAIL` | `support@linknux.com` | 客服联系方式 |

SMTP 连接本身仍由 `SMTP_HOST`、`SMTP_PORT`、`SMTP_USERNAME`、`SMTP_PASSWORD` 配置。

## 注册通道配置与验收

只使用邮箱验证码时，在私有配置中设置 `account.verification-mode: EMAIL_ONLY`，或设置环境变量 `ACCOUNT_VERIFICATION_MODE=EMAIL_ONLY`。企业账户仍需填写联系手机号，但不需要短信验证码。保持 `verification.debug-code-enabled: false`；不要用返回调试验证码代替真实投递。

生产私有配置位于 `/etc/api-transit/application-local.yaml`，权限为 `root:api-transit 0640`。配置 `spring.mail` 的连接、认证和 STARTTLS，以及 `verification.email.sender-address`；修改前备份，重启 `api-transit.service` 后等待 readiness 为 `UP`。

验收依次检查 SMTP TLS 与认证、`GET /auth/verification/policy` 返回 `mode=EMAIL_ONLY` 和 `registrationReady=true`，再向管理员指定的测试邮箱调用 `POST /auth/verification/email/send`（`purpose=REGISTER`）。确认接口成功、响应没有 `debugCode`，并由收件人确认实际收到邮件。管理员后台刷新后应不再提示注册通道未就绪。

只有接入真实短信适配器并完成投递验收后，才切换为 `EMAIL_AND_PHONE`。短信未配置不影响 `EMAIL_ONLY` 注册。
