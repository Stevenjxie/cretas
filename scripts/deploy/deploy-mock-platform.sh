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
#
# 前置条件（brief Step 3 / Step 4 手工做过一次，本脚本不代劳）：
#   1) 139 上有 /www/wwwroot/mock-platform/.env（含密钥，不进 git）
#   2) /etc/systemd/system/cretas-mock-platform.service 已装且 daemon-reload 过
#   3) 本机能 ssh 到 **139 和 47 两台** —— [7/7] 要从 47 发起公网检查
# 三条都在 [0/7] 预检里硬校验 —— 缺了就在动共享网关机之前停下来。
set -euo pipefail

SERVER="root@139.196.165.140"
PULLER="root@47.100.235.168"      # 拉取端。公网路径必须从它那儿验，不是从我的机器
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

echo "[0/7] 预检前置条件（在动共享网关机之前）..."
# ⚠️ 顺序很要紧: 先查再动。否则一次误跑会留下 mkdir + rsync + 在生产网关上
#    yum install + 建 venv 的副作用, 最后才在 systemctl restart 上因为
#    「unit 不存在」失败 —— 半个部署的残留换一个本可以一行查出来的前提。
# 先单独确认两台都连得上。否则 ssh 自身失败(网络/认证, rc=255)会和「文件不存在」
# 走同一个分支, 把「连不上主机」误报成「unit 没装」, 让人去 scp 到一台根本够不着的机器。
for host in "$SERVER" "$PULLER"; do
    ssh -o ConnectTimeout=15 -o BatchMode=yes "$host" true || {
        echo "❌ ssh 连不上 $host（网络或认证问题，不是部署内容的问题）"
        exit 1
    }
done
echo "  ✅ 139 / 47 都连得上"

ssh "$SERVER" "test -f /etc/systemd/system/cretas-mock-platform.service" || {
    echo "❌ 139 上没装 systemd unit。先做 brief Step 4:"
    echo "   scp scripts/systemd/cretas-mock-platform.service $SERVER:/etc/systemd/system/"
    echo "   ssh $SERVER 'systemctl daemon-reload && systemctl enable cretas-mock-platform'"
    exit 1
}
ssh "$SERVER" "test -f $BASE_DIR/.env" || {
    echo "❌ 139 上没有 $BASE_DIR/.env（含密钥，不进 git）。先做 brief Step 3。"
    echo "   注意 unit 用的是 EnvironmentFile= 而非 -EnvironmentFile=，缺文件会"
    echo "   配合 Restart=always 变成 10s 一次的重启循环。"
    exit 1
}
echo "  ✅ unit 与 .env 都在"

echo "[1/7] 校验隔离铁律..."
# ⚠️ 不能写成 `if grep ...; then 拒绝; fi` —— grep 找不到文件时返回 2,
#    在 if 里和「没匹配」的 1 一样走 else 分支, 于是路径写错时会打印
#    "✅ 零命中" 却什么都没查。断言一件没做过的检查就是降级。
set +e
grep -rEn "smartbi|psycopg|asyncpg|smartbi_prod_db|cretas_prod_db" \
     "$LOCAL_DIR/mock_platform" --include="*.py"
gate_rc=$?
set -e
case "$gate_rc" in
    0) echo "❌ 模拟端泄漏了本系统依赖，拒绝部署"; exit 1 ;;
    1) echo "  ✅ 零命中" ;;
    *) echo "❌ 隔离检查本身失败（grep rc=$gate_rc，多半是路径不对），拒绝部署"; exit 1 ;;
esac

echo "[2/7] 本地跑测试..."
(cd "$LOCAL_DIR" && "${PYTHON:-python}" -m pytest tests/ -q)

echo "[3/7] 同步代码到 139..."
ssh "$SERVER" "mkdir -p $REMOTE_DIR"
# --delete 让服务器不留僵尸文件（本仓吃过「没 --delete 留下僵尸文件」的亏）。
# 世界库其实在 $BASE_DIR/data.db，**在 rsync 目标 code/ 之外**，本来就不受影响；
# 排除 *.db* 是为了防有人把 MOCK_DB_PATH 指进 code/ —— 那时 --delete 会把
# 正在用的 data.db 连同 WAL 模式留下的 -wal / -shm 一起删掉（db.py 开了 WAL，
# 而那两个 sidecar 不匹配 *.db，只匹配 *.db*）。
rsync -az --delete --timeout=60 \
    --exclude "__pycache__" --exclude ".pytest_cache" --exclude "*.db*" \
    "$LOCAL_DIR/" "$SERVER:$REMOTE_DIR/"

echo "[4/7] 确保 python3.11 + 装依赖 + 重启服务..."
# ⚠️ 139 出厂只有 python3.6 / python3.8（实测）。alinux3-updates 源里有
#    python3.11-3.11.13-7.0.1.al8，缺了就装。
ssh "$SERVER" "command -v python3.11 >/dev/null 2>&1 || yum install -y python3.11"
ssh "$SERVER" "set -e; cd $REMOTE_DIR; \
    test -d $VENV || python3.11 -m venv $VENV; \
    $VENV/bin/pip install -q --upgrade pip; \
    $VENV/bin/pip install -q -r requirements.txt; \
    systemctl restart cretas-mock-platform"

echo "[5/7] 装/校验 nginx /mock/ 反代..."
# ⛔ 这一步改的是**共享生产网关**的 vhost（0.default.conf 接管裸 IP:80，同机还跑着
#    web-admin / showcase / foodcourt）。三道保险：
#    a) 插入点锚在「最后一个顶格 }」而不是行尾位置 —— BT 面板保存站点会重写这个
#       文件并可能补尾随空行，那时按行尾插会把 location 顶到 server 块外面，
#       变成 http 层的 location => nginx [emerg]，整台网关起不来。
#    b) nginx -t 失败立刻用备份回滚。不回滚的话磁盘上留着坏配置，运行中的 nginx
#       毫无异样，直到下一次任何人 reload（面板存站点 / 证书续期 / 重启）才炸，
#       而那时已经和这次部署完全对不上了。
#    c) 找不到收尾 } 就拒绝动手，不猜。
#    d) 校验与回滚覆盖**两个**文件。snippet 每次部署都被无条件重写, 且它被运行中的
#       vhost 引用着 —— 只保护 vhost 的话, 第二次及以后的部署里一个写坏的 snippet
#       会原样留在磁盘上(那次 include 已存在, 走"跳过"分支, 连备份都不做),
#       换个文件重演一遍 c) 里那个"下次 reload 才炸"的事故。
ssh "$SERVER" "set -e
TS=\$(date +%Y%m%d_%H%M%S)
SNIP_BAK=\"\"
if [ -f $NGINX_SNIPPET ]; then
    SNIP_BAK=$NGINX_SNIPPET.bak.\$TS
    cp $NGINX_SNIPPET \"\$SNIP_BAK\"
fi
VHOST_BAK=\"\"

restore() {
    [ -n \"\$VHOST_BAK\" ] && cp -f \"\$VHOST_BAK\" $NGINX_VHOST
    if [ -n \"\$SNIP_BAK\" ]; then
        cp -f \"\$SNIP_BAK\" $NGINX_SNIPPET
    else
        rm -f $NGINX_SNIPPET      # 本来就没有, 回滚就是让它继续不存在
    fi
}

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
if grep -q 'mock-platform-location.conf' $NGINX_VHOST; then
    echo '  include 已存在，跳过'
else
    VHOST_BAK=$NGINX_VHOST.bak.\$TS
    cp $NGINX_VHOST \"\$VHOST_BAK\"
    LAST=\$(grep -n '^}' $NGINX_VHOST | tail -1 | cut -d: -f1)
    if [ -z \"\$LAST\" ]; then
        echo '  ❌ 找不到 server 块收尾 }，拒绝改共享 vhost'
        restore
        exit 1
    fi
    sed -i \"\${LAST}i\\\\    include $NGINX_SNIPPET;\" $NGINX_VHOST
    echo \"  已插入 include 到第 \$LAST 行前（备份 \$VHOST_BAK）\"
fi
# 唯一的校验点, 覆盖 snippet 与 vhost 两个文件, 无论走的是哪个分支。
if ! nginx -t; then
    restore
    echo '  ❌ nginx -t 失败，snippet 与 vhost 都已回滚到本次部署前'
    exit 1
fi
# BT 机器上 nginx.service 是 sysv 生成的 unit，systemctl reload 不可靠；
# nginx -V 显示 --prefix=/www/server/nginx，裸 nginx 命令读的就是对的配置。
nginx -s reload"

echo "[6/7] 健康检查（139 本机）..."
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

echo "[7/7] 公网路径检查（**从 47 发起**）..."
# ⚠️ 必须从 47 验，不能从开发机验。47→139 正是实测证明会出问题的方向
#    （9200/8085/8082 全 TIMEOUT）。从我的机器 curl 通只能证明「互联网上某处能访问」，
#    证明不了拉取端能访问。这条不验的话，部署全绿而 Step 8 查出来 0 行，
#    却没有任何线索指向网络。
if ssh "$PULLER" "curl -fsS -m 10 http://139.196.165.140/mock/healthz" \
     | grep -q '"generator":"running"'; then
    echo "  ✅ 47 能通过 /mock/ 拿到模拟器"
else
    echo "❌ 139 本机健康，但 47 打不通 http://139.196.165.140/mock/"
    ssh "$PULLER" "curl -s -m 10 -o /dev/null -w '  47 看到的 http_code=%{http_code}\n' \
        http://139.196.165.140/mock/healthz" || true
    exit 1
fi

echo "✅ 模拟器部署完成"
