#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
宝塔面板API调用客户端
使用方法:
    python3 bt_api_client.py GetSystemTotal
    python3 bt_api_client.py GetDir files path=/www/wwwroot
    python3 bt_api_client.py GetFileBody files path=/path/to/file
"""

import hashlib
import time
import sys
import urllib.parse
import urllib.request
import ssl
import json
import os

# Load credentials from the caller environment or a root-only environment file.
BT_PANEL_URL = os.environ.get("BT_PANEL_URL", "https://139.196.165.140:16435")
API_KEY = os.environ.get("BT_API_KEY", "")


def generate_token():
    """Generate a short-lived API signature without logging the secret."""
    if not API_KEY:
        raise RuntimeError("BT_API_KEY must be supplied from a root-only credential source")
    request_time = str(int(time.time()))
    md5_api_sk = hashlib.md5(API_KEY.encode()).hexdigest()
    request_token = hashlib.md5((request_time + md5_api_sk).encode()).hexdigest()
    return request_time, request_token


def call_api(action, module="system", **kwargs):
    """
    调用宝塔API
    
    Args:
        action: API动作名称
        module: API模块 (system/files/site/data等)
        **kwargs: 额外的POST参数
    
    Returns:
        API响应结果
    """
    # 生成签名
    request_time, request_token = generate_token()
    
    # 构建URL
    url = f"{BT_PANEL_URL}/{module}?action={action}"
    
    # 构建POST数据
    data = {
        "request_time": request_time,
        "request_token": request_token,
    }
    data.update(kwargs)
    
    # 编码数据
    post_data = urllib.parse.urlencode(data).encode('utf-8')
    
    # 创建请求
    context = ssl._create_unverified_context()  # 忽略SSL证书验证
    req = urllib.request.Request(url, data=post_data, method='POST')
    req.add_header('Content-Type', 'application/x-www-form-urlencoded')
    
    try:
        # 发送请求
        print(f"调用API: {url}")
        safe_data = dict(data)
        safe_data["request_token"] = "<redacted>"
        print(f"Request data: {safe_data}")
        print("-" * 50)
        
        with urllib.request.urlopen(req, context=context, timeout=30) as response:
            result = response.read().decode('utf-8')
            
            # 尝试解析JSON
            try:
                json_result = json.loads(result)
                return json.dumps(json_result, indent=2, ensure_ascii=False)
            except json.JSONDecodeError:
                return result
                
    except urllib.error.URLError as e:
        return f"错误: {e}"


def main():
    """主函数"""
    if len(sys.argv) < 2:
        print("使用方法: python3 bt_api_client.py <action> [module] [参数...]")
        print("")
        print("常用操作:")
        print("  python3 bt_api_client.py GetSystemTotal")
        print("  python3 bt_api_client.py GetDiskInfo")
        print("  python3 bt_api_client.py GetDir files path=/www/wwwroot")
        print("  python3 bt_api_client.py GetFileBody files path=/path/to/file")
        print("")
        print("模块说明:")
        print("  system  - 系统相关 (默认)")
        print("  files   - 文件管理")
        print("  site    - 网站管理")
        print("  data    - 数据查询")
        sys.exit(1)
    
    action = sys.argv[1]
    module = sys.argv[2] if len(sys.argv) > 2 and not sys.argv[2].startswith('path=') else "system"
    
    # 解析额外参数
    kwargs = {}
    start_idx = 2 if module != "system" else 1
    for arg in sys.argv[start_idx + 1:]:
        if '=' in arg:
            key, value = arg.split('=', 1)
            kwargs[key] = value
    
    # 调用API
    result = call_api(action, module, **kwargs)
    print(result)


if __name__ == "__main__":
    main()
