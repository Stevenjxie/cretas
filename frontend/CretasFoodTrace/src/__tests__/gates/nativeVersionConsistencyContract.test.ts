/**
 * 闸 —— 一个 App 的版本号存在**四个**地方, 它们必须一致。
 *
 * <h2>🔴 为什么有这道闸 (2026-08-17 实测)</h2>
 * `android/` 目录是**提交在 git 里**的, `expo prebuild` 看到它已存在就不会覆盖里面的值。
 * 于是 2026-08-09 的 1.0.4 bump 改了 `app.json` 和 `build.gradle`, **漏了 strings.xml**:
 *
 * ```
 * app.json          "version": "1.0.4"   "runtimeVersion": "1.0.4"
 * build.gradle      versionName "1.0.4"
 * strings.xml       expo_runtime_version = 1.0.3      ← 漂了
 * ```
 *
 * 后果不是"显示的版本不对", 而是 **OTA 彻底失效, 且每次推送都报成功**:
 *
 * | | 值 | 谁在用 |
 * |---|---|---|
 * | `versionName` | 1.0.4 ✓ | 设置里显示的版本 / 强制更新判断 |
 * | `expo_runtime_version` | 1.0.3 ✗ | **只**用来问 OTA 服务器要哪棵 bundle 树 |
 *
 * 装着 1.0.4 二进制的机器拿着一张写"我是 1.0.3"的名片去要更新,
 * 而 `push-bundle.sh` 从 app.json 读 runtimeVersion, 每次都推进 **1.0.4** 树 ——
 * **零交集**。自 8-09 起 OTA 一次都没送达过任何设备。
 *
 * 【当天的实测读数】服务器端埋点 (`OTA_PULL`) 一上线就把它照出来了:
 * ```
 * 10 分钟内 5 次真实拉取 / 3 个公网 IP, 全部 runtime=1.0.3
 * runtime 1.0.3 树  最新 bundle = 08-09 12:14   ← 设备只够得到这个
 * runtime 1.0.4 树  最新 bundle = 08-17 22:44   ← 我们一直在推这里
 * ```
 * 而在此之前, 「OTA 有没有送达」这件事**没有任何读数** —— 推送脚本报成功、
 * manifest 四步验收全过, 全都在验那棵没人在的树。
 *
 * <h2>这是形态 D 的教科书例子</h2>
 * 「同一个东西有两份, 它一定会漂」。而且是最阴的那种:
 * **能跑、不报错、只是跑的是旧的那份。**
 * 抽不成一份(原生资源必须是字面量), 所以按规则的解法 —— **上一道闸钉住"两份一致"**。
 */
import fs from 'fs';
import path from 'path';

const RN_ROOT = path.resolve(__dirname, '../../../');
const APP_JSON = path.join(RN_ROOT, 'app.json');
const STRINGS_XML = path.join(RN_ROOT, 'android/app/src/main/res/values/strings.xml');
const BUILD_GRADLE = path.join(RN_ROOT, 'android/app/build.gradle');
const APP_CONFIG = path.join(RN_ROOT, 'app.config.js');

function readAppJson(): { version?: string; runtimeVersion?: string } {
  const raw = JSON.parse(fs.readFileSync(APP_JSON, 'utf8')) as {
    expo?: { version?: string; runtimeVersion?: string };
  };
  return raw.expo ?? {};
}

/** 从 strings.xml 取 expo_runtime_version 的字面值。 */
function nativeRuntimeVersion(): string | null {
  const src = fs.readFileSync(STRINGS_XML, 'utf8');
  const m = src.match(/<string\s+name="expo_runtime_version"[^>]*>([^<]+)<\/string>/);
  return m?.[1] ? m[1].trim() : null;
}

function gradleValue(key: 'versionName' | 'versionCode'): string | null {
  const src = fs.readFileSync(BUILD_GRADLE, 'utf8');
  const m =
    key === 'versionName'
      ? src.match(/versionName\s+"([^"]+)"/)
      : src.match(/versionCode\s+(\d+)/);
  return m?.[1] ? m[1].trim() : null;
}

function appConfigVersionCode(): string | null {
  const src = fs.readFileSync(APP_CONFIG, 'utf8');
  const m = src.match(/versionCode:\s*(\d+)/);
  return m?.[1] ? m[1].trim() : null;
}

describe('原生版本号一致性契约', () => {
  it('四个来源都要解析得出值 (阳性对照 —— 解析不到时下面的比较会变成恒真)', () => {
    // 🔴 没有这一条, 「两个 null 相等」会让整道闸静默失效, 而它看起来完全正常。
    const app = readAppJson();
    const parsed = {
      'app.json version': app.version ?? null,
      'app.json runtimeVersion': app.runtimeVersion ?? null,
      'strings.xml expo_runtime_version': nativeRuntimeVersion(),
      'build.gradle versionName': gradleValue('versionName'),
      'build.gradle versionCode': gradleValue('versionCode'),
      'app.config.js versionCode': appConfigVersionCode(),
    };
    const unparsed = Object.entries(parsed)
      .filter(([, v]) => !v)
      .map(([k]) => k);
    expect(unparsed).toEqual([]);
    // 再钉一下形状: 版本号必须是 x.y.z, 免得正则抓到别的东西也算"解析到了"
    expect(parsed['app.json runtimeVersion']).toMatch(/^\d+\.\d+\.\d+$/);
    expect(parsed['strings.xml expo_runtime_version']).toMatch(/^\d+\.\d+\.\d+$/);
  });

  it('strings.xml 的 expo_runtime_version 必须等于 app.json 的 runtimeVersion', () => {
    // 这一条就是 2026-08-09~08-17 那九天 OTA 全线失效的那个缺口。
    // 它决定设备去问**哪一棵 bundle 树**要更新; 推送脚本读的是 app.json。
    // 两者不等 = 推的树和要的树不是同一棵 = OTA 静默失效(而推送照样报成功)。
    const declared = readAppJson().runtimeVersion;
    expect(nativeRuntimeVersion()).toBe(declared);
  });

  it('build.gradle 的 versionName 必须等于 app.json 的 version', () => {
    // versionName 决定强制更新判断(后端按它比 minimumVersion)。
    // 它和 runtimeVersion 是**两件事**, 8-09 那次只改对了这一个 —— 所以两条都要钉。
    expect(gradleValue('versionName')).toBe(readAppJson().version);
  });

  it('build.gradle 与 app.config.js 的 versionCode 必须一致', () => {
    expect(gradleValue('versionCode')).toBe(appConfigVersionCode());
  });
});
