import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 质检记录不许把「质检员」显示成数据库主键。
 *
 * <p>事故形态：列名叫「质检员ID」，格子里就是一个 `inspectorId` 数字主键。
 * 详情面板同样。而系统**本来就会解析质检员姓名** ——
 * `ProcessingServiceImpl.buildQualityDetails` 里早有
 * `userRepository.findById(...).getFullName()` 的写法，只是列表这处没用。
 * 又是「一处做对、另一处漏了」。
 *
 * <p>对车间/质检岗用户，「3417」不构成任何信息；要追责或复核时他还得再去查这个号是谁。
 *
 * <p>⛔ 修法刻意保留 ID 回落而不是编造「未知质检员」：后端解析不出时返回 null，
 * 前端显示 `#ID`。**诚实-null 比假装有名字好** —— 前者用户知道要去查这个号，
 * 后者会让人以为系统真记录了这么一个人。`#` 前缀让人一眼看出这是编号不是名字。
 */
describe('质检记录的质检员显示', () => {
    const source = readFileSync(
        resolve(process.cwd(), 'src/views/quality/inspections/list.vue'),
        'utf-8',
    );

    it('阳性对照: 源码读得到', () => {
        expect(source).toContain('el-table-column');
    });

    it('列名不许再叫「质检员ID」', () => {
        expect(source, '列名把数据库标识摆到台面上').not.toContain('label="质检员ID"');
        expect(source).toContain('label="质检员"');
    });

    it('优先显示姓名, 解析不出才回落 ID', () => {
        expect(source).toContain('row.inspectorName');
        expect(source).toContain('detailData.inspectorName');
    });

    it('回落时带 # 前缀 —— 让人看出这是编号不是名字', () => {
        expect(source).toContain('#${row.inspectorId}');
    });

    it('⛔ 不许编造「未知质检员」之类的假名字 (诚实-null)', () => {
        expect(source).not.toContain('未知质检员');
        expect(source).not.toContain('未知用户');
    });
});
