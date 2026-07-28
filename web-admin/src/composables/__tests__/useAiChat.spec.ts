import { describe, it, expect, vi, beforeEach } from 'vitest';
import { ref } from 'vue';

const mockParseFormInput = vi.fn();
vi.mock('@/api/formAssistant', () => ({
  parseFormInput: (...args: unknown[]) => mockParseFormInput(...args),
}));

let mockFactoryId = 'FAC001';
vi.mock('@/composables/useFactoryId', () => ({
  useFactoryId: () => ref(mockFactoryId),
}));

import { useAiChat } from '../useAiChat';
import { PRODUCTION_PLAN_CONFIG, PURCHASE_ORDER_CONFIG } from '@/components/ai-entry/types';

function ok(body: Record<string, unknown>) {
  return { success: true, data: { success: true, confidence: 0.9, ...body }, message: '' };
}

describe('useAiChat — /form-assistant/parse 迁移', () => {
  beforeEach(() => {
    mockParseFormInput.mockReset();
    mockFactoryId = 'FAC001';
  });

  it('只传 entityType + formFields，不再发 systemPrompt', async () => {
    mockParseFormInput.mockResolvedValue(ok({ fieldValues: {} }));
    const { sendMessage } = useAiChat(PRODUCTION_PLAN_CONFIG);

    await sendMessage('帮我建个计划');

    const [factoryId, payload] = mockParseFormInput.mock.calls[0];
    expect(factoryId).toBe('FAC001');
    expect(payload.entityType).toBe('PRODUCTION_PLAN');
    expect(payload.userInput).toBe('帮我建个计划');
    expect(payload).not.toHaveProperty('systemPrompt');
    expect(payload).not.toHaveProperty('messages');
    expect(payload.formFields).toContainEqual({
      name: 'plannedQuantity', title: '计划数量', type: 'number', required: true,
    });
    expect(payload.formFields).toContainEqual({
      name: 'notes', title: '备注', type: 'string', required: false,
    });
  });

  it('必填项齐全 → 直接给预览，字段值取自 fieldValues', async () => {
    mockParseFormInput.mockResolvedValue(ok({
      fieldValues: {
        productTypeName: '黄金鱼片 400g 盒装',
        plannedQuantity: 500,
        quantityUnit: 'kg',
        plannedDate: '2026-07-29',
      },
    }));
    const { sendMessage, previewParams } = useAiChat(PRODUCTION_PLAN_CONFIG);

    await sendMessage('明天做500kg黄金鱼片 400g 盒装');

    // SKU 名逐字进预览 —— 页面要拿它跟真实产品表唯一匹配
    expect(previewParams.value?.productTypeName).toBe('黄金鱼片 400g 盒装');
    expect(previewParams.value?.plannedQuantity).toBe(500);
  });

  it('缺必填项 → 不出预览，追问缺的字段', async () => {
    mockParseFormInput.mockResolvedValue(ok({
      fieldValues: { productTypeName: '黄金鱼片 400g 盒装' },
      followUpQuestion: '请问计划生产多少？',
    }));
    const { sendMessage, previewParams, messages } = useAiChat(PRODUCTION_PLAN_CONFIG);

    await sendMessage('做点黄金鱼片 400g 盒装');

    expect(previewParams.value).toBeNull();
    const reply = messages.value.at(-1)!.content;
    expect(reply).toContain('请问计划生产多少？');
    expect(reply).toContain('计划数量');
  });

  it('模型漏报 missingRequiredFields 也不会放行 —— 缺项以本地字段定义为准', async () => {
    mockParseFormInput.mockResolvedValue(ok({
      fieldValues: { productTypeName: '黄金鱼片 400g 盒装' },
      missingRequiredFields: [],
    }));
    const { sendMessage, previewParams } = useAiChat(PRODUCTION_PLAN_CONFIG);

    await sendMessage('做点黄金鱼片 400g 盒装');

    expect(previewParams.value).toBeNull();
  });

  it('多轮：历史折进 userInput，字段跨轮累积', async () => {
    mockParseFormInput
      .mockResolvedValueOnce(ok({ fieldValues: { productTypeName: '黄金鱼片 400g 盒装' } }))
      .mockResolvedValueOnce(ok({
        fieldValues: { plannedQuantity: 300, quantityUnit: 'kg', plannedDate: '2026-07-30' },
      }));
    const { sendMessage, previewParams } = useAiChat(PRODUCTION_PLAN_CONFIG);

    await sendMessage('做黄金鱼片 400g 盒装');
    await sendMessage('300kg，后天');

    const secondInput = mockParseFormInput.mock.calls[1][1].userInput as string;
    expect(secondInput).toContain('做黄金鱼片 400g 盒装');
    expect(secondInput).toContain('用户：300kg，后天');
    // 第一轮的产品名没有在第二轮返回，但必须留在预览里
    expect(previewParams.value?.productTypeName).toBe('黄金鱼片 400g 盒装');
    expect(previewParams.value?.plannedQuantity).toBe(300);
  });

  it('用户改口 → 新值覆盖旧值', async () => {
    mockParseFormInput
      .mockResolvedValueOnce(ok({
        fieldValues: {
          productTypeName: '黄金鱼片 400g 盒装', plannedQuantity: 500,
          quantityUnit: 'kg', plannedDate: '2026-07-29',
        },
      }))
      .mockResolvedValueOnce(ok({ fieldValues: { plannedQuantity: 300 } }));
    const { sendMessage, previewParams } = useAiChat(PRODUCTION_PLAN_CONFIG);

    await sendMessage('明天做500kg黄金鱼片 400g 盒装');
    await sendMessage('不对，是300kg');

    expect(previewParams.value?.plannedQuantity).toBe(300);
  });

  it('items 明细数组原样带进预览', async () => {
    mockParseFormInput.mockResolvedValue(ok({
      fieldValues: {
        supplierName: 'XX供应商',
        items: [{ materialName: '大豆', quantity: 500, unit: 'kg', unitPrice: 0 }],
      },
    }));
    const { sendMessage, previewParams } = useAiChat(PURCHASE_ORDER_CONFIG);

    await sendMessage('从XX供应商采购500kg大豆');

    expect(previewParams.value?.items).toEqual([
      { materialName: '大豆', quantity: 500, unit: 'kg', unitPrice: 0 },
    ]);
  });

  it('空 items 数组不算已填 —— 不放行预览', async () => {
    mockParseFormInput.mockResolvedValue(ok({
      fieldValues: { supplierName: 'XX供应商', items: [] },
    }));
    const { sendMessage, previewParams, messages } = useAiChat(PURCHASE_ORDER_CONFIG);

    await sendMessage('从XX供应商采购');

    expect(previewParams.value).toBeNull();
    expect(messages.value.at(-1)!.content).toContain('采购明细');
  });

  it('后端 success=false → 原样显示错误，不伪装成正常回答', async () => {
    mockParseFormInput.mockResolvedValue({
      success: true,
      data: { success: false, message: 'AI服务未配置，请手动填写表单', fieldValues: {} },
      message: '',
    });
    const { sendMessage, previewParams, messages } = useAiChat(PRODUCTION_PLAN_CONFIG);

    await sendMessage('明天做500kg黄金鱼片');

    expect(previewParams.value).toBeNull();
    expect(messages.value.at(-1)!.content).toBe('AI服务未配置，请手动填写表单');
  });

  it('请求抛错 → 显示具体原因', async () => {
    mockParseFormInput.mockRejectedValue(new Error('权限不足'));
    const { sendMessage, messages } = useAiChat(PRODUCTION_PLAN_CONFIG);

    await sendMessage('明天做500kg黄金鱼片');

    expect(messages.value.at(-1)!.content).toContain('权限不足');
  });

  it('无 factoryId → 明确拒绝，不打无工厂的裸 LLM 通道', async () => {
    mockFactoryId = '';
    const { sendMessage, messages } = useAiChat(PRODUCTION_PLAN_CONFIG);

    await sendMessage('明天做500kg黄金鱼片');

    expect(mockParseFormInput).not.toHaveBeenCalled();
    expect(messages.value.at(-1)!.content).toContain('未绑定工厂');
  });

  it('reset 清空累积字段', async () => {
    mockParseFormInput.mockResolvedValue(ok({
      fieldValues: { productTypeName: '黄金鱼片 400g 盒装' },
    }));
    const { sendMessage, reset, previewParams } = useAiChat(PRODUCTION_PLAN_CONFIG);

    await sendMessage('做黄金鱼片 400g 盒装');
    reset();
    mockParseFormInput.mockResolvedValue(ok({
      fieldValues: { plannedQuantity: 300, quantityUnit: 'kg', plannedDate: '2026-07-30' },
    }));
    await sendMessage('300kg，后天');

    // 上一轮的产品名不能串到新会话
    expect(previewParams.value).toBeNull();
  });
});
