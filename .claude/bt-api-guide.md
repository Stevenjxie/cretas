# 宝塔面板API指南

## 连接信息
- **地址**: `https://139.196.165.140:16435` (必须HTTPS)
- **密钥**: `<REVOKED_BAOTA_API_KEY>`
- **注意**: 必须添加IP白名单，使用 `-k` 忽略SSL

## 签名算法

```bash
TIME_TOKEN=$(python3 << 'EOF'
import hashlib, time
api_sk = "<REVOKED_BAOTA_API_KEY>"
t = str(int(time.time()))
token = hashlib.md5((t + hashlib.md5(api_sk.encode()).hexdigest()).encode()).hexdigest()
print(f"{t}|{token}")
EOF
)
REQUEST_TIME=$(echo $TIME_TOKEN | cut -d'|' -f1)
REQUEST_TOKEN=$(echo $TIME_TOKEN | cut -d'|' -f2)
```

## 常用接口

### 系统信息
```bash
curl -k -X POST "https://139.196.165.140:16435/system?action=GetSystemTotal" \
  -d "request_time=$REQUEST_TIME" -d "request_token=$REQUEST_TOKEN"
```

### 文件操作
```bash
# 目录列表
curl -k -X POST "https://139.196.165.140:16435/files?action=GetDir" \
  -d "request_time=$REQUEST_TIME" -d "request_token=$REQUEST_TOKEN" -d "path=/www/wwwroot"

# 读取文件
curl -k -X POST "https://139.196.165.140:16435/files?action=GetFileBody" \
  -d "request_time=$REQUEST_TIME" -d "request_token=$REQUEST_TOKEN" \
  -d "path=/www/wwwroot/project/app.log"

# 上传文件
curl -k -X POST "https://139.196.165.140:16435/files?action=upload" \
  -F "request_time=$REQUEST_TIME" -F "request_token=$REQUEST_TOKEN" \
  -F "f_path=/www/wwwroot/project" -F "f_name=file.sh" \
  -F "f_size=$FILE_SIZE" -F "f_start=0" -F "blob=@/local/file"

# 设置权限
curl -k -X POST "https://139.196.165.140:16435/files?action=SetFileAccess" \
  -d "request_time=$REQUEST_TIME" -d "request_token=$REQUEST_TOKEN" \
  -d "filename=/www/wwwroot/project/script.sh" -d "user=root" -d "access=755"
```

## 项目路径
- JAR: `/www/wwwroot/project/cretas-backend-system-1.0.0.jar`
- 日志: `/www/wwwroot/project/cretas-backend.log`
- 端口: 10010

## 局限性
- ❌ 不能执行Shell命令、查看进程、直接重启服务
- ✅ 替代：上传脚本后在宝塔终端执行

## 常见错误
- `IP校验失败` → 添加IP白名单
- `连接被拒绝` → 确认使用HTTPS和正确端口
- `文件不存在` → SaveFileBody只能编辑已存在文件，用upload创建
