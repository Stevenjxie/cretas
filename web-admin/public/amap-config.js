/* 高德地图运行时配置占位 (不含密钥 — 可提交)。
 * 生产环境由 nginx 从 dist 外稳定位置 serve 一个含真 key 的同名文件覆盖本占位
 * (部署不覆盖 → key 独立于构建, 并发/别 worktree 部署不再冲掉地图 key)。
 * 未配 nginx 时本占位为空, amapLoader 回落到构建期 VITE_AMAP_JS_KEY。 */
window.__AMAP_CONFIG__ = window.__AMAP_CONFIG__ || {};
