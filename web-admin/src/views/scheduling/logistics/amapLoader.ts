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
  Marker: new (opts: Record<string, unknown>) => AMapOverlay;
  Polyline: new (opts: Record<string, unknown>) => AMapOverlay;
  Pixel: new (x: number, y: number) => unknown;
  LngLat: new (lng: number, lat: number) => AMapLngLat;
  Driving: new (opts: Record<string, unknown>) => AMapDriving;
  DrivingPolicy?: Record<string, number>;
};

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
  on(event: string, handler: () => void): void;
}

export interface AMapInstance {
  add(overlays: AMapOverlay[]): void;
  remove(overlays: AMapOverlay[]): void;
  setFitView(overlays: AMapOverlay[] | null, immediately?: boolean, avoid?: number[]): void;
  destroy(): void;
}

export function getAmapKey(): string | undefined {
  const key = import.meta.env.VITE_AMAP_JS_KEY as string | undefined;
  return key && key.trim() ? key.trim() : undefined;
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

  const securityCode = import.meta.env.VITE_AMAP_JS_SECURITY_CODE as string | undefined;
  if (securityCode && securityCode.trim()) {
    w._AMapSecurityConfig = { securityJsCode: securityCode.trim() };
  }

  amapPromise = new Promise<AMapNamespace>((resolve, reject) => {
    const script = document.createElement('script');
    // plugin=AMap.Driving —— 驾车路径规划，画沿实际道路的导航路线（非直线连点）
    script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(key)}&plugin=AMap.Driving`;
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
