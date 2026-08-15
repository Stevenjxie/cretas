# E2E 跑在哪、用哪个账号 —— 以及三个会让读数作废的坑

**最后更新**: 2026-08-15

## 结论先行

```bash
# web 五套件(生产)
cd web-admin
export E2E_BASE_URL=https://admin.cretaceousfuture.com
export TEST_FACTORY_ADMIN_USER=f006_admin
export TEST_FACTORY_ADMIN_PASS=<本地配置, 见 .env.test.example>
npx playwright test --project=web-admin-e2e --no-deps --workers=1

# RN 两套件: 先起 Expo, 且**必须换一个 env 文件名**(见坑 ③)
cd frontend/CretasFoodTrace
ENVFILE=.env.prod.local npx expo start --web --port 3010 --clear
```

- **写入只落 F006**(团队测试租户, `.env.test.example` 明确「可以写入」)。
  已核实 7 个套件里没有任何一处引用真客户 **LIUSHANMEN**。
- 工厂 ID 一律**跟随会话**, 不写死 —— 写死 `F001` 会让纯 API 用例全 403,
  而页面用例照常通过, 长得像「某个接口坏了」。

## 账号

`factory_admin1 / 123456` 是**死值**: 迁移 `V20261029_68` 把工厂域收敛成只剩
F006 + LIUSHANMEN, F001 连同 `factory_admin1` / `workshop_sup1` 一并物理删除
(`.env.test.example` 开头就记着)。实测生产库 130 个用户里没有它, 两个登录端点都 401。

用 `.env.test.example` 里既有的 `TEST_FACTORY_ADMIN_USER/PASS`。没配口令时套件会
自动退回 storageState 会话注入 —— 仍然全绿, 但那是**注入**不是登录, 看日志里
「口令登录不可用」出现几次就知道走的哪条路。

---

## ⚠️ 坑 ①: `139.196.165.140:8086` 连的是**生产库**

那台「测试环境」的 Java 进程 `DB_NAME=cretas_prod_db`(问 `/proc/<pid>/environ` 得到,
不是看配置文件)。所以在 :8086 上跑写入型用例, 数据落在**生产库**里 ——
只是租户是 F006。别以为「测试环境 = 测试数据」。

## ⚠️ 坑 ②: 端口有响应 ≠ 是我刚起的那个实例

重启 Expo 时若旧进程还占着 3010, 新进程在非交互模式下会直接退出
(它想问「用 3011 吗」), 而 `curl localhost:3010` 照样 200 —— 那是**旧实例**。
判据要问「监听 3010 的进程是哪个 pid、什么时候起的」:

```bash
powershell -NoProfile -Command "
  \$c = Get-NetTCPConnection -LocalPort 3010 -State Listen
  \$p = Get-Process -Id \$c[0].OwningProcess
  'pid=' + \$p.Id + ' start=' + \$p.StartTime"
```

## ⚠️ 坑 ③: 改 `.env` 内容不会换掉 RN 的 API 地址

`babel.config.js`:

```js
api.cache.using(() => process.env.ENVFILE);
```

**缓存键是 env 文件的「名字」, 不是它的「内容」。** 用同一个 `.env.local` 改内容重启,
Babel 直接复用旧转译结果 —— 进程是新的、日志正常、端口正常, 而 bundle 里烘的还是旧地址。

判据: **grep 产物**, 不要信配置文件。

```bash
curl -s http://localhost:3010/ -o idx.html
SRC=$(grep -oE 'src="[^"]*bundle[^"]*"' idx.html | head -1 | cut -d'"' -f2)
curl -s "http://localhost:3010${SRC}" -o bundle.js
grep -c "admin.cretaceousfuture.com" bundle.js   # 应 >0
grep -c "139.196.165.140" bundle.js              # 应 =0
```

修法: 换一个**文件名**(如 `.env.prod.local`, 仍被 `.env*.local` 忽略) + `--clear`。

---

## 三个坑的共同形态

都不是「报错了没看见」, 而是**读数完全正常、但量的不是我以为的那个东西**:
端口通 → 通的是旧实例;「测试环境」→ 写的是生产库; 重启了 → bundle 没变。
判据一律是**问被测对象本身**(pid / `/proc/environ` / 产物字节), 不是问配置或日志。

## ⚠️ 坑 ④: 别在套件跑的时候动工作树

Playwright 是**每个 project 启动时**才加载 spec 文件。跑到一半 `git checkout` 到别的分支,
前面的 project 用的是旧版、后面的用新版 —— **同一轮读数里混着两份代码**, 而输出完全正常。

实测长相 (2026-08-15): 我在生产跑 5 个套件的中途切分支去写文档, 而账号重构还在未合入的
分支上。结果各套件「口令登录不可用」的次数是 0/1/1/1/2 —— 我一度当成正常波动,
其实是前半程走真登录、后半程读到 origin/main 的硬编 `factory_admin1` 后退回注入。

判据: 跑长套件前把工作树固定住(`git checkout --detach <sha>`), 跑完再动。
要并行改代码就**另开 worktree**, 别在同一个里切。

顺带一条便宜的自检: 在结果行里打印「兜底次数」这类**旁证计数**。
它和通过数不是同一个来源, 一旦混版就会露出不一致 —— 这次就是靠它发现的。
