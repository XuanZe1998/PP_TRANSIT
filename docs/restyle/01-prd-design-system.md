# Linknux 三端统一设计系统规范 PRD

## 1. 文档信息

| 项 | 内容 |
| --- | --- |
| 文档名称 | Linknux 三端统一设计系统规范 PRD |
| 文档版本 | v1.0 |
| 发布日期 | 2026-09-29 |
| 负责人 | 许清楚（Xu）· 产品经理 |
| 协作角色 | 高见远（架构师）· CSS 分层设计；工程师 · 落地实施；测试 · 视觉与守卫回归 |
| 适用范围 | `web/src` 全量前端样式层（三端外壳 + 共享组件），**不含**后端、路由结构、信息架构改造 |
| 原始需求 | 「品牌延续·精炼升级」——保留蓝青科技调性，把并存冲突的四套令牌体系收敛为唯一一套，重写令牌层 / 基础层 / 组件层，统一用户前端、用户控制台、管理后台三端的全部界面元素视觉，不改变模板结构与信息架构 |
| Language | 中文 |
| Programming Language | Vue 3 + TypeScript + Element Plus + Tailwind CSS（沿用现有栈，本次仅样式层） |
| Project Name | `linknux_design_system` |

---

## 2. 产品目标与问题定义

### 2.1 背景

Linknux 是面向开发者与团队的一站式 AI 能力平台（统一大模型网关 + 运营后台），前端由三套外壳组成：用户前端（公共站）、用户控制台、管理后台。三端历史上由不同阶段、不同批次开发，样式被反复以「后置补丁」的方式堆叠在 `src/style.css` 中，形成**样式熵增**：同一个视觉语义在不同页面由不同的变量、不同的具体值承担。

### 2.2 技术问题（可核查）

| 编号 | 问题 | 事实证据 | 影响 |
| --- | --- | --- | --- |
| P-1 | **四套令牌体系并存且互相打架** | `--tech-*`（`style.css` 顶部 `:root`，主色 `#1769ff`）、`--ui-*`（`style.css:6219`）、`--admin-*`（`style.css:6687`）、`--lnx-*`（`styles/tokens.css`，主色 `#2563eb`） | 同一语义（主色/正文色/背景）在不同页面解析到不同值 |
| P-2 | **两个不同的蓝同时全站生效** | `--tech-blue: #1769ff` vs `--lnx-brand-600: #2563eb` / `--admin-primary: #2563eb` | 官网与后台主色肉眼可见不一致，品牌识别度被稀释 |
| P-3 | **大量硬编码色值穿越令牌层** | `style.css` 851 个 hex；`components.css` 50 个 hex；页面 scoped 样式另约 2000 行 | 令牌改动无法全局生效，任何统一化都会被局部硬编码击穿 |
| P-4 | **历史层体积失控** | `style.css` 6984 行 / 170KB，含多层 `!important` 互相覆盖 | 可维护性差，样式冲突不可预测，回归风险高 |
| P-5 | **三端视觉割裂** | 官网卡片圆角/阴影/密度、控制台面板、后台表格各自一版 | 跨端跳转时「像三个产品」，损害专业度与信任感 |
| P-6 | **对比度不达标风险** | `--tech-surface: rgba(255,255,255,.9)` 半透明表面上叠 `--tech-muted: #647792`；`ui-*`/`admin-*` 之外的旧规则仍用浅底浅字 | 无障碍不达标，长文与次要信息不可读 |
| P-7 | **无统一焦点可见规范** | 焦点样式散落且被 `!important` 覆盖风险高 | 键盘用户无法定位当前焦点 |

### 2.3 解决方案

| 层级 | 动作 | 交付物 |
| --- | --- | --- |
| L1 令牌层 | 建立唯一令牌命名空间 `--lnx-*`，覆盖色彩/排版/间距/圆角/阴影/层级/布局，并将 `--tech-*`/`--ui-*`/`--admin-*` 全部映射或废弃 | `styles/tokens.css` 全量重写（唯一真源） |
| L2 基础层 | 语义化元素基线（`html/body`、标题、正文、链接、代码、焦点环、选中态、滚动条、容器宽度、断点） | `styles/foundation.css` 重写 |
| L3 组件层 | 用 `#app` 作用域前缀统一 Element Plus 组件与自定义容器视觉，消除页面级差异 | `styles/components.css` 重写 |
| L4 业务层 | `style.css` 清空全部 `:root` 覆盖与硬编码 hex，仅保留布局骨架规则；页面 scoped 样式中的 hex 替换为令牌引用 | `style.css` 瘦身 + 页面 scoped 清理 |

**关键工程约束（架构师必须纳入设计）**：入口加载顺序为 `style.css → tokens.css → foundation.css → components.css` 且**测试强依赖该顺序、不可调整**。因此：

1. 新令牌层必须落在 `styles/tokens.css`（在 `style.css` 之后加载，可反向覆盖 `style.css` 顶部 `:root`）。
2. 真正击穿统一化的是 `style.css` 中**具体选择器内的 851 个硬编码 hex**，它们靠选择器优先级取胜。**P0：必须定点清除或替换为令牌引用**，否则令牌层再多也是空转。
3. 变量名一旦收敛为 `--lnx-*`，`--tech-*` / `--ui-*` / `--admin-*` 的定义必须删除，仅保留一个过渡期别名段（见 4.6 映射表），并在同一迭代内清零。

### 2.4 收益与可量化目标

| 指标 | 现状 | 目标 |
| --- | --- | --- |
| 生效中的令牌体系数量 | 4 套（tech / ui / admin / lnx） | **1 套**（`--lnx-*`） |
| `style.css` 中硬编码 hex 数量 | 851 | **0**（白名单外），过渡期别名段 ≤ 30 条 |
| `components.css` 中硬编码 hex 数量 | 50 | **0**（白名单外） |
| 页面 scoped 样式中硬编码 hex | 约 2000 行含色值 | 色值全部走令牌；只保留布局数值 |
| `style.css` 行数 | 6984 行 | **≤ 1500 行**（仅布局骨架） |
| 三端同类组件视觉统一率 | 未定义（按页面各异） | **100%**（按钮/输入/表格/卡片/标签/分页/弹窗在 `#app` 下同一套规则） |
| 正文文字 WCAG AA 对比度达标率 | 存在不达标项 | **100%**（正文 ≥ 4.5:1，大字/非文本 UI ≥ 3:1） |
| 键盘焦点可见覆盖率 | 部分 | **100%** 可交互元素具备 `:focus-visible` 可见焦点环 |
| 1280 / 768 / 375 三宽度横向溢出 | 未基线化 | **0 处页面级横向滚动** |
| 品牌旧名残留 | 0（需保持） | **0** |
| 构建 | 通过 | `npm run build`（含 `vue-tsc`）**通过** |

### 2.5 明确不做（Out of Scope）

- 不引入任何动效、过渡、`transform` 动画；不做深色主题；`color-scheme: light` 固定。
- 不改变模板结构、路由表、信息架构、组件层级树。
- 不新增页面、不删除页面、不改业务逻辑与接口契约。
- 不动分页/搜索/排序的服务端契约。

---

## 3. 用户故事

| # | 角色 | 用户故事 | 验收要点 |
| --- | --- | --- | --- |
| US-1 | 访问者 / 开发者用户 | 作为一名在选型阶段访问官网的开发者，我希望从首页到定价页再到文档页的视觉语言完全一致（同一个蓝、同一套卡片圆角与阴影），以便我判断这是一个可靠、成熟的产品。 | 官网全部页面的主按钮、链接、卡片圆角/阴影、页面底色取自同一令牌；跨页截图比对无「两套蓝」 |
| US-2 | 开发者用户 | 作为一名日常使用控制台管理 Key 与用量日志的开发者，我希望控制台在 375px 手机上依旧能读到完整日志与余额，且焦点环清晰可见，以便我在通勤时也能安全操作。 | 375px 无横向溢出；`user-mobile-drawer` 抽屉可用；表格在自身滚动容器内横滚；`Tab` 键焦点环可见 |
| US-3 | 企业用户 | 作为企业账户管理员，我希望企业控制台与个人控制台的卡片、表格、状态标签风格一致，并让状态色（成功/警告/危险）有清晰的深色文字而非浅底浅字，以便快速扫描审批与告警。 | 状态标签均为「浅底 + 深字」配对且达 AA；企业控制台与个人控制台同类组件视觉一致 |
| US-4 | 平台运营管理员 | 作为每天在高密度数据表里核对订单与流水的运营管理员，我希望后台表格、工具栏、筛选区比官网更紧凑但依然易读，且所有后台页面都使用同一个工具栏，以便我在不同模块之间不需要重新学习布局。 | 8 个后台页面均含 `<AdminPageToolbar>`；表格行高/字号呈高密度基线；后台与官网的密度差有明确规范值 |
| US-5 | 平台运营管理员 | 作为管理员，我希望在 1280 宽屏上后台侧边栏与内容区不互相挤压，在 700px 以下自动切换为抽屉导航，以便在不同设备上都能完成操作。 | 980px / 700px 断点行为符合规范；`admin-mobile-drawer` 存在且可用 |

---

## 4. 设计令牌规范（核心交付物）

> 命名空间唯一：`--lnx-*`。命名规则见 4.6。**所有数值即为编码真值，架构师与工程师可直接落 `styles/tokens.css`。**

### 4.1 色彩 · 品牌色阶（蓝色，主色）

| 令牌 | 值 | 用途 |
| --- | --- | --- |
| `--lnx-color-brand-50` | `#eff6ff` | 品牌极浅底、表头底、选中行底 |
| `--lnx-color-brand-100` | `#dbeafe` | 品牌浅底、文字选中背景、Tag 底 |
| `--lnx-color-brand-200` | `#bfdbfe` | 信息态边框、浅色描边 |
| `--lnx-color-brand-300` | `#93c5fd` | 禁用态/装饰性品牌色 |
| `--lnx-color-brand-400` | `#60a5fa` | 折线图辅助线、图表第二序列 |
| `--lnx-color-brand-500` | `#3b82f6` | 图表主序列、强调描边 |
| `--lnx-color-brand-600` | `#2563eb` | **主品牌色**：主按钮、链接、选中态、`--el-color-primary` |
| `--lnx-color-brand-700` | `#1d4ed8` | 主按钮 hover / 品牌深字 |
| `--lnx-color-brand-800` | `#1e40af` | 主按钮 active、深色标题强调 |
| `--lnx-color-brand-900` | `#1e3a8a` | 极深强调（仅用于深色文字于浅底） |

### 4.2 色彩 · 辅助色阶（青色，次强调）

| 令牌 | 值 | 用途 |
| --- | --- | --- |
| `--lnx-color-cyan-50` | `#ecfeff` | 青色浅底 |
| `--lnx-color-cyan-100` | `#cffafe` | 青色 Tag 底、图表填充 |
| `--lnx-color-cyan-300` | `#67e8f9` | 图表辅助、装饰 |
| `--lnx-color-cyan-500` | `#06b6d4` | 图表第二序列 |
| `--lnx-color-cyan-600` | `#0891b2` | **辅助品牌色**：次强调图标/描边/渐变端点 |
| `--lnx-color-cyan-700` | `#0e7490` | 青色深字（于 `cyan-50` 上 5.15:1 ✅AA） |
| `--lnx-color-cyan-800` | `#155e75` | 青色极深字（于 `cyan-50` 上 6.99:1 ✅AA） |

### 4.3 色彩 · 中性色阶

| 令牌 | 值 | 用途 |
| --- | --- | --- |
| `--lnx-color-neutral-0` | `#ffffff` | 内容表面 |
| `--lnx-color-neutral-50` | `#f8fafc` | 极弱表面、表头斑马纹 |
| `--lnx-color-neutral-100` | `#f1f5f9` | 弱表面、禁用底、Tag 中性底 |
| `--lnx-color-neutral-200` | `#e2e8f0` | 细分隔线 |
| `--lnx-color-neutral-300` | `#cbd5e1` | 强分隔线、禁用描边 |
| `--lnx-color-neutral-400` | `#94a3b8` | 输入框默认描边、占位符底 |
| `--lnx-color-neutral-500` | `#64748b` | 次级中性（仅用于非文本 UI） |
| `--lnx-color-neutral-600` | `#475569` | 次级文本 |
| `--lnx-color-neutral-700` | `#334155` | 正文文本 |
| `--lnx-color-neutral-800` | `#1e293b` | 标题文本 |
| `--lnx-color-neutral-900` | `#0f172a` | 主标题文本、代码块文本 |

### 4.4 色彩 · 表面 / 背景 / 边框

| 令牌 | 值 | 用途 |
| --- | --- | --- |
| `--lnx-color-bg` | `#f4f7fb` | 三端页面底色（唯一页面底色） |
| `--lnx-color-surface` | `#ffffff` | 卡片、面板、弹窗、输入框填充 |
| `--lnx-color-surface-soft` | `#f6f9fd` | 卡片内嵌弱区、表头、工具条底 |
| `--lnx-color-surface-muted` | `#eef3f9` | 更弱的分区底、代码块底 |
| `--lnx-color-surface-brand-soft` | `#eff6ff` | 品牌倾向弱底（选中行、高亮区） |
| `--lnx-color-border-subtle` | `#e6edf6` | 卡片内分隔、表格内框线 |
| `--lnx-color-border` | `#dbe4ef` | 默认边框（卡片、面板、分隔） |
| `--lnx-color-border-strong` | `#b6c6d8` | hover 边框、强调描边 |
| `--lnx-color-border-input` | `#94a3b8` | 表单控件默认描边（非文本 UI，随焦点环共同满足可辨识性） |
| `--lnx-color-border-focus` | `#2563eb` | 焦点描边（对白 5.17:1 ✅） |
| `--lnx-color-border-disabled` | `#dbe4ef` | 禁用描边 |

> 边框属 WCAG 1.4.11 非文本对比度范畴。装饰性分隔线不设 3:1 要求；**表单控件的可辨识边界由 `--lnx-color-border-input` + 焦点环共同保证，聚焦态描边对白 ≥ 3:1**。

### 4.5 色彩 · 文本 / 交互态 / 语义状态（**含对比度数值，全部达标**）

#### 文本色阶（背景：`#ffffff` 主表面 / `#f4f7fb` 页面底）

| 令牌 | 值 | 典型背景 | 对比度 | AA |
| --- | --- | --- | --- | --- |
| `--lnx-text-primary` | `#0f172a` | `#ffffff` / `#f4f7fb` | 17.85:1 / 16.61:1 | ✅ AA |
| `--lnx-text-regular` | `#334155` | `#ffffff` / `#f4f7fb` / `#f6f9fd` | 10.35:1 / 9.64:1 / 9.81:1 | ✅ AA |
| `--lnx-text-secondary` | `#475569` | `#ffffff` / `#f4f7fb` | 7.58:1 / 7.05:1 | ✅ AA |
| `--lnx-text-muted` | `#5b6b80` | `#ffffff` / `#f4f7fb` / `#f6f9fd` | 5.44:1 / 5.06:1 / 5.15:1 | ✅ AA |
| `--lnx-text-placeholder` | `#7c8aa0` | `#ffffff` | 3.50:1 | ⚠️ 仅大字/非文本（占位符文本按 WCAG 不属正文要求，仍取 ≥3:1） |
| `--lnx-text-disabled` | `#61708a` | `#f1f5f9` | 4.57:1 | ✅ AA |
| `--lnx-text-on-brand` | `#ffffff` | `#2563eb`（`brand-600`） | 5.17:1 | ✅ AA |
| `--lnx-text-on-brand-strong` | `#ffffff` | `#1e40af`（`brand-800`） | 8.72:1 | ✅ AA |
| `--lnx-text-brand` | `#1d4ed8` | `#eff6ff`（`brand-50`） | 6.16:1 | ✅ AA |
| `--lnx-text-link` | `#2563eb` | `#ffffff` / `#f4f7fb` | 5.17:1 / 4.81:1 | ✅ AA |
| `--lnx-text-code` | `#1e293b` | `#f8fafc` | 13.98:1 | ✅ AA |

> 变更说明：`--lnx-text-muted` 由旧值 `#64748b`（对页面底仅 4.43:1，**不达标**）收紧为 `#5b6b80`（5.06:1）。

#### 交互态（选中 / 悬停 / 激活 / 禁用）

| 令牌 | 值 | 说明 |
| --- | --- | --- |
| `--lnx-state-hover-surface` | `#f6f9fd` | 行/项 hover 底 |
| `--lnx-state-selected-surface` | `#eff6ff` | 选中行/项底 |
| `--lnx-state-selected-border` | `#bfdbfe` | 选中项描边 |
| `--lnx-state-selected-text` | `#1d4ed8` | 选中项文字（于 `#eff6ff` 上 6.16:1 ✅AA） |
| `--lnx-state-active-surface` | `#dbeafe` | 按下底 |
| `--lnx-state-disabled-surface` | `#f1f5f9` | 禁用底 |
| `--lnx-state-disabled-text` | `#61708a` | 禁用文字（`#f1f5f9` 上 4.57:1 ✅AA） |
| `--lnx-state-hover-overlay` | `rgba(37, 99, 235, .06)` | 图标按钮 hover 叠色层 |

#### 语义状态色（**必须「深字 + 浅底」成对使用，禁止浅底浅字 / 深底深字**）

| 语义 | 前景（text/border） | 背景（surface） | 对比度 | AA | 实心按钮用色（白字） | 白字对比度 | AA |
| --- | --- | --- | --- | --- | --- | --- | --- |
| success 成功 | `--lnx-status-success-text: #047857` | `--lnx-status-success-surface: #ecfdf5` | 5.21:1 | ✅ | `--lnx-status-success-solid: #047857` | 5.48:1 | ✅ |
| warning 警告 | `--lnx-status-warning-text: #b45309` | `--lnx-status-warning-surface: #fffbeb` | 4.84:1 | ✅ | `--lnx-status-warning-solid: #b45309` | 5.02:1 | ✅ |
| danger 危险 | `--lnx-status-danger-text: #b91c1c` | `--lnx-status-danger-surface: #fef2f2` | 5.91:1 | ✅ | `--lnx-status-danger-solid: #b91c1c` | 6.47:1 | ✅ |
| info 信息 | `--lnx-status-info-text: #1d4ed8` | `--lnx-status-info-surface: #eff6ff` | 6.16:1 | ✅ | `--lnx-status-info-solid: #1d4ed8` | 6.70:1 | ✅ |

语义态边框令牌：`--lnx-status-{semantic}-border` = success `#a7f3d0` / warning `#fde68a` / danger `#fecaca` / info `#bfdbfe`（纯装饰，无对比度要求）。

> 决策依据：原 `--ui-success-text: #09633f` 等值虽达标，但四套体系下被局部硬编码击穿；本规范统一收敛为上表 4 组，并在 `el-alert` / `el-tag` / `el-message` / `el-badge` 全部强制「浅底深字」。

#### 品牌渐变（保留品牌调性，收敛为两条）

| 令牌 | 值 | 用途 |
| --- | --- | --- |
| `--lnx-gradient-brand` | `linear-gradient(135deg, #2563eb 0%, #1d4ed8 60%, #0e7490 100%)` | 主按钮、Hero 品牌面 |
| `--lnx-gradient-page` | `radial-gradient(circle at 8% 0%, rgba(37,99,235,.06), transparent 32%), radial-gradient(circle at 94% 8%, rgba(8,145,178,.05), transparent 30%)` | 页面底噪（比现状更克制） |

> 主按钮不再叠加渐变 + 重投影；渐变仅用于 Hero 与主按钮，浮层一律用纯色。

### 4.6 命名规范与旧体系映射

**新命名规则**：`--lnx-{类别}-{语义}[-{档位|状态}]`

| 类别 | 前缀 | 示例 |
| --- | --- | --- |
| 颜色 | `--lnx-color-*` | `--lnx-color-brand-600`、`--lnx-color-neutral-200` |
| 文本 | `--lnx-text-*` | `--lnx-text-primary`、`--lnx-text-muted` |
| 表面/边框 | `--lnx-color-surface-*` / `--lnx-color-border-*` | `--lnx-color-surface-soft` |
| 语义状态 | `--lnx-status-{semantic}-{text\|surface\|border\|solid}` | `--lnx-status-danger-text` |
| 交互态 | `--lnx-state-{hover\|selected\|active\|disabled}-*` | `--lnx-state-selected-surface` |
| 排版 | `--lnx-font-*` / `--lnx-text-size-*` / `--lnx-font-weight-*` / `--lnx-leading-*` / `--lnx-tracking-*` | `--lnx-text-size-14` |
| 间距 | `--lnx-space-*` | `--lnx-space-4` |
| 圆角 | `--lnx-radius-*` | `--lnx-radius-md` |
| 阴影 | `--lnx-shadow-*` | `--lnx-shadow-md` |
| 层级 | `--lnx-z-*` | `--lnx-z-drawer` |
| 布局 | `--lnx-layout-*` | `--lnx-layout-admin-sidebar` |

**旧四套前缀 → 新令牌映射表（过渡期别名段，一个迭代内清零）**

| 旧令牌 | 旧值 | 新令牌 | 新值 | 处理 |
| --- | --- | --- | --- | --- |
| `--tech-blue` | `#1769ff` | `--lnx-color-brand-600` | `#2563eb` | **改值统一**（核心修复 P-2） |
| `--tech-cyan` | `#00a9cc` | `--lnx-color-cyan-600` | `#0891b2` | 改值统一 |
| `--tech-indigo` | `#6559f5` | `--lnx-color-brand-600` | `#2563eb` | **废弃** |
| `--tech-bg` | `#f2f7ff` | `--lnx-color-bg` | `#f4f7fb` | 改值统一 |
| `--tech-surface` | `rgba(255,255,255,.9)` | `--lnx-color-surface` | `#ffffff` | **去半透明**（消除对比度风险 P-6） |
| `--tech-line` / `--tech-line-strong` | `#d7e4f7` / `#b9cff0` | `--lnx-color-border` / `--lnx-color-border-strong` | `#dbe4ef` / `#b6c6d8` | 映射 |
| `--tech-text` | `#102541` | `--lnx-text-primary` | `#0f172a` | 映射 |
| `--tech-muted` | `#647792` | `--lnx-text-secondary` | `#475569` | 映射（提升对比度） |
| `--tech-glow` | `0 16px 45px rgba(23,105,255,.09)` | `--lnx-shadow-sm` | 见 4.9 | **废弃重投影** |
| `--ui-text` | `#18314f` | `--lnx-text-primary` | `#0f172a` | 映射 |
| `--ui-muted` / `--ui-subtle` | `#52657e` / `#5f7087` | `--lnx-text-secondary` / `--lnx-text-muted` | `#475569` / `#5b6b80` | 合并为一级 |
| `--ui-primary-surface` | `#e2efff` | `--lnx-color-brand-50` | `#eff6ff` | 映射 |
| `--ui-primary-text` | `#0d4f9f` | `--lnx-text-brand` | `#1d4ed8` | 映射 |
| `--ui-success/ warning/ danger-*` | `#e8f8f1`/`#09633f` 等 | `--lnx-status-{semantic}-{surface\|text}` | 见 4.5 | 映射为统一 4 组 |
| `--admin-bg` | `#f3f7fc` | `--lnx-color-bg` | `#f4f7fb` | 与前端共用同一底色 |
| `--admin-surface` / `--admin-surface-soft` | `#ffffff` / `#f7faff` | `--lnx-color-surface` / `--lnx-color-surface-soft` | `#ffffff` / `#f6f9fd` | 映射 |
| `--admin-line` / `--admin-line-strong` | `#d8e3f0` / `#bfd0e4` | `--lnx-color-border` / `--lnx-color-border-strong` | `#dbe4ef` / `#b6c6d8` | 映射 |
| `--admin-text` / `--admin-muted` | `#18314f` / `#52657e` | `--lnx-text-primary` / `--lnx-text-secondary` | `#0f172a` / `#475569` | 映射 |
| `--admin-primary` / `--admin-primary-hover` | `#2563eb` / `#1d4ed8` | `--lnx-color-brand-600` / `--lnx-color-brand-700` | 同值 | 保留值、改名 |
| `--lnx-brand-600` … | `#2563eb` … | `--lnx-color-brand-*` | 同名新前缀 | 重命名 |
| `--lnx-bg` / `--lnx-surface` / `--lnx-line` / `--lnx-text*` | — | 见上 | — | 重命名 |
| `--lnx-success` / `--lnx-warning` … | `#08734f` … | `--lnx-status-*-text` | 见 4.5 | 重命名 + 校准 |
| `--lnx-radius-control` | `10px` | `--lnx-radius-md` | `8px` | **收紧** |
| `--lnx-radius-card` | `14px` | `--lnx-radius-lg` | `12px` | **收紧** |
| `--lnx-shadow-card` / `-hover` / `-overlay` | — | `--lnx-shadow-sm` / `-md` / `-lg` | 见 4.9 | 收紧重投影 |
| `--lnx-focus-ring` | `rgba(37,99,235,.16)` | `--lnx-shadow-focus` | `rgba(37,99,235,.22)` | 提高可见度 |
| `--lnx-content-width` | `1248px` | `--lnx-layout-content` | `1248px` | 重命名 |
| `--lnx-user-sidebar` / `--lnx-admin-sidebar` | `248px` / `256px` | `--lnx-layout-user-sidebar` / `--lnx-layout-admin-sidebar` | 同值 | 重命名 |

### 4.7 排版

| 令牌 | 值 | 说明 |
| --- | --- | --- |
| `--lnx-font-sans` | `Inter, "Noto Sans SC", "PingFang SC", "Microsoft YaHei", system-ui, sans-serif` | 全站唯一无衬线栈（去掉 `Segoe UI` 以统一 Windows/Mac 观感） |
| `--lnx-font-mono` | `"JetBrains Mono", "Cascadia Code", "SFMono-Regular", Consolas, monospace` | 代码块 / API Key / 请求 ID |

**字号阶梯（收敛为 9 档，禁止页面自定义字号）**

| 令牌 | 值 | 用途 |
| --- | --- | --- |
| `--lnx-text-size-12` | `12px` | 表头、Tag、徽标、时间戳、脚注 |
| `--lnx-text-size-13` | `13px` | 表格单元格、辅助说明、面包屑、表单错误 |
| `--lnx-text-size-14` | `14px` | **默认正文**、表单控件、按钮、列表项 |
| `--lnx-text-size-16` | `16px` | 强调正文、卡片标题 |
| `--lnx-text-size-18` | `18px` | 区块标题（h3） |
| `--lnx-text-size-20` | `20px` | 页面副标题（h2） |
| `--lnx-text-size-24` | `24px` | 后台页面标题（h1，`AdminPageToolbar` 现行值，保留） |
| `--lnx-text-size-30` | `30px` | 官网次级标题（h2） |
| `--lnx-text-size-38` | `38px` | 官网 Hero 主标题（h1） |

**字重阶梯**

| 令牌 | 值 | 用途 |
| --- | --- | --- |
| `--lnx-font-weight-regular` | `400` | 正文、表格单元格 |
| `--lnx-font-weight-medium` | `500` | 表单标签、菜单项、次要强调 |
| `--lnx-font-weight-semibold` | `600` | 按钮、卡片标题、表格表头（**收紧**：不再默认 700） |
| `--lnx-font-weight-bold` | `700` | 页面标题、统计数值 |

**行高 / 字距**

| 令牌 | 值 | 用途 |
| --- | --- | --- |
| `--lnx-leading-tight` | `1.25` | 标题（h1–h3）、统计数值 |
| `--lnx-leading-snug` | `1.45` | 表格、按钮、紧凑列表 |
| `--lnx-leading-normal` | `1.6` | **默认正文**（沿用现状） |
| `--lnx-leading-relaxed` | `1.75` | 文档页长文、法务页 |
| `--lnx-tracking-normal` | `0` | 默认 |
| `--lnx-tracking-tight` | `-0.01em` | 30px / 38px 大标题 |
| `--lnx-tracking-wide` | `0.02em` | 表格表头、全大写小标、Tag |

### 4.8 间距（8px 基础栅格）

| 令牌 | 值 | 典型用途 |
| --- | --- | --- |
| `--lnx-space-1` | `4px` | 图标与文字间隙 |
| `--lnx-space-2` | `8px` | 紧凑内间距、控件间隙 |
| `--lnx-space-3` | `12px` | 表单行间隙、Tag 间隙 |
| `--lnx-space-4` | `16px` | **默认内边距**、卡片内边距（紧凑/后台） |
| `--lnx-space-5` | `20px` | 工具栏内边距（沿用 `AdminPageToolbar` 20px） |
| `--lnx-space-6` | `24px` | **卡片默认内边距**（前端/控制台） |
| `--lnx-space-8` | `32px` | 区块间距 |
| `--lnx-space-10` | `40px` | 大区块间距 |
| `--lnx-space-12` | `48px` | 官网 section 垂直间距（移动端降为 32px） |
| `--lnx-space-16` | `64px` | 官网 Hero / 首屏垂直间距 |
| `--lnx-space-page-x` | `24px` | 页面左右安全边距（≥681px） |
| `--lnx-space-page-x-sm` | `16px` | 页面左右安全边距（≤680px） |

### 4.9 圆角（**整体收紧**）

| 令牌 | 值 | 用途 |
| --- | --- | --- |
| `--lnx-radius-xs` | `4px` | Tag、Badge、`el-tag--small` |
| `--lnx-radius-sm` | `6px` | 小号控件、`el-button--small`、代码块行内 |
| `--lnx-radius-md` | `8px` | **默认控件**：按钮、输入框、选择器、下拉项（由 10px 收紧） |
| `--lnx-radius-lg` | `12px` | 卡片、面板、表格外壳、工具栏（由 14px 收紧） |
| `--lnx-radius-xl` | `16px` | 对话框、抽屉、大型浮层 |
| `--lnx-radius-pill` | `999px` | 胶囊按钮、圆形图标按钮、徽标点 |

### 4.10 阴影（浅色主题专用，**禁止重投影**）

| 令牌 | 值 | 用途 |
| --- | --- | --- |
| `--lnx-shadow-xs` | `0 1px 2px rgba(15, 23, 42, .05)` | 输入框、细分隔、静态小面 |
| `--lnx-shadow-sm` | `0 2px 8px rgba(15, 23, 42, .05), 0 1px 2px rgba(15, 23, 42, .04)` | **卡片默认**（替代旧 `0 10px 28px`） |
| `--lnx-shadow-md` | `0 6px 20px rgba(15, 23, 42, .09)` | 卡片 hover、可点击面浮起 |
| `--lnx-shadow-lg` | `0 16px 44px rgba(15, 23, 42, .14)` | 对话框、抽屉、下拉浮层 |
| `--lnx-shadow-brand` | `0 2px 8px rgba(37, 99, 235, .22)` | 主按钮（替代旧 `0 8px 18px`） |
| `--lnx-shadow-focus` | `0 0 0 3px rgba(37, 99, 235, .22)` | 输入框/按钮聚焦外环（另有 `outline` 兜底） |

> 规则：① 同一元素**最多一层阴影**；② 层次投影（`0 24px 64px`）仅用于 `lg`；③ 卡片默认阴影必须极轻，靠边框与底色区分层级，禁用大面积模糊投影。

### 4.11 层级 z-index

| 令牌 | 值 | 用途 |
| --- | --- | --- |
| `--lnx-z-base` | `0` | 常规内容 |
| `--lnx-z-sticky` | `10` | 顶栏、表格表头吸附、工具栏吸附 |
| `--lnx-z-dropdown` | `20` | 下拉、Popper、Popover（页面内自实现） |
| `--lnx-z-drawer` | `30` | 三端移动抽屉（`*-mobile-drawer`） |
| `--lnx-z-dialog` | `2000` | Element Plus 对话框遮罩/主体（**保留 EP 默认值，勿改**） |
| `--lnx-z-message` | `3000` | `el-message` / `el-message-box` / loading |
| `--lnx-z-notification` | `4000` | `el-notification` |

> 约束：禁止新增未登记的任意 `z-index`（如 9999）；超过 30 的层级一律保留给 Element Plus。

### 4.12 布局尺寸

| 令牌 | 值 | 说明 |
| --- | --- | --- |
| `--lnx-layout-content` | `1248px` | 官网/文档/法务内容最大宽度（沿用，不改） |
| `--lnx-layout-admin-sidebar` | `256px` | 管理后台桌面侧边栏（沿用） |
| `--lnx-layout-admin-sidebar-collapsed` | `64px` | 后台侧边栏收起宽度 |
| `--lnx-layout-user-sidebar` | `248px` | 用户控制台桌面侧边栏（沿用） |
| `--lnx-layout-topbar` | `64px` | 桌面顶栏高度 |
| `--lnx-layout-topbar-sm` | `56px` | ≤980px 顶栏高度 |
| `--lnx-layout-touch-target` | `44px` | 最小触摸目标（WCAG 2.5.5） |
| `--lnx-layout-control-sm` | `32px` | 小号控件高度 |
| `--lnx-layout-control-md` | `40px` | **默认控件高度**（按钮/输入） |
| `--lnx-layout-control-lg` | `48px` | 大号控件高度 |
| `--lnx-layout-row-height` | `48px` | 表格数据行高（后台 48px / 前端 52px 见 5.3） |
| `--lnx-layout-row-height-head` | `46px` | 表格表头行高（沿用） |

### 4.13 断点

| 断点 | 行为 |
| --- | --- |
| `max-width: 1200px` | 官网多列卡片降列 |
| `max-width: 980px` | **强制要求存在于 `components.css` 与 `AdminPageToolbar.vue`**：控制台/后台侧边栏转抽屉，顶栏 56px，工具栏纵向堆叠 |
| `max-width: 700px` | **强制要求存在于 `components.css` 与 `AdminPageToolbar.vue`**：多列表单/筛选改单列，字号与内边距降档，表格仅在自身容器横滚 |
| `max-width: 680px` | 页面左右边距降为 `16px` |
| `max-width: 390px` | 无页面级横向滚动（验收宽度为 375px） |

### 4.14 Element Plus 变量映射（直接落 tokens.css）

```css
:root {
  --el-color-primary: var(--lnx-color-brand-600);
  --el-color-primary-dark-2: var(--lnx-color-brand-700);
  --el-color-primary-light-3: var(--lnx-color-brand-400);
  --el-color-primary-light-5: var(--lnx-color-brand-300);
  --el-color-primary-light-7: var(--lnx-color-brand-200);
  --el-color-primary-light-8: var(--lnx-color-brand-100);
  --el-color-primary-light-9: var(--lnx-color-brand-50);
  --el-color-success: var(--lnx-status-success-solid);
  --el-color-warning: var(--lnx-status-warning-solid);
  --el-color-danger:  var(--lnx-status-danger-solid);
  --el-color-error:   var(--lnx-status-danger-solid);
  --el-color-info:    var(--lnx-status-info-solid);
  --el-bg-color: var(--lnx-color-surface);
  --el-bg-color-page: var(--lnx-color-bg);
  --el-bg-color-overlay: var(--lnx-color-surface);
  --el-text-color-primary: var(--lnx-text-primary);
  --el-text-color-regular: var(--lnx-text-regular);
  --el-text-color-secondary: var(--lnx-text-secondary);
  --el-text-color-placeholder: var(--lnx-text-placeholder);
  --el-text-color-disabled: var(--lnx-text-disabled);
  --el-border-color: var(--lnx-color-border);
  --el-border-color-light: var(--lnx-color-border-subtle);
  --el-border-color-lighter: var(--lnx-color-border-subtle);
  --el-border-color-extra-light: var(--lnx-color-border-subtle);
  --el-fill-color: var(--lnx-color-surface-muted);
  --el-fill-color-light: var(--lnx-color-surface-soft);
  --el-fill-color-lighter: var(--lnx-color-neutral-50);
  --el-fill-color-blank: var(--lnx-color-surface);
  --el-border-radius-base: var(--lnx-radius-md);
  --el-border-radius-small: var(--lnx-radius-sm);
  --el-border-radius-round: var(--lnx-radius-pill);
  --el-box-shadow: var(--lnx-shadow-md);
  --el-box-shadow-light: var(--lnx-shadow-sm);
  --el-box-shadow-lighter: var(--lnx-shadow-xs);
  --el-box-shadow-dark: var(--lnx-shadow-lg);
  --el-font-family: var(--lnx-font-sans);
  --el-transition-duration: 0s;   /* 静态主题：无过渡 */
}
```

---

## 5. 三端视觉规范（仅视觉层，结构不变）

### 5.1 用户前端（公共站）· `PublicLayout.vue` + 12 个页面

| 维度 | 规格 |
| --- | --- |
| 布局骨架 | 顶栏（高 `--lnx-layout-topbar`，白面 + 1px 底边 `--lnx-color-border`，**不使用半透明毛玻璃**）+ 内容区 + `SiteFooter`。内容宽 `min(1248px, 100% - 48px)` 居中 |
| 卡片风格 | 圆角 `radius-lg(12px)`，白面，`shadow-sm`，边框 `--lnx-color-border`；hover 升为 `shadow-md` 且边框转 `--lnx-color-border-strong`；**卡片不嵌套卡片** |
| 密度与留白 | **宽松**：section 垂直间距 48–64px，卡片内边距 24px，正文 14–16px，行高 1.6–1.75 |
| 主色使用 | 主 CTA 用 `brand-600`→渐变；链接用 `--lnx-text-link`；Hero 保留品牌渐变面 |
| 移动端 | ≤980px 顶栏收起为汉堡 + `site-mobile-drawer` 抽屉导航（**类名不可改**） |
| 特例页 | `DocsPage` / `LegalPage` 走长文规范（`leading-relaxed`，正文最大宽度 72ch）；`CreativeStudio` 走工作区规范（左侧配置 + 右侧预览，≤980px 上下堆叠） |

### 5.2 用户控制台 · `UserConsole.vue`（8 路由）+ `OrganizationConsole` / `AgentConsole`

| 维度 | 规格 |
| --- | --- |
| 布局骨架 | 桌面：左侧栏 248px（白面 + 右侧 1px 分隔）+ 顶栏 64px + 内容区（内边距 24px）。移动：顶栏 + `user-mobile-drawer`（**类名不可改**） |
| 侧栏项 | 默认 `--lnx-text-regular`；hover 底 `--lnx-state-hover-surface`；选中底 `--lnx-state-selected-surface` + 左侧 3px `brand-600` 指示条 + 文字 `--lnx-state-selected-text` |
| 卡片/面板 | 统一 `.panel/.console-panel` = 白面、`radius-lg`、`--lnx-color-border`、`shadow-xs`（控制台不需要浮起感） |
| 密度与留白 | **中等**：卡片内边距 20–24px，区块间距 24–32px，表格行高 52px |
| 数据区 | 统计卡：标签(12px/`text-muted`) + 数值(24px/`font-weight-bold`/`text-primary`) + 一条说明(13px) |
| 特例 | Playground / ModelProbe 的代码块用 `--lnx-color-surface-muted` 底 + `font-mono` + 13px；日志表格必须分页（默认 10 条） |

### 5.3 管理后台 · `AdminLayout.vue` + `AdminConsole.vue` + 9 个独立页

| 维度 | 规格 |
| --- | --- |
| 布局骨架 | 桌面：侧栏 256px（深色文字于白面，**不再使用 `#app > .admin-shell` 作用域变量**，改用全局令牌）+ 顶栏 64px + 内容区。≤980px 侧栏转 `admin-mobile-drawer`（**类名不可改**） |
| 工具栏 | 所有后台页首部统一 `<AdminPageToolbar>`（`name="filters"` / `name="actions"` 插槽必须保留）；白面、`radius-lg`、内边距 20px，标题 24px，描述 14px `--lnx-text-muted` |
| 卡片/面板 | 白面、`radius-lg`、`--lnx-color-border`、`shadow-xs`；后台**不用** `shadow-md`，避免密集网格出现「浮起噪音」 |
| 密度与留白 | **高密度**（与官网有意区分）：区块间距 16px，卡片内边距 16–20px，正文 13px，表格行高 48px，表头 12px/`tracking-wide` |
| 表格 | 表头底 `brand-50` + 文字 `--lnx-text-regular` 12px；斑马纹 `neutral-50`；数值列右对齐；操作列右固定；横向滚动仅在表格容器内 |
| 弹窗/抽屉 | `radius-xl`、`shadow-lg`、正文区独立滚动、短视口下主体操作始终可达 |

### 5.4 三端共性基调（表格 / 表单 / 弹窗 / 空态 / 加载态）

| 元素 | 视觉基调 |
| --- | --- |
| 表格 `el-table` | 外壳 `radius-lg`；表头底 `--lnx-color-brand-50`，文字 `--lnx-text-regular` 12px `tracking-wide`；行 hover `--lnx-state-hover-surface`；边框 `--lnx-color-border-subtle`；无竖向裁切线外溢 |
| 表单 | 控件高 40px、`radius-md`、白底、1px `--lnx-color-border-input`；hover 转 `--lnx-color-border-strong`；focus 转 `brand-600` + `shadow-focus`；错误态用 `--lnx-status-danger-text` + 边框 `--lnx-status-danger-border`，错误文案紧邻字段 |
| 弹窗 `el-dialog` / 抽屉 `el-drawer` | 白面、`radius-xl`、`shadow-lg`、无半透明遮罩滤镜（遮罩 `rgba(15,23,42,.45)`）；标题 18px/600；底部操作右对齐，主操作在最右 |
| 空态 `el-empty` | 中性灰插图 + 标题 16px `--lnx-text-regular` + 说明 13px `--lnx-text-muted` + **唯一一个**主操作按钮 |
| 加载态 | 优先骨架屏（`--lnx-color-surface-muted` 静态块，**无 shimmer 动画**）；次选 `el-loading` 遮罩（白 60% + 静态 spinner + 文案）。禁止旋转以外的动画 |
| 状态标签 | 一律「浅底 + 深字 + 浅边框」三元组（见 4.5），**禁止仅用颜色表达状态**，必须带文字 |

---

## 6. 共享组件视觉清单

> 所有规则统一写在 `styles/components.css`，以 `#app` 为根作用域，禁止页面级覆盖。

| # | 组件 | 视觉规格 | 状态覆盖 |
| --- | --- | --- | --- |
| 1 | 按钮 `el-button` | 高度 40px（小号 32px），`radius-md`，字重 600，15px 内边距 16px | default（白面 + `--lnx-color-border` + `--lnx-text-regular`）/ primary（渐变 + 白字 + `shadow-brand`）/ plain（`brand-50` 底 + `--lnx-text-brand` + `brand-200` 边）/ danger（`--lnx-status-danger-solid`）/ text·link（无底，`--lnx-text-link`）/ hover（色阶下移一档）/ active（`brand-800`）/ disabled（`--lnx-state-disabled-surface` + `--lnx-state-disabled-text` + 无阴影）/ focus-visible（`shadow-focus` + outline） |
| 2 | 输入 `el-input` / `el-textarea` / `el-input-number` | 高 40px，`radius-md`，白底，1px `--lnx-color-border-input`，文字 14px `--lnx-text-regular` | 默认 / hover（`border-strong`）/ focus（`brand-600` + `shadow-focus`）/ error（`--lnx-status-danger-text`）/ disabled（`#f1f5f9` 底 + `--lnx-text-disabled`）/ 前缀后缀图标 16px `--lnx-text-muted` |
| 3 | 下拉 `el-select` / `el-cascader` / `el-date-picker` | 触发器同输入框；浮层白面 `radius-lg` `shadow-lg` 边框 `--lnx-color-border`；选项高 36px | 默认 / hover（`--lnx-state-hover-surface`）/ selected（`--lnx-state-selected-surface` + `--lnx-state-selected-text` + 右侧 ✓）/ disabled / 空态文案居中 13px |
| 4 | 开关 `el-switch` / 复选 `el-checkbox` / 单选 `el-radio` | 开关：开 `brand-600`，关 `neutral-300`；复选/单选选中 `brand-600`，未选边框 `--lnx-color-border-input` | 默认 / hover / focus-visible（`shadow-focus`）/ disabled / indeterminate（`brand-600`） |
| 5 | 标签 `el-tag` | `radius-xs(4px)`，高 22px，内边距 8px，12px/500 | 类型：primary（`brand-50`+`--lnx-text-brand`）/ success·warning·danger·info（严格取 4.5 的 surface+text 配对）/ info 中性（`neutral-100`+`--lnx-text-secondary`）；尺寸 small/default；可关闭态 |
| 6 | 徽标 `el-badge` | 数值徽标 `radius-pill`，高 18px，11px/600，白字；**底色必须用 solid 档**（`--lnx-status-*-solid` 或 `brand-600`），关系徽标用 `brand-600` | 默认 / 溢出 `99+` / 圆点（8px）/ 无内容空态 |
| 7 | 表格 `el-table` | 见 5.4。外壳 `radius-lg`，表头高 46px，行高 48px（后台）/ 52px（前端），单元格内边距 12px 16px | 默认 / hover 行 / 选中行 / 斑马纹 / 排序激活（`brand-600` 箭头）/ 固定列（右侧阴影改 1px 边框，不用投影）/ 空数据 / 加载中骨架 |
| 8 | 分页 `ListPagination` / `el-pagination` / `PagedTable` / `PagedList` / `SelectablePagination` | 最小高 64px，间距 12px，文字 13px `--lnx-text-muted`；当前页 `brand-600` 白字 `radius-sm`；**默认每页 10 条，可选 10/20/50/100；仅 total ≤ 200 时显示「全部」** | 默认 / hover / disabled / 单页隐藏 / 尺寸切换 / 总数文案 |
| 9 | 对话框 `el-dialog` / `el-message-box` | 白面，`radius-xl`，`shadow-lg`，`max-width: calc(100vw - 24px)`；标题 18px/600；正文 14px；底部按钮右对齐 | 打开 / 关闭 / 短视口（≤700px 全宽、正文独立滚动）/ 遮罩 `rgba(15,23,42,.45)` 无模糊 |
| 10 | 抽屉 `el-drawer` | 白面，`shadow-lg`，圆角仅靠内容区一侧（≤16px）；宽度 320px（默认）/ 可全宽 | 默认 / 窄屏全宽 / 正文独立滚动 / 三端移动抽屉类名不可改 |
| 11 | 消息 `el-message` / `el-alert` / `el-notification` | 白面 + `radius-lg` + `shadow-lg` + 左侧 4px 语义色条；标题 14px/600，描述 13px；语义色严格取 4.5 配对 | success / warning / danger / info / 可关闭 / 带描述 / 长文本换行 |
| 12 | 面包屑 `el-breadcrumb` | 13px，分隔符 `--lnx-text-placeholder`，当前项 `--lnx-text-regular` 不加粗，可点击项 `--lnx-text-link` + hover 下划线 | 默认 / hover / 移动端隐藏中间层级 |
| 13 | 标签页 `el-tabs` | 顶部 1px `--lnx-color-border` 底线；激活项 `brand-600` 文字 + 2px `brand-600` 下划线；未激活 `--lnx-text-secondary`；项间距 24px | 默认 / hover / active / disabled / 卡片型变体（`brand-50` 底 + 选中白面） |
| 14 | 菜单 `el-menu` | 项高 44px，`radius-md`，图标 18px + 文字 14px；选中/激活见 5.2；子菜单展开无动画 | 默认 / hover / active / 折叠态（居中图标 + tooltip）/ 三端侧栏复用同一套 |
| 15 | 卡片容器 `.panel` / `.el-card` / `.market-card` / `.pricing-card` / `.service-card` / `.report-card` / `.settings-card` / `.security-card` | 统一白面 + `radius-lg` + `--lnx-color-border` + `shadow-sm`；hover 仅对**可点击卡片**生效（升 `shadow-md`，不位移） | 默认 / hover / 选中 / 禁用 / 头部（标题 16px/600 + 右侧操作） |
| 16 | 空状态 `el-empty` | 见 5.4；插图统一线性风格单色 `--lnx-color-neutral-300` | 无数据 / 无搜索结果 / 无权限 / 加载失败（失败态带「重试」按钮） |
| 17 | 统计卡 / 指标卡 | 高 ≥ 112px；标签 12px `--lnx-text-muted` + 数值 24–30px/700 `--lnx-text-primary`（等宽数字 `font-variant-numeric: tabular-nums`）+ 趋势标注 12px（升降用 success/danger 文字色 + ↑↓ 箭头文字） | 默认 / 加载骨架 / 无数据（显示 `—`）/ 负向趋势 |
| 18 | 代码块 `pre` / `.code-block` | `--lnx-color-surface-muted` 底，`radius-md`，内边距 16px，`font-mono` 13px/`--lnx-text-code`，行高 1.6；行内 code `brand-50` 底 + `--lnx-text-brand` | 默认 / 可复制（右上角图标按钮）/ 溢出横向滚动（容器内）/ 长行换行开关 |
| 19 | 图表容器 ECharts | 外框同卡片；内边距 16px；标题 14px/600；坐标轴文字 12px `--lnx-text-muted`；网格线 `--lnx-color-border-subtle`；序列色序：`brand-600` → `cyan-600` → `brand-400` → `cyan-300` → `neutral-400`；主色填充透明度 ≤ 18% | 默认 / 加载骨架 / 空数据 / 单点数据 / 深色文字对比达标（AA） |
| 20 | 页脚 `SiteFooter` | 底色 `--lnx-color-surface-soft` + 顶边 `--lnx-color-border`；链接 13px `--lnx-text-secondary` hover `--lnx-text-link`；版权 12px `--lnx-text-muted` | 默认 / hover / 移动端单列堆叠 |
| 21 | 工具栏 `AdminPageToolbar` | 白面、`radius-lg`、`--lnx-color-border`、内边距 20px；标题 24px/700；描述 14px `--lnx-text-muted`；筛选区 flex:1，操作区右对齐；**≤980px 纵向堆叠，≤700px 内边距 16px 且控件 100% 宽**。`name="filters"` / `name="actions"` 插槽不可删 | 默认 / 无插槽（隐藏控制区）/ 移动端 |
| 22 | 移动抽屉（三端） | 白面，宽 `min(320px, 84vw)`，`shadow-lg`，遮罩 `rgba(15,23,42,.45)`；`PublicLayout`→`.site-mobile-drawer`、`UserConsole`→`.user-mobile-drawer`、`AdminLayout`→`.admin-mobile-drawer`（**类名禁止修改**） | 打开 / 关闭 / 路由切换自动关闭 / Esc 关闭 / 焦点回到触发按钮 |

---

## 7. 无障碍与验收标准（可测）

### 7.1 硬性守卫（Must Pass）

| # | 断言（Given / When / Then） | 判定方式 |
| --- | --- | --- |
| A-1 | Given 三端外壳组件，When 运行守卫测试，Then `PublicLayout.vue` 含 `class="site-mobile-drawer"`、`UserConsole.vue` 含 `class="user-mobile-drawer"`、`AdminLayout.vue` 含 `class="admin-mobile-drawer"` | 守卫测试全绿 |
| A-2 | Given `styles/components.css`，When 检索断点，Then 同时存在 `@media (max-width: 980px)` 与 `@media (max-width: 700px)`；`AdminPageToolbar.vue` 同理 | 守卫测试全绿 |
| A-3 | Given 8 个后台页面（`AdminConsole` / `ModelGateway` / `AdminCreativeConfig` / `AdminAgents` / `AdminModelProbe` / `AdminOtherServices` / `AdminContactMethods` / `AdminVmCardTest`），When 检查模板，Then 每个均包含 `<AdminPageToolbar`，且该组件保留 `name="filters"` 与 `name="actions"` 插槽 | 守卫测试全绿 |
| A-4 | Given `src/**/*.{vue,ts}`，When 检索品牌旧名，Then `API Transit` / `API 中转` / `中转站` 命中数为 **0** | 品牌守卫测试全绿 |
| A-5 | Given 前端工程，When 执行 `npm run build`（含 `vue-tsc` 类型检查），Then 退出码 0，无类型错误、无构建告警阻断 | CI / 本地 |
| A-6 | Given `styles/foundation.css`，When 读取 `*, *::before, *::after` 规则，Then 仍包含 `animation: none !important; transition: none !important; scroll-behavior: auto !important;` | 守卫测试全绿 |
| A-7 | Given `:root`，Then `color-scheme: light` 存在，且全仓无 `prefers-color-scheme: dark` 分支、无 `@media (prefers-color-scheme: dark)` | 守卫测试全绿 |
| A-8 | Given `src/style.css`，When 统计硬编码 hex，Then 数量为 0（白名单外），且 `:root` 中不再定义 `--tech-*` / `--ui-*` / `--admin-*` | 守卫测试 + 人工复核 |
| A-9 | Given `src/main.ts`，When 检查导入顺序，Then 严格为 `style.css → styles/tokens.css → styles/foundation.css → styles/components.css` 且未被改动 | 守卫测试全绿 |

### 7.2 视觉与响应式验收

| # | 断言 | 判定方式 |
| --- | --- | --- |
| B-1 | Given 三端各一个代表页，When 分别以 1280 / 768 / 375 宽度渲染，Then `document.documentElement.scrollWidth <= clientWidth`（无页面级横向滚动），宽表溢出仅发生在 `.el-table__body-wrapper` / `.table-scroll` 等声明容器内 | 自动截图 + DOM 断言 |
| B-2 | Given 三端相同语义元素（主按钮、默认输入框、数据表、卡片），When 计算最终计算样式（computed style），Then 品牌主色均为 `rgb(37, 99, 235)`、控件圆角均为 `8px`、卡片圆角均为 `12px`、卡片阴影均为 `--lnx-shadow-*` 档位 | 计算样式断言，统一率 100% |
| B-3 | Given 375px 视口，When 打开三端移动导航，Then 抽屉类名存在且可打开、可关闭、Esc 可关闭、遮罩点击可关闭 | 交互测试 |
| B-4 | Given 任一后台页，When 渲染，Then 页面首部存在 `AdminPageToolbar` 且标题为 24px、描述为 `--lnx-text-muted` | 计算样式断言 |
| B-5 | Given 三端页面，When 采集全部文本节点与其背景，Then 正文（≤ 18.66px 常规字重）对比度 ≥ 4.5:1、大字号（≥ 24px 或 ≥ 18.66px/700）≥ 3:1，达标率 100% | axe-core / 自研对比度脚本 |
| B-6 | Given 语义状态标签/提示（success/warning/danger/info），When 渲染，Then 前景为对应 `-text` 深色值、背景为对应 `-surface` 浅色值，对比度 ≥ 4.5:1，且**不存在**浅底浅字组合 | 计算样式枚举断言 |
| B-7 | Given 任一可交互元素，When 通过 `Tab` 聚焦，Then 存在可见焦点样式（`outline` 或 `box-shadow` 差异 ≥ 3:1 相对背景），覆盖率 100% | 键盘走查 + 样式断言 |

### 7.3 业务约定验收（不可回归）

| # | 断言 | 判定方式 |
| --- | --- | --- |
| C-1 | Given 任一业务数据列表（表格/卡片/弹窗内记录），When 渲染，Then 存在分页组件（`PagedTable` / `PagedList` / `ListPagination` / `SelectablePagination`），默认每页 **10** 条，可选 **10 / 20 / 50 / 100**；仅 `total ≤ 200` 时提供「全部」 | 分页守卫测试 |
| C-2 | Given 分页/搜索/排序，When 用户操作，Then 请求参数走服务端（`page` / `size` / 关键字 / 排序字段），客户端不做截断式假分页 | 接口断言 + 分页守卫测试 |
| C-3 | Given 本次样式改造，When 对比改造前后，Then `docs/LIST_PAGINATION_INVENTORY.md` 覆盖的列表数量与分页参数未变化 | 清单 diff |
| C-4 | Given 改造后代码，When 检查路由与组件树，Then 路由表、模板 DOM 结构、道具契约（如 `UserConsole` 的 props 切模块、`AdminConsole` 的 7 模块）零变更 | 人工复核 + 快照 |

### 7.4 非功能验收

| # | 断言 |
| --- | --- |
| D-1 | `src/style.css` 行数 ≤ 1500 行；`styles/tokens.css` 为唯一令牌真源，且所有令牌均带 `--lnx-` 前缀 |
| D-2 | 全仓样式文件中出现的 hex 色值仅允许出现在 `styles/tokens.css`（以及白名单：ECharts 配置对象、品牌 Logo SVG） |
| D-3 | 全仓无新增 `animation` / `transition` / `@keyframes`（除 Element Plus 内置且被全局 `!important` 置零的声明） |
| D-4 | 全仓未新增未登记的 `z-index`；> 30 的层级仅由 Element Plus 提供 |

---

## 8. 需求池

### P0 —— 不收敛令牌就无法继续（阻塞项）

| ID | 需求 | 验收 |
| --- | --- | --- |
| P0-1 | 建立唯一令牌命名空间 `--lnx-*`，全量落地 4.1–4.13（色彩/排版/间距/圆角/阴影/层级/布局/断点） | 4.14 EP 映射 + 所有令牌可 `getComputedStyle` 取值 |
| P0-2 | 删除 `--tech-*` / `--ui-*` / `--admin-*` 定义，按 4.6 映射表替换全部引用；`--tech-blue: #1769ff` 与 `#2563eb` 双蓝并存**必须终结** | A-8；全站主色统一为 `rgb(37,99,235)` |
| P0-3 | 清除 `style.css` 中 851 个硬编码 hex 与页面 scoped 样式中的色值，改为令牌引用 | A-8、D-2 |
| P0-4 | 重写 `styles/components.css`，以 `#app` 根作用域统一 22 类共享组件视觉 | B-2、B-6、§6 全覆盖 |
| P0-5 | 保留并强化静态浅色基线：`color-scheme: light` + `animation/transition/scroll-behavior` 置零规则 | A-6、A-7、D-3 |
| P0-6 | 三端移动抽屉类名、`AdminPageToolbar` 用法与插槽、980/700 断点、品牌旧名——四项守卫全部保持 | A-1 ~ A-4 |
| P0-7 | 语义状态色改为 4.5 表中「surface + text」强制配对，消除浅底浅字 | B-6 |
| P0-8 | 修正不达标对比度：`--lnx-text-muted` `#64748b → #5b6b80`；`--tech-surface` 去半透明 | B-5 |
| P0-9 | 三端全部业务列表分页默认 10 条、可选 10/20/50/100、服务端分页不受影响 | C-1、C-2、C-3 |

### P1 —— 应做（显著提升一致性）

| ID | 需求 | 验收 |
| --- | --- | --- |
| P1-1 | 圆角与阴影整体收紧（控件 10→8，卡片 14→12；卡片阴影 `0 10px 28px` → `0 2px 8px`） | B-2 |
| P1-2 | 字号/字重收敛为 9 档 + 4 档，页面不再自定义字号；表格表头固定 12px `tracking-wide` | 计算样式枚举断言 |
| P1-3 | 三端密度分层落地：官网宽松 / 控制台中等 / 后台高密度，并写入规范 | §5.1–5.3 数值与实现一致 |
| P1-4 | 焦点可见全覆盖：全局 `:focus-visible` outline + 表单 `shadow-focus` | B-7 |
| P1-5 | 统一加载态为静态骨架屏，禁止 shimmer/旋转以外的动画 | 代码检索无 keyframes |
| P1-6 | 统一空态为「原因 + 唯一下一步」，失败态带重试 | 空态走查 |
| P1-7 | z-index 阶梯登记并清理任意值 | D-4 |

### P2 —— 可做（观感优化）

| ID | 需求 | 验收 |
| --- | --- | --- |
| P2-1 | 建立设计令牌对照页（内部 `/_design-tokens` 或 Storybook 式静态页），便于评审与回归 | 页面可访问、令牌可枚举 |
| P2-2 | 图表主题封装置换为令牌驱动（ECharts theme 从 CSS 变量读色） | 图表使用品牌色序 |
| P2-3 | `TechBackdrop.vue`（当前未挂载）要么按新令牌重写并挂载，要么明确标注废弃 | 二选一决策落地 |
| P2-4 | 补齐 `--lnx-*` 的 Tailwind 主题映射（`tailwind.config` 引用 CSS 变量），避免 Tailwind 硬编码色值再生 | Tailwind 配置无 hex |
| P2-5 | 增加样式守卫脚本（硬编码 hex 扫描 + 断点扫描 + 抽屉类名扫描），并入 CI | 脚本可在 CI 运行 |

---

## 9. 待确认问题

| # | 问题 | 影响面 | 建议默认 |
| --- | --- | --- | --- |
| Q-1 | `--lnx-*` 是否作为唯一对外命名空间彻底替换 `--tech-*`/`--ui-*`/`--admin-*`（零别名），还是保留一个迭代的别名兼容段？ | 改造风险 vs 一次性收敛 | 建议**保留一个迭代的别名段**（≤30 条），同迭代内清零 |
| Q-2 | 旧的品牌渐变色标 `#1f66d4` / `#0e7490` 是否保留（4.5 已收敛为 `135deg, #2563eb → #1d4ed8 → #0e7490`）？是否允许调整 Hero 视觉？ | 官网首屏观感 | 建议保留品牌调性、仅收敛端点 |
| Q-3 | `--lnx-color-bg` 统一取 `#f4f7fb`（现后台底）还是 `#f2f6fc`（现前端/令牌底）？两者差异极小但会让三端底色出现一次整体位移。 | 三端全局底色 | 建议统一为 `#f4f7fb`（对比度实测更优） |
| Q-4 | 后台是否允许在本次一并引入「侧边栏收起（64px）」交互？属功能行为，非纯视觉。 | 是否越界 | 建议**本次不引入**，仅登记令牌 |
| Q-5 | `AdminConsole.vue`（3407 行）内的 607 行 scoped 样式，是就地替换色值为令牌，还是抽离到 `styles/admin.css`？ | 架构分层 | 交由架构师决策，PRD 不预设 |
| Q-6 | `TechBackdrop.vue` 未被挂载：保留改造还是标记删除？ | 死代码 | 建议明确取舍（见 P2-3） |

---

**附：本 PRD 的对比度数值均由 WCAG 2.1 相对亮度公式实算得出（`(L1+0.05)/(L2+0.05)`，sRGB 线性化），非估算值，可直接作为验收基线。**
