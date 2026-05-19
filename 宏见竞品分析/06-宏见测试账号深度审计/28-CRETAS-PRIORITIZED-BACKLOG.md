# 28 — Cretas Prioritized Backlog (基于 Round 1-9 累计 +370d 重排)

> **统一战略 backlog** — 把 9 轮 audit 累计的 61+ 项整合, 按客户群 + ROI 重新 P0/P1/P2/P3/Archive 分类.
>
> **Cretas Sprint 计划应基于本 doc** (取代 v2.1 amendments 估的 84d, 真实 454d).
>
> ⚠️ **2026-05-16 audit reconcile**: 真实 ship 状态详见 `30-BACKLOG-STATUS-AUDIT.md`. **14 项已 ship + 3 项 in flight, 真实剩余 74 项 / ~373d nominal**. Sign-off "9 月 P0+P1" 真实剩余 **7 月** (省 2 月).
>
> ⚡ **2026-05-19 Round 11 deep re-audit reconcile (本 doc 内 Table Status 列严重 stale)**:
> - **30+ 项已 ship 但本 doc 仍标 ❌** — 包括 F-VFLAG-1 (#1, PR #693) / C-LINKARRAY-1 (#2) / S-LOCK-1 (#3) / M-BOM-VER-1 backend (#4) / C-APPROVAL-EDITOR Phase 1 (#5, PR #862) / C-PRT-EDITOR (#6) / S-CRM-FULL-1 (#19) / S-CUSTOMER-TAB-1 (#20) / S-CRM-1 (#21) / S-PRICE-1 (#22) / S-CREDIT-1 (#23) / S-INVOICE-CLIENT-1 (#24) / S-PROFIT-DETAIL-1 (#25) / S-REMIND-1 (#26) / S-NEED-1 (#27) / S-PAYMENT-DATE-1 (#28) / P-NUCLEAR-1 (#30) / P-IMPORT-1 (#32) / W-CLASS-1 (#33) / M-WIP-1 (#34) / M-MATTREE-1 (#35) / M-PREP-1 (#36) / M-DELIVERY-WARN-1 (#37) / F-AR-1 (#38) / F-INV-1 (#39) / H-WAGE-FULL (#40) / H-LEAVE+OVT+EXP (#42) / Q-MODE-1 (#43) / Q-PROCESS-1 (#44) / Q-RETURN-1 (#45) / C-CHECKPOWER-1 (#46) / C-LOG-AUDIT-1 (#51) / C-EXPORT-CENTER-1 (#52) / C-IMPORT-CENTER-1 (#53) / U-VIEW-1 (#57) / U-NEW-1 (#58) / U-ICON-1 (#59) / U-MARKER-1 (#60) / U-FEED-1 (#61) / U-DEPT-1 (#63) / U-CHIP-MULTI-1 (#64) / C-WIDGET-1 (#65) / C-INLINE-CS-1 (#66).
> - **真实 ship 数字**: 49 ✅ + 7 ⚠️ + 3 🟡 + 29 ❌ = 88 (vs 5-16 audit 12 ✅ / 73 ❌).
> - **真实剩余工时**: ~150d nominal / **~3 月** (vs 7 月).
> - **C-APPROVAL-EDITOR-1 (#5) 严重高估**: 标 20d 实际剩 3-5d (Cretas Track-I 758-line VueFlow + Canvas Phase 1 已 ship, 仅剩 incremental).
> - **完整 reconcile**: `../04-最终决策/31-DEEP-RE-AUDIT.md` (3517 行, 16 sections, +10 fresh HJ screenshots) + `30-BACKLOG-STATUS-AUDIT.md §13`.
> - **后续**: 本 doc 下方 Table Status 列待 inline 修正, 当前以 31-doc §P.4 reconcile 表为 truth source. 决策面建议直接读 31-doc §P.

---

## 0.1 Ship Status Summary (2026-05-16 audit)

### 28-Backlog 88 项 ship 状态

| 优先级 | 总数 | ✅ Ship | ⚠️ Flight/Blocked | ❌ 剩余 |
|---|---|---|---|---|
| P0 战略 | 12 | 5 (N20/N24/25/N32/N13/N48) | 1 (Track-B1 钉钉 Day 5/6) | 6 |
| P0 必修 | 6 | 4 (M3/M4/M5/N3) | 2 (M1 #538 / M2 #622-623) | 0 |
| P1 战术 | 47 | 3 (U-NAV-1/U-ACT-1/U-FOOTER-1) | 0 | 44 |
| P2 选做 | 15 | 0 | 0 | 15 |
| P3 长期 | 8 | 0 | 0 | 8 |
| **合计 88** | | **12 ✅** | **3 ⚠️** | **73 ❌** |

### 28-Backlog 漏收录 (但已 ship 的 main MUST_COPY 项)

| 项 | 来源 | Ship 证据 |
|---|---|---|
| S-MRP-1 销售→采购自动分流 | MUST_COPY N31 (P0) | Sprint2-E #682 ✅ |
| P-FIN-1 采购订单财务审核+三价标红 | MUST_COPY P1-2 (P1) | Sprint2-J #675 ✅ |

**说明**: 28-Backlog 偏重 R-HJ Round 4-9 新增, 部分主 MUST_COPY 项没重列. 这 2 项已 ship 但本 doc 没账, 详见 `04-最终决策/MUST_COPY.md` 主表.

**ship 已 saved**: ~56d nominal / ~34 工日 (含 2 项漏收录).

**真实"9 月 P0+P1"剩余**: ~50 项 / ~196d nominal / 118 工日 / **7 月** (单人 Claude 加速).

---

## 0. 客户群定位 (战略前提)

| 客户群 | 优先级 | 决策影响 |
|---|---|---|
| **F006 六扇门 (卤制品工厂)** | **ASAP/P0** | 主战场, 当前唯一活跃客户 |
| **餐饮 QHJ (已上线)** | 维护 P1 | 10% 资源, bug fix only |
| **食品厂第 2-3 客户 (在谈)** | P0/P1 | 跟 F006 同业, 复用度 ≥ 70% |
| **大型集团 (上市公司/跨国)** | P2 | 长期, 复式记账/期间结账/资料定制 |
| **餐饮多门店连锁** | P2 | 餐饮主线扩展 |
| **电子/注塑/五金工厂** | Archive (Cretas 不主推) | 宏见主推, 避开正面竞争 |

**Cretas 战略**: **食品 + 餐饮专精**, AI/食品溯源/移动原生 差异化. 不跟宏见正面拼"全功能 ERP".

---

## 1. P0 战略级 (12 项 / ~80d) — Sprint 0-4 必上

### 1.1 已 list (主 MUST_COPY)

| # | 编号 | 项 | 工时 (nominal) | Claude 加速后 | **Status (audit 2026-05-16)** |
|---|---|---|---|---|---|
| 1 | **F-VFLAG-1** | 凭证生成 hook (vflag 4 状态 + 7 generator + 批量) | 10d | 6d | ❌ |
| 2 | **C-LINKARRAY-1** | linkListArray 8 类跨业务关联 | 2d | 1d | ❌ |
| 3 | **S-LOCK-1** | 锁定/备货/缺料 3 维度 + 公式 (行内显示) | 1d | 0.5d | ❌ |
| 4 | **M-BOM-VER-1 升级** | BOM 工程级 (BOMID + 版本 + 工作流 + ECN + 反查 + 批量) | 15d | 9d | ❌ |
| 5 | **C-APPROVAL-EDITOR-1** | 工作流可视化拖拽编辑器 (后端已实装) | 20d | 12d | ❌ |
| 6 | **C-PRT-EDITOR-1** | 打印模板可视化设计器 (后端已 ship 5 单据) | 10d | 6d | ❌ |
| 7 | **N49 C-AI-1** | 钉钉机器人 PoC | 6d | 4d | ⚠️ Track-B1 Day 5/6 (no PR) |
| 8 | **N20 C-ATT-1** | 通用 attachment 系统 | 5d | 3d | **✅ Track-C #658** |
| 9 | **N24/N25 M-WP-1/2** | 工序管理 + 产品工序配置 (前端) | 5d | 3d | **✅ Track-D2 #650** |
| 10 | **N32 M-BOM-1** | BOM 配方编辑 UI | 5d | 3d | **✅ Track-D1 #656** |
| 11 | **N13 W-ABA-1** | 抄码品识别 | 2d | 1d | **✅ Track-B2 #649** |
| 12 | **N48 S-RD-1** | 研发样品 → BOM → 报价链路 | 5d | 3d | **✅ Sprint2-F #680** |

**P0 合计**: ~86d nominal / **~52d 实际 (Claude 1.7×)** = **~10 周单人**

---

## 2. P0 必修 Bug (6 项 / ~14d)

| # | 编号 | 项 | 工时 | **Status (audit 2026-05-16)** |
|---|---|---|---|---|
| 13 | M1 三价对比刷新 | T3-14 test env seed blocker | 2d | ⚠️ blocked by issue #538 (F006 test seed) |
| 14 | M2 生产工序通用关联 | partial #567 + open #622/#623 | (P3 deferred, 不抄) | ⚠️ partial ship — feature live in prod, #622/#623 QA-gap P3 deferred per original author intent (2026-05-16 reconcile) |
| 15 | M3 PDF + 扫码 RN 端 | 后端 #413 ship, RN 待 | 4d | **✅ Track-B2 #653** |
| 16 | M4 BOM 物料选择器 | spec D2 | 2d | **✅ Track-D1 #656** |
| 17 | M5 单位转换强校验 | spec D3 | 2d | **✅ Track-D1 #656** |
| 18 | C-RBAC-1 仓管隔离审计 | PR #423 + 35-view defense | 2d | **✅ #661 + k4/k5 follow-ups (#668/#671/#673/#674)** |

**P0 必修合计**: 14d / 8d 实际 — **ship 10d (M3+M4+M5+N3), 剩 4d blocked**

---

## 3. P1 战术级 (28 项 / ~85d)

### 3.1 客户/CRM 域 (5 项)
| # | 编号 | 项 | 工时 |
|---|---|---|---|
| 19 | S-CRM-FULL-1 | Customer 扩展 22 字段 (税号/法人/客户状态11/重要程度4/来源11) | 5d |
| 20 | S-CUSTOMER-TAB-1 | 客户档案 21 跟踪 tab (跟踪/微信/通话/谈话录音/邮件) | 15d |
| 21 | S-CRM-1 | 客户跟踪记录 UI (后端有) | 3d |
| 22 | S-PRICE-1 | 客户记忆价 | 3d |
| 23 | S-CREDIT-1 | 客户信用管理 (P2 候选, 大客户) | 5d |

### 3.2 销售域 (6 项)
| # | 编号 | 项 | 工时 |
|---|---|---|---|
| 24 | S-INVOICE-CLIENT-1 | 客户级开票税率 17 档 + 发票类型 6 档 (含数电票) | 2d |
| 25 | S-PROFIT-DETAIL-1 | 产品级销售利润详情页 (11 列) | 2d |
| 26 | S-REMIND-1 | 收款提醒 → OA 任务集成 | 3d |
| 27 | S-NEED-1 | 销售需求独立模块 | 5d |
| 28 | S-PAYMENT-DATE-1 | 客户级对账日期 (1-31 号) | 1d |
| 29 | S-REPORTS-PRESETS | 销售 14+ 预置报表模板 | 8d |

### 3.3 采购域 (3 项)
| # | 编号 | 项 | 工时 |
|---|---|---|---|
| 30 | P-NUCLEAR-1 | 核价单 (询价→核价→采购) | 3d |
| 31 | P-DRAFT-1 | 采购底稿草稿态 | 1d |
| 32 | P-IMPORT-1 | 采购类型 (正常/进口) | 1d |

### 3.4 仓库/生产域 (5 项)
| # | 编号 | 项 | 工时 |
|---|---|---|---|
| 33 | W-CLASS-1 | 仓库 10 分类枚举 | 1d |
| 34 | M-WIP-1 | 在制品 (WIP) 状态 + 独立子域 | 3d |
| 35 | M-MATTREE-1 | 物料需求 tree 模式 | 4d |
| 36 | M-PREP-1 | 生产任务预备 (草稿态) | 2d |
| 37 | M-DELIVERY-WARN-1 | 生产交货预警 dashboard | 3d |

### 3.5 财务/HR 域 (4 项)
| # | 编号 | 项 | 工时 |
|---|---|---|---|
| 38 | F-AR-1 | 销售订单财务成本核算审核 | 5d |
| 39 | F-INV-1 | 开票申请 + 发票回写 + 收款流水 | 8d |
| 40 | H-WAGE-FULL | 工资管理 11 项 (社保/专项扣除/年度) | 10d |
| 41 | H-ATT-FULL | 考勤管理 11 项 (高级排班/异常分析) | 10d |
| 42 | H-LEAVE-1+OVT+EXP | 请假/调休/报销 3 套 | 12d |

### 3.6 品质域 (2 项)
| # | 编号 | 项 | 工时 |
|---|---|---|---|
| 43 | Q-MODE-1 | 全检/抽检模式区分 | 1d |
| 44 | Q-PROCESS-1 | 工序质检不良 + 失败原因 + 处理结果闭环 | 5d |
| 45 | Q-RETURN-1 | 质检退回单 (退采购/委外) | 3d |

### 3.7 系统/平台域 (8 项)
| # | 编号 | 项 | 工时 |
|---|---|---|---|
| 46 | C-CHECKPOWER-1 | RBAC 权限检查统一函数 | 3d |
| 47 | C-WF-RULE-1 | 流转规则引擎 (金额/部门/角色阈值) | 10d |
| 48 | C-WF-VAR-1 | 工作流系统变量库 ({#own}, 业务变量) | 3d |
| 49 | C-OPINION-1 | 节点意见模板 (常用语) | 2d |
| 50 | C-VOUCHER-TPL-1 | 凭证模板系统 | 5d |
| 51 | C-LOG-AUDIT-1 | 系统操作日志独立 (5 列 + 查询导出) | 3d |
| 52 | C-EXPORT-CENTER-1 | 数据导出规则中心 (跨 12 模块) | 5d |
| 53 | C-IMPORT-CENTER-1 | 数据导入规则中心 (含校验/未导入/成功/失败) | 5d |

### 3.8 UX 域 (8 项)
| # | 编号 | 项 | 工时 | **Status (audit 2026-05-16)** |
|---|---|---|---|---|
| 54 | U-NAV-1 | 业务流程图导航 (jsPlumb 7-14 节点 + AI 触发) | 6d | **✅ Sprint2-G #683/#684** |
| 55 | U-ACT-1 | 行末"操作 ▼" 11 项 (含 AI 入口) | 6d | **✅ Sprint2-H #678** |
| 56 | U-FOOTER-1 | Sticky Footer 实时合计 | 4d | **✅ Sprint2-I #681** |
| 57 | U-VIEW-1 | 列表 view 5 模式切换 | 3d | ❌ |
| 58 | U-NEW-1 | 创建 4 模式 (普通/一维/二维/BOM 展开) | 4d | ❌ |
| 59 | U-ICON-1 | 行内 7 icon 工具集 | 3d | ❌ |
| 60 | U-MARKER-1 | 订单标记 7 色 | 1d | ❌ |
| 61 | U-FEED-1 | 升级日志 in-app feed | 2d | ❌ |
| 62 | U-DESKTOP-MODAL-1 | layui-layer 桌面级 modal (4 操作) | 3d | ✅ |
| 63 | U-DEPT-1 | 部门切换 button row | 1d | ❌ |
| 64 | U-CHIP-MULTI-1 | 行内多 chip 状态 (4 chip 垂直堆) | 1d | ❌ |
| 65 | C-WIDGET-1 | dashboard 卡片插件式 (10 独立 endpoint 渲染) | 5d | ❌ |
| 66 | C-INLINE-CS-1 | 在线客服 iframe | 1d | ❌ |

**P1 合计**: ~152d nominal / ~92d 实际 = **~18 周单人**

---

## 4. P2 选做 (15 项 / ~110d) — Sprint 5-8

### 4.1 大型企业/上市公司财务
| # | 编号 | 项 | 工时 | 客户群 |
|---|---|---|---|---|
| 67 | F-VOUCHER-2-1 | 复式记账凭证 (借/贷 + 辅助核算) | 20d | 大企业 |
| 68 | F-PERIOD-1 | 期间结账 (月结/年结锁定) | 8d | 大企业 |
| 69 | F-3REPORT-1 | 报表三表 (资产负债/损益/现金流) | 12d | 上市公司 |
| 70 | C-CUSTOM-1 | 资料定制 (字段/公式) | 20d | 多行业 |

### 4.2 大销售团队 / B2B 协同
| # | 编号 | 项 | 工时 | 客户群 |
|---|---|---|---|---|
| 71 | S-OPP-1 | 商机管理 (lead/opportunity 漏斗) | 8d | 大销售 |
| 72 | P-SPLIT-1 | 采购订单按供应商拆单 | 5d | |
| 73 | P-RFQ-1 | 询价管理 (多供应商比价) | 5d | |
| 74 | S-COMPLAINT-1 | 售后服务投诉 12 字段 | 4d | |
| 75 | S-COMMISSION-1 | 合作伙伴佣金报表 (12 月统计) | 5d | 代理/分销 |
| 76 | S-CALL-STAT-1 | 外呼通话统计 (15s/30s/60s/120s 多档) | 8d | (需云硬件) |
| 77 | C-CRM-FULL | 客户 50 项含商机 3 / 报表 6 / 资料定义 6 | 15d | |

### 4.3 餐饮 / 多门店 / 食品扩展
| # | 编号 | 项 | 工时 |
|---|---|---|---|
| 78 | C-STORE-1 | 门店管理 5 子项 (餐饮 QHJ 升级) | 5d |
| 79 | S-STORE-REPLEN-1 | 门店补货 10 列 | 5d |
| 80 | C-IMAGE-LIB-1 | 公共图片库 (跨企业共享) | 3d |
| 81 | C-FILE-DOMAIN-1 | 文件管理独立子域 (file.hongjian.com) | 3d |

**P2 合计**: ~126d nominal / ~76d 实际 = **~15 周单人**

---

## 5. P3 长期 / 战略级 (8 项 / ~70d)

| # | 编号 | 项 | 工时 | 备注 |
|---|---|---|---|---|
| 82 | **C-TV-DASHBOARD-1** | TV 大屏 Android app (跟 SmartBI 集成) | 15d | HoanTV.apk 对照, 餐饮厨房屏/工厂车间屏 |
| 83 | **C-MENU-ENGINE-1** | menu.jsp?m=X 配置驱动菜单架构 | 8d | (Cretas 当前 hardcoded) |
| 84 | **C-RBAC-FNO-1** | 细粒度 f_no 权限点 (跟 C-CHECKPOWER-1) | 15d | 长期 P3 |
| 85 | **C-MICROSERVICE-1** | 38 子域微服务架构 (Cretas 当前 monolith) | 长期 | 战略 |
| 86 | **C-WECHAT-DOMAIN-1** | 微信子域独立 (weixin.hongjian.com) | 5d | F006 用钉钉, 暂不需 |
| 87 | **C-PARTNER-DOMAIN-1** | 合作伙伴管理独立子域 | 3d | |
| 88 | **C-DOCS-DOMAIN-1** | help.cretas.com 独立 docs 子域 | 5d | |
| 89 | **C-SERVICE-CODE-1** | 服务代码显示 (footer small) | 0.5d | 客户报问题方便 |

**P3 合计**: ~51d nominal / ~30d 实际

---

## 6. Archive (Cretas 不抄, 客户群不需要 / 反对项)

### 6.1 宏见主推但 Cretas 食品/餐饮线不需要
- **委外管理整模块** (entrust 41 项) — F006 不外包
- **办公自动化整模块** (oa 93 项, 报销/借款除外) — 用钉钉
- **mould 模具管理** — F006 卤制品无模具
- **device 设备生命周期** — F006 设备简单, Cretas 现有够
- **多公司账套 / 集团合并** — Cretas factory_id 已支持基础
- **wxshop 微信网店** — 接入腾讯微分销, F006 不卖小商品
- **mail.hongjian.com / sms.hongjian.com** — 客户用阿里云

### 6.2 跟宏见正面竞争模块 (避开)
- 跨国/多币种深度 (32 币种)
- 国际贸易 / 报关 / 信用证
- APS 高级排产 (M-APS-1, 长期)
- 模具/工具/周转箱深度

---

## 7. 工时累计最终修正

| 优先级 | 项数 | Nominal 工时 | Claude 加速后 |
|---|---|---|---|
| **P0 战略** | 12 | 86d | 52d |
| **P0 必修** | 6 | 14d | 8d |
| **P1 战术** | 47 | 152d | 92d |
| **P2 选做** | 15 | 126d | 76d |
| **P3 长期** | 8 | 51d | 30d |
| **合计** | **88 项** | **429d** | **258 实际工日** |

### 时间表
- ASAP (P0 战略 + 必修, 18 项): 100d nominal / **60 工日 ≈ 13 周 ≈ 3 月**
- Sprint 1-2 (+ P1 28 项): + 152d / 92 工日 ≈ 18 周
- ASAP + Sprint 1-2 (66 项): **252d / 152 工日 ≈ 31 周 ≈ 7 月**
- + Sprint 3-4 (P2 15 项): + 126d / 76 工日 ≈ 15 周
- 全 P0+P1+P2 (81 项): **378d / 228 工日 ≈ 46 周 ≈ 10.5 月**
- + Sprint 5+ (P3 8 项): + 51d / 30 工日 ≈ 6 周
- **全部 88 项**: **429d / 258 工日 ≈ 52 周 ≈ 12 月单人**

**+ 25% buffer (audit 1 经验 — 隐藏成本 / 客户反馈 / 重写)**:
- ASAP: 3 月 → **3.75 月** (15 周)
- ASAP + P1: 7 月 → **8.75 月** (38 周)
- 全 P0+P1+P2: 10.5 月 → **13.1 月**
- **全部**: 12 月 → **15 月** (65 周)

---

## 8. Sprint 计划修正建议 (vs 现有 v2.1)

### 8.1 现有 v2.1 vs 修正版
| Sprint | v2.1 (基于错估 84d) | **修正版 (基于 429d)** |
|---|---|---|
| Sprint 0 | Week 1-2 (设计) | Week 1-2 (无变) |
| ASAP (Sprint 1) | Week 3-7 (P0) | **Week 3-15 (P0 + 必修) — 12 周** |
| Sprint 2 | Week 7-10 | **Week 16-22 — P1 上半 (CRM + 销售)** |
| Sprint 3 | Week 11-14 | **Week 23-30 — P1 下半 (财务 + HR + 系统)** |
| Sprint 4 | Week 15-18 | **Week 31-36 — UX 13 项 (U-NAV/U-ACT 等)** |
| Sprint 5 | Week 19-22 | **Week 37-44 — P2 财务深 (复式记账 + 三表)** |
| Sprint 6 | Week 23-24+ | **Week 45-52 — P2 其他 (商机/拆单/RFQ)** |
| Sprint 7+ | (无规划) | **Week 53-65 — P3 长期 (TV 大屏 / 微服务架构 / RBAC 细粒度)** |

### 8.2 战略推荐
1. **不要全做** — 88 项全做需 15 月. **选 P0+P1 优先, 共 66 项 / 252d nominal / 9 月**
2. **客户群导向**: 食品/餐饮专精, **避免 P2 大客户场景** (复式记账/资料定制) 除非客户实际需求
3. **AI/移动差异化** — Cretas 当前 Tool 404 + RN App + 食品溯源 + YOLO 是宏见永远赶不上的
4. **配置中台** P0 必上 (C-APPROVAL-EDITOR + C-PRT-EDITOR + C-WF-RULE) — 客户自服务能力跟宏见拉平
5. **TV 大屏 P3** 选做 — 餐饮厨房屏 / 工厂车间屏, 跟 SmartBI 集成有差异化

---

## 9. 数字对比

| 维度 | 原 MUST_COPY | **本 backlog** | 增长 |
|---|---|---|---|
| 项数 | 33 | **88** | 2.7× |
| Nominal 工时 | 84d | **429d** | 5.1× |
| Claude 加速后 | ~50d | **258d** | 5.2× |
| 单人时间 | 2-3 月 | **15 月** | 6-7× |

---

## 10. 团队规模 / 工时分配建议

### Option A — 单人 15 月 (现 Cretas 资源)
- 9-10 月: 一个 Cretas 工程师, 含 Claude 加速 + 25% buffer

### Option B — 双人 (Sprint 2 起加人) ~8 月
- Sprint 0-1 单人 (现有)
- Sprint 2 起加 1 工程师 (CRM/财务 vs UX 分工)

### Option C — 3 人并行 ~5-6 月
- A: Backend (vflag/工作流/打印 P0 战略)
- B: Frontend UX 13 项 + 移动 RN 增强
- C: CRM/财务 P1

**推荐**: B (双人) — Cretas 现资源 + ROI 平衡

---

## 11. 完成度

✅ 88 项统一 list (从 9 轮 audit 整合)
✅ P0/P1/P2/P3/Archive 重新分类
✅ 工时 nominal + Claude 加速 + buffer 三档
✅ Sprint 计划 v2.1 → 修正版对比
✅ 团队规模 3 option (单/双/3 人)
✅ 客户群导向 (食品/餐饮专精, 避大客户场景)

---

## 12. Steve sign-off (2026-05-16)

✅ **Sprint 范围**: P0+P1 共 66 项 / 9 月 (Recommended)
✅ **客户群战略**: 食品/餐饮专精 (Recommended) — 不打电子/注塑/五金
✅ **团队规模**: 单人 Steve (现状) — 不加工程师
⛔ **P2 延后 / P3 选做** (隐含, 跟 9 月范围一致)

**生效**: 后续 Sprint 计划锁定 252d nominal / 152 工日 / 38 周 (8.75 月) + 25% buffer = 9-10 月.
