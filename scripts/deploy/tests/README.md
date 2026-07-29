# 传输链路的负路径测试

这些脚本存在的理由只有一条：**没测过的校验等于没有的校验**。

方案 1 的每个组件都写了一堆拒绝逻辑（URL 白名单、哈希重校验、前缀白名单、
过期上限、并发互斥）。正路径跑通**不能**证明这些拒绝逻辑真的会拒绝 —— 它们
在正路径上根本不执行。所以这里逐条喂坏输入，并断言**具体的错误 token**，
避免一个测试因为工具在别处坏掉而"通过"。

## 跑法

需要真实服务器（不能在 CI 跑）。

```bash
# 东京 Lightsail 上传工具（14 条）
tr -d '\r' < negative-lightsail.sh | ssh <tokyo> 'cat > /tmp/n.sh && bash /tmp/n.sh; rm -f /tmp/n.sh'

# 东京 CI artifact 解包（13 条，含 zip-slip）
tr -d '\r' < negative-artifact-stage.sh | ssh <tokyo> 'cat > /tmp/n.sh && bash /tmp/n.sh; rm -f /tmp/n.sh'

# 上海 ECS 签名器 + 校验器（11 条）
tr -d '\r' < negative-ecs.sh | ssh <ecs> 'cat > /tmp/n.sh && bash /tmp/n.sh; rm -f /tmp/n.sh'
```

```powershell
# Windows 编排器参数校验（8 条）
pwsh -NoProfile -File negative-windows.ps1

# 跨进程并发互斥
pwsh -NoProfile -File test-publish-mutex.ps1
```

## 覆盖

| 组件 | 断言 |
|---|---|
| 东京上传器 | http / 外部 host / 未批准前缀 / URL 带凭证 / 非 443 端口 / 路径穿越 / 前缀相似域名；大写与短 sha；零 size；空 stdin；缓存缺失；**内容被篡改**；size 不符 |
| ECS 签名器 | 未批准前缀 / 穿越前缀 / 大写与短 sha / 非法 tree sha（含 shell 元字符）/ 零 size / 过期超 900s / 拒绝时不泄漏凭证 |
| ECS 校验器 | 未批准前缀 / 对真实前缀拒绝 purge / 对象不存在 |
| Windows 编排器 | 大写与短 sha / 未批准前缀 / 穿越前缀 / 非法 tree sha / repo 含 shell 元字符 / 非数字 assetId / 零 size |
| CI artifact 解包 | jar-name 含路径分隔符 / 绝对路径 / 子目录 / 非 `.jar` 后缀；zip-size 非正；URL 白名单 5 条；空 stdin；**zip-slip**（构造含 `../` 与绝对路径成员的压缩包，断言不写出工作目录、只提取精确成员）|
| 并发互斥 | 跨进程持锁时拒绝；崩溃留下的遗弃锁能恢复而不是卡死后续发布 |

## 两个踩过的坑（写测试时踩的，记下来免得重踩）

1. **`$Args` 是 PowerShell 自动变量**，用它当参数名会让整个 harness 静默失效 ——
   当时输出是 `pass=0 fail=0`，只看 `fail=0` 会误判成全部通过。断言数量，不要只断言失败数。

2. **Windows Mutex 对同一线程可重入**。在测试进程里持锁再 `& $script` 调用，
   锁**不会**拦住它 —— 这会让人误判互斥失效。真实场景是两个独立进程，
   必须用 `Start-Process` 起独立进程持锁才测得准。
