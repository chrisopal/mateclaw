"""Prepare only source materials. Model suggestions must come from actual natural-language chat."""
import sys,json,hashlib,datetime
from pathlib import Path
OUT=Path(__file__).resolve().parent;sys.path.insert(0,str(OUT.parent/'2026-09-11-b2'))
from runtime_client import Client
c=Client();c.workspace=str(c.call('/workspaces',{'name':'T12 完整验收 '+datetime.datetime.now().strftime('%Y%m%d-%H%M%S')})['id'])
k=c.call('/wiki/knowledge-bases',{'name':'T12 固定领域资料'})
materials=[]
for file in sorted((OUT/'materials').glob('*-v1.txt')):
 text=file.read_text();r=c.call('/wiki/knowledge-bases/'+str(k['id'])+'/raw/text',{'title':file.stem,'content':text});materials.append({'file':str(file.relative_to(OUT)),'sourceRef':str(r['id']),'sha256':hashlib.sha256(text.encode()).hexdigest()})
(OUT/'fixture.json').write_text(json.dumps({'workspaceId':c.workspace,'knowledgeBaseId':str(k['id']),'materials':materials},indent=2));print('Isolated T12 source workspace '+c.workspace)
