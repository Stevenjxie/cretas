import fs from 'fs';
import path from 'path';

const srcRoot = path.resolve(__dirname, '../../../');
const read = (relativePath: string) =>
  fs.readFileSync(path.join(srcRoot, relativePath), 'utf8');

describe('quality inspector home workflow contract', () => {
  const homeSource = read('screens/quality-inspector/QIHomeScreen.tsx');
  const navigatorSource = read('navigation/QualityInspectorNavigator.tsx');

  it('places pending human review on the home screen with one-tap queue navigation', () => {
    expect(homeSource).toContain('testID="qi-home-pending-review"');
    expect(homeSource).toContain("statuses: ['NEEDS_REVIEW', 'ANALYSIS_FAILED']");
    expect(homeSource).toContain("screen: 'QILabelQcQueue'");
    expect(homeSource.indexOf('qi-home-pending-review')).toBeLessThan(
      homeSource.indexOf('qi-home-new-label-qc'),
    );
  });

  it('keeps the primary mobile actions focused on review, capture, and records', () => {
    expect(homeSource).toContain('待我审核');
    expect(homeSource).toContain('发起标签拍检');
    expect(homeSource).toContain('查看质检记录');
    expect(homeSource).not.toContain('home.voiceInspection');
    expect(homeSource).not.toContain('home.dataAnalysis');
  });

  it('adds the device safe area to the full bottom navigation height', () => {
    expect(navigatorSource).toContain('useSafeAreaInsets');
    expect(navigatorSource).toContain('height: 64 + bottomInset');
    expect(navigatorSource).toContain('paddingBottom: bottomInset + 2');
    expect(navigatorSource).toContain('minHeight: 48');
    expect(navigatorSource).toContain('tabBarHideOnKeyboard: true');
  });

  it('refreshes the current-user unread badge whenever the home screen regains focus', () => {
    expect(homeSource).toContain('useFocusEffect(');
    expect(homeSource).toContain('qualityInspectorApi.getUnreadCount(userId)');
    expect(homeSource).toContain('}, [factoryId, loadReviewQueue, userId])');
  });

  it('keeps the pending human-review card current while AI analysis finishes', () => {
    expect(homeSource).toContain('const loadReviewQueue = useCallback');
    expect(homeSource).toContain('setPendingReviewCount(page.totalElements)');
    expect(homeSource).toContain('setNextReviewTask(page.content[0] ?? null)');
    expect(homeSource).toContain('setInterval(() =>');
    expect(homeSource).toContain('}, 10_000)');
    expect(homeSource).toContain('clearInterval(reviewRefreshTimer)');
  });
});
