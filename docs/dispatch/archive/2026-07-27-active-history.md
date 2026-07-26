# Dispatch 完成归档 — 2026-07-27

### `FEATURE-RN-MULTIDEVICE-QC-CONCURRENCY-20260727-001`

- **状态**：`merged`
- **Owner**：`/root`
- **Base SHA**：`986676d1ec1e4e8de54c72f8071a00fe6e0dfda3`
- **功能提交 / PR / main 合并提交**：`4157c93b6c8b4562af7bf8c7279af879c770d541` / [#1839](https://github.com/Stevenjxie/cretas/pull/1839) / `412e6f610bd97889f3bcb6efaa931d08eb21b4f8`
- **实际范围**：移动端 JWT 增加唯一 `jti`，退出仅撤销当前设备会话；标签 QC 审核增加版本、请求幂等键和租户范围行锁；RN 对其他设备已抢先审核的任务给出明确返回待审核列表提示。
- **验证证据**：最终 exact-main 前置 release Maven 目标测试 `13/13`，其中真实 JPA Context 与双线程锁测试 `2/2`；RN 目标 Jest `7/7`；PR 密钥回归门禁通过。
- **发布状态**：按用户指示仅合并，后端生产与 RN OTA 均未部署；生产账号、QC 数据和其他业务数据写入为 `0`。

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
