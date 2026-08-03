import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(
  resolve(process.cwd(), 'src/views/restaurant/staffing/index.vue'),
  'utf8',
);

const askQuestionSource = source.slice(
  source.indexOf('async function askQuestion'),
  source.indexOf('function formatDateTime'),
);

describe('预测排班 AI 可信交互契约', () => {
  it('复用后端 Java session 并渲染真实 follow-up，而不是伪造本地追问', () => {
    expect(askQuestionSource).toContain('sessionId: aiSessionId.value ?? undefined');
    expect(askQuestionSource).toContain(
      'aiSessionId.value = response.javaSessionId ?? response.sessionId ?? null',
    );
    expect(askQuestionSource).toContain('aiFollowups.value = response.followUpChips ?? []');
    expect(source).toContain('v-for="item in aiFollowups"');
  });

  it('只把真实排班意图标为 FactBook 已绑定，并明确表格筛选不改变 AI 范围', () => {
    expect(source).toContain('isGroundedStaffingIntent(aiIntentCode.value)');
    expect(source).toContain('排班 FactBook 已绑定');
    expect(source).toContain('本次回答未命中预测排班能力');
    expect(source).toContain('不会缩小 AI 的全店 FactBook');
  });

  it('显示真实分析阶段，失败时保留上一条有效回答并提供原问题重试', () => {
    expect(source).toContain('识别范围 → 生成预测 FactBook → 大模型解释');
    expect(source).toContain('上一条有效回答仍保留');
    expect(source).toContain('@click="askQuestion(aiRetryQuestion)"');
    expect(askQuestionSource).not.toContain("aiAnswer.value = ''");
    expect(askQuestionSource).toContain("aiStatus.value = 'error'");
  });

  it('使用 DOMPurify 清洗 Markdown 后再渲染模型回答', () => {
    expect(source).toContain("import DOMPurify from 'dompurify'");
    expect(source).toContain('DOMPurify.sanitize(marked(aiAnswer.value) as string)');
    expect(source).toContain('v-html="aiAnswerHtml"');
  });
});
