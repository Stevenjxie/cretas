/**
 * 闸 —— RN 依赖必须被 lockfile 钉住, 且安装出来的版本要与 lockfile 一致。
 *
 * <h2>为什么有这道闸</h2>
 * 2026-08-17 实测: 分发中的 **v1.0.4 APK 一启动就崩**, 任何设备都崩:
 *
 * ```
 * java.lang.NoSuchMethodError: No static method getDirectConverter(...)
 *   in class Lexpo/modules/kotlin/types/ReturnTypeKt;
 *   at expo.modules.font.FontLoaderModule.definition(FontLoaderModule.kt:98)
 * ```
 *
 * 从 APK 的 dex 直接证死(不需要设备, dex 全 ABI 共用):
 *   · 调用方在: `invoke-static Lexpo/modules/kotlin/types/ReturnTypeKt;.getDirectConverter:(...)`
 *   · 被调方不在: `ReturnTypeKt` 只声明了 `toReturnType`
 *   · 阳性对照: 同一把尺子看得到 `toReturnType`(2 处) 与 `FontLoaderModule`(92 处)
 *
 * 成因: **打 APK 的脚本用的是 `npm install`, 不是 `npm ci`**
 * (`scripts/build-android-apk.sh` 与 `.claude/skills/build-android-apk/scripts/build-apk.sh`),
 * 而且没有任何 CI workflow 打 APK —— 是本地人工打的。
 * `npm install` 会在 lockfile 之外重新解析; `expo-font` 又不在 `package.json` 里,
 * 是 `expo` 的**传递依赖** ⇒ 打包机上它解析成了比锁里更新的版本,
 * 配着仍是 2.5.0 的 `expo-modules-core`, 配出「新 expo-font + 旧 core」这一对。
 *
 * ⚠️ **我第一版把成因写成「lockfile 没进 git」—— 那是错的, 留在这里当教训。**
 * 真相是 lockfile **一直在版本控制里**(`frontend/CretasFoodTrace/package-lock.json`,
 * 自 2026-06-16 起没变过), 且在 1.0.4 bump 那天锁的就是今天这套好版本
 * (`expo@53.0.27 / expo-modules-core@2.5.0 / expo-font@13.3.2`)。
 * 我当时用 `git ls-files … | head -3` 查, **被截断**了 —— 同一个截断错误那天犯了三次。
 * ⇒ 判据: 查「某文件在不在版本控制里」用 `git ls-files --error-unmatch <file>`, ⛔ 不要用管道 + head。
 *
 * <h2>⚠️ 这道闸守什么、不守什么(口径, 必须写清)</h2>
 * **守**: 依赖漂移 —— lockfile 在不在、有没有钉住关键包、装出来的版本对不对得上。
 * ⛔ **不守**: 「App 能不能启动」。它看不见 dex 层的悬空方法调用。
 * 真正能抓到那次事故的只有**构建后把 APK 装起来跑一次**(冒烟闸), 那是另一件事。
 * 把这道闸当成「装了就不会再崩」是错的 —— 它只是把复发概率从「随机」降到「有人显式改了 lockfile」。
 */
import fs from 'fs';
import path from 'path';

const RN_ROOT = path.resolve(__dirname, '../../../');
const LOCK = path.join(RN_ROOT, 'package-lock.json');

/** 这几个包的版本错配已经造成过一次线上事故, 必须钉死。 */
const MUST_BE_PINNED = ['expo', 'expo-modules-core', 'expo-font'];

interface LockShape {
  lockfileVersion?: number;
  packages?: Record<string, { version?: string }>;
}

function readLock(): LockShape {
  return JSON.parse(fs.readFileSync(LOCK, 'utf8')) as LockShape;
}

function installedVersion(pkg: string): string | null {
  const p = path.join(RN_ROOT, 'node_modules', pkg, 'package.json');
  if (!fs.existsSync(p)) return null;
  return (JSON.parse(fs.readFileSync(p, 'utf8')) as { version?: string }).version ?? null;
}

describe('RN 依赖锁定契约', () => {
  it('package-lock.json 必须存在 (2026-08-17 之前它不在版本控制里, 那是 v1.0.4 崩溃的根因)', () => {
    expect(fs.existsSync(LOCK)).toBe(true);
  });

  it('lockfile 必须钉住三个出过事的包', () => {
    const lock = readLock();
    const pkgs = lock.packages ?? {};

    // 🔴 阳性对照: 先证明这把尺子真的解析到了东西。
    //    没有它,「三个包都钉住了」和「packages 是空对象」长得一模一样。
    expect(Object.keys(pkgs).length).toBeGreaterThan(500);

    // ⚠️ Jest 的 expect 只收一个参数(两参形式是 Vitest 的) —— 失败信息靠这个数组带出来。
    const missing: string[] = [];
    const badVersion: string[] = [];
    for (const name of MUST_BE_PINNED) {
      const entry = pkgs[`node_modules/${name}`];
      if (!entry) { missing.push(name); continue; }
      if (!/^\d+\.\d+\.\d+/.test(entry.version ?? '')) badVersion.push(`${name}=${entry.version}`);
    }
    expect(missing).toEqual([]);       // 不在 lockfile 里 = 它会随每次 install 漂
    expect(badVersion).toEqual([]);    // 在 lockfile 里但没有确定版本号
  });

  it('装出来的版本要与 lockfile 一致 (对不上说明有人 npm install 覆盖了锁)', () => {
    const pkgs = readLock().packages ?? {};
    const drifted: string[] = [];
    let checked = 0;

    for (const name of MUST_BE_PINNED) {
      const locked = pkgs[`node_modules/${name}`]?.version;
      const actual = installedVersion(name);
      if (!locked || !actual) continue;   // 没装依赖的环境跳过, 由上一条断言兜底
      checked += 1;
      if (locked !== actual) drifted.push(`${name}: lock=${locked} 实际=${actual}`);
    }

    // 🔴 阳性对照: 至少真的比对过一个包, 否则「没有漂移」只是因为一个都没比。
    expect(checked).toBeGreaterThan(0);
    expect(drifted).toEqual([]);
  });

  it('打 APK 的脚本必须用 npm ci —— 用 npm install 就会绕开锁 (v1.0.4 事故的直接成因)', () => {
    const REPO = path.resolve(RN_ROOT, '../../');
    const scripts = [
      'scripts/build-android-apk.sh',
      '.claude/skills/build-android-apk/scripts/build-apk.sh',
    ].map((p) => path.join(REPO, p));

    // 🔴 阳性对照: 脚本得真的在, 否则「没有裸 npm install」只是因为没读到文件。
    const present = scripts.filter((p) => fs.existsSync(p));
    expect(present.length).toBe(scripts.length);

    const offenders: string[] = [];
    for (const p of present) {
      const src = fs.readFileSync(p, 'utf8');
      // 逐行看: 只认「实际执行的那一行」, 注释里提到的不算。
      src.split('\n').forEach((line, i) => {
        const code = line.replace(/#.*$/, '').trim();
        if (/^npm\s+install(\s|$)/.test(code)) {
          offenders.push(`${path.relative(REPO, p)}:${i + 1}: ${code}`);
        }
      });
    }
    expect(offenders).toEqual([]);
  });

  it('打 APK 的脚本必须调冒烟闸 —— 「构建成功」不等于「跑得起来」', () => {
    // 这一条守的是**接线**, 不是脚本存在。
    // v1.0.4 那次, 依赖是坏的、包也打出来了、所有判据都绿 ——
    // 缺的就是「装起来跑一次」这一步。冒烟脚本写了而没人调, 等于没写。
    const REPO = path.resolve(RN_ROOT, '../../');
    const SMOKE = path.join(REPO, 'scripts/smoke-android-apk.sh');
    expect(fs.existsSync(SMOKE)).toBe(true);

    // 冒烟闸必须是**三态**(本仓硬约束 4): rc=2「这次没量到」要与 rc=0「没问题」分开,
    // 否则一台没有设备的打包机会安静地天天绿, 而它一次都没打开过那个包。
    const smokeSrc = fs.readFileSync(SMOKE, 'utf8');
    expect(smokeSrc).toContain('exit 2');

    const callers = [
      // 🔑 发布入口最重要 —— v1.0.4 通过了 deploy-apk.sh 的**每一道**闸
      //    (签名对/残留扫描过/上传成功/下载可达), 因为没有一道问「它打得开吗」。
      'scripts/deploy/deploy-apk.sh',
      'scripts/build-android-apk.sh',
      '.claude/skills/build-android-apk/scripts/build-apk.sh',
    ];
    const notWired: string[] = [];
    let scanned = 0;
    for (const rel of callers) {
      const src = fs.readFileSync(path.join(REPO, rel), 'utf8');
      scanned += 1;
      // 只认执行行: 注释里写「以后要接冒烟」不算接上了。
      const wired = src.split('\n').some((line) => {
        const code = line.replace(/#.*$/, '').trim();
        return code.includes('smoke-android-apk.sh') || /"\$\{?SMOKE\}?"/.test(code) || /"\$smoke"/.test(code);
      });
      if (!wired) notWired.push(rel);
    }

    // 🔴 阳性对照: 真的读了两个脚本, 否则「都接上了」可能只是一个都没读。
    expect(scanned).toBe(callers.length);
    expect(notWired).toEqual([]);
  });
});
