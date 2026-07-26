/**
 * Phase 1 API对接测试脚本
 *
 * 测试范围：28个Phase 1 API
 * - 认证授权（7个）✅ 已在authService.ts实现
 * - 设备激活（3个）
 * - 用户管理（14个）
 * - 白名单（5个）
 *
 * 使用方法：
 * 1. 确保后端服务运行在 http://139.196.165.140:10010/
 * 2. 运行：npx ts-node src/tests/phase1-api-test.ts
 * 或在React Native项目中导入并调用测试函数
 */

import { activationApiClient } from '../services/api/future/activationApiClient';
import { userApiClient } from '../services/api/userApiClient';
import { whitelistApiClient } from '../services/api/whitelistApiClient';

// 测试配置
const TEST_CONFIG = {
  FACTORY_ID: 'TEST_2024_001',
  ACTIVATION_CODE: 'DEV_TEST_2024',
  DEVICE_ID: 'test-device-phase1-001',
  TEST_USER: {
    username: 'test_phase1_user',
    password: 'Test@123456',
    realName: '测试用户Phase1',
    phone: '+8613800000001',
    role: 'operator',
    department: 'processing'
  },
  TEST_WHITELIST: {
    phoneNumber: '+8613800000002',
    realName: '白名单测试用户',
    role: 'operator',
    department: 'processing'
  }
};

// ========== 设备激活API测试 ==========

export async function testActivationAPIs() {
  console.log('\n' + '='.repeat(80));
  console.log('【测试1/4】设备激活API（3个）');
  console.log('='.repeat(80));

  const results = {
    total: 3,
    passed: 0,
    failed: 0,
    errors: [] as string[]
  };

  try {
    // 1. 测试设备激活
    console.log('\n1️⃣ POST /api/mobile/activation/activate - 设备激活');
    try {
      const activationRes = await activationApiClient.activateDevice({
        activationCode: TEST_CONFIG.ACTIVATION_CODE,
        deviceInfo: {
          deviceId: TEST_CONFIG.DEVICE_ID,
          deviceModel: 'Test Device Model',
          deviceType: 'smartphone',
          platform: 'android',
          osVersion: '12.0',
          appVersion: '1.0.0'
        }
      });

      if (activationRes.success) {
        console.log('   ✅ 设备激活成功');
        console.log(`   工厂ID: ${activationRes.factoryId}`);
        console.log(`   激活时间: ${activationRes.activatedAt}`);
        results.passed++;
      } else {
        throw new Error('激活失败');
      }
    } catch (error: any) {
      console.log('   ❌ 设备激活失败:', error.message);
      results.failed++;
      results.errors.push(`设备激活: ${error.message}`);
    }

    // 需要先登录才能测试设备列表
    console.log('\n   ⚠️  注意：设备列表和移除设备API需要Token，跳过测试');
    console.log('   2️⃣ GET /api/mobile/devices - 需要登录');
    console.log('   3️⃣ DELETE /api/mobile/devices/{deviceId} - 需要登录');

  } catch (error: any) {
    console.log('❌ 设备激活API测试异常:', error.message);
  }

  return results;
}

// ========== 用户管理API测试 ==========

export async function testUserManagementAPIs(token: string) {
  console.log('\n' + '='.repeat(80));
  console.log('【测试2/4】用户管理API（14个）');
  console.log('='.repeat(80));

  const results = {
    total: 14,
    passed: 0,
    failed: 0,
    errors: [] as string[],
    createdUserId: null as number | null
  };

  try {
    // 1. 获取用户列表
    console.log('\n1️⃣  GET /api/{factoryId}/users - 获取用户列表');
    try {
      const usersRes = await userApiClient.getUsers({
        factoryId: TEST_CONFIG.FACTORY_ID,
        page: 0,
        size: 10
      });

      console.log(`   ✅ 获取用户列表成功，共${usersRes.content?.length || 0}个用户`);
      results.passed++;
    } catch (error: any) {
      console.log('   ❌ 失败:', error.message);
      results.failed++;
      results.errors.push(`获取用户列表: ${error.message}`);
    }

    // 2. 检查用户名是否存在
    console.log('\n2️⃣  GET /api/{factoryId}/users/check/username - 检查用户名');
    try {
      const existsRes = await userApiClient.checkUsernameExists(
        TEST_CONFIG.TEST_USER.username,
        TEST_CONFIG.FACTORY_ID
      );

      console.log(`   ✅ 用户名检查成功: ${existsRes ? '已存在' : '可用'}`);
      results.passed++;
    } catch (error: any) {
      console.log('   ❌ 失败:', error.message);
      results.failed++;
      results.errors.push(`检查用户名: ${error.message}`);
    }

    // 3. 创建用户
    console.log('\n3️⃣  POST /api/{factoryId}/users - 创建用户');
    try {
      const createRes = await userApiClient.createUser({
        ...TEST_CONFIG.TEST_USER
      }, TEST_CONFIG.FACTORY_ID);

      console.log(`   ✅ 创建用户成功，用户ID: ${createRes.id}`);
      results.createdUserId = createRes.id;
      results.passed++;
    } catch (error: any) {
      console.log('   ❌ 失败:', error.message);
      results.failed++;
      results.errors.push(`创建用户: ${error.message}`);
    }

    // 4. 获取用户详情
    if (results.createdUserId) {
      console.log('\n4️⃣  GET /api/{factoryId}/users/{userId} - 获取用户详情');
      try {
        const userRes = await userApiClient.getUserById(
          results.createdUserId,
          TEST_CONFIG.FACTORY_ID
        );

        console.log(`   ✅ 获取用户详情成功: ${userRes.realName}`);
        results.passed++;
      } catch (error: any) {
        console.log('   ❌ 失败:', error.message);
        results.failed++;
        results.errors.push(`获取用户详情: ${error.message}`);
      }
    }

    // 5. 更新用户信息
    if (results.createdUserId) {
      console.log('\n5️⃣  PUT /api/{factoryId}/users/{userId} - 更新用户');
      try {
        const updateRes = await userApiClient.updateUser(
          results.createdUserId,
          { realName: '测试用户Phase1-已更新', position: '测试员' },
          TEST_CONFIG.FACTORY_ID
        );

        console.log(`   ✅ 更新用户成功`);
        results.passed++;
      } catch (error: any) {
        console.log('   ❌ 失败:', error.message);
        results.failed++;
        results.errors.push(`更新用户: ${error.message}`);
      }
    }

    // 6. 搜索用户
    console.log('\n6️⃣  GET /api/{factoryId}/users/search - 搜索用户');
    try {
      const searchRes = await userApiClient.searchUsers({
        keyword: 'test',
        factoryId: TEST_CONFIG.FACTORY_ID
      });

      console.log(`   ✅ 搜索用户成功，找到${searchRes.length || 0}个用户`);
      results.passed++;
    } catch (error: any) {
      console.log('   ❌ 失败:', error.message);
      results.failed++;
      results.errors.push(`搜索用户: ${error.message}`);
    }

    // 7. 按角色获取用户
    console.log('\n7️⃣  GET /api/{factoryId}/users/role/{roleCode} - 按角色获取');
    try {
      const roleRes = await userApiClient.getUsersByRole(
        'operator',
        TEST_CONFIG.FACTORY_ID
      );

      console.log(`   ✅ 按角色获取成功，共${roleRes.length || 0}个用户`);
      results.passed++;
    } catch (error: any) {
      console.log('   ❌ 失败:', error.message);
      results.failed++;
      results.errors.push(`按角色获取: ${error.message}`);
    }

    console.log('\n   其他7个API（激活/停用/角色更新/导入导出）需要在实际场景中测试');

  } catch (error: any) {
    console.log('❌ 用户管理API测试异常:', error.message);
  }

  return results;
}

// ========== 白名单API测试 ==========

export async function testWhitelistAPIs(token: string) {
  console.log('\n' + '='.repeat(80));
  console.log('【测试3/4】白名单API（5个）');
  console.log('='.repeat(80));

  const results = {
    total: 5,
    passed: 0,
    failed: 0,
    errors: [] as string[]
  };

  try {
    // 1. 获取白名单列表
    console.log('\n1️⃣  GET /api/{factoryId}/whitelist - 获取白名单列表');
    try {
      const listRes = await whitelistApiClient.getWhitelist({
        factoryId: TEST_CONFIG.FACTORY_ID,
        page: 0,
        size: 10
      });

      console.log(`   ✅ 获取白名单成功，共${listRes.content?.length || 0}条记录`);
      results.passed++;
    } catch (error: any) {
      console.log('   ❌ 失败:', error.message);
      results.failed++;
      results.errors.push(`获取白名单: ${error.message}`);
    }

    // 2. 批量添加白名单
    console.log('\n2️⃣  POST /api/{factoryId}/whitelist/batch - 批量添加');
    try {
      const batchAddRes = await whitelistApiClient.batchAddWhitelist({
        entries: [
          {
            phoneNumber: TEST_CONFIG.TEST_WHITELIST.phoneNumber,
            name: TEST_CONFIG.TEST_WHITELIST.realName,
          },
          {
            phoneNumber: '+8613800000003',
            name: '批量测试用户2',
          }
        ],
        role: 'operator',
        department: 'processing',
      }, TEST_CONFIG.FACTORY_ID);

      console.log(`   ✅ 批量添加成功: 成功${batchAddRes.successCount}条，失败${batchAddRes.failedCount}条`);
      results.passed++;
    } catch (error: any) {
      console.log('   ❌ 失败:', error.message);
      results.failed++;
      results.errors.push(`批量添加: ${error.message}`);
    }

    // 3. 验证手机号
    console.log('\n3️⃣  GET /api/{factoryId}/whitelist/check - 验证手机号');
    try {
      const validateRes = await whitelistApiClient.validatePhoneNumber(
        TEST_CONFIG.TEST_WHITELIST.phoneNumber,
        TEST_CONFIG.FACTORY_ID
      );

      console.log(`   ✅ 验证手机号成功: ${validateRes.isValid ? '在白名单中' : '不在白名单中'}`);
      results.passed++;
    } catch (error: any) {
      console.log('   ❌ 失败:', error.message);
      results.failed++;
      results.errors.push(`验证手机号: ${error.message}`);
    }

    console.log('\n   其他2个API（删除、批量删除）需要有数据ID后测试');

  } catch (error: any) {
    console.log('❌ 白名单API测试异常:', error.message);
  }

  return results;
}

// ========== 主测试函数 ==========

export async function runPhase1APITests() {
  console.log('');
  console.log('╔' + '═'.repeat(78) + '╗');
  console.log('║' + ' '.repeat(20) + 'Phase 1 API对接测试' + ' '.repeat(39) + '║');
  console.log('╚' + '═'.repeat(78) + '╝');
  console.log('');
  console.log('测试服务器: http://139.196.165.140:10010/');
  console.log('测试范围: Phase 1共28个API');
  console.log('');

  const allResults = {
    activation: { total: 3, passed: 0, failed: 0, errors: [] as string[] },
    users: { total: 14, passed: 0, failed: 0, errors: [] as string[] },
    whitelist: { total: 5, passed: 0, failed: 0, errors: [] as string[] }
  };

  // 测试设备激活
  allResults.activation = await testActivationAPIs();

  // 注意：用户管理和白名单需要Token，需要先登录
  console.log('\n' + '='.repeat(80));
  console.log('⚠️  注意：用户管理和白名单API需要先登录获取Token');
  console.log('请先使用authService.unifiedLogin登录，然后传入token继续测试');
  console.log('='.repeat(80));

  // 生成测试报告
  console.log('\n' + '='.repeat(80));
  console.log('测试总结');
  console.log('='.repeat(80));

  const totalTests = allResults.activation.total + allResults.users.total + allResults.whitelist.total;
  const totalPassed = allResults.activation.passed + allResults.users.passed + allResults.whitelist.passed;
  const totalFailed = allResults.activation.failed + allResults.users.failed + allResults.whitelist.failed;

  console.log(`\n总测试数: ${totalTests}`);
  console.log(`✅ 通过: ${totalPassed}`);
  console.log(`❌ 失败: ${totalFailed}`);
  console.log(`⏸️  跳过: ${totalTests - totalPassed - totalFailed}`);

  if (totalFailed > 0) {
    console.log('\n失败详情:');
    [...allResults.activation.errors, ...allResults.users.errors, ...allResults.whitelist.errors]
      .forEach(err => console.log(`  - ${err}`));
  }

  return {
    total: totalTests,
    passed: totalPassed,
    failed: totalFailed,
    details: allResults
  };
}

// 导出供React Native使用
export const Phase1APITest = {
  testActivationAPIs,
  testUserManagementAPIs,
  testWhitelistAPIs,
  runPhase1APITests
};
