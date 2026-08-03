const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const assert = require('node:assert/strict');

const root = path.resolve(__dirname, '..', '..');
const service = fs.readFileSync(path.join(
  root, 'scripts/systemd/cretas-restaurant-demo-stream-20260805.service',
), 'utf8');
const timer = fs.readFileSync(path.join(
  root, 'scripts/systemd/cretas-restaurant-demo-stream-20260805.timer',
), 'utf8');
const qhjService = fs.readFileSync(path.join(
  root, 'scripts/systemd/cretas-restaurant-demo-stream-qhj-20260805.service',
), 'utf8');
const qhjTimer = fs.readFileSync(path.join(
  root, 'scripts/systemd/cretas-restaurant-demo-stream-qhj-20260805.timer',
), 'utf8');
const installer = fs.readFileSync(path.join(
  root, 'scripts/deploy/install-restaurant-demo-stream.sh',
), 'utf8');

test('services are bounded to the two approved restaurant tenants and ten-second cadence', () => {
  assert.match(service, /--factory MOCK_REST/);
  assert.match(service, /--source cretas_live_showcase_20260805/);
  assert.match(service, /--interval-seconds 10/);
  assert.match(service, /--apply --confirm MOCK_REST/);
  assert.match(service, /--start 2026-08-05T09:00:00\+08:00/);
  assert.match(service, /--end 2026-08-05T14:00:00\+08:00/);
  assert.match(
    service,
    /Environment=PYTHONPATH=\/www\/wwwroot\/cretas\/code\/backend\/python:\/www\/wwwroot\/cretas\/code\/backend\/python\/smartbi/,
  );
  assert.doesNotMatch(service, /RES_3101_009|F006/);

  assert.match(qhjService, /--factory RES_3101_009/);
  assert.match(qhjService, /--source cretas_live_showcase_20260805/);
  assert.match(qhjService, /--interval-seconds 10/);
  assert.match(qhjService, /--apply --confirm RES_3101_009/);
  assert.match(qhjService, /--start 2026-08-05T09:00:00\+08:00/);
  assert.match(qhjService, /--end 2026-08-05T14:00:00\+08:00/);
  assert.match(
    qhjService,
    /Environment=PYTHONPATH=\/www\/wwwroot\/cretas\/code\/backend\/python:\/www\/wwwroot\/cretas\/code\/backend\/python\/smartbi/,
  );
  assert.doesNotMatch(qhjService, /MOCK_REST|F006/);
});

test('timer is persistent, one-day, timezone-explicit and one-second accurate', () => {
  assert.match(timer, /OnCalendar=2026-08-05 09:00:00 Asia\/Singapore/);
  assert.match(timer, /AccuracySec=1s/);
  assert.match(timer, /Persistent=true/);
  assert.match(timer, /Unit=cretas-restaurant-demo-stream-20260805\.service/);
  assert.match(qhjTimer, /OnCalendar=2026-08-05 09:00:00 Asia\/Singapore/);
  assert.match(qhjTimer, /AccuracySec=1s/);
  assert.match(qhjTimer, /Persistent=true/);
  assert.match(qhjTimer, /Unit=cretas-restaurant-demo-stream-qhj-20260805\.service/);
});

test('installer requires exact main, explicit production confirmation and writes a receipt', () => {
  assert.match(installer, /YES-PROD-DEMO-STREAM/);
  assert.match(installer, /git status --porcelain/);
  assert.match(installer, /git rev-parse origin\/main/);
  assert.match(installer, /systemd-analyze verify/);
  assert.match(installer, /systemctl enable --now/);
  assert.match(installer, /cretas-restaurant-demo-stream-install-v1/);
  assert.match(installer, /event_ceiling["']?:? 1800/);
  assert.match(installer, /factory_ids["']?:? \["MOCK_REST", "RES_3101_009"\]/);
  assert.match(installer, /QHJ_SERVICE/);
  assert.match(installer, /QHJ_TIMER/);
  assert.match(installer, /systemctl enable --now "\$qhj_timer"/);
  assert.match(installer, /grep -Eq -- 'F006'/);
});
