"""Local persistent QA API helper. Credentials remain in memory and are never recorded."""
import json,re
from pathlib import Path
from urllib.request import Request,urlopen
ROOT=Path(__file__).resolve().parents[3]
OUT=Path(__file__).resolve().parent
BASE='http://127.0.0.1:18109/api/v1'
class Client:
    def __init__(self):
        self.workspace=json.loads((OUT/'runtime-fixture.json').read_text())['workspaceId']
        password=re.search(r'密码：([^，]+)',(ROOT/'mateclaw-server/src/main/resources/db/data-mysql-zh.sql').read_text())[1]
        self.token=None
        self.token=self.call('/auth/login',{'username':'admin','password':password})['token']
    def call(self,path,body=None,method=None,timeout=120):
        headers={'Content-Type':'application/json','X-Workspace-Id':self.workspace}
        if self.token:headers['Authorization']='Bearer '+self.token
        req=Request(BASE+path,data=None if body is None else json.dumps(body,ensure_ascii=False).encode(),headers=headers,method=method or ('POST' if body is not None else 'GET'))
        with urlopen(req,timeout=timeout) as response:result=json.load(response)
        if result.get('code') not in (0,200):raise RuntimeError({'code':result.get('code'),'message':result.get('msg')})
        return result.get('data')
