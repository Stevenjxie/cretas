#!/bin/bash
# 把餐饮外部平台模拟器部署到 139。
#
# ⚠️ 只上 139，绝不上 47 —— 47 是我们自己的系统，模拟器上 47 就破坏了
#    「它是外部世界」这个前提，整套链路的验证价值归零。
#
# 网络形状（实测，别凭直觉改）：
#   模拟器绑 127.0.0.1:9200 不对外 → 139 已有的 nginx:80 反代 /mock/ → 外部
#   139 的阿里云安全组（账号 B）只放行 80/443/8086；47→139 打 9200/8085/8082
#   全 TIMEOUT，连 firewalld 已放行的端口也不通。所以自开端口这条路走不通。
set -eo pipefail

SERVER="root@139.196.165.140"
BASE_DIR="/www/wwwroot/mock-platform"
REMOTE_DIR="$BASE_DIR/code"
VENV="$BASE_DIR/venv311"
LOCAL_DIR="mock-platform"
# BT nginx 在 http 层 include /www/server/panel/vhost/nginx/*.conf，
# 所以 location 不能单独放那儿（那是 server 上下文）。改为：把 location 体放进
# 自己的文件，再往 0.default.conf（server_name _，接管裸 IP:80）里插一行 include。
# 对共享 vhost 的改动就这一行，且幂等。
NGINX_SNIPPET="/www/server/nginx/conf/mock-platform-location.conf"
NGINX_VHOST="/www/server/panel/vhost/nginx/0.default.conf"

cd "$(dirname "$0")/../.."

echo "[1/6] 校验隔离铁律..."
if grep -rEn "smartbi|psycopg|asyncpg|smartbi_prod_db|cretas_prod_db" \
     "$LOCAL_DIR/mock_platform" --include="*.py"; then
    echo "错误: 模拟端泄漏了本系统依赖，拒绝部署"
    exit 1
fi
echo "  ✅ 零命中"

echo "[2/6] 本地跑测试..."
(cd "$LOCAL_DIR" && python -m pytest tests/ -q)

echo "[3/6] 同步代码到 139..."
ssh "$SERVER" "mkdir -p $REMOTE_DIR"
# --delete 让服务器不留僵尸文件。data.db 排除在外，否则会把线上世界删掉。
rsync -az --delete --timeout=60 \
    --exclude "__pycache__" --exclude ".pytest_cache" --exclude "*.db" \
    "$LOCAL_DIR/" "$SERVER:$REMOTE_DIR/"

echo "[4/6] 确保 python3.11 + 装依赖 + 重启服务..."
# ⚠️ 139 出厂只有 python3.6 / python3.8（实测）。alinux3-updates 源里有
#    python3.11-3.11.13-7.0.1.al8，缺了就装。
ssh "$SERVER" "command -v python3.11 >/dev/null 2>&1 || yum install -y python3.11"
ssh "$SERVER" "set -e; cd $REMOTE_DIR; \
    test -d $VENV || python3.11 -m venv $VENV; \
    $VENV/bin/pip install -q --upgrade pip; \
    $VENV/bin/pip install -q -r requirements.txt; \
    systemctl restart cretas-mock-platform"

echo "[5/6] 装/校验 nginx /mock/ 反代..."
ssh "$SERVER" "set -e
cat > $NGINX_SNIPPET <<'NGINX'
# 餐饮外部平台模拟器（2026-07-29）。模拟器绑 127.0.0.1:9200 不对外，
# 这里是它唯一的公网出口。proxy_pass 末尾的 / 会剥掉 /mock 前缀：
#   /mock/keruyun/open/order/list  ->  /keruyun/open/order/list
location /mock/ {
    proxy_pass http://127.0.0.1:9200/;
    proxy_set_header Host \$host;
    proxy_set_header X-Real-IP \$remote_addr;
    proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
    proxy_read_timeout 60s;
}
NGINX
# 幂等插 include：已有就不动，避免每次部署都改共享 vhost
if ! grep -q 'mock-platform-location.conf' $NGINX_VHOST; then
    cp $NGINX_VHOST $NGINX_VHOST.bak.\$(date +%Y%m%d_%H%M%S)
    # 插在最后一个 } 之前（server 块收尾）
    sed -i '\$ i\\    include $NGINX_SNIPPET;' $NGINX_VHOST
    echo '  已插入 include（原文件已备份）'
else
    echo '  include 已存在，跳过'
fi
nginx -t
nginx -s reload"

echo "[6/6] 健康检查..."
# ⚠️ 判据必须是 generator=running，不能只看 status=ok ——
#    生成器被 GC / 抛异常退出时 healthz 回 {"status":"degraded","generator":"stopped"}，
#    而「没挂生成器」那一档是 {"status":"ok","generator":"not_armed"}。
#    用 status=ok 做验收会在一个死掉的生成器上通过。
ok=0
for _ in $(seq 1 15); do
    if ssh "$SERVER" "curl -fsS -m 3 http://127.0.0.1:9200/healthz 2>/dev/null" \
         | grep -q '"generator":"running"'; then
        echo "  ✅ 本机 9200 健康，生成器在跑"
        ok=1
        break
    fi
    sleep 2
done
if [ "$ok" -ne 1 ]; then
    echo "❌ 健康检查失败（generator 未 running）"
    ssh "$SERVER" "curl -s -m 3 http://127.0.0.1:9200/healthz; echo; \
        tail -40 $BASE_DIR/mock-platform.log"
    exit 1
fi

# 本机绿但公网路径不通 = 47 拉不到任何东西，必须一起验，否则这次部署等于没上。
if curl -fsS -m 10 "http://139.196.165.140/mock/healthz" | grep -q '"generator":"running"'; then
    echo "  ✅ 公网 /mock/ 反代通"
else
    echo "❌ 本机 9200 健康但公网 http://139.196.165.140/mock/ 不通"
    curl -s -m 10 -o /dev/null -w '  http_code=%{http_code}\n' "http://139.196.165.140/mock/healthz" || true
    exit 1
fi

echo "✅ 模拟器部署完成"
