"""Exercise a forum backend integration against the loopback demo. Uses only synthetic posts."""
import json
from datetime import datetime, timezone
from pathlib import Path
from time import monotonic, sleep
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4


def main():
    private = Path(__file__).resolve().parents[2] / ".local"
    def config(name):
        return json.loads((private / name).read_text(encoding="utf-8-sig"))
    keys = config("credentials.json")
    port = int(config("deployment.json")["enginePort"])
    base = f"http://127.0.0.1:{port}/api/engine/v1/"
    source = "forum-demo-" + uuid4().hex[:10]
    filters = {"sources": [source]}
    def call(route, payload, admin=False, status=200):
        headers = {"Authorization": "Bearer " + keys["adminKey" if admin else "queryToken"],
                   "Content-Type": "application/json"}
        request = Request(base + route, data=json.dumps(payload, ensure_ascii=False).encode(), headers=headers)
        try:
            with urlopen(request, timeout=30) as response:
                code, body = response.status, json.load(response)
        except HTTPError as exc:
            code, body = exc.code, json.load(exc)
        if code != status:
            raise RuntimeError(f"{route}: expected HTTP {status}, received {code}")
        return body

    def post(native_id, title, text):
        return {"object":{"domain":"demo","source":source,"nativeId":native_id},"type":"post","version":1,
                "deleted":False,"updatedAt":datetime.now(timezone.utc).isoformat(),"title":title,"text":text,
                "mediaType":"text/plain","language":"zh","parseAllowed":True,
                "location":{"uri":f"engine-source://demo/{source}/{native_id}"},
                "fields":{"category":"maintenance","status":"open"},
                "acl":{"public":False,"users":[],"groups":["staff"]}}

    def write(items):
        result = call("items:batch", {"schemaVersion":"1.0","items":items},admin=True)
        if any(row["status"] != "applied" for row in result["results"]):
            raise RuntimeError("Batch write rejected: "+str([r["status"] for r in result["results"]]))

    question = post("question","设备 A 滤芯更换讨论","更换滤芯后应在哪里登记工单？")
    answer = post("answer","设备 A 滤芯更换工单说明","请在维保台账记录本次更换的资产编号与日期。")
    question["relations"] = [{"kind":"explicit","target":answer["object"]}]
    def analyze(template, **fields):
        return call("analyze",dict(schemaVersion="1.0",template=template,**fields))
    try:
        write([answer,question])
        assert analyze("count",filters=filters)["count"] == 2
        assert analyze("group_by",groupBy="category",filters=filters)["groups"][0]["count"] == 2
        assert analyze("relations",item=question["object"])["relations"] == question["relations"]
        assert call("source",answer["object"])["item"]["version"] == 1
        print("2 篇合成帖子已写入。当前用户可见 2 篇，论坛明确声明的关联可回读。")
        until = monotonic() + 120
        while monotonic() < until:
            hits = call("search",dict(schemaVersion="1.0",query="滤芯更换",mode="hybrid",filters=filters,pageSize=5))
            if len(hits["items"]) == 2 and not hits["degraded"]:
                break
            sleep(1)
        else:
            raise RuntimeError("Forum search index not ready within 120 seconds")
        related = call("recommendations",dict(schemaVersion="1.0",kind="related",item=question["object"],filters=filters,pageSize=5))
        assert any(x["item"]["object"] == answer["object"] for x in related["items"])
        print("论坛搜索命中 2 篇，帖子详情的相关推荐包含工单说明。")
        answer["version"] = 2
        answer["acl"]["groups"] = ["supervisors"]
        write([answer])
        call("source",answer["object"],status=404)
        assert not analyze("relations",item=question["object"])["relations"]
        print("版块收紧权限后，普通用户无法读取目标帖，关联列表也已更新。")
    finally:
        for item in (question,answer):
            item.update(version=item["version"]+1, deleted=True)
        write([question,answer])
    assert analyze("count",filters=filters)["count"] == 0
    print("两篇演示帖子已标记删除，来源可见计数为 0。")


if __name__=="__main__":
    main()
