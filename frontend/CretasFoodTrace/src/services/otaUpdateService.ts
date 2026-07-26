import AsyncStorage from '@react-native-async-storage/async-storage';
import { Alert, AppState, type AppStateStatus } from 'react-native';
import * as Updates from 'expo-updates';

import i18n from '../i18n';

export type OtaUpdateResult =
  | 'up_to_date'
  | 'available'
  | 'downloaded'
  | 'skipped'
  | 'failed';

export type OtaManualStatus =
  | 'up_to_date'
  | 'available'
  | 'downloaded'
  | 'disabled'
  | 'failed'
  | 'timeout';

type OtaApplyAttempt = {
  updateId: string;
  attemptedAt: number;
};

const OTA_APPLY_ATTEMPT_KEY = '@cretas_ota_apply_attempt';
const OTA_APPLY_RETRY_SUPPRESS_MS = 60 * 60 * 1000;
const FOREGROUND_RECHECK_MIN_MS = 5 * 60 * 1000;
const MANUAL_CHECK_TIMEOUT_MS = 20_000;
const MANUAL_DOWNLOAD_TIMEOUT_MS = 120_000;
const OTA_TIMEOUT_MARKER = '__cretas_ota_timeout__';

let foregroundListenerAttached = false;
let checkInFlight = false;
let applyPromptShown = false;
let hasReloadedThisSession = false;
let otaUpdating = false;
let lastCheckStartedAt = 0;
let availableUpdateIdThisSession: string | null = null;
let updateDownloadedThisSession = false;
let updateDownloadPromise: Promise<void> | null = null;
const otaUpdatingListeners = new Set<(updating: boolean) => void>();

function getManifestUpdateId(manifest: unknown): string | null {
  if (!manifest || typeof manifest !== 'object') return null;
  const id = Reflect.get(manifest, 'id');
  return typeof id === 'string' && id.length > 0 ? id : null;
}

function setOtaUpdating(next: boolean): void {
  if (otaUpdating === next) return;
  otaUpdating = next;
  otaUpdatingListeners.forEach((listener) => listener(next));
}

export function subscribeOtaUpdating(
  listener: (updating: boolean) => void,
): () => void {
  listener(otaUpdating);
  otaUpdatingListeners.add(listener);
  return () => {
    otaUpdatingListeners.delete(listener);
  };
}

async function readApplyAttempt(): Promise<OtaApplyAttempt | null> {
  try {
    const raw = await AsyncStorage.getItem(OTA_APPLY_ATTEMPT_KEY);
    if (!raw) return null;
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object') return null;
    const updateId = Reflect.get(parsed, 'updateId');
    const attemptedAt = Reflect.get(parsed, 'attemptedAt');
    if (typeof updateId !== 'string' || typeof attemptedAt !== 'number') {
      return null;
    }
    return { updateId, attemptedAt };
  } catch {
    return null;
  }
}

async function rememberApplyAttempt(updateId: string | null): Promise<void> {
  if (!updateId) return;
  try {
    await AsyncStorage.setItem(
      OTA_APPLY_ATTEMPT_KEY,
      JSON.stringify({ updateId, attemptedAt: Date.now() }),
    );
  } catch (error) {
    console.warn('[OTA] Failed to persist apply attempt:', error);
  }
}

async function clearAppliedAttemptIfNeeded(): Promise<void> {
  const attempt = await readApplyAttempt();
  if (!attempt || Updates.updateId !== attempt.updateId) return;
  try {
    await AsyncStorage.removeItem(OTA_APPLY_ATTEMPT_KEY);
  } catch {
    // A stale suppression record is non-fatal and expires after one hour.
  }
}

async function shouldSuppressRepeatedPrompt(
  updateId: string | null,
): Promise<boolean> {
  if (!updateId) return false;
  if (Updates.updateId === updateId) return true;

  const attempt = await readApplyAttempt();
  if (!attempt || attempt.updateId !== updateId) return false;
  return Date.now() - attempt.attemptedAt <= OTA_APPLY_RETRY_SUPPRESS_MS;
}

function predownloadOtaUpdate(): Promise<void> {
  if (updateDownloadedThisSession) return Promise.resolve();
  if (updateDownloadPromise) return updateDownloadPromise;

  updateDownloadPromise = Updates.fetchUpdateAsync()
    .then((result) => {
      availableUpdateIdThisSession =
        getManifestUpdateId(result.manifest) ?? availableUpdateIdThisSession;
      updateDownloadedThisSession = true;
    })
    .catch((error: unknown) => {
      updateDownloadPromise = null;
      throw error;
    });

  return updateDownloadPromise;
}

async function applyDownloadedUpdateWithReload(): Promise<void> {
  if (hasReloadedThisSession) return;

  try {
    setOtaUpdating(true);
    await predownloadOtaUpdate();
    await rememberApplyAttempt(availableUpdateIdThisSession);
    hasReloadedThisSession = true;
    await Updates.reloadAsync();
  } catch (error) {
    hasReloadedThisSession = false;
    applyPromptShown = false;
    setOtaUpdating(false);
    console.warn('[OTA] User-triggered update failed:', error);
  }
}

function promptToApplyUpdate(): void {
  if (applyPromptShown || hasReloadedThisSession) return;
  applyPromptShown = true;
  Alert.alert(
    i18n.t('common:ota.update_ready_title'),
    i18n.t('common:ota.update_ready_message'),
    [
      {
        text: i18n.t('common:ota.later'),
        style: 'cancel',
      },
      {
        text: i18n.t('common:ota.update_now'),
        onPress: () => {
          void applyDownloadedUpdateWithReload();
        },
      },
    ],
  );
}

export async function checkAndPromptForOtaUpdate(): Promise<OtaUpdateResult> {
  if (__DEV__ || !Updates.isEnabled) return 'skipped';

  try {
    await clearAppliedAttemptIfNeeded();
    const check = await Updates.checkForUpdateAsync();
    if (!check.isAvailable) return 'up_to_date';

    availableUpdateIdThisSession = getManifestUpdateId(check.manifest);
    if (await shouldSuppressRepeatedPrompt(availableUpdateIdThisSession)) {
      return 'skipped';
    }

    promptToApplyUpdate();
    void predownloadOtaUpdate().catch((error: unknown) => {
      console.warn('[OTA] Background predownload failed:', error);
    });
    return 'available';
  } catch (error) {
    console.warn('[OTA] Non-fatal update check failed:', error);
    return 'failed';
  }
}

function runBackgroundCheck(force: boolean): void {
  if (__DEV__ || checkInFlight) return;
  const now = Date.now();
  if (!force && now - lastCheckStartedAt < FOREGROUND_RECHECK_MIN_MS) return;

  lastCheckStartedAt = now;
  checkInFlight = true;
  void checkAndPromptForOtaUpdate().finally(() => {
    checkInFlight = false;
  });
}

/**
 * Run once after the application shell mounts and re-check when the app returns
 * to the foreground. Native launch checks stay disabled so weak factory
 * networks never block the splash screen.
 */
export function scheduleBackgroundOtaCheck(): void {
  if (__DEV__) return;
  runBackgroundCheck(true);

  if (foregroundListenerAttached) return;
  foregroundListenerAttached = true;
  AppState.addEventListener('change', (next: AppStateStatus) => {
    if (next === 'active') runBackgroundCheck(false);
  });
}

function withTimeout<T>(promise: Promise<T>, timeoutMs: number): Promise<T> {
  return Promise.race([
    promise,
    new Promise<T>((_, reject) => {
      setTimeout(() => reject(new Error(OTA_TIMEOUT_MARKER)), timeoutMs);
    }),
  ]);
}

export async function checkForOtaUpdateManual(): Promise<{
  status: OtaManualStatus;
  error?: string;
}> {
  if (__DEV__ || !Updates.isEnabled) return { status: 'disabled' };
  try {
    const check = await withTimeout(
      Updates.checkForUpdateAsync(),
      MANUAL_CHECK_TIMEOUT_MS,
    );
    if (check.isAvailable) {
      availableUpdateIdThisSession = getManifestUpdateId(check.manifest);
    }
    return { status: check.isAvailable ? 'available' : 'up_to_date' };
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    return {
      status: message.includes(OTA_TIMEOUT_MARKER) ? 'timeout' : 'failed',
      error: message,
    };
  }
}

export async function downloadOtaUpdateManual(): Promise<{
  status: 'downloaded' | 'failed' | 'timeout';
  error?: string;
}> {
  setOtaUpdating(true);
  try {
    await withTimeout(predownloadOtaUpdate(), MANUAL_DOWNLOAD_TIMEOUT_MS);
    return { status: 'downloaded' };
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    setOtaUpdating(false);
    return {
      status: message.includes(OTA_TIMEOUT_MARKER) ? 'timeout' : 'failed',
      error: message,
    };
  }
}

export async function applyOtaUpdateNow(): Promise<void> {
  await applyDownloadedUpdateWithReload();
}

export function getOtaRuntimeVersion(): string | null {
  return Updates.runtimeVersion ?? null;
}

export function getOtaUpdateLabel(): string {
  if (__DEV__ || !Updates.isEnabled || !Updates.updateId) return 'embedded';
  return Updates.updateId.replace(/-/g, '').slice(0, 8);
}
