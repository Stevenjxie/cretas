/**
 * 高德地图 JS API 2.0 按需加载器。
 *
 * 密钥/安全密钥从 Vite env 注入（`VITE_AMAP_JS_KEY` / `VITE_AMAP_JS_SECURITY_CODE`），
 * 源码不硬编码任何真值（两个 GitHub 仓库均 public，见 feedback_public_repo_no_hardcoded_secrets）。
 * 真值放 gitignored 的 `.env.production.local`，构建时 Vite 烘进 bundle；线上由高德「域名白名单」保护。
 *
 * 加载失败（无 key / 网络 / 域名未白名单）时调用方应回落到 SVG 示意图，绝不阻塞工作台。
 */

let amapPromise: Promise<AMapNamespace> | null = null;

/** 高德 JS API 命名空间（局部弱类型即可，避免引入完整 @amap/amap-jsapi-types 依赖）。 */
export type AMapNamespace = {
  Map: new (container: HTMLElement, opts: Record<string, unknown>) => AMapInstance;
  Marker: new (opts: Record<string, unknown>) => AMapMarker;
  Polyline: new (opts: Record<string, unknown>) => AMapOverlay;
  Pixel: new (x: number, y: number) => unknown;
  LngLat: new (lng: number, lat: number) => AMapLngLat;
  Driving: new (opts: Record<string, unknown>) => AMapDriving;
  DrivingPolicy?: Record<string, number>;
  AutoComplete?: new (opts: Record<string, unknown>) => AMapAutoComplete;
  Geocoder?: new (opts?: Record<string, unknown>) => AMapGeocoder;
  plugin?: (names: string[], cb: () => void) => void;
};

/** 输入提示（AMap.AutoComplete）—— 门店定位搜索框用，仿导航 app 输入联想。 */
export interface AMapAutoComplete {
  search(keyword: string, callback: (status: string, result: AMapAutoCompleteResult) => void): void;
}
export interface AMapAutoCompleteTip {
  name: string;
  district?: string;
  address?: string;
  location?: AMapLngLat | { lng: number; lat: number };
}
export interface AMapAutoCompleteResult {
  tips?: AMapAutoCompleteTip[];
}

/** 地理编码（AMap.Geocoder）—— 打开定位面板时对地址做一次初始猜测定位, 供用户在此基础上拖拽/搜索修正。 */
export interface AMapGeocoder {
  getLocation(
    address: string,
    callback: (status: string, result: { geocodes?: Array<{ location: AMapLngLat }> }) => void,
  ): void;
}

export interface AMapLngLat {
  getLng(): number;
  getLat(): number;
}

/** 驾车路径规划：search(起点, 终点, {waypoints}, cb) → 沿实际道路的 steps[].path。 */
export interface AMapDriving {
  search(
    origin: AMapLngLat,
    destination: AMapLngLat,
    opts: { waypoints?: AMapLngLat[] },
    callback: (status: string, result: AMapDrivingResult) => void,
  ): void;
}

export interface AMapDrivingResult {
  routes?: Array<{ steps?: Array<{ path?: AMapLngLat[] }> }>;
}

export interface AMapOverlay {
  on(event: string, handler: (e: unknown) => void): void;
}

/** 可拖拽定位用 Marker（继承基础 Marker，加 position 读写 —— LocationPicker 拖拽取经纬度用）。 */
export interface AMapMarker extends AMapOverlay {
  setPosition(pos: [number, number] | AMapLngLat): void;
  getPosition(): AMapLngLat;
  setDraggable?(draggable: boolean): void;
  setMap?(map: AMapInstance | null): void;
}

export interface AMapInstance {
  add(overlays: AMapOverlay[]): void;
  remove(overlays: AMapOverlay[]): void;
  setFitView(overlays: AMapOverlay[] | null, immediately?: boolean, avoid?: number[]): void;
  on(event: string, handler: (e: unknown) => void): void;
  resize(): void;
  destroy(): void;
}

interface AmapRuntimeConfig {
  key?: string;
  securityCode?: string;
}

/** 运行时注入的高德配置 (index.html 引的 /amap-config.js 里 window.__AMAP_CONFIG__)，优先于构建期 env。 */
function runtimeAmapConfig(): AmapRuntimeConfig {
  const cfg = (window as unknown as { __AMAP_CONFIG__?: AmapRuntimeConfig }).__AMAP_CONFIG__;
  return cfg && typeof cfg === 'object' ? cfg : {};
}

export function getAmapKey(): string | undefined {
  // 运行时配置优先 (nginx serve, 部署不覆盖)，回落构建期 env (本地开发 / 未配 nginx 时)。
  const runtimeKey = runtimeAmapConfig().key;
  const key = (runtimeKey && runtimeKey.trim()) || (import.meta.env.VITE_AMAP_JS_KEY as string | undefined);
  return key && key.trim() ? key.trim() : undefined;
}

function getAmapSecurityCode(): string | undefined {
  const runtimeCode = runtimeAmapConfig().securityCode;
  const code = (runtimeCode && runtimeCode.trim()) || (import.meta.env.VITE_AMAP_JS_SECURITY_CODE as string | undefined);
  return code && code.trim() ? code.trim() : undefined;
}

let drivingPromise: Promise<boolean> | null = null;

/**
 * 按需懒加载 AMap.Driving 驾车规划插件（不阻塞基础地图首屏）。
 *
 * 用法：地图挂载后 / "AI 计算中" loading 期间在后台 `void ensureDriving()` 预热，
 * 到真正需要实时驾车规划兜底（某车次缺后端 roadPath）时插件已就绪。
 * resolve(true)=插件可用；resolve(false)=加载失败（调用方回落虚线直线，不阻断）。
 */
export function ensureDriving(): Promise<boolean> {
  const w = window as unknown as { AMap?: AMapNamespace & { plugin?: (names: string[], cb: () => void) => void } };
  if (w.AMap && w.AMap.Driving) return Promise.resolve(true);
  if (drivingPromise) return drivingPromise;
  drivingPromise = loadAmap()
    .then(
      () =>
        new Promise<boolean>((resolve) => {
          const amap = w.AMap as (AMapNamespace & { plugin?: (names: string[], cb: () => void) => void }) | undefined;
          if (!amap || typeof amap.plugin !== 'function') {
            resolve(false);
            return;
          }
          amap.plugin(['AMap.Driving'], () => resolve(Boolean(w.AMap && w.AMap.Driving)));
        }),
    )
    .catch(() => {
      drivingPromise = null; // 允许下次重试
      return false;
    });
  return drivingPromise;
}

let pickerPluginsPromise: Promise<boolean> | null = null;

/**
 * 按需懒加载 AMap.AutoComplete + AMap.Geocoder 插件（门店定位面板用：搜索联想 + 地址初始猜测定位）。
 * 用法与 {@link ensureDriving} 一致：打开定位面板时调用一次，resolve(true)=均可用；
 * resolve(false)=加载失败，调用方回落「无搜索建议 / 手动拖拽」，不阻断定位面板打开。
 */
export function ensurePicker(): Promise<boolean> {
  const w = window as unknown as { AMap?: AMapNamespace };
  if (w.AMap && w.AMap.AutoComplete && w.AMap.Geocoder) return Promise.resolve(true);
  if (pickerPluginsPromise) return pickerPluginsPromise;
  pickerPluginsPromise = loadAmap()
    .then(
      () =>
        new Promise<boolean>((resolve) => {
          const amap = w.AMap;
          if (!amap || typeof amap.plugin !== 'function') {
            resolve(false);
            return;
          }
          amap.plugin(['AMap.AutoComplete', 'AMap.Geocoder'], () =>
            resolve(Boolean(w.AMap && w.AMap.AutoComplete && w.AMap.Geocoder)));
        }),
    )
    .catch(() => {
      pickerPluginsPromise = null; // 允许下次重试
      return false;
    });
  return pickerPluginsPromise;
}

export function loadAmap(): Promise<AMapNamespace> {
  const w = window as unknown as { AMap?: AMapNamespace; _AMapSecurityConfig?: unknown };
  if (w.AMap) {
    return Promise.resolve(w.AMap);
  }
  if (amapPromise) {
    return amapPromise;
  }

  const key = getAmapKey();
  if (!key) {
    return Promise.reject(new Error('未配置 VITE_AMAP_JS_KEY'));
  }

  const securityCode = getAmapSecurityCode();
  if (securityCode && securityCode.trim()) {
    w._AMapSecurityConfig = { securityJsCode: securityCode.trim() };
  }

  amapPromise = new Promise<AMapNamespace>((resolve, reject) => {
    const script = document.createElement('script');
    // 只加载基础地图，不内联 AMap.Driving 插件 —— 后端已可靠持久化每条车次的 roadPath(沿路折线)，
    // 前端画线零调用驾车 API；内联插件曾让脚本加载慢到 ~9s。缺 roadPath 的极少数车次画诚实虚线直线。
    // (若将来确需实时驾车规划，用 AMap.plugin(['AMap.Driving'], cb) 按需懒加载，别塞回基础脚本。)
    script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(key)}`;
    script.async = true;
    script.onerror = () => {
      amapPromise = null; // 允许下次重试
      reject(new Error('高德地图 JS 加载失败'));
    };
    script.onload = () => {
      if (w.AMap) {
        resolve(w.AMap);
      } else {
        amapPromise = null;
        reject(new Error('高德地图 JS 已加载但 AMap 未就绪'));
      }
    };
    document.head.appendChild(script);
  });
  return amapPromise;
}
