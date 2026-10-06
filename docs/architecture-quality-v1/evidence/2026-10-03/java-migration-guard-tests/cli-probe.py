import hashlib,json,subprocess,tempfile
from pathlib import Path
repo=Path('/Users/guojiexie/.codex/worktrees/architecture-quality/mateclaw')
checker=repo/'scripts/quality/gate.py'
published='bf7a12f5406f46fcac1e3292a078f45414be3c6e'
manifest='.quality/frozen-migrations.json'
data=json.loads((repo/manifest).read_text())
files={p:subprocess.check_output(['git','-C',str(repo),'show',published+':'+p]) for p in data['files']}
assert all(hashlib.sha256(v).hexdigest()==data['files'][p] for p,v in files.items())
for dialect in ('h2','mysql','kingbase'):
    p=f'mateclaw-server/src/main/resources/db/migration/{dialect}/V217__presales_listing_projection.sql'
    files[p]=subprocess.check_output(['git','-C',str(repo),'show',published+':'+p])
files[manifest]=(repo/manifest).read_bytes()
helper='mateclaw-server/src/main/java/vip/mate/presales/PresalesListingProjectionV1.java'
backfill='mateclaw-server/src/main/java/vip/mate/presales/migration/PresalesListingProjectionBackfillV1.java'
java=[p for p in files if '/java/db/migration/' in p]

def declaration(values):
    return (json.dumps({'version':1,'files':{p:hashlib.sha256(v).hexdigest() for p,v in values.items() if p.endswith('.java')}},indent=2)+'\n').encode()

cases=[('unchanged',dict(files),0,None)]
for p in java:
    dialect=p.split('/migration/')[1].split('/')[0]
    cases.append(('edit-'+dialect,files|{p:files[p]+b'// attempted edit\n'},1,'DB-001'))
    cases.append(('delete-'+dialect,{k:v for k,v in files.items() if k!=p},1,'DB-001'))
for name,p in [('factory',helper),('backfill',backfill)]:
    changed=files|{p:files[p]+b'// attempted algorithm edit\n'}
    cases.append(('edit-'+name,changed,1,'DB-004'))
    cases.append(('rehash-'+name,changed|{manifest:declaration(changed)},1,'DB-004'))
changed={k:v for k,v in files.items() if k!=helper}
changed[helper.replace('V1','V2')]=files[helper]
cases.append(('rename-factory',changed,1,'DB-004'))
changed=dict(files)
frozen=json.loads(changed[manifest]); del frozen['files'][helper]
changed[manifest]=json.dumps(frozen).encode()
cases.append(('remove-declaration',changed,1,'DB-004'))
cases.append(('delete-manifest',{k:v for k,v in files.items() if k!=manifest},1,'DB-004'))
cases.append(('invalid-manifest',files|{manifest:b'not json'},1,'DB-003'))
new={p.replace('V218','V219'):b'class SyntheticEntry {}\n' for p in java}
changed=files|{next(iter(new)):next(iter(new.values()))}
changed[manifest]=declaration(changed)
cases.append(('missing-new-dialects',changed,1,'DB-002'))
changed=files|new|{helper.replace('V1','V2'):b'class SyntheticFactoryV2 {}\n'}
changed[manifest]=declaration(changed)
cases.append(('complete-new-version',changed,0,None))
sql=next(p for p in files if p.endswith('.sql'))
cases.append(('existing-sql-still-blocked',files|{sql:files[sql]+b'-- attempted edit\n'},1,'DB-001'))
results=[]
with tempfile.TemporaryDirectory(prefix='mateclaw-published-migration-probe-') as tmp:
    root=Path(tmp)
    def git(*args):
        return subprocess.check_output(['git','-C',str(root),*args],stderr=subprocess.STDOUT).decode().strip()
    def write(values):
        for p in files.keys()|values.keys():
            target=root/p
            if p not in values:
                target.unlink(missing_ok=True)
            else:
                target.parent.mkdir(parents=True,exist_ok=True); target.write_bytes(values[p])
    git('init','-q');git('config','user.name','Synthetic migration guard');git('config','user.email','guard@example.invalid')
    write(files);git('add','.');git('commit','-qm','Freeze copied published sources for isolated check')
    synthetic_base=git('rev-parse','HEAD')
    for name,values,expected,rule in cases:
        write(values);git('add','-A')
        modes=[]
        for mode in ('worktree','staged'):
            call=subprocess.run(['python3','-B',str(checker),'--repo',str(root),'--base',synthetic_base,'--mode',mode],capture_output=True,text=True)
            report=json.loads(call.stdout)
            rules=sorted({x['rule'] for x in report.get('new_violations',[])})
            assert call.returncode==expected and (rule is None or rule in rules),(name,mode,call.returncode,report)
            modes.append({'mode':mode,'exit':call.returncode,'status':report['status'],'rules':rules,'target_identity':report['target_identity']})
        results.append({'case':name,'checks':modes})
result={'published_source_head':published,'copied_source_sha256':{p:hashlib.sha256(v).hexdigest() for p,v in files.items()},'synthetic_git_base':synthetic_base,'cases':results,'database_execution':'NOT_RUN','meaning':'real CLI on copied published source bytes; source freeze only'}
Path('/tmp/mateclaw-java-migration-cli-results.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({'cases':len(results),'cli_checks':len(results)*2,'all_expected_results':True,'source_head':published}))
