# B3F 施工卡 · 旧值 → 新令牌 对照补全表

> **定位**：`02-architecture.md` 附录 C 已定死「类 → 归属文件 → 层」。本文件只补**最后一跳**：每个类的「改造前视觉声明原文」中**色值 → 令牌**的机械翻译。
> **输入**：`03-b3-visual-loss-report.md`（原声明原文）、`02-architecture.md` §5.4.1/§5.4.2（令牌真源与旧前缀映射）、`src/styles/tokens.css`（已登记令牌，B1 产出）。
> **约束**：① **不重判归属层级**（附录 C 已定）；② **不新造令牌**（只从已登记令牌中就近匹配）；③ 只翻译色值，布局数值原样保留；④ 本文档为**只读设计产物**，不改任何代码/样式/测试。
> **作者**：高见远（Gao）· 架构师 ｜ **版本**：v1.0 ｜ **适用批次**：B3F

---

## 1. 三类值形态 → 处理规则

`03-b3-visual-loss-report.md` 中每个类的原声明，色值形态分三类，逐一处置：

| # | 值形态 | 例子 | 处理规则 |
| --- | --- | --- | --- |
| **①** | **旧令牌引用** | `var(--tech-text)` / `var(--ui-primary-surface)` / `var(--admin-line-strong)` | 按 **§5.4.2 映射表**直接给新令牌（见 §2.1） |
| **②** | **裸 hex** | `#e2efff` / `#0d4f9f` / `#f4f7fb` / `#102541` | 按**语义就近**匹配 `tokens.css` 已登记令牌（见 §2.2）；**不得**新增令牌 |
| **③** | **`rgba(...)` 透明度变体** | `rgba(23,105,255,.09)` / `rgba(255,255,255,.9)` | 三选一：**（a）** 复用已登记的 `--lnx-state-hover-overlay`；**（b）** 用 `color-mix(in srgb, … <pct>, transparent)` 表达；**（c）** 明确**丢弃**（原为旧深色主题的补偿/霓虹光晕）——**禁止新造令牌** |

### 1.1 一条总原则（判「丢弃」还是「恢复」）

> **带 `#app` 前缀且取值为浅色者 = 权威声明 → 恢复；不前缀且取值为深色/霓虹/绿市集者 = 旧主题补偿 → 丢弃。**

理由：改造前的 `style.css` 里，同一类几乎都有**两条**声明——一条「深色主题基色」（无 `#app`，如 `rgba(9,23,50,.7)`、`linear-gradient(145deg, rgba(14,29,60,.94)…)`、`#050916`），一条「浅色最终覆盖」（带 `#app` + 浅色 + `!important`）。深色主题段（`style.css:4359–5772`）已被 B3 整段删除，故**浅色 `#app` 那条才是应当渲染的权威声明**，深色那条是**已失去意义的补偿**，一律丢弃。

---

## 2. 通用字典

### 2.1 旧令牌 → 新令牌（穷尽，取自 §5.4.2）

| 旧令牌 | 新令牌 | 备注 |
| --- | --- | --- |
| `var(--tech-text)` | `var(--lnx-text-primary)` | 页面正文/标题色 |
| `var(--tech-muted)` | `var(--lnx-text-secondary)` | 次级文本 |
| `var(--tech-bg)` | `var(--lnx-color-bg)` | 页面底色 |
| `var(--tech-surface)` | `var(--lnx-color-surface)` | 卡片填充 |
| `var(--tech-line)` | `var(--lnx-color-border)` | 默认边框 |
| `var(--tech-line-strong)` | `var(--lnx-color-border-strong)` | 强调边框 |
| `var(--tech-glow)` | `var(--lnx-shadow-sm)` | 旧发光 → 收敛为浅阴影 |
| `var(--ui-text)` | `var(--lnx-text-primary)` | — |
| `var(--ui-muted)` | `var(--lnx-text-secondary)` | — |
| `var(--ui-subtle)` | `var(--lnx-text-muted)` | — |
| `var(--ui-primary-surface)` | `var(--lnx-color-brand-50)` | — |
| `var(--ui-primary-text)` | `var(--lnx-text-brand)` | — |
| `var(--admin-text)` | `var(--lnx-text-primary)` | — |
| `var(--admin-muted)` | `var(--lnx-text-secondary)` | — |
| `var(--admin-line)` | `var(--lnx-color-border)` | — |
| `var(--admin-surface)` | `var(--lnx-color-surface)` | — |
| `var(--admin-surface-soft)` | `var(--lnx-color-surface-soft)` | — |

> **替换注意**：`--lnx-text` 是 `--lnx-text-*` 的前缀，替换须用**词边界**（`--lnx-text\b`），避免误伤 `--lnx-text-muted`。

### 2.2 裸 hex → 新令牌（本报告实际出现值，按语义就近）

**文本 / 中性（灰）**

| 原 hex | 新令牌 | 依据 |
| --- | --- | --- |
| `#ffffff`（作 color 用于深底） | `var(--lnx-text-on-brand)` | 品牌面上白字 |
| `#ffffff`（作 background） | `var(--lnx-color-surface)` | 卡片/面板填充 |
| `#0f172a` `#111827` `#17251c` `#142944` `#102541` | `var(--lnx-text-primary)` | 主文本（`#0f172a` 精确） |
| `#1e293b` `#1e3a5f` `#142e4d` | `var(--lnx-text-code)` | 代码/深文本（`#1e293b` 精确） |
| `#334155` `#334139` `#26362c` | `var(--lnx-text-regular)` | 正文（`#334155` 精确） |
| `#475569` `#455249` `#294766` `#42566f` `#3f536c` `#40566f` `#385270` `#526070` `#526173` `#526984` `#536273` `#244a70` `#263e5d` `#4c5c52` `#3b4840` | `var(--lnx-text-secondary)` | 次级文本（`#475569` 精确） |
| `#64748b` `#69766f` `#667a95` `#71839a` `#7589aa` `#8295b3` `#8295b4` `#8295b5` `#8699b8` `#8a958e` `#929d96` `#7185a4` `#7185a6` `#506281` `#a5b7d4` | `var(--lnx-text-muted)` | 弱文本（`#64748b`→`#5b6b80` 收紧） |
| `#94a3b8` `#98a9c1` `#a9bbd2` `#b6c5d9` `#c0cee1` | `var(--lnx-text-placeholder)` 或 `var(--lnx-color-neutral-400)` | 占位/极弱（`#94a3b8` 精确=`neutral-400`） |

**表面 / 背景 / 边框**

| 原 hex | 新令牌 | 依据 |
| --- | --- | --- |
| `#f4f7fb` `#f6f8fb` | `var(--lnx-color-bg)` | 页面底（`#f4f7fb` 精确） |
| `#ffffff` `#fff` | `var(--lnx-color-surface)` | 卡面 |
| `#f8fafc` `#f8faf8` `#f7faf8` | `var(--lnx-color-neutral-50)` | 极弱底（`#f8fafc` 精确） |
| `#f6f9fd` `#f4f8fd` `#f6f8fb` | `var(--lnx-color-surface-soft)` | 弱区底（`#f6f9fd` 精确） |
| `#eef3f9` `#edf3f9` `#eef3f8` `#f5f8fc` `#eef5fc` `#f3f7fb` `#edf2f7` `#f1f5f9` `#f3f9f5` `#f0f7e9` `#edf1ee` `#eef6e7` `#eef7e8` | `var(--lnx-color-surface-muted)` | 更弱底（`#eef3f9` 精确） |
| `#e2e8f0` `#e3e8f0` `#e4eae6` `#e0e7e2` `#e1e7e3` `#e6edf6` `#e3eef9` | `var(--lnx-color-border-subtle)` 或 `var(--lnx-color-neutral-200)` | 细分隔（`#e2e8f0` 精确=`neutral-200`） |
| `#dbe4ef` `#dbe7f5` `#d7e4f7` `#dbe7f6` `#d9e5ef` `#d9e5f5` `#dfe2e1` `#d5e0ec` `#d6e2ef` `#dfe9f6` `#edf1ee` | `var(--lnx-color-border)` | 默认边框（`#dbe4ef` 精确） |
| `#bfd5ee` `#c9d9ea` `#c9dcfb` `#bdd8fa` `#b6c6d8` `#afc6df` #a9ddc8 | `var(--lnx-color-border-strong)` 或 `var(--lnx-color-brand-200)` | 强调/hover 边框 |
| `#94a3b8` | `var(--lnx-color-border-input)` | 输入描边（精确） |

**品牌 / 蓝**

| 原 hex | 新令牌 | 依据 |
| --- | --- | --- |
| `#2563eb` `#2d75c7` `#3d78ff` `#1769ff` `#0566f2` `#0755c9` `#0b63ce` `#0d5b9f` `#0d4f9f` `#174b8f` `#174b78` `#174f9d` | `var(--lnx-color-brand-600)`（深字用 `var(--lnx-color-brand-700)`） | 品牌主色族 |
| `#1d4ed8` `#1e40af` | `var(--lnx-color-brand-700)` / `var(--lnx-color-brand-800)` | 品牌深 |
| `#eff6ff` `#eef5ff` `#eaf2ff` `#edf4ff` `#f1f6ff` `#edf5ff` `#eef6ff` `#e2efff` | `var(--lnx-color-brand-50)` | 品牌极浅底 |
| `#4456ff` `#4056ff` `#5265ff` `#606cff` `#554fff` `#2e8dff` `#29baff` `#42ceff` `#38caff` `#39cfff` | `var(--lnx-color-brand-500)` / `var(--lnx-gradient-brand)` | 品牌霓虹（**深色补偿，多数丢弃**，见 §4） |
| `#0ea5e9` `#06b6d4` `#0e7490` | `var(--lnx-color-cyan-600)` / `var(--lnx-color-cyan-700)` | 辅助青 |
| `#0891b2` | `var(--lnx-color-cyan-600)` | 精确 |

**状态色**

| 原 hex | 新令牌 | 依据 |
| --- | --- | --- |
| `#059669` `#047857` `#08734f` `#176b50` `#39e99c` | `var(--lnx-status-success-text)` | 成功字 |
| `#ecfdf5` `#e8f8f1` `#f1faf6` | `var(--lnx-status-success-surface)` | 成功底 |
| `#a7f3d0` `#a9ddc8` `#cce9df` | `var(--lnx-status-success-border)` | 成功边 |
| `#ea580c` `#b45309` `#94511b` `#9a3412` | `var(--lnx-status-warning-text)` | 警告字 |
| `#fffbeb` `#fff7ed` `#fff8f1` | `var(--lnx-status-warning-surface)` | 警告底 |
| `#fde68a` `#fed7aa` `#f4dcc4` | `var(--lnx-status-warning-border)` | 警告边 |
| `#b91c1c` `#9f2636` | `var(--lnx-status-danger-text)` | 危险字 |
| `#fef2f2` `#fff0f2` | `var(--lnx-status-danger-surface)` | 危险底 |
| `#fecaca` | `var(--lnx-status-danger-border)` | 危险边 |
| `#dbeafe` | `var(--lnx-state-active-surface)` | 按下/选中底（精确） |

### 2.3 `rgba(...)` 透明度变体 → 处置

| 原 rgba | 处置 |
| --- | --- |
| `rgba(37,99,235,.06)` `rgba(23,105,255,.06/.07/.09)`（浅蓝叠层，做 hover/选中底） | **（a）** `var(--lnx-state-hover-overlay)` |
| `rgba(255,255,255,.9/.92/.96/.98)`（半透白卡面） | **（b）** 收敛为 `var(--lnx-color-surface)`（**去半透明**，静态浅色无需透出背景） |
| `rgba(236,245,255,.88)` `rgba(238,247,255,.88)`（浅蓝卡面） | **（b）** `color-mix(in srgb, var(--lnx-color-brand-50) 88%, transparent)`（或直接 `var(--lnx-color-surface-soft)`） |
| `rgba(15,23,42,.06/.05/.09/.14)`（浅阴影） | **（b）** 已是 `--lnx-shadow-xs/sm/md/lg` 的取值，直接用对应 `--lnx-shadow-*` |
| `rgba(51,75,105,.07/.08/.12)` `rgba(20,72,145,.06)`（蓝灰浅阴影） | **（b）** `var(--lnx-shadow-sm)` / `var(--lnx-shadow-md)` |
| `rgba(14,29,60,.94)` `rgba(8,19,42,.92)` `rgba(9,23,50,.7)` `rgba(6,13,29,.82)` `rgba(3,7,19,.34)` `rgba(10,23,49,.72)` `rgba(32,85,164,.2)` `rgba(28,57,105,.66)`（**深色底**） | **（c）丢弃**（深色主题补偿，见 §1.1） |
| `rgba(32,174,255,.17)` `rgba(37,181,255,.22)` `rgba(35,168,255,.38)` `rgba(46,117,255,.25)` `rgba(118,185,0,.23)` `rgba(94,141,224,.24)`（**霓虹/绿光晕**） | **（c）丢弃**（旧主题发光），或**（b）** 收敛为 `var(--lnx-shadow-brand)` |
| `rgba(100,141,215,.2)` `rgba(77,156,255,.28)` `rgba(98,139,212,.18)` `rgba(101,142,216,.22)`（半透蓝边框，深底态） | **（c）丢弃**；浅色态改 `var(--lnx-color-border)` |

### 2.4 复现率最高的 14 条「声明模板」（`#app` 浅色权威态）

> 工程师按模板整体替换即可；下方 §3 逐类引用模板编号。**`!important` 原样保留**（原则 2 已使断言与之解耦）。

| 模板 | 原声明（浅色权威） | 新声明（令牌化） |
| --- | --- | --- |
| **T1 卡片面（带阴影）** | `border-color: var(--tech-line) !important; background: #ffffff !important; background-image: none !important; color: var(--tech-text) !important; box-shadow: 0 8px 24px rgba(51,75,105,.08) !important; backdrop-filter: none !important;` | `border-color: var(--lnx-color-border) !important; background: var(--lnx-color-surface) !important; background-image: none !important; color: var(--lnx-text-primary) !important; box-shadow: var(--lnx-shadow-sm) !important; backdrop-filter: none !important;` |
| **T2 弱底卡面** | `border-color: var(--tech-line) !important; background: #f8fafc !important; background-image: none !important; color: var(--tech-text) !important; box-shadow: none !important;` | `border-color: var(--lnx-color-border) !important; background: var(--lnx-color-neutral-50) !important; background-image: none !important; color: var(--lnx-text-primary) !important; box-shadow: none !important;` |
| **T3 标题/正文色** | `color: var(--tech-text) !important; text-shadow: none !important;` | `color: var(--lnx-text-primary) !important; text-shadow: none !important;` |
| **T4 次级文本** | `color: var(--tech-muted) !important;` | `color: var(--lnx-text-secondary) !important;` |
| **T5 代码块** | `border-color: var(--tech-line) !important; background: #f8fafc !important; color: #1e3a5f !important; box-shadow: none !important;` | `border-color: var(--lnx-color-border) !important; background: var(--lnx-color-neutral-50) !important; color: var(--lnx-text-code) !important; box-shadow: none !important;` |
| **T6 后台标题** | `color: var(--admin-text) !important; text-shadow: none !important;` | `color: var(--lnx-text-primary) !important; text-shadow: none !important;` |
| **T7 后台弱文本** | `color: var(--admin-muted) !important;` | `color: var(--lnx-text-secondary) !important;` |
| **T8 后台弱底卡** | `border-color: var(--admin-line) !important; background: var(--admin-surface-soft) !important; background-image: none !important; color: var(--admin-text) !important; box-shadow: none !important;` | `border-color: var(--lnx-color-border) !important; background: var(--lnx-color-surface-soft) !important; background-image: none !important; color: var(--lnx-text-primary) !important; box-shadow: none !important;` |
| **T9 `--ui-muted` 文本** | `color: var(--ui-muted) !important;` | `color: var(--lnx-text-secondary) !important;` |
| **T10 芯片/徽标（浅蓝）** | `border-color: #bfd5ee !important; background: #eaf2ff !important; color: #174b8f !important;` | `border-color: var(--lnx-color-brand-200) !important; background: var(--lnx-color-brand-50) !important; color: var(--lnx-text-brand) !important;` |
| **T11 导航项选中/悬停（浅蓝）** | `border-color: #bfd5ee !important; background: #eaf2ff !important; background-image: none !important; color: #174b8f !important;` | `border-color: var(--lnx-color-brand-200) !important; background: var(--lnx-color-brand-50) !important; background-image: none !important; color: var(--lnx-text-brand) !important;` |
| **T12 页面骨架底色** | `color: var(--tech-text) !important; background: var(--tech-bg) !important; background-image: none !important;` | `color: var(--lnx-text-primary) !important; background: var(--lnx-color-bg) !important; background-image: none !important;` |
| **T13 非主按钮（描边态）** | `border-color: var(--tech-line) !important; background: #ffffff !important; color: #294766 !important; box-shadow: none !important;` | `border-color: var(--lnx-color-border) !important; background: var(--lnx-color-surface) !important; color: var(--lnx-text-regular) !important; box-shadow: none !important;` |
| **T14 metric 数值** | `color: var(--ui-text) !important; text-shadow: none !important;` | `color: var(--lnx-text-primary) !important; text-shadow: none !important;` |

---

## 3. 逐类映射表（124 类）

> 列：`类` ｜ `归属层`（引 附录 C）｜ `原声明（应恢复的权威/浅色）` ｜ **`新声明（令牌化）`** ｜ `备注`。
> 「模板」列 = §2.4 模板编号；只列**需恢复**的权威声明，**深色补偿声明全部丢弃**（规则见 §1.1/§4）。

### 3.1 层 A 共享区（`components.css` `#app`）

| 类 | 原声明（权威） | 新声明 | 模板/备注 |
| --- | --- | --- | --- |
| `.docs-code-head` | `#app .docs-code-head strong → color: var(--ui-text) !important;` | `color: var(--lnx-text-primary) !important;` | T14 |
| `.paged-table` | `#app … .paged-table → --el-table-*…; color:#294766 !important; background:#ffffff !important;` | `background: var(--lnx-color-surface) !important; color: var(--lnx-text-regular) !important;`（`--el-table-*` 交由 `tokens.css` 的 `--el-*` 映射；仅补 `background`/`color`） | `--el-*` 已覆盖行列底 |
| `.oauth-binding` | `#app … .oauth-binding → border-color: var(--tech-line) !important; background:#ffffff !important; background-image:none !important; color: var(--tech-text) !important;` | `border-color: var(--lnx-color-border) !important; background: var(--lnx-color-surface) !important; background-image: none !important; color: var(--lnx-text-primary) !important;` | T1 减阴影 |
| `.channel-ledger-title` | `#app .admin-shell .channel-ledger-title → color: var(--admin-text) !important; text-shadow:none !important;` | `color: var(--lnx-text-primary) !important; text-shadow: none !important;` | T6 |
| `.module-error-state` | `.overview-inline-error,.module-error-state → border:1px solid #fed7aa; border-radius:8px; background:#fff7ed; color:#9a3412;` | `border: 1px solid var(--lnx-status-warning-border); border-radius: var(--lnx-radius-md); background: var(--lnx-status-warning-surface); color: var(--lnx-status-warning-text);` | 警告态成对 |
| `.tag-row` | `.tone-blue, .tag-row span → color:#0566f2; background: rgba(5,102,242,.1);` | `color: var(--lnx-color-brand-600); background: var(--lnx-color-brand-50);` | 芯片族 |
| `.tone-green` | `.tone-green → color:#059669; background: rgba(16,185,129,.12);` | `color: var(--lnx-status-success-text); background: var(--lnx-status-success-surface);` | 成功态 |
| `.tone-orange` | `.tone-orange → color:#ea580c; background: rgba(249,115,22,.12);` | `color: var(--lnx-status-warning-text); background: var(--lnx-status-warning-surface);` | 警告态 |
| `.panel-title` | `#app .admin-shell .panel-title → color: var(--admin-text) !important; text-shadow:none !important;` | `color: var(--lnx-text-primary) !important; text-shadow: none !important;` | T6 |
| `.brand-image` | `.console-brand .brand-image → background: rgba(255,255,255,.96) !important; box-shadow: 0 0 24px rgba(32,174,255,.17);` | `background: var(--lnx-color-surface) !important; box-shadow: var(--lnx-shadow-sm);` | **R-T**；丢弃霓虹光晕 |
| `.site-brand-mark` | `.site-brand-mark → width:34px;height:34px;place-items:center;border-radius:8px;background:#0f172a;color:#ffffff;font-size:15px;` | `border-radius: var(--lnx-radius-md); background: var(--lnx-text-primary); color: var(--lnx-text-on-brand);`（宽高/字号保留数值） | **R-T**；深色方标 |
| `.console-brand` | `#app … .console-brand → color: var(--tech-text) !important;` | `color: var(--lnx-text-primary) !important;` + `small → color: var(--lnx-text-muted);` | **R-T**；T3 |
| `.site-section` | `.site-section{color:var(--tech-text);}` + headings `color: var(--tech-text) !important;` + p `color: var(--tech-muted) !important;` | 容器 `color: var(--lnx-text-primary);`；标题 T3；段落 T4 | 跨 5 页族 |
| `.feature-card` | T1（+ `.feature-card h2 → color:#0f172a` / `p → color:#64748b`） | T1；`h2 → var(--lnx-text-primary)`；`p → var(--lnx-text-muted)` | T1 |
| `.health-card` | `#app … .health-card → color: var(--tech-text) !important;`（面属 F1） | `border-color: var(--lnx-color-border-subtle); background: var(--lnx-color-surface-soft); color: var(--lnx-text-primary);` | Flow 卡；丢弃 `rgba(255,255,255,.07)` |
| `.constellation` | `.constellation → … color:#93afd2;` + `::before/::after` 霓虹 | **v1.6 一并恢复（§4-A 原「不恢复」被硬判据覆盖）** | 父 `.model-constellations` 已 `display:none` |
| `.grid-icon` | `.grid-icon i → border:1px solid #7ddcff; border-radius:1px;` | `border: 1px solid var(--lnx-color-cyan-300); border-radius: var(--lnx-radius-xs);` | 概念装饰（可见，保留） |
| `.hero-actions` | `#app … .hero-actions button.hero-secondary → border-color: var(--tech-line) !important; background:#ffffff !important; color:#294766 !important; box-shadow:none !important;` | T13 | 见 §4-D（`.hero-primary` 特例） |
| `.hero-atmosphere` | `.public-site.home-theme .site-hero::before,::after, .hero-atmosphere, .model-constellations → display:none !important; background:none !important; content:none !important;` | **见 §5（R12 抑制声明）** | 恢复抑制 |
| `.hero-feature-grid` | T1（`article`）+ `strong → var(--tech-text)` / `small → var(--tech-muted)` | T1；`strong` T3；`small` T4 | T1 |
| `.hero-footnote` | `small → color: var(--tech-muted) !important;` | T4（`p`/`small`） | 丢弃 `#a5b7d4`/`#506281`/`#4489ff` 霓虹 |
| `.hero-primary` | `.hero-actions .hero-primary → border-color:#3d78ff !important; background:linear-gradient(135deg,#29baff,#4056ff) !important; color:#fff !important;` | `border-color: var(--lnx-color-brand-600) !important; background: var(--lnx-color-brand-600) !important; color: var(--lnx-text-on-brand) !important;` | **⚠️ R11：纯色**（`.hero-primary` = `.el-button--primary`，受 §5.1.4 支配，**禁止渐变**，否则撞 B-2） |
| `.hero-product` | T1（`.public-site.home-theme .hero-product`）+ strong T3 / small T4 | T1；strong T3；small T4 | 丢弃深色网格底 |
| `.hero-promise` | `.hero-copy > p.hero-promise → margin-top:8px; color:#d8e5f8; font-size:15px;` | `color: var(--lnx-text-secondary);`（margin/字号保留） | 丢弃 `#d8e5f8` |
| `.hero-secondary` | `#app … .hero-actions button.el-button.hero-secondary → border-color: var(--tech-line) !important; background:#ffffff !important; color:#294766 !important; box-shadow:none !important;` | T13 | 非 primary，保留描边 |
| `.model-constellations` | 同 `.hero-atmosphere`（`display:none` 组） | **见 §5（R12 抑制声明）** | 恢复抑制 |
| `.orbit` | `.hero-atmosphere .orbit → position:absolute; border:1px solid rgba(61,115,255,.36); border-radius:50%;` | **v1.6 一并恢复（§4-A 原「不恢复」被硬判据覆盖）** | 父已 `display:none` |
| `.output-stack` | T1（`article`）+ strong T3 / small T4 | T1；strong T3；small T4 | 丢弃深底/霓虹点 |
| `.product-logo` | `.product-toolbar .product-logo → … background:linear-gradient(145deg,#2e8dff,#554fff); color:#fff; box-shadow:0 0 22px rgba(63,111,255,.38);` | `background: var(--lnx-gradient-brand); color: var(--lnx-text-on-brand); box-shadow: var(--lnx-shadow-brand);` | 品牌面允许渐变（U-1） |
| `.product-toolbar` | `.public-site.home-theme .product-toolbar → border:1px solid rgba(85,138,218,.23); background: rgba(10,30,62,.58);` + `#app … .product-toolbar → #ffffff !important` | `border-color: var(--lnx-color-border); background: var(--lnx-color-surface);` | 取浅色 `#app` 权威 |
| `.routing-core` | `.routing-core span → border-color:#93c5fd !important; background:#eff6ff !important; color:#2563eb !important; box-shadow:none !important; text-shadow:none !important;` | `border-color: var(--lnx-color-brand-300) !important; background: var(--lnx-color-brand-50) !important; color: var(--lnx-color-brand-600) !important; box-shadow: none !important; text-shadow: none !important;` | 取浅色 `#app` 覆盖 |
| `.routing-lines` | `.routing-lines path → filter:none !important; box-shadow:none !important;`（+ `fill:none;stroke:rgba(55,194,255,.84)` 深色） | `filter: none !important; box-shadow: none !important;`（丢弃霓虹 stroke） | 丢弃深色描边/发光 |
| `.routing-stats` | T1 + strong T3 / small T4 | T1；strong T3；small T4 | 丢弃深底 |
| `.signal` | `.hero-atmosphere .signal → background:#39cfff; box-shadow:0 0 18px 5px rgba(35,168,255,.38);` | **v1.6 一并恢复（§4-A 原「不恢复」被硬判据覆盖）** | 父已 `display:none` |
| `.signal-two` | `.hero-atmosphere .signal-two → background:#606cff;` | **v1.6 一并恢复（§4-A 原「不恢复」被硬判据覆盖）** | 同上 |
| `.site-hero` | 同 `.hero-atmosphere`（`display:none` 组，`::before/::after`） | **见 §5（R12）** | 恢复抑制 |
| `.source-stack` | T1（`article`）+ strong T3 / small T4 | T1；strong T3；small T4 | 丢弃深底/霓虹点 |
| `.available` | `#app .market-badge.available → border-color:#a9ddc8 !important; background:#e8f8f1 !important; color:#08734f !important;` | `border-color: var(--lnx-status-success-border) !important; background: var(--lnx-status-success-surface) !important; color: var(--lnx-status-success-text) !important;` | 成功态 |
| `.market-active-filters` | `.market-active-filters → color:#69766f;` + `button → border:1px solid #cfe0d5; background:#f3f9f5; color:#285a3e;` | `.market-active-filters{color:var(--lnx-text-muted);}` `button{border:1px solid var(--lnx-color-border); background:var(--lnx-color-surface-muted); color:var(--lnx-text-secondary);}` | **丢弃绿市集色**（§4-C） |
| `.market-badge` | T10（`#app .market-badge…`）+ `.market-badge{display:inline-flex;…}`（布局） | T10 | 布局保留 |
| `.market-card-actions` | `.market-card-actions .el-button → width:100%;min-height:28px;padding:5px 7px;font-size:11px;` | 纯布局，**无颜色** → 无需令牌化（可留在层 A 或下调层 C） | 无类色 |
| `.market-card-desc` | `small,.muted,…,.market-card-desc → color:#8295b5;` | `color: var(--lnx-text-muted);` | T4 族 |
| `.market-card-details` | `#app .market-card-details > summary → border-color:#c9d9ea !important; background:#eef5fc !important; color:#174b78 !important;`（+ hover `#9dbddd/#e3effb/#0d4f9f`） | `border-color: var(--lnx-color-brand-200) !important; background: var(--lnx-color-brand-50) !important; color: var(--lnx-text-brand) !important;`（hover 用 `--lnx-color-border-strong`/`--lnx-state-active-surface`/`--lnx-color-brand-700`） | T10 变体 |
| `.market-catalog-shell` | `.market-catalog-shell → background:transparent !important;` | `background: transparent !important;`（**保留，勿删**） | 附录 A 骨架 |
| `.market-details-chevron` | `font-size:16px; transition` | 纯布局，无颜色 | 无类色 |
| `.market-empty` | T1 + `.market-meta,.market-card footer,.market-empty → border-color: var(--tech-line)…; background:#f8fafc…` | T1（或 T2） | 浅色权威 |
| `.market-fallback-note` | `.market-fallback-note → border:1px solid #bfdbfe; background:#eff6ff; color:#1d4ed8;` | `border: 1px solid var(--lnx-color-brand-200); background: var(--lnx-color-brand-50); color: var(--lnx-text-brand);` | 信息态 |
| `.market-filter-group` | `#app .market-filter-group h3 → color: var(--ui-text) !important;` | `color: var(--lnx-text-primary) !important;` | **R-T**；T14 |
| `.market-filter-icon` | `#app .market-filter-icon → background:#eaf2ff !important; color:#174b8f !important;` | `background: var(--lnx-color-brand-50) !important; color: var(--lnx-text-brand) !important;` | T10 减边 |
| `.market-filter-options` | `#app .market-filter-options button:hover,.active → border-color:#bfd5ee !important; background:#eaf2ff !important; color:#174b8f !important;`（+ `#app … button → color:#40566f`） | T11；静态态 `color: var(--lnx-text-secondary) !important;` | **R-T**（双位置） |
| `.market-filter-panel` | T1 + `#app .market-filter-panel h2 → color: var(--ui-text) !important;` | T1；`h2 → color: var(--lnx-text-primary);` | 丢弃绿市集 `#dfe6e1`/`#ffffff` 基色 |
| `.market-grid` | `background:transparent !important;` | `background: transparent !important;`（**保留**） | 骨架 |
| `.market-hero` | T1（`#app` 浅色）+ `h1 → color: var(--tech-text)` | T1；`h1 → var(--lnx-text-primary)` | 丢弃深色 radial 底 |
| `.market-hero-label` | `#app .market-hero-label span → color:#0d5b9f !important;` | `color: var(--lnx-text-brand) !important;` | 丢弃霓虹 `#45d8ff` |
| `.market-meta` | `#app .market-meta dt → color: var(--ui-muted) !important;` + `.market-meta dd → T3` | `dt → var(--lnx-text-secondary) !important;` / `dd → var(--lnx-text-primary) !important;` | 丢弃深底 |
| `.market-page` | `.market-page{color:var(--tech-text);}` + `h1` T3 + `p` T4 | 容器 `color: var(--lnx-text-primary);`；h1 T3；p T4 | 跨页标题族 |
| `.market-publisher` | T3（strong）+ T4（small） | strong T3；small T4 | 丢弃绿 `#26362c`/`#929d96` |
| `.market-publisher-fallback` | `.market-publisher-fallback → color:#4c5c52;` | `color: var(--lnx-text-secondary);` | 丢弃绿 |
| `.market-publisher-icon` | `#app … .market-publisher-icon → border-color: rgba(101,142,216,.22) !important; background: rgba(20,39,75,.74) !important;` | `border-color: var(--lnx-color-border-strong) !important; background: var(--lnx-color-surface-soft) !important;` | 取浅色语义（丢深底） |
| `.market-result-count` | `#app .market-toolbar .market-result-count strong → color: var(--ui-text) !important;` / `… count → var(--ui-muted)` | `strong → var(--lnx-text-primary) !important;` / 自身 `→ var(--lnx-text-secondary) !important;` | T14/T9 |
| `.market-tags` | `#app .market-tags span → border-color:#d5e0ec !important; background:#f1f5f9 !important; color:#334155 !important;` | `border-color: var(--lnx-color-border) !important; background: var(--lnx-color-surface-muted) !important; color: var(--lnx-text-regular) !important;` | 中性 chip |
| `.service-card-meta` | `#app .other-services-page .service-card-meta → color: var(--ui-muted) !important;` | `color: var(--lnx-text-secondary) !important;` | T9 |
| `.services-count` | T1 + `strong → var(--ui-text)` + `span/small → #64748b` + T10 | 面 T1；strong T14；`span/small → var(--lnx-text-muted)`；chip T10 | 多声明 |
| `.services-grid` | `background:transparent !important;` | `background: transparent !important;` | 骨架 |
| `.services-hero` | T3（`h1 → color: var(--tech-text) !important;`）+ `> div > p → color:#536273` | h1 T3；p `color: var(--lnx-text-secondary);` | 跨页标题族 |
| `.pricing-grid` | `background:transparent !important;` | `background: transparent !important;` | 骨架 |
| `.section-head` | `.section-head h2 → color:#111827;` | `color: var(--lnx-text-primary);` | T3 |
| `.organization-page` | T3（`h1`）+ T4（`p`）+ `background:transparent !important;` | h1 T3；p T4；`background: transparent !important;` | 跨页族 |
| `.gateway-workbench` | `#app .gateway-workbench .heading p → color: var(--ui-muted) !important;` | `color: var(--lnx-text-secondary) !important;` | T9 |

### 3.2 层 B 审计段（`components.css` 审计段 `#app`）

| 类 | 原声明（权威） | 新声明 | 模板/备注 |
| --- | --- | --- | --- |
| `.channel-ledger-table` | `.channel-ledger-table → margin-top:8px; border:1px solid #e2e8f0; border-radius:8px;` | `border: 1px solid var(--lnx-color-border-subtle); border-radius: var(--lnx-radius-md);` | 布局+边框 |
| **`.channel-test-ledger`** | `.channel-test-ledger → margin-top:22px; padding-top:18px; border-top:1px solid #e2e8f0;` + `--el-collapse-border-color: transparent;`（`HEAD:800-819`） | `border-top: 1px solid var(--lnx-color-border-subtle);`（`margin-top`/`padding-top` 布局保留）+ **保留** `--el-collapse-border-color: transparent;` | **附录 C 补漏 C-L1**（长写 `border-top` 被漏判）；层 B；`components.css:1671-1672` |
| `.model-filter-bar` | `.model-filter-bar → … color:#64748b;` | `color: var(--lnx-text-muted);` | 工具条 |
| `.overview-inline-error` | 同 `.module-error-state`（警告态） | `border: 1px solid var(--lnx-status-warning-border); background: var(--lnx-status-warning-surface); color: var(--lnx-status-warning-text);` | 警告态 |
| `.console-hero-sub` | `.console-hero .console-hero-sub → color:#536273;` | `color: var(--lnx-text-secondary);` | — |
| `.is-blue` | `.console-status-note.is-blue → border-color:#c9dcfb; background:#f1f6ff; color:#174f9d;` | `border-color: var(--lnx-color-brand-200); background: var(--lnx-color-brand-50); color: var(--lnx-text-brand);` | 信息态 |
| `.is-green` | `.console-status-note.is-green → border-color:#cce9df; background:#f1faf6; color:#176b50;` | `border-color: var(--lnx-status-success-border); background: var(--lnx-status-success-surface); color: var(--lnx-status-success-text);` | 成功态 ⚠️ 守卫断言 `.is-green` 选择器存在（勿改结构） |
| `.is-orange` | `.console-status-note.is-orange → border-color:#f4dcc4; background:#fff8f1; color:#94511b;` | `border-color: var(--lnx-status-warning-border); background: var(--lnx-status-warning-surface); color: var(--lnx-status-warning-text);` | 警告态 |
| `.is-purple` | `.console-status-note.is-purple → border-color:#ded7f8; background:#f7f5ff; color:#5d479c;` | **建议丢弃或改信息态**（无紫色令牌，见 §4-B） | ⚠️ 无 `--lnx-status-*purple*` |
| `.total-cost` | `.playground-usage-grid article.total-cost → border-color:#bdd8fa; background:#eef6ff;` + `strong → #0755c9` | `border-color: var(--lnx-color-brand-200); background: var(--lnx-color-brand-50);` + `strong → var(--lnx-color-brand-600);` | 品牌强调 |
| `.usage-day-bar` | `.usage-day-bar → background:#edf1ee;` + `.miss{#78ad30} .hit{#35a7a0} .write{#e6a23c} .output{#6b7fd7}` | `background: var(--lnx-color-surface-muted);`；`.miss → var(--lnx-status-success-solid)`、`.hit → var(--lnx-color-cyan-600)`、`.write → var(--lnx-status-warning-solid)`、`.output → var(--lnx-color-brand-500)` | 图表色；`#6b7fd7`≈`brand-500` |
| `.usage-table-scroll` | `::-webkit-scrollbar-thumb → background:#9aabc0;` `::-webkit-scrollbar-track → #edf2f7;` | `thumb → var(--lnx-color-border-strong);` `track → var(--lnx-color-neutral-100);` | — |
| `.sidebar-balance` | `#app .user-console-page .sidebar-balance → border-color: var(--tech-line) !important; background:#ffffff !important; background-image:none !important; color: var(--tech-text) !important;` | T1 减阴影 | 浅色权威 |
| `.response-panel` | `.response-panel → border-color:#d7e4f7 !important; background: rgba(255,255,255,.92) !important;` + `h2 → #142944` | `border-color: var(--lnx-color-border) !important; background: var(--lnx-color-surface) !important;` + `h2 → var(--lnx-text-primary);` | 去半透明 |
| `.playground-answer-scroll` | T5（`#app .playground-answer-scroll`） | T5 | 代码块 |
| `.playground-raw-collapse` | T5 + `#app … .playground-raw-collapse … → var(--tech-line)…#ffffff…var(--tech-text)` | T5 | 代码块 |
| `.playground-raw-scroll` | `pre` 纯布局 | 无颜色 | 无类色 |
| `.playground-result-head` | `#app .playground-billing-panel .playground-result-head p → color: var(--ui-muted) !important;` | `color: var(--lnx-text-secondary) !important;` | T9 |
| `.playground-billing-panel` | `.playground-billing-panel → border-color:#d7e4f7 !important; background: rgba(255,255,255,.92) !important;` + `h2 → #142944` | `border-color: var(--lnx-color-border) !important; background: var(--lnx-color-surface) !important;` + `h2 → var(--lnx-text-primary);` | 去半透明 |
| `.playground-billing-note` | `.playground-billing-note → color:#71839a;` | `color: var(--lnx-text-muted);` | — |
| `.playground-cost-breakdown` | `.playground-cost-breakdown → color:#526984;` | `color: var(--lnx-text-secondary);` | — |
| `.playground-model-empty` | `.playground-model-empty → color:#94a3b8;` | `color: var(--lnx-text-placeholder);` | — |
| `.playground-model-summary` | `.playground-model-summary span → border:1px solid #dbe4ef; background:#f8fafc; color:#526173;` | `border: 1px solid var(--lnx-color-border); background: var(--lnx-color-neutral-50); color: var(--lnx-text-secondary);` | chip |
| `.playground-rate-grid` | `span → color:#667a95;` `div → border:1px solid #e0e9f5; background:#ffffff;` `b → color:#263e5d;` | `span → var(--lnx-text-muted);` `div → border:1px solid var(--lnx-color-border-subtle); background: var(--lnx-color-surface);` `b → var(--lnx-text-secondary);` | — |
| `.playground-usage-grid` | `article → border:1px solid #dfe9f6; background:#ffffff;` `span → #667a95;` `strong → #102541;` | `article → border:1px solid var(--lnx-color-border); background: var(--lnx-color-surface);` `span → var(--lnx-text-muted);` `strong → var(--lnx-text-primary);` | — |
| `.playground-usage-summary` | T1 + `border-color: var(--tech-line)…; background:#ffffff…; color: var(--tech-text)…` | T1 减阴影 | 浅色权威 |
| `.console-status-note` | `.console-status-note → border:1px solid #d9e5f5; background:#f5f8fc; color:#42566f;` | `border: 1px solid var(--lnx-color-border); background: var(--lnx-color-surface-soft); color: var(--lnx-text-secondary);` | 中性 chip ⚠️ 断言目标 |
| `.flow-brand` | `#app → .flow-brand strong/span,.nav-item,.health-card… → color: var(--tech-text) !important;` | `color: var(--lnx-text-primary) !important;`（`span` 另用 `--lnx-text-secondary`） | T3 |
| `.flow-shell` | `.flow-shell{min-height:100dvh;display:flex;background:#f4f7fb;}` + T12 类 | `background: var(--lnx-color-bg);`（骨架数值保留） | 可下调层 C |
| `.flow-sidebar` | `.site-nav,.studio-nav,.flow-topbar,.admin-header,…, .flow-sidebar → border-color: var(--tech-line) !important; background:#ffffff !important; color: var(--tech-text) !important; box-shadow: 0 4px 16px rgba(51,75,105,.07) !important;` | `border-color: var(--lnx-color-border) !important; background: var(--lnx-color-surface) !important; color: var(--lnx-text-primary) !important; box-shadow: var(--lnx-shadow-sm) !important;` | 浅色权威（丢深 `#0e1421`） |
| `.flow-topbar` | 同上组 | 同上 | 浅色权威 |
| `.nav-item` | `#app` 选中态 T11 + `.nav-item{…color:#cbd5e1…}` | `active/hover` T11；静态 `color: var(--lnx-text-secondary);` | 丢深 `#cbd5e1`/`#ffffff` |
| `.topbar-copy` | `.topbar-copy h1 → color:#0f172a;` `p → color:#64748b;` | `h1 → var(--lnx-text-primary);` `p → var(--lnx-text-muted);` | T3/T4 |
| `.price-hint` | `#app .admin-shell .price-hint → color: var(--admin-muted) !important;` | `color: var(--lnx-text-secondary) !important;` | **R-T**；T7 |

### 3.3 层 C 页面本地层（`.vue` scoped）

| 类 | 原声明 | 新声明 | 备注 |
| --- | --- | --- | --- |
| `.creation-layout` | `background:transparent !important;`（布局为主） | 纯布局 + `background: transparent;` | 无类色 |
| `.work-grid` | `background:transparent !important;` | `background: transparent;` | 无类色 |
| `.detail-layout` | `background:transparent !important;` | `background: transparent;` | 无类色 |
| `.pricing-disclosure` | `.pricing-disclosure → border:1px solid #bfdbfe; background:#eff6ff; color:#1e40af;` | `border: 1px solid var(--lnx-color-brand-200); background: var(--lnx-color-brand-50); color: var(--lnx-text-brand);` | 信息态 |
| `.product-pricing-cover` | `.product-pricing-cover → background:#e2e8f0;` | `background: var(--lnx-color-neutral-200);` | — |
| `.service-image` | `#app … .service-image → background:#f4f8fd !important;` | `background: var(--lnx-color-surface-soft) !important;` | — |
| `.service-image-placeholder` | `.service-image-placeholder → background:linear-gradient(135deg,#f8fafc,#edf3fa); color:#64748b;` + `small → #94a3b8` | `background: linear-gradient(135deg, var(--lnx-color-neutral-50), var(--lnx-color-surface-muted)); color: var(--lnx-text-muted);` `small → var(--lnx-text-placeholder);` | 丢深 radial |
| `.service-index` | `#app … .service-index → color:#0d5b9f !important;` | `color: var(--lnx-text-brand) !important;` | — |
| `.access-row` | `.access-row → border:1px solid #edf2f7; background:#f8fafc;` `span → #64748b` `code → #0f172a` | `border: 1px solid var(--lnx-color-border-subtle); background: var(--lnx-color-neutral-50);` `span → var(--lnx-text-muted);` `code → var(--lnx-text-primary);` | — |
| `.account-summary-grid` | `#app … account-summary-grid > div → border-color: var(--tech-line) !important; background:#ffffff !important; color: var(--ui-text) !important;` | `border-color: var(--lnx-color-border) !important; background: var(--lnx-color-surface) !important; color: var(--lnx-text-primary) !important;` | 浅色权威 |
| `.user-console` | `.user-console{…background:#f6f8fb;}` | `background: var(--lnx-color-bg);`（grid 数值保留） | ⚠️ 3216 断点断言 |
| `.user-main` | `.user-console-page .user-main → background: var(--tech-bg) !important; backdrop-filter:none !important;` | `background: var(--lnx-color-bg) !important; backdrop-filter: none !important;` | — |
| `.user-nav` | `#app` 选中态 T11 + `.user-nav button → color:#526070` | `active/hover` T11；静态 `color: var(--lnx-text-secondary);` | — |
| `.wallet-layout` | `background:transparent !important;`（布局） | `background: transparent;` | 无类色 |
| `.quick-list` | `.quick-list button → color:#526070` + `active/hover → background:#eef5ff; color:#0b63ce` | `button → color: var(--lnx-text-secondary);` `active/hover → background: var(--lnx-color-brand-50); color: var(--lnx-text-brand);` | — |
| `.polished` | `.console-stat.polished → box-shadow: 0 14px 32px rgba(15,23,42,.06);` | `box-shadow: var(--lnx-shadow-md);`（布局保留） | — |
| `.refined` | `.quick-list.refined strong → color:#111827;` `small → #64748b` | `strong → var(--lnx-text-primary);` `small → var(--lnx-text-muted);` | — |
| `.nav-dot` | `.nav-dot → background:#94a3b8;` + `.active .nav-dot → #0ea5e9` | `background: var(--lnx-color-neutral-400);` + `.active → var(--lnx-color-cyan-600);` | — |

> **计数校验（v1.6）**：层 A 69 类 + 层 B 37 类 + 层 C 18 类 = 124（与附录 C 一致）+ **补漏 4 类**（附录 C 补漏清单）= **128 类**。**§4-A 的 4 类按硬判据一并恢复**（主理人裁定：硬判据「需补回=0」优先于「建议不恢复」），故 B3F **实际恢复 128 类** + 2 条抑制声明（§5）。

---

## 4. 建议不恢复清单（**高价值**：比机械恢复更重要的是「不恢复什么」）

### 4-A. ~~整类不恢复（4 类）~~ → **v1.6 裁定：一并恢复（4 类）**——父容器 `display:none`，行为零变化

| 类 | 归属文件 | 理由 |
| --- | --- | --- |
| `.orbit` | `HomePage.vue:4-5` | 父 `.hero-atmosphere` 被 §5 R12 抑制为 `display:none !important`，子元素声明**永不渲染** → 恢复即死代码 |
| `.signal` | `HomePage.vue:6` | 同上（`.hero-atmosphere` 子元素） |
| `.signal-two` | `HomePage.vue:7` | 同上 |
| `.constellation` | `HomePage.vue:76-79` | 父 `.model-constellations` 被 R12 抑制为 `display:none !important` → 永不渲染 |

> **净效果（v1.6 裁定覆盖）**：本节 4 类原「建议不恢复」，**现按硬判据「需补回=0」一并恢复**（硬判据优先于「建议不恢复」；R12 已证父 `display:none`、行为零变化，恢复为惰性声明）。故 B3F 恢复目标 = **128 类**（附录 C 124 + 补漏 4），并保留 `.hero-atmosphere`/`.model-constellations`/`.site-hero::before/::after` 的**抑制声明**（§5）。

### 4-B. 逐类「部分声明」不恢复——**深色主题补偿 / 霓虹光晕**（覆盖几乎所有类）

报告中原声明**不带 `#app` 前缀**且取值为下述形态者，**一律丢弃**，对应类仅恢复其浅色 `#app` 权威声明：

- **深色底**：`rgba(14,29,60,.94)`、`rgba(8,19,42,.92)`、`rgba(9,23,50,.7)`、`rgba(6,13,29,.82)`、`rgba(3,7,19,.34)`、`rgba(10,23,49,.72)`、`linear-gradient(145deg, rgba(20,42,82,.86)…)`、`#050916`、`#0b1c3b`、`#08152f`、`#080f25`、`#0d1321`、`#0b1220`、`#0e1421`、`#09152e`、`#101f41`、`#030713` 等。
- **霓虹/发光**：`#39cfff`、`#606cff`、`#80cbff`、`#45d8ff`、`#50d7ff`、`#53d9ff`、`#36cfff`、`#42cfff`、`#9ec5eb`、`#86a5cc`、`#93afd2`，及 `box-shadow: 0 0 24px rgba(32,174,255,.17)`、`0 0 18px 5px rgba(35,168,255,.38)`、`text-shadow: 0 0 19px rgba(49,213,255,.9)`、`filter: drop-shadow(0 0 5px rgba(40,177,255,.48))` 等。
- **深色描边**：`rgba(57,196,255,.48)`、`rgba(70,140,240,.3)`、`rgba(55,227,184,.16)` 等半透霓虹边。

> **典型例**：`.routing-core span` 有两条——深色 `radial-gradient(circle, rgba(37,119,231,.6)…)` + 浅色 `#app … border-color:#93c5fd !important; background:#eff6ff !important; color:#2563eb !important`。**只恢复后者**（→ brand-300/50/600），前者丢弃。
> **⚠️ 反例（必须保留 `none`）**：`background-image: none !important` / `background: none !important` / `backdrop-filter: none !important` / `text-shadow: none !important` / `box-shadow: none !important` 是**抑制指令**（压 EP 或旧图），**不得丢弃**——丢弃会导致 EP 背景图/旧渐变重新显现。

### 4-C. 逐类「部分声明」不恢复——**绿市集遗留子主题**（`.market-*` 专属）

市集页在改造前有一套**绿色子主题**（与蓝色品牌体系冲突），其浅色基色取值应**丢弃**，改由品牌/中性令牌（T1/T3/T4/T10）表达：

`#dfe6e1`、`#cfe0d5`、`#285a3e`、`#eef7e8`、`#497517`、`#eef6e7`、`#4f7b1b`、`#f0f7e9`、`#3f6811`、`#3b4840`、`#455249`、`#26362c`、`#929d96`、`#4c5c52`、`#e1e7e3`、`#f7faf8`、`#e0e7e2`、`#f8faf8`、`#4a5a50`、`#e4eae6`、`#8a958e`、`#334139`、`#69766f`、`#118...`、`#78ad30`、`#219...` 等。

> 涉及类：`.market-badge`、`.market-filter-panel`、`.market-filter-group`、`.market-filter-options`、`.market-filter-icon`、`.market-active-filters`、`.market-meta`、`.market-publisher`、`.market-publisher-fallback`、`.market-result-count`、`.market-hero`、`.market-hero-label`、`.market-tags`、`.market-card-desc`、`.market-empty`。

### 4-D. 逐类「部分声明」不恢复——**渐变 / 特有交互**

| 原声明 | 处置 | 理由 |
| --- | --- | --- |
| `.hero-primary { background: linear-gradient(135deg,#29baff,#4056ff) !important; }`（含 `:hover`） | **丢弃渐变** → `background: var(--lnx-color-brand-600) !important;` | **R11**：`.hero-primary`=`type="primary"`（`HomePage.vue:17`）即 `.el-button--primary`，受 §5.1.4 唯一主按钮支配，渐变会触发 B-2 计算样式断言冲突 |
| `.market-badge.nvidia { background:#edf4ff; color:#3169a7 }` | 丢弃（专有名残留），归入 T10 通用 chip | 无对应令牌语义 |
| `.product-logo / .hero-product 的 radial 网格底` | 丢弃深色版，保留浅色 `#app` 版 | 深色补偿 |
| `.console-status-note.is-purple` 紫色族（`#5d479c`/`#ded7f8`/`#f7f5ff`） | **建议丢弃**或改信息态（`--lnx-status-info-*`） | 令牌体系无紫色档（PRD 未登记）；保留会引入未登记色 |

> **汇总（v1.6）**：整类**不恢复 = 0**（§4-A 4 类经硬判据一并恢复）；**逐声明**不恢复归纳为 **3 个类族**（深色补偿 / 绿市集 / 渐变与紫色），覆盖报告内**约 2/3 的原始声明行**（多数类同时存在深色与浅色两条声明，仅恢复浅色那条）。

---

## 5. R12 关键：浅色下 `display:none` 抑制声明的**原文与新写法**（**工程师最易漏**）

> **陷阱**：`.hero-atmosphere` / `.model-constellations` 是**深色概念层的装饰**。浅色主题下它们的**正确行为是「隐藏」**——依赖一条 `display:none !important` 抑制声明。若 B3F「只补颜色不补抑制」，装饰元素会**重新显现**，破坏官网首屏。
> **证据**：`HomePage.vue:3` `.hero-atmosphere` 内含 `.orbit`（4-5）/`.signal`（6-8）；`HomePage.vue:75` `.model-constellations` 内含 `.constellation`（76-79）。

### 5.1 原始声明（`style.css` 改造前原文）

```css
.public-site.home-theme .site-hero::before,
.public-site.home-theme .site-hero::after,
.hero-atmosphere,
.model-constellations {
  display: none !important;
  background: none !important;
  content: none !important;
}
```

### 5.2 新写法（B3F 恢复目标 · 层 A `#app`）

```css
/* R12：浅色主题下隐藏深色概念装饰层（HomePage 首屏），防止重显现 */
#app .public-site.home-theme .site-hero::before,
#app .public-site.home-theme .site-hero::after,
#app .hero-atmosphere,
#app .model-constellations {
  display: none !important;
  background: none !important;
  content: none !important;
}
```

> **说明**：该声明**不含任何色值**，故**无需令牌化**；改造点仅两点 —— ① 加 `#app` 作用域前缀（`HomePage` 在 `#app` 子树内，非 teleport，前缀有效）；② **原样保留全部三个 `none` 抑制属性**。**不得**因「看起来像遗留代码」而删除。
> **同组附注**：`.constellation` / `.orbit` / `.signal` / `.signal-two`（4 类）因父容器被本抑制隐藏，**其自身声明无需恢复**（见 §4-A）。

---

## 6. 与 `02-architecture.md` 的登记

- **§5.3 B3F「涉及文件」** 已登记本文件：`docs/restyle/04-restore-declarations.md`（见 §7）。
- 本文件为 **B3F 的唯一色值翻译真源**；附录 C 为**归属真源**。二者交叉使用：**先按附录 C 定位容器，再按本文件 §3 取令牌化声明，最后按 §2.4 模板展开。**
- **承诺校验**：B3F 完成后，本文件 §3 每行的「新声明」应可在 `components.css` / 页面 scoped 中 `grep` 命中；**§4-B/C/D 的逐声明不恢复项**不得出现（**§4-A 的 4 类已于 v1.6 并入恢复**，须命中）。

---

## 附录：本文件覆盖的 124 类清单（校验用）

层 A（69）：docs-code-head, paged-table, oauth-binding, channel-ledger-title, module-error-state, tag-row, tone-green, tone-orange, panel-title, brand-image, site-brand-mark, console-brand, site-section, feature-card, health-card, constellation*, grid-icon, hero-actions, hero-atmosphere, hero-feature-grid, hero-footnote, hero-primary, hero-product, hero-promise, hero-secondary, model-constellations, orbit*, output-stack, product-logo, product-toolbar, routing-core, routing-lines, routing-stats, signal*, signal-two*, site-hero, source-stack, available, market-active-filters, market-badge, market-card-actions, market-card-desc, market-card-details, market-catalog-shell, market-details-chevron, market-empty, market-fallback-note, market-filter-group, market-filter-icon, market-filter-options, market-filter-panel, market-grid, market-hero, market-hero-label, market-meta, market-page, market-publisher, market-publisher-fallback, market-publisher-icon, market-result-count, market-tags, service-card-meta, services-count, services-grid, services-hero, pricing-grid, section-head, organization-page, gateway-workbench.

层 B（37）：channel-ledger-table, model-filter-bar, overview-inline-error, console-hero-sub, is-blue, is-green, is-orange, is-purple, total-cost, usage-day-bar, usage-table-scroll, sidebar-balance, response-panel, playground-answer-scroll, playground-raw-collapse, playground-raw-scroll, playground-result-head, playground-billing-panel, playground-billing-note, playground-cost-breakdown, playground-model-empty, playground-model-summary, playground-rate-grid, playground-usage-grid, playground-usage-summary, console-status-note, flow-brand, flow-shell, flow-sidebar, flow-topbar, nav-item, topbar-copy, price-hint, created-key-secret, test-io, test-result-panel, test-result-grid.

层 C（18）：creation-layout, work-grid, detail-layout, pricing-disclosure, product-pricing-cover, service-image, service-image-placeholder, service-index, access-row, account-summary-grid, user-console, user-main, user-nav, wallet-layout, quick-list, polished, refined, nav-dot.

层 A/B **补漏（4，v1.5）**：层 A —— orbit-two, connected, public-page-content；层 B —— channel-test-ledger。这 4 项为**附录 C 124 清单漏项**（类提取口径边界：长写属性 / 复合后缀 / 多选择器同名），由 `02-architecture.md`「附录 C 补漏清单」与 `tests/visualRestoreGuard.test.ts` 常量 `APPENDIX_C_GAPS` 跟踪。

> `*` = §4-A 4 类（**v1.6 按硬判据一并恢复**，原「不恢复」建议被覆盖）。合计 124 类；**B3F 实恢复 128 类**（124 + 补漏 4）。
