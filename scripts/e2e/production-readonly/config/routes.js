'use strict';

const ROUTES = Object.freeze({
  login: '/login',
  dashboard: '/dashboard',
  bom: '/production/bom',
  purchasing: '/procurement/orders',
  finance: '/finance/ar-ap',
  suppliers: '/procurement/suppliers',
  products: '/system/products',
  workflow: '/system/product-processes',
  productionPlans: '/production/plans',
  labelQc: '/quality/label-qc',
});

module.exports = { ROUTES };
