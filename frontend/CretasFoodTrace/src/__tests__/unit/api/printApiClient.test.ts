import fs from 'fs';
import path from 'path';

const readSource = (relativeFromSrc: string): string =>
  fs.readFileSync(path.resolve(__dirname, '../../../', relativeFromSrc), 'utf8');

describe('production work order print entry routing', () => {
  it('RN print service exposes production-work-order and no longer exposes production-task', () => {
    const source = readSource('services/api/printApiClient.ts');

    expect(source).toContain("'production-work-order'");
    expect(source).not.toContain("'production-task'");
  });

  it('dispatcher plan list print action uses production-work-order', () => {
    const source = readSource('screens/dispatcher/plan/PlanListScreen.tsx');

    expect(source).toContain("safePrint('production-work-order'");
    expect(source).not.toContain("safePrint('production-task'");
  });

  it('dispatcher plan detail print action uses production-work-order', () => {
    const source = readSource('screens/dispatcher/plan/PlanDetailScreen.tsx');

    expect(source).toContain("safePrint('production-work-order'");
    expect(source).not.toContain("safePrint('production-task'");
  });
});
