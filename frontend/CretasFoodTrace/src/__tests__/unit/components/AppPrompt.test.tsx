import React from 'react';
import { Alert } from 'react-native';
import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { AppDialogHost, appPrompt } from '../../../components/ui/AppDialog';

/**
 * `Alert.prompt` 是 iOS 专有 API —— Android 上它不存在，调用即什么都不发生：
 * 按钮点下去没有输入框、没有报错、没有任何反馈。仓里原有 4 处 `Alert.prompt`，
 * 只有 QIScanScreen 做了 `typeof Alert.prompt === 'function'` 守卫，
 * 报工驳回 / 采购备注 / 换工种三处都是裸调，在 Android 上是死按钮。
 *
 * 这组断言守的是「跨平台都能拿到用户输入」。
 */
describe('appPrompt — 跨平台文本输入弹窗', () => {
  it('渲染输入框，确定后把用户输入交给回调', () => {
    const onSubmit = jest.fn();
    render(<AppDialogHost />);

    act(() => {
      appPrompt('驳回原因', '请说明驳回这条报工的原因', onSubmit);
    });

    const input = screen.getByTestId('app-dialog-input');
    fireEvent.changeText(input, '数量与实际不符');
    fireEvent.press(screen.getByTestId('app-dialog-prompt-confirm'));

    expect(onSubmit).toHaveBeenCalledTimes(1);
    expect(onSubmit).toHaveBeenCalledWith('数量与实际不符');
  });

  it('默认必填：空输入时不提交', () => {
    const onSubmit = jest.fn();
    render(<AppDialogHost />);

    act(() => {
      appPrompt('驳回原因', undefined, onSubmit);
    });

    fireEvent.press(screen.getByTestId('app-dialog-prompt-confirm'));
    expect(onSubmit).not.toHaveBeenCalled();

    // 填了就能提交 —— 证明上面那条不是「按钮永远点不动」造成的假绿
    fireEvent.changeText(screen.getByTestId('app-dialog-input'), '  原因  ');
    fireEvent.press(screen.getByTestId('app-dialog-prompt-confirm'));
    expect(onSubmit).toHaveBeenCalledWith('  原因  ');
  });

  it('required:false 时允许空提交（采购备注可以清空）', () => {
    const onSubmit = jest.fn();
    render(<AppDialogHost />);

    act(() => {
      appPrompt('编辑备注', undefined, onSubmit, { required: false });
    });

    fireEvent.press(screen.getByTestId('app-dialog-prompt-confirm'));
    expect(onSubmit).toHaveBeenCalledWith('');
  });

  it('取消不触发回调', () => {
    const onSubmit = jest.fn();
    render(<AppDialogHost />);

    act(() => {
      appPrompt('驳回原因', undefined, onSubmit);
    });

    fireEvent.changeText(screen.getByTestId('app-dialog-input'), '写了一半');
    fireEvent.press(screen.getByTestId('app-dialog-prompt-cancel'));
    expect(onSubmit).not.toHaveBeenCalled();
  });

  /**
   * 棘轮：`Alert.prompt` 只能出现在**做了平台守卫**的地方。
   *
   * 这条比运行时断言实在 —— 缺陷的形态就是「某个屏直接调了 Alert.prompt」，
   * 而那在 Android 上是死按钮，任何单测都测不到（它根本不抛错）。
   */
  it('棘轮: 屏幕里不许裸调 Alert.prompt', () => {
    const fs = require('fs');
    const path = require('path');
    const root = path.join(__dirname, '..', '..', '..', 'screens');

    const files: string[] = [];
    const walk = (dir: string) => {
      for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, e.name);
        if (e.isDirectory()) walk(full);
        else if (e.name.endsWith('.tsx') || e.name.endsWith('.ts')) files.push(full);
      }
    };
    walk(root);

    // 「一个文件都没扫到」最像「一切正常」—— 先证明仪器在工作。
    expect(files.length).toBeGreaterThan(100);

    const offenders: string[] = [];
    for (const f of files) {
      const src: string = fs.readFileSync(f, 'utf-8');
      // 剥注释: 闸不该把自己的说明文字也数进去
      const code = src.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/\/\/.*/g, ' ');
      if (!code.includes('Alert.prompt(')) continue;
      // 唯一允许的形态: 显式检测过它存不存在
      if (code.includes("typeof Alert.prompt === 'function'")) continue;
      offenders.push(path.relative(root, f));
    }

    expect(offenders).toEqual([]);
  });
});
