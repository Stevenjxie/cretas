# Dispatch 完成归档 — 2026-07-27

### `FEATURE-QC-ENTRY-WEB-TRAINING-QCSOP-20260727-002`

- **状态**：`merged`
- **Owner**：`/root`
- **Base SHA**：`cb09348e7ecbd1520e0666932e5d5c1a9fb0b8a0`
- **功能提交 / PR**：`3a5e4d80f`、`bfce86dc1` / [#1844](https://github.com/Stevenjxie/cretas/pull/1844)
- **实际范围**：质检员 RN 首页首屏增加待人工审核入口并收敛无关信息，底部导航适配安全区；QCSOP 使用真实 Expo Web 与 Web Admin 截图，覆盖手机号白名单邀请、锁定工厂与角色、员工本人设密、登录及忘记密码；Web 标签质检审核后增加可恢复归档、备份记录、技术管理员训练集确认与已批准数据导出，普通质检员无训练确认权限。
- **验证证据**：真实 JPA Context、权限、服务及多设备会话 Java 目标测试 `15/15`；RN Jest `10/10`；Web Vitest `11/11`；Java JAR 与 Web production 制品构建成功且 relevant Git tree 与可信 manifest 一致；tracked secret scan、diff check、真实 Expo Web/Web Admin 截图及 QCSOP 资源完整性检查通过。RN 全量 typecheck 仅剩未修改文件 `ProcessTaskListScreen.test.tsx:227` 的既有 `totalPages` 基线错误。
- **发布状态**：用户后续授权统一发布。Java 已从蓝槽 `10010` 切换到绿槽 `10020`，健康检查、功能标记与切流后 `5/5` 轮观察通过；Web Admin 已原子发布且本地/服务器/网关/公网四方 `index.html` SHA-256 一致；QCSOP 已发布至 `https://aiassist.cretaceousfuture.com/lsmsop/qcsop/`，公网 HTTP 200 且资源校验通过；RN 以 runtime `1.0.3` 发布 production Android OTA `1785095974077`，manifest、CDN bundle 与功能标记校验通过。未训练或发布模型，生产账号、白名单、QC 任务及其他业务数据写入为 `0`。

### `FEATURE-LIUSHANMEN-PHONE-WHITELIST-ONBOARDING-20260727-001`

- **状态**：`merged`
- **Owner**：`/root`
- **Base SHA**：`96025f9e3cfb1d7de1c87fe70cb92177c4760aeb`
- **功能提交 / PR / main 合并提交**：`fef5ec7fb` / [#1840](https://github.com/Stevenjxie/cretas/pull/1840) / `cb09348e7ecbd1520e0666932e5d5c1a9fb0b8a0`
- **实际范围**：工厂管理员按手机号创建白名单邀请并指定本厂角色；员工注册账号固定为手机号、本人设置密码；注册阶段二再次验证同一工厂邀请并拒绝客户端提权；同时保留同手机号多设备并发登录。
- **验证证据**：真实 JPA Context 与 Java 注册/白名单/多设备目标测试 `10/10`，RN Jest `19/19`，Web Vitest `3/3`，Java/Web 候选制品一次构建成功，PR 密钥扫描通过。
- **发布状态**：该能力随 `FEATURE-QC-ENTRY-WEB-TRAINING-QCSOP-20260727-002` 后续统一发布至 Java/Web 生产与 RN production Android OTA；生产账号、白名单和业务数据写入为 `0`。

### `FEATURE-RN-MULTIDEVICE-QC-CONCURRENCY-20260727-001`

- **状态**：`merged`
- **Owner**：`/root`
- **Base SHA**：`986676d1ec1e4e8de54c72f8071a00fe6e0dfda3`
- **功能提交 / PR / main 合并提交**：`4157c93b6c8b4562af7bf8c7279af879c770d541` / [#1839](https://github.com/Stevenjxie/cretas/pull/1839) / `412e6f610bd97889f3bcb6efaa931d08eb21b4f8`
- **实际范围**：移动端 JWT 增加唯一 `jti`，退出仅撤销当前设备会话；标签 QC 审核增加版本、请求幂等键和租户范围行锁；RN 对其他设备已抢先审核的任务给出明确返回待审核列表提示。
- **验证证据**：最终 exact-main 前置 release Maven 目标测试 `13/13`，其中真实 JPA Context 与双线程锁测试 `2/2`；RN 目标 Jest `7/7`；PR 密钥回归门禁通过。
- **发布状态**：该能力随 `FEATURE-QC-ENTRY-WEB-TRAINING-QCSOP-20260727-002` 后续统一发布至 Java 生产与 RN production Android OTA；生产账号、QC 数据和其他业务数据写入为 `0`。

### `BUG-LABEL-QC-WEB-FOOLPROOF-20260726-001`

- **状态**：`merged`
- **Owner**：`/root`
- **Base SHA**：`7e15310df92b85b6031973908e4ee9bc7ae584c2`
- **功能提交 / PR / main 合并提交**：`4c49501ecc01b209b2d42022de952d23e21a18d0` / [#1831](https://github.com/Stevenjxie/cretas/pull/1831) / `1884e9fa8caa30e20441db420de9e175c61e772b`
- **实际范围**：
  - Web QC 审核工作台在低高度桌面端固定显示本图结论与导航，未完成当前照片时禁止进入下一张。
  - 整图正常前显示批量拒绝数量并二次确认；被拒绝 AI 疑点保持可见且支持撤销。
  - 人工补框与整图正常结论互斥；审核页存在未保存变更时阻止误关闭。
- **验证证据**：
  - 目标 Vitest `8/8`，`vue-tsc` 与可信 Web production build 通过。
  - 1440×900、1024×768 浏览器流程通过；四张真实样图按“缺白标 / 正常 / 缺彩标 / 缺白标”完成至 `4/4`。
  - PR `tracked-secret-scan` 通过，浏览器与发布验收的生产业务写入均为 `0`。
- **生产发布**：
  - 从 clean exact `origin/main@1884e9fa8caa30e20441db420de9e175c61e772b` 复用相同 `web-admin` tree `5da5a7ce5d742aed136147b620df7076c7f4ac4f` 的可信制品并原子发布。
  - 制品 archive SHA-256 为 `eecc4115497e6748e30f1f9af53c0e775aa0054f76c23028c1c32de6449dc0d1`。
  - local/server/gateway HTTP/public HTTPS 四方 `index.html` SHA-256 均为 `6a491e118f26c6fb626d4ea2b9bcebea85114f0b44ade5adb4453b2dfc86b79f`，公开 QC 路由 HTTP 200。
  - QC 懒加载资源 `index-Cty-GNXL.js` 的本地/公开 SHA-256 均为 `4b4d2ba2942e38ae6a4b769965a4605a7e9099fa6cbeb38b4add4b69e4b52f7e`，且包含本次功能标记。
- **业务写入审计**：本次仅发布静态 Web 制品；生产业务数据写入为 `0`。
