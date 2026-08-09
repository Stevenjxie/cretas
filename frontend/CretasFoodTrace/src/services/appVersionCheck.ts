import axios from 'axios';
import Constants from 'expo-constants';
import { Platform } from 'react-native';

import { API_BASE_URL } from '../constants/config';
import { logger } from '../utils/logger';
import { compareSemver } from './compareSemver';

export { compareSemver };

const versionLogger = logger.createContextLogger('AppVersionCheck');
const VERSION_CHECK_TIMEOUT_MS = 10_000;

interface VersionCheckPayload {
  currentVersion?: string;
  latestVersion?: string;
  minimumVersion?: string;
  updateRequired?: boolean;
  updateAvailable?: boolean;
  downloadUrl?: string;
  releaseNotes?: string;
  fileSize?: number;
}

interface VersionCheckEnvelope {
  success?: boolean;
  data?: VersionCheckPayload;
}

export type AppVersionCheckResult =
  | {
      status: 'supported';
      currentVersion: string;
    }
  | {
      status: 'update_required';
      currentVersion: string;
      minimumVersion: string;
      latestVersion: string;
      downloadUrl: string | null;
      releaseNotes: string | null;
    }
  | {
      status: 'unavailable';
      currentVersion: string;
    };

function getCurrentVersion(): string {
  return (
    Constants.nativeAppVersion ??
    Constants.expoConfig?.version ??
    '0.0.0'
  );
}

/**
 * Check the anonymous version endpoint before mounting any login or business
 * navigation. A confirmed unsupported binary is fail-closed. A network error
 * is fail-open so an already supported build can retain offline field use.
 */
export async function checkAppMinVersion(): Promise<AppVersionCheckResult> {
  const currentVersion = getCurrentVersion();

  // The minimum binary version applies only to the native RN application.
  if (Platform.OS === 'web') {
    return { status: 'supported', currentVersion };
  }

  try {
    const response = await axios.get<VersionCheckEnvelope>(
      `${API_BASE_URL}/api/mobile/version/check`,
      {
        params: {
          currentVersion,
          platform: Platform.OS === 'ios' ? 'ios' : 'android',
        },
        timeout: VERSION_CHECK_TIMEOUT_MS,
      },
    );

    const payload = response.data?.data;
    if (!payload) {
      throw new Error('Version response did not contain data');
    }

    const minimumVersion = payload.minimumVersion?.trim() || '';
    const latestVersion = payload.latestVersion?.trim() || minimumVersion;
    const updateRequired =
      payload.updateRequired === true ||
      (minimumVersion.length > 0 &&
        compareSemver(currentVersion, minimumVersion) < 0);

    versionLogger.info(
      `App version check: current=${currentVersion}, minimum=${minimumVersion || 'unknown'}, latest=${latestVersion || 'unknown'}, required=${updateRequired}`,
    );

    if (!updateRequired) {
      return { status: 'supported', currentVersion };
    }

    return {
      status: 'update_required',
      currentVersion,
      minimumVersion: minimumVersion || latestVersion || '—',
      latestVersion: latestVersion || minimumVersion || '—',
      downloadUrl: payload.downloadUrl?.trim() || null,
      releaseNotes: payload.releaseNotes?.trim() || null,
    };
  } catch (error) {
    versionLogger.warn('App version check failed; preserving offline access', error);
    return { status: 'unavailable', currentVersion };
  }
}
