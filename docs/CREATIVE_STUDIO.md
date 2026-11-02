# 灵感工坊（AI 创作平台）

访问地址：`/studio`

## 小云雀营销成片

在左侧选择“小云雀营销成片”，登录后保存自己的 **Access Key**。密钥使用已有 AES-256-GCM 加密机制落库，前端仅获知是否已配置，不回显原文。服务端必须配置 `security.data-encryption-key`。升级时执行 Flyway `V38__xiaoyunque_marketing.sql`。

已接入素材上传、营销任务提交、结果查询、视频/图片预览与下载入口、积分查询，以及使用原会话继续创作。生成参数包括模型、比例、时长区间、分辨率与字幕。模型标识由用户填写，保留大小写，不自动替换模型或回退；权限和积分由小云雀判断。单文件上限5MB，与现有 Spring 和 Nginx 上传限制一致。

用户作品记录与媒体结果均使用服务端分页，默认10，可选10/20/50/100，“全部”仅在不超过200条时允许。任务保存提交时的加密密钥快照，更换或移除配置后仍可查询原任务。移除配置不撤销供应商密钥，也不删除已提交任务的快照。提交失败或超时标记为待确认，不自动重提。等待人工确认、补充输入或未知状态时停止自动查询，由用户在小云雀处理；旧 Run 不会被假定为最新 Run。

协议依据：[小云雀官方 CLI 营销接口契约](https://github.com/Pippit-dev/cli/blob/main/skills/xyq-marketing-skill/references/api.md)及其鉴权实现。该契约引用用户提供的飞书指南；本次飞书正文读取失败，尚不能确认原文全部能力已覆盖。这里接入的是已核实的公开营销 API，未将 CLI 的短剧、Canvas、普通图片/视频生成和视频后处理能力视为本指南内容。

接口前缀 `/creative/xiaoyunque`：`GET/PUT/DELETE /settings`、`POST /balance`、`POST /assets`、`POST/GET /runs`、`POST /runs/{id}/refresh`、`GET /runs/{id}/media`。全部要求登录且按当前用户隔离。素材必须由当前用户、当前密钥上传；鉴权只发送到固定官方域名，不接受用户提供的任意上游 URL。

平台目前提供文生视频、图生视频、首尾帧控制、故事分镜、视频续写入口、提示词助手、模板和个人作品任务库。生成任务由后端创建并轮询，浏览器不会得到供应商 API Key。

## TXT 自动成片

启用后，`/studio` 会出现“TXT 自动成片”：上传 UTF-8 TXT 或粘贴原文，依次确认结构化剧本、角色/场景画像和视频分镜，最后由 FFmpeg 合成为完整 MP4。首期支持 30–90 秒、最多 12 个镜头。

```yaml
creative:
  auto-movie:
    enabled: true
    public-base-url: https://你的公网域名
    ffmpeg-path: ffmpeg
    text:
      base-url: https://api.openai.com
      api-key: ${AUTO_MOVIE_TEXT_API_KEY}
      default-model: gpt-4.1-mini
    image:
      base-url: https://api.openai.com
      api-key: ${AUTO_MOVIE_IMAGE_API_KEY}
      default-model: gpt-image-1
```

生产环境的 `public-base-url` 必须能从视频供应商侧访问，否则本地保存的角色/场景图无法作为 Seedance 参考图。服务器还需要安装 FFmpeg 并保证配置路径可执行。用户也可以在“模型设置”中创建 `openai-chat`、`openai-image` 和 `seedance` 三类自有连接；自有连接不扣平台余额。

项目接口位于 `/creative/projects/**`，任务使用数据库租约队列，可在服务重启后继续。平台默认接口按 `creative.auto-movie.price` 的整数金额单位进行阶段预冻结与成功结果结算。

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

连接测试调用只读任务列表接口，不创建视频任务。Base URL 只允许公网 HTTPS 地址；本地开发如确实需要连接局域网兼容服务，可显式设置 `gateway.allow-private-upstreams: true`。

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

## 接入兼容服务

如果后续使用的授权服务兼容火山方舟的 Seedance 协议，只需在“模型设置”中填写服务域名、Key 和模型 ID。Base URL 支持以下形式，任务路径会自动规范化：

- `https://ark.cn-beijing.volces.com`
- `https://relay.example.com/api/v3`
- `https://relay.example.com/api/v3/contents/generations/tasks`

其他协议的供应商应实现 `CreativeVideoProvider` 接口；任务持久化、轮询、作品库和创作界面可以继续复用，无需重写。

## 素材与合规

目前首帧、尾帧和参考素材采用可访问的 HTTPS 图片链接，或方舟已授权的 `asset://` URI。平台会在对象存储接入后启用上传入口。请仅提交拥有肖像、版权和品牌使用授权的素材。
