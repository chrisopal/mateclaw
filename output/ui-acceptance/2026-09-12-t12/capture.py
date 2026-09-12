"""Capture only the isolated T12 conversation and modeling result, no authentication material."""
import json,sys
from pathlib import Path
OUT=Path(__file__).resolve().parent;sys.path.insert(0,str(OUT.parent/'2026-09-11-b2'))
from runtime_client import Client
c=Client();c.workspace=json.loads((OUT/'fixture.json').read_text())['workspaceId']
conv=sys.argv[1];task=sys.argv[2];label=sys.argv[3]
messages=c.call('/conversations/'+conv+'/messages');task=c.call('/semantic/modeling-tasks/'+task)
(OUT/(label+'-task.json')).write_text(json.dumps(task,ensure_ascii=False,indent=2))
allowed=['id','role','content','toolName','toolCalls','toolCallId','model','modelName','promptTokens','completionTokens','totalTokens','tokens','cost','createTime','metadata','runtimeModel','runtimeProvider','cacheReadTokens','cacheWriteTokens','reasoningTokens','contentParts']
clean=[{k:v for k,v in m.items() if k in allowed} for m in messages]
(OUT/(label+'-conversation.json')).write_text(json.dumps(clean,ensure_ascii=False,indent=2))
print({'messages':len(messages),'messageFields':list(messages[-1]) if messages else [],'stage':task['stage'],'proposals':len(task['proposals'])})
