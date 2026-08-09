import { getUserRole, User } from '../types/auth';
import { isRestaurant } from './factoryType';

/**
 * 工厂老板和运营协调员在跨业务复用页中都只查看。
 * 餐饮管理员仍沿用餐饮移动管理路径，不受工厂老板边界影响。
 */
export function isMobileBusinessObserver(user: User | null): boolean {
  const role = getUserRole(user);
  return role === 'operations_coordinator'
    || (role === 'factory_super_admin' && !isRestaurant(user));
}
