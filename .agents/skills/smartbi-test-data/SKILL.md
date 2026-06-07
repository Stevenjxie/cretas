---
name: smartbi-test-data
description: SmartBI 测试数据生成与管理。一键生成工厂报表、餐饮报表、边界用例、压力测试 Excel。支持选择性生成和数据验证。
allowed-tools:
  - Bash
  - Read
  - Glob
  - Grep
---

# SmartBI 测试数据生成

## 命令解析

解析用户输入的参数（`$ARGUMENTS`）：

| 输入 | 动作 |
|------|------|
| _(空)_ 或 `gen` | 生成全部 4 类（工厂 + 餐饮 + 边界 + 压力 L1） |
| `gen factory` | 仅工厂（12 文件, 264 sheets） |
| `gen restaurant` | 仅餐饮（12 文件, 204 sheets） |
| `gen edge` | 仅边界（6 文件, 6 sheets） |
| `gen stress` | 仅压力 L1（2 文件, 50K 行） |
| `gen stress l2` | 含 L2（4 文件, 200K 行, ~50MB） |
| `verify` | 验证已生成数据的完整性 |
| `list` | 显示当前 tests/test-data/ 下的文件清单 |
| `clean` | 删除 tests/test-data/ 下所有生成的文件（保留 Test.xlsx） |

## 生成器对照表

| 类型 | 脚本 | 输出目录 | 文件命名 |
|------|------|----------|----------|
| 工厂 | `tests/generate_test_excel.py` | `tests/test-data/` | `Test-mock-{industry}-{scenario}-s42.xlsx` |
| 餐饮 | `tests/generate_restaurant_excel.py` | `tests/test-data/restaurant/` | `Restaurant-{industry}-{scenario}-s42.xlsx` |
| 边界 | `tests/generate_edge_cases.py` | `tests/test-data/edge-cases/` | `Edge-*.xlsx` |
| 压力 | `tests/generate_stress_test.py` | `tests/test-data/stress/` | `Stress-{type}-{level}-s42.xlsx` |

工厂行业: `food`, `mfg`, `retail` × 场景: `normal`, `growth`, `loss`, `sparse` = 12 文件
餐饮行业: `fish`, `hotpot`, `bakery` × 场景: `normal`, `growth`, `loss`, `sparse` = 12 文件
边界用例: `wide-120col`, `mixed-types`, `empty-regions`, `formula-cells`, `numeric-colnames`, `cross-year-yoy` = 6 文件
压力 L1: `Stress-factory-L1-s42.xlsx`, `Stress-restaurant-L1-s42.xlsx` = 2 文件
压力 L2: 同上 + `Stress-factory-L2-s42.xlsx`, `Stress-restaurant-L2-s42.xlsx` = 4 文件

## 执行策略

### gen（全部）或 gen factory/restaurant/edge

工厂 + 餐饮 + 边界可以 **3 个 Bash 并行执行**（互不依赖）：

```bash
# 并行运行（各自独立）
python tests/generate_test_excel.py          # 工厂 ~20s
python tests/generate_restaurant_excel.py    # 餐饮 ~15s
python tests/generate_edge_cases.py          # 边界 ~3s
```

### gen stress / gen stress l2

压力测试单独运行（L1 ~16s, L2 ~60s，内存占用大）：

```bash
python tests/generate_stress_test.py              # L1 only
python tests/generate_stress_test.py --level l2   # L1 + L2
```

### 生成后自动 verify

每次生成完成后，自动执行验证检查。

## verify 验证检查项

用 Python 单行脚本或 Bash 检查：

1. **文件数统计**: `find tests/test-data -name "*.xlsx" | wc -l`（排除 Test.xlsx）
2. **Sheet 数统计**: 用 openpyxl 打开每个文件，统计 sheetnames 数量
3. **总文件大小**: `du -sh tests/test-data/`
4. **Merge cells**: 边界和压力文件中应有 merged_cells（`Edge-wide-120col.xlsx`, `Stress-*-L1-*.xlsx`）
5. **百分比格式**: 检查存在 `number_format` 为 `0.0%` 的单元格
6. **公式存在性**: 检查 `Edge-formula-cells.xlsx` 中有 `=SUM` / `=AVERAGE` 公式

### 预期值

| 类型 | 文件数 | Sheets | 大小范围 |
|------|--------|--------|----------|
| 工厂 | 12 | ~264 | 8-15MB |
| 餐饮 | 12 | ~204 | 6-12MB |
| 边界 | 6 | 6 | <1MB |
| 压力 L1 | 2 | 2 | 2-5MB |
| 压力 L2 | 2 | 2 | 20-50MB |
| **合计（含 L2）** | **34** | **~478** | **40-80MB** |

## list 输出格式

```
## SmartBI 测试数据清单

| 类型 | 文件 | Sheets | 大小 |
|------|------|--------|------|
| 工厂 | Test-mock-food-normal-s42.xlsx | 22 | 1.2MB |
| ... | ... | ... | ... |
| **合计** | **N 文件** | **N sheets** | **N MB** |
```

## clean 行为

删除以下文件（保留 `Test.xlsx` 原始测试文件）：

```bash
rm -f tests/test-data/Test-mock-*.xlsx
rm -rf tests/test-data/restaurant/
rm -rf tests/test-data/edge-cases/
rm -rf tests/test-data/stress/
```

确认删除前列出将被删除的文件数量。
