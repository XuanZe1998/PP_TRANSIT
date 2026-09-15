# 验证码邮件配置

当前本地环境通过 **Resend SMTP**（`smtp.resend.com:587`）发送验证码邮件，发件人为 `Linknux <linknux@linknux.com>`。SMTP 密码只保存在本地或生产私有配置中，不应提交到仓库。

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
| `VERIFICATION_EMAIL_FROM` | 无 | 发件地址，必须是 Resend 中已验证的域名 |
| `VERIFICATION_EMAIL_SUBJECT` | `Linknux 安全验证码` | 邮件主题 |
| `VERIFICATION_EMAIL_BRAND_NAME` | `Linknux` | 品牌名 |
| `VERIFICATION_EMAIL_WEBSITE_URL` | `https://linknux.com` | 邮件底部官网链接 |
| `VERIFICATION_EMAIL_SUPPORT_EMAIL` | `support@linknux.com` | 客服联系方式 |

SMTP 连接本身仍由 `SMTP_HOST`、`SMTP_PORT`、`SMTP_USERNAME`、`SMTP_PASSWORD` 配置。
