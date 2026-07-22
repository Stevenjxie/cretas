#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""为「没有商家截图」的 4 个档口生成菜品示意图（通义万相 wanx2.1-t2i-turbo）。

客户发来的 6 张现状小程序截图只覆盖了 6 个档口，其余 4 家（东池 Plus / 老城厢 /
磊子川菜 / 代巴尼）没有任何菜品图。这些是 demo 用的示意图，正式上线须换商家原图。

Prompt 统一走「餐厅实拍感」而非精修 CG，以便与其他 6 家的商家真图混排时风格不打架。

用法:
    python tools/gen_dish_images.py              # 生成全部缺图档口
    python tools/gen_dish_images.py leizi        # 只生成指定档口
"""
import json
import os
import re
import sys
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "platform", "foodcourt", "assets", "dish")
ENV = r"C:\Users\Steve\my-prototype-logistics\backend\python\.env"
API = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis"
TASK = "https://dashscope.aliyuncs.com/api/v1/tasks/"

STYLE = ("，专业美食摄影，45度俯拍，浅景深，自然光，中式餐厅实拍质感，"
         "画面干净，无文字无水印无人物")

DISHES = {
    "dongchi": [
        ("招牌照烧鸡腿饭", "日式照烧鸡腿便当，米饭配照烧鸡腿、玉子烧、西兰花、渍萝卜，木质便当盒"),
        ("日式炸猪排饭", "日式炸猪排饭，金黄酥脆猪排切块铺在米饭上，配卷心菜丝和猪排酱"),
        ("鳗鱼饭", "日式蒲烧鳗鱼饭，油亮鳗鱼铺满米饭，撒白芝麻和海苔丝，黑色漆器碗"),
        ("亲子丼", "日式亲子丼，滑蛋鸡肉盖浇饭，半熟蛋液包裹鸡肉块，撒葱花，陶碗"),
        ("和风牛肉饭", "日式牛肉饭，薄切牛肉片配洋葱铺在米饭上，中间一颗温泉蛋，白瓷碗"),
    ],
    "laochengxiang": [
        ("蟹粉小笼", "上海蟹粉小笼包，竹蒸笼里六只小笼，皮薄透亮可见蟹粉汤汁，配姜丝醋碟"),
        ("鲜肉小笼", "上海鲜肉小笼包，竹蒸笼装，褶皱精致，皮薄馅足冒着热气"),
        ("灌汤包", "淮扬灌汤包，大个头汤包配吸管，竹蒸笼，皮薄透亮汤汁饱满"),
        ("三鲜馄饨", "上海三鲜馄饨，清汤大馄饨浮在碗中，撒紫菜蛋皮丝和葱花，白瓷碗"),
        ("蟹壳黄", "上海蟹壳黄烧饼，酥皮层层，表面撒满白芝麻，烤成金黄色，竹编垫"),
    ],
    "leizi": [
        ("水煮牛肉", "川菜水煮牛肉，红油辣汤里嫩牛肉片，铺满干辣椒花椒和豆芽，撒葱花蒜末，深口瓷盆"),
        ("麻婆豆腐", "川菜麻婆豆腐，红亮豆瓣油汁裹嫩豆腐块，撒花椒粉和青蒜苗，黑色石锅"),
        ("宫保鸡丁", "川菜宫保鸡丁，鸡丁配花生米和干辣椒，酱汁油亮，撒葱段，白瓷盘"),
        ("夫妻肺片", "川菜夫妻肺片，牛肉牛杂薄片淋红油辣汁，撒花生碎和芝麻香菜，浅口瓷盘"),
        ("回锅肉", "川菜回锅肉，五花肉片炒至灯盏窝状，配青蒜苗和青红椒，豆瓣酱色泽红亮"),
        ("辣子鸡", "川菜辣子鸡，酥脆鸡块埋在干辣椒堆里，撒白芝麻和花椒，铁盘盛装"),
    ],
    "deibagni": [
        ("玛格丽特披萨", "意式手工窑炉玛格丽特披萨，饼边焦斑蓬松，番茄酱底配水牛芝士和新鲜罗勒叶，木质披萨板"),
        ("帕尔玛火腿披萨", "意式窑炉披萨铺满帕尔玛生火腿片和芝麻菜，撒帕玛森芝士碎，木质披萨板"),
        ("四芝士披萨", "意式四芝士披萨，融化的马苏里拉戈贡佐拉芝士拉丝，饼边焦香，木质披萨板"),
        ("辣香肠披萨", "意式辣香肠披萨，红色萨拉米香肠片铺满融化芝士，边缘微焦起泡，木质披萨板"),
    ],
}


def key():
    return re.search(r"LLM_API_KEY=(\S+)", open(ENV, encoding="utf-8").read()).group(1)


def post(url, payload, k, extra=None):
    h = {"Authorization": "Bearer " + k, "Content-Type": "application/json"}
    h.update(extra or {})
    req = urllib.request.Request(url, data=json.dumps(payload).encode(), headers=h)
    return json.loads(urllib.request.urlopen(req, timeout=60).read())


def get(url, k):
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + k})
    return json.loads(urllib.request.urlopen(req, timeout=60).read())


def main():
    k = key()
    only = sys.argv[1:] or list(DISHES)
    os.makedirs(OUT, exist_ok=True)

    jobs = []
    for sid in only:
        for i, (name, prompt) in enumerate(DISHES[sid], 1):
            r = post(API, {
                "model": "wanx2.1-t2i-turbo",
                "input": {"prompt": prompt + STYLE},
                "parameters": {"size": "1024*768", "n": 1, "prompt_extend": True},
            }, k, {"X-DashScope-Async": "enable"})
            tid = r["output"]["task_id"]
            jobs.append((sid, i, name, tid))
            print("submit %-14s %d %-12s %s" % (sid, i, name, tid))
            time.sleep(0.4)

    print("\n--- polling %d tasks ---" % len(jobs))
    pending = list(jobs)
    deadline = time.time() + 900
    while pending and time.time() < deadline:
        time.sleep(6)
        still = []
        for sid, i, name, tid in pending:
            try:
                r = get(TASK + tid, k)
            except Exception as e:
                print("  poll err", tid, str(e)[:80]); still.append((sid, i, name, tid)); continue
            st = r["output"]["task_status"]
            if st == "SUCCEEDED":
                u = r["output"]["results"][0]["url"]
                path = os.path.join(OUT, "%s-ai-%d.jpg" % (sid, i))
                urllib.request.urlretrieve(u, path)
                print("  OK  %-14s %-12s -> %s" % (sid, name, os.path.basename(path)))
            elif st in ("FAILED", "UNKNOWN"):
                print("  FAIL %-14s %-12s %s" % (sid, name, str(r["output"])[:120]))
            else:
                still.append((sid, i, name, tid))
        pending = still
        if pending:
            print("  ... %d remaining" % len(pending))
    if pending:
        print("TIMEOUT on %d tasks" % len(pending))
    print("done")


if __name__ == "__main__":
    main()
