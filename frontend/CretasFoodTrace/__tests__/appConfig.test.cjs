const assert = require('node:assert/strict');
const { afterEach, describe, it } = require('node:test');
const configureApp = require('../app.config');

describe('app.config', () => {
  const originalEnv = process.env.EXPO_PUBLIC_ENV;

  afterEach(() => {
    if (originalEnv === undefined) {
      delete process.env.EXPO_PUBLIC_ENV;
    } else {
      process.env.EXPO_PUBLIC_ENV = originalEnv;
    }
  });

  it('keeps EAS project id in extra for push token registration', () => {
    process.env.EXPO_PUBLIC_ENV = 'production';

    const config = configureApp({
      config: {
        extra: {
          router: {},
        },
      },
    });

    assert.deepEqual(config.extra, {
      router: {},
      env: 'production',
      eas: {
        projectId: 'com.cretas.foodtrace',
      },
    });
  });
});
