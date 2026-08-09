import fs from 'fs';
import path from 'path';

describe('mandatory app update gate contract', () => {
  const appSource = fs.readFileSync(
    path.resolve(__dirname, '../../../../App.tsx'),
    'utf8',
  );
  const gateSource = fs.readFileSync(
    path.resolve(
      __dirname,
      '../../../components/common/MandatoryUpdateGate.tsx',
    ),
    'utf8',
  );

  it('does not mount AppNavigator while the minimum-version gate is checking or blocked', () => {
    const gateBranch = appSource.indexOf(
      "checkingVersion || versionResult?.status === 'update_required'",
    );
    const appNavigatorBranch = appSource.indexOf('<AppNavigator />');

    expect(gateBranch).toBeGreaterThan(-1);
    expect(appNavigatorBranch).toBeGreaterThan(gateBranch);
    expect(appSource).toContain('<MandatoryUpdateGate');
  });

  it('provides update and retry actions without a later or close action', () => {
    expect(gateSource).toContain("i18n.t('common:ota.update_now')");
    expect(gateSource).toContain("i18n.t('common:ota.required_retry')");
    expect(gateSource).not.toContain("i18n.t('common:ota.later')");
    expect(gateSource).not.toContain('onRequestClose');
  });

  it('keeps low-tech-user touch targets at least 44 points high', () => {
    expect(gateSource).toContain('minHeight: 52');
    expect(gateSource).toContain('minHeight: 48');
  });
});
