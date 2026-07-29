import {
  buildScreeningReferenceBoxes,
  LABEL_QC_ALL_LAYERS_VISIBLE,
  parseScreeningTrays,
} from './labelQcScreeningLayers';

const SAMPLE = JSON.stringify({
  trays: [
    {
      index: 0,
      bbox: [0.1, 0.1, 0.4, 0.5],
      trayConfidence: 0.91,
      labels: [
        { type: 'white', confidence: 0.83, bbox: [0.15, 0.2, 0.22, 0.26] },
        { type: 'color', confidence: 0.77, bbox: [0.3, 0.2, 0.37, 0.26] },
      ],
    },
    { index: 1, bbox: [0.5, 0.1, 0.8, 0.5], labels: [] },
  ],
});

describe('parseScreeningTrays', () => {
  it('读出托盘明细', () => {
    expect(parseScreeningTrays(SAMPLE)).toHaveLength(2);
  });

  it('明细缺失或损坏时静默降级为空, 不抛错', () => {
    // 参考层只是背景信息, 坏掉的 JSON 不能让整个复核台打不开
    expect(parseScreeningTrays(undefined)).toEqual([]);
    expect(parseScreeningTrays('')).toEqual([]);
    expect(parseScreeningTrays('{ 这不是 json')).toEqual([]);
    expect(parseScreeningTrays('{"trays":"nope"}')).toEqual([]);
  });
});

describe('buildScreeningReferenceBoxes', () => {
  it('按图层拆出盒子 / 白标 / 彩标三类框', () => {
    const boxes = buildScreeningReferenceBoxes(
      parseScreeningTrays(SAMPLE),
      LABEL_QC_ALL_LAYERS_VISIBLE,
    );
    expect(boxes.filter((box) => box.layer === 'tray')).toHaveLength(2);
    expect(boxes.filter((box) => box.layer === 'white')).toHaveLength(1);
    expect(boxes.filter((box) => box.layer === 'color')).toHaveLength(1);
    expect(new Set(boxes.map((box) => box.key)).size).toBe(boxes.length);
  });

  it('关掉的图层不出框', () => {
    const boxes = buildScreeningReferenceBoxes(parseScreeningTrays(SAMPLE), {
      tray: false,
      white: true,
      color: false,
    });
    expect(boxes).toHaveLength(1);
    expect(boxes[0]?.layer).toBe('white');
  });

  it('丢弃退化框和非法坐标', () => {
    const trays = parseScreeningTrays(
      JSON.stringify({
        trays: [
          {
            index: 0,
            bbox: [0.1, 0.1, 0.1, 0.5], // 零宽
            labels: [
              { type: 'white', bbox: [0.2, 0.2, 0.1, 0.3] }, // xMax < xMin
              { type: 'color', bbox: [0.2, 0.2, 0.3] }, // 少一个坐标
              { type: 'white', bbox: [0.2, 0.2, 'x', 0.3] }, // 非数字
              { type: 'color', bbox: [0.4, 0.4, 0.5, 0.5] }, // 唯一合法
            ],
          },
        ],
      }),
    );
    const boxes = buildScreeningReferenceBoxes(trays, LABEL_QC_ALL_LAYERS_VISIBLE);
    expect(boxes).toHaveLength(1);
    expect(boxes[0]?.layer).toBe('color');
  });

  it('未知标签类型归到彩标, 不静默丢掉一个真实检出', () => {
    const trays = parseScreeningTrays(
      JSON.stringify({
        trays: [{ index: 0, labels: [{ bbox: [0.1, 0.1, 0.2, 0.2] }] }],
      }),
    );
    const boxes = buildScreeningReferenceBoxes(trays, LABEL_QC_ALL_LAYERS_VISIBLE);
    expect(boxes).toHaveLength(1);
    expect(boxes[0]?.layer).toBe('color');
  });

  it('置信度进无障碍朗读文案, 缺失时不显示百分比', () => {
    const boxes = buildScreeningReferenceBoxes(
      parseScreeningTrays(SAMPLE),
      LABEL_QC_ALL_LAYERS_VISIBLE,
    );
    expect(boxes.find((box) => box.layer === 'white')?.caption).toBe(
      '盒子 1 的白标 83%',
    );
    const noConfidence = buildScreeningReferenceBoxes(
      parseScreeningTrays(
        JSON.stringify({
          trays: [{ index: 2, labels: [{ type: 'white', bbox: [0.1, 0.1, 0.2, 0.2] }] }],
        }),
      ),
      LABEL_QC_ALL_LAYERS_VISIBLE,
    );
    expect(noConfidence[0]?.caption).toBe('盒子 3 的白标');
  });
});
