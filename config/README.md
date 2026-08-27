# 统一配置目录

项目运行时需要修改的配置集中在本目录：

| 文件 | 用途 | 是否提交 Git |
| --- | --- | --- |
| `application-local.example.yaml` | 后端完整配置模板 | 是 |
| `application-local.yaml` | 后端本地/私有配置 | 否 |
| `.env.example` | 前端完整配置模板 | 是 |
| `.env.local` | 前端本地配置 | 否 |

首次运行：

```powershell
Copy-Item config/application-local.example.yaml config/application-local.yaml
Copy-Item config/.env.example config/.env.local
```

后端会从项目根目录自动加载 `config/application-local.yaml`，前端 Vite 会自动加载 `config/.env.local`。无需再指定 `local` Profile，也无需为了本地开发逐项设置环境变量。

Spring Boot 的环境变量优先级高于本地 YAML，因此生产环境仍可使用容器 Secret、KMS/Vault 注入或环境变量覆盖。不要提交两个私有配置文件。

## 密钥注意事项

- 好易智算 Key：`application-local.yaml` 的 `haoee.api-key`。
- NVIDIA Key：`application-local.yaml` 的 `nvidia.key`；旧的 `nvida.key` 在兼容期内仍可读取。
- `haoee.verify-on-startup` / `nvidia.verify-on-startup` 只控制启动后的低成本模型验证，不控制目录是否展示。模型广场始终可查看全量目录，只有 `AVAILABLE` 状态能授权或调用。
- 渠道密钥加密主密钥：`security.data-encryption-key`，必须是 Base64 编码的 32 字节随机值。
- JWT 签名密钥：`security.jwt.secret`，至少 32 个字符。
- `data-encryption-key` 一旦用于加密渠道 Key，必须稳定保存；直接更换会导致历史密文无法解密，应通过专门的轮换流程迁移。
- 仓库曾出现过的任何真实 Key 都应在供应商控制台轮换，仅从文件删除不能使旧 Key 失效。

可用 OpenSSL 生成加密主密钥：

```powershell
openssl rand -base64 32
```
