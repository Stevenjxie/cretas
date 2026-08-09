import fs from 'fs';
import path from 'path';

const source = fs.readFileSync(
  path.resolve(__dirname, '../../../formily/components/Select.tsx'),
  'utf8',
);

describe('Formily searchable Select contract', () => {
  it('uses a deterministic native modal instead of the Paper Portal menu', () => {
    expect(source).toContain("ScrollView, Modal } from 'react-native'");
    expect(source).toContain('if (searchable)');
    expect(source).toContain('testID="formily-select-modal"');
    expect(source).toContain('testID="formily-select-search"');
    expect(source).toContain('testID={`formily-select-option-${String(option.value)}`}');
  });

  it('keeps accessible touch targets and the existing simple-select menu path', () => {
    expect(source).toContain('<TouchableRipple');
    expect(source).toContain('accessibilityState={{ selected, disabled: option.disabled }}');
    expect(source).toContain('minHeight: 52');
    expect(source).toContain('<Menu');
    expect(source).toContain('anchor={anchor}');
  });
});
