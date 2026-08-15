import React from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { AppDialogHost, appChoose, AppChoice } from '../../../components/ui/AppDialog';

/**
 * 防呆设计 Rule 3：「为什么」这类字段（取消原因 / 驳回原因 / 审批意见）要用
 * **标准选项**，不要让使用者对着空白框自己编。
 *
 * 自由文本对班组长是负担，对统计也没价值 —— 同一个原因十个人能写出十种说法，
 * 事后没法按原因归类看哪道工序总出问题。
 */
describe('appChoose — 跨平台标准选项弹窗', () => {
  const CHOICES: AppChoice[] = [
    { value: 'QUANTITY_MISMATCH', label: '数量与实际不符', description: '报的产出对不上现场' },
    { value: 'OTHER', label: '其他原因…' },
  ];

  it('渲染全部选项，点中哪个就回传哪个', () => {
    const onPick = jest.fn();
    render(<AppDialogHost />);

    act(() => {
      appChoose('驳回原因', '选一个原因', CHOICES, onPick);
    });

    expect(screen.getByText('数量与实际不符')).toBeTruthy();
    expect(screen.getByText('其他原因…')).toBeTruthy();
    // 说明性副标题也要出来 —— 光有标签对低技术素养用户还不够
    expect(screen.getByText('报的产出对不上现场')).toBeTruthy();

    fireEvent.press(screen.getByTestId('app-dialog-choice-0'));

    expect(onPick).toHaveBeenCalledTimes(1);
    expect(onPick).toHaveBeenCalledWith(CHOICES[0]);
  });

  it('取消不回传', () => {
    const onPick = jest.fn();
    render(<AppDialogHost />);

    act(() => {
      appChoose('驳回原因', undefined, CHOICES, onPick);
    });

    fireEvent.press(screen.getByTestId('app-dialog-choice-cancel'));
    expect(onPick).not.toHaveBeenCalled();
  });

  /**
   * 对照：选「其他」时回传的是 OTHER，由调用方决定要不要再开自由文本框。
   * 这条保证「其他」不会被当成一个普通原因直接提交上去。
   */
  it('「其他」回传 OTHER，由调用方接管', () => {
    const onPick = jest.fn();
    render(<AppDialogHost />);

    act(() => {
      appChoose('驳回原因', undefined, CHOICES, onPick);
    });

    fireEvent.press(screen.getByTestId('app-dialog-choice-1'));
    expect(onPick).toHaveBeenCalledWith(expect.objectContaining({ value: 'OTHER' }));
  });
});
