# 灵感工坊（AI 创作平台）

访问地址：`/studio`

平台目前提供文生视频、图生视频、首尾帧控制、故事分镜、视频续写入口、提示词助手、模板和个人作品任务库。生成任务由后端创建并轮询，浏览器不会得到供应商 API Key。

## 用户自带模型（BYOK）

登录用户可在 `/studio` 右上角打开“模型设置”，保存多组自己的模型连接。每组连接包含：

- 连接名称
- Seedance / 火山方舟兼容接口 Base URL
- API Key
- 一个或多个模型 ID
- 默认模型和启用状态

用户提交任务时，可在“API 来源”中选择平台默认连接或自己的连接，再选择该连接下的模型。任务会记录连接 ID，后续进度查询继续使用同一连接。

API Key 使用 `security.data-encryption-key` 对应的 AES-256-GCM 主密钥加密后落库，接口只返回掩码。服务端必须先配置该主密钥，否则会拒绝保存用户 Key：

```yaml
security:
  # Base64 编码的随机 32 字节密钥；生产环境应从环境变量或密钥管理服务注入。
  data-encryption-key: ${DATA_ENCRYPTION_KEY}
```

连接测试调用只读任务列表接口，不创建视频任务。Base URL 只允许公网 HTTPS 地址；本地开发如确实需要连接局域网中转站，可显式设置 `gateway.allow-private-upstreams: true`。

## 启用 Seedance

在本机私有文件 `config/application-local.yaml` 中加入（不要提交 API Key）：

```yaml
creative:
  seedance:
    enabled: true
    base-url: https://ark.cn-beijing.volces.com
    api-key: ${SEEDANCE_API_KEY}
    default-model: doubao-seedance-1-5-pro-251215
    request-timeout-seconds: 45
```

然后在启动后端的 PowerShell 中设置 Key：

```powershell
$env:SEEDANCE_API_KEY = "你的方舟 API Key"
```

也可以直接在 `application-local.yaml` 设置 `api-key`，但该文件必须保留在 `.gitignore` 中。默认调用的是火山方舟兼容接口：

- 创建任务：`POST /api/v3/contents/generations/tasks`
- 查询任务：`GET /api/v3/contents/generations/tasks/{taskId}`

## 接入中转站

如果后续使用的 API 中转站兼容火山方舟的 Seedance 协议，只需在“模型设置”中填写中转站域名、Key 和模型 ID。Base URL 支持以下形式，任务路径会自动规范化：

- `https://ark.cn-beijing.volces.com`
- `https://relay.example.com/api/v3`
- `https://relay.example.com/api/v3/contents/generations/tasks`

其他协议的供应商应实现 `CreativeVideoProvider` 接口；任务持久化、轮询、作品库和创作界面可以继续复用，无需重写。

## 素材与合规

目前首帧、尾帧和参考素材采用可访问的 HTTPS 图片链接，或方舟已授权的 `asset://` URI。平台会在对象存储接入后启用上传入口。请仅提交拥有肖像、版权和品牌使用授权的素材。
