# 本地生产标准测试指南

项目默认从外部 YAML 读取秘密。不要把密码、OAuth Secret、JWT Secret 或渠道 API Key 写进 Git。

## 1. 外部 YAML 必填项

建议文件：`C:/Users/18746/.api-transit/application-local.yaml`

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/api_transit?createDatabaseIfNotExist=true&useSSL=false&serverTimezone=UTC
    username: root
    password: "<你的本地 MySQL 密码>"

security:
  data-encryption-key: "<Base64 编码的 32 字节随机值>"
  jwt:
    secret: "<至少 32 字节的独立随机值>"
  bootstrap-admin:
    username: "<首次启动管理员用户名>"
    password: "<14-72 字节强密码>"
  cors:
    allowed-origins: http://localhost:5173,http://127.0.0.1:5173

oauth:
  google:
    client-id: "<Google Client ID>"
    client-secret: "<Google Client Secret>"
    redirect-uri: http://localhost:5173/oauth/callback/google
  github:
    client-id: "<GitHub Client ID>"
    client-secret: "<GitHub Client Secret>"
    redirect-uri: http://localhost:5173/oauth/callback/github

features:
  shopgpt:
    enabled: false
```

生成渠道加密主密钥（PowerShell）：

```powershell
$bytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
[Convert]::ToBase64String($bytes)
```

JWT Secret 必须另外生成，不要复用渠道加密主密钥。管理员首次创建成功后，应从 YAML 删除 `bootstrap-admin.password`；数据库只保留 BCrypt 哈希。

## 2. Google Cloud 配置

- 应用类型选择“Web 应用”。
- Authorized JavaScript origins 添加 `http://localhost:5173`。
- Authorized redirect URIs 添加 `http://localhost:5173/oauth/callback/google`，必须与 YAML 完全一致。
- “目标对象”处于测试模式时，把你的 Google 账号加入测试用户。
- 品牌名称、支持邮箱和开发者联系邮箱必须填写；本地测试不需要发布应用。

GitHub OAuth App 的 callback URL 对应填写 `http://localhost:5173/oauth/callback/github`。

## 3. 启动

后端：

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.arguments=--spring.config.additional-location=file:C:/Users/18746/.api-transit/application-local.yaml'
```

前端：

```powershell
Set-Location web
npm ci
npm run dev
```

访问 `http://localhost:5173`。后端默认端口为 `8089`。

## 4. 首次验收顺序

1. 检查 `GET http://localhost:8089/actuator/health` 返回 `UP`。
2. 注册本地账号，注销后确认旧 Token 返回 401，再重新登录。
3. 测试 Google/GitHub 登录，确认 state 错误或重复回调会被拒绝。
4. 管理员新增渠道；若缺少 `data-encryption-key`，系统应返回 503 而不是明文保存。
5. 执行真实渠道探测，成功后渠道才进入 `HEALTHY` 并出现在公开模型目录。
6. 创建模型映射并填写输入、输出、缓存的每百万 Token 售价和成本。
7. 创建 API Key，立即保存一次性 secret；刷新列表后只应看到预览。
8. 用 API Key 调用 `/v1/chat/completions`，核对请求日志、Token、余额与金额明细。
9. Plus/ShopGPT 在没有真实支付和商户资料时保持关闭；不要用伪订单验证“支付成功”。

## 5. 仍需你提供的信息

- Google/GitHub OAuth Client ID 与 Client Secret。
- MySQL 连接信息、独立 JWT Secret、独立 AES-256 主密钥。
- 至少一个真实 Provider 的 HTTPS Base URL、API Key、模型名、成本价和销售价。
- 若启用 Plus 收据：商户法定名称、地址、联系邮箱、注册号和时区。
- 若启用 ShopGPT：授权确认、HTTPS Base URL、真实商品 ID 和业务合规结论。
- 上线前还需要支付服务、域名/TLS、KMS、Redis、监控告警和备份策略。
