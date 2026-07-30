import { describe, expect, it } from 'vitest';
import {
  isReadableSupplierAddress,
  isValidSupplierPhone,
  normalizeSupplierPayload,
  supplierDisplayName,
  supplierProfileComplete,
  supplierStatus,
  supplierStatusLabel,
} from './supplierModel';

describe('supplier required profile contract', () => {
  it.each([
    '13800138000',
    '+86 13800138000',
    '021-12345678',
    '01012345678',
    '0755-12345678 转 806',
    '010-12345678 ext. 12',
    '010-12345678-12',
  ])('accepts supported mobile and enterprise phone %s', (phone) => {
    expect(isValidSupplierPhone(phone)).toBe(true);
  });

  it.each(['', '123', '1380013800', '12345678', 'phone'])('rejects invalid phone %s', (phone) => {
    expect(isValidSupplierPhone(phone)).toBe(false);
  });

  it('requires readable address text and rejects punctuation placeholders', () => {
    expect(isReadableSupplierAddress('上海市浦东新区张江路 88 号')).toBe(true);
    expect(isReadableSupplierAddress(' - ')).toBe(false);
    expect(isReadableSupplierAddress('……')).toBe(false);
    expect(isReadableSupplierAddress('   ')).toBe(false);
  });

  it('trims every writable text field before creating or editing', () => {
    // shortName 于 2026-07-30 加入 payload（客户要简称）——原意「每个可写文本字段
    // 都被 trim」不变，只是多了一个字段参与。
    expect(normalizeSupplierPayload({
      name: '  华东食品  ',
      shortName: '  华东  ',
      contactPerson: ' 张经理 ',
      phone: ' 021-12345678 ',
      address: ' 上海市浦东新区 88 号 ',
      email: ' test@example.com ',
      bankAccount: ' 6222 ',
      taxNumber: ' TAX-1 ',
      notes: ' 稳定供货 ',
    })).toEqual({
      name: '华东食品',
      shortName: '华东',
      contactPerson: '张经理',
      phone: '021-12345678',
      address: '上海市浦东新区 88 号',
      email: 'test@example.com',
      bankAccount: '6222',
      taxNumber: 'TAX-1',
      notes: '稳定供货',
    });
  });

  it('marks legacy missing profiles incomplete but keeps them readable', () => {
    expect(supplierProfileComplete({ name: '历史供应商', isActive: true })).toBe(false);
    expect(supplierProfileComplete({
      name: '完整供应商', contactPerson: '李经理', phone: '010-12345678', address: '北京市朝阳区 1 号',
    })).toBe(true);
    expect(supplierProfileComplete({
      name: '后端判定不完整', contactPerson: '李经理', phone: '010-12345678', address: '北京市朝阳区 1 号', profileComplete: false,
    })).toBe(false);
  });
});

describe('supplier short name (客户反馈: 下拉里好认)', () => {
  it('prefers the backend-computed displayName so every dropdown agrees', () => {
    expect(supplierDisplayName({
      displayName: '飞熊', shortName: '飞熊', name: '北京飞熊食品有限公司',
    })).toBe('飞熊');
  });

  it('falls back to shortName then full name for projections without displayName', () => {
    expect(supplierDisplayName({ shortName: '飞熊', name: '北京飞熊食品有限公司' }))
      .toBe('飞熊');
    expect(supplierDisplayName({ name: '北京飞熊食品有限公司' }))
      .toBe('北京飞熊食品有限公司');
  });

  it('treats a blank short name as unset instead of rendering an empty option', () => {
    // 空白简称如果被当成"有值", 下拉会出现一条看不见标签的选项 —— 比没简称更糟。
    expect(supplierDisplayName({ shortName: '   ', name: '北京飞熊食品有限公司' }))
      .toBe('北京飞熊食品有限公司');
    expect(supplierDisplayName({ displayName: '  ', shortName: '飞熊', name: '北京飞熊' }))
      .toBe('飞熊');
  });

  it('normalizes a whitespace-only short name to empty so the backend clears it', () => {
    // 用户清空简称 → 前端送空串 → 后端 trimToNull → 简称被删掉。
    expect(normalizeSupplierPayload({
      name: '华东食品', shortName: '   ', contactPerson: '张经理',
      phone: '021-12345678', address: '上海市浦东新区 88 号',
    }).shortName).toBe('');
  });
});

describe('supplier status semantics', () => {
  it('uses ACTIVE as cooperation and inactive as suspension', () => {
    expect(supplierStatus({ status: 'ACTIVE' })).toBe('ACTIVE');
    expect(supplierStatusLabel({ status: 'ACTIVE' })).toBe('合作中');
    expect(supplierStatus({ status: 'INACTIVE' })).toBe('INACTIVE');
    expect(supplierStatusLabel({ isActive: false })).toBe('暂停合作');
  });
});
