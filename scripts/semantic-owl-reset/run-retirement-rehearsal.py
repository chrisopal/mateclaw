#!/usr/bin/env python3
"""Compile against verified application dependencies, run H2 and disposable MySQL DDL checks."""
import os,json,secrets,subprocess,tempfile,xml.etree.ElementTree as ET
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
report=ROOT/'mateclaw-server/target/surefire-reports/TEST-vip.mate.semantic.SemanticOntologyIntegrationTest.xml'
cp=next(p.attrib['value'] for p in ET.parse(report).getroot().findall('properties/property') if p.attrib['name']=='java.class.path')
out=Path(tempfile.mkdtemp(prefix='mateclaw-retirement-rehearsal-'))
java=Path(os.environ.get('JAVA_HOME','/Users/guojiexie/Library/Java/JavaVirtualMachines/temurin-21/Contents/Home'))/'bin'
sources=[ROOT/'scripts/semantic-owl-reset'/p for p in ['src/LegacyColumnRetirementGuard.java','src/LegacyColumnRetirement.java','test/LegacyColumnRetirementGuardTest.java']]
subprocess.run([str(java/'javac'),'-proc:none','-cp',cp,'-d',str(out),*map(str,sources)],check=True)
results=[]
def run(label,extra):
    env=os.environ.copy();env.pop('SEMANTIC_RESET_WRITER_FENCE',None);env.pop('RETIREMENT_TEST_URL',None);env.update(extra)
    log=out/(label+'.log')
    with log.open('w') as f:r=subprocess.run([str(java/'java'),'-cp',str(out)+':'+cp,'LegacyColumnRetirementGuardTest'],env=env,stdout=f,stderr=subprocess.STDOUT)
    results.append({'case':label,'exitCode':r.returncode,'log':str(log)})
    if r.returncode:raise RuntimeError('Retirement rehearsal failed: '+label+'; log='+str(log))
run('h2-fence-missing',{})
run('h2-ddl-recovery',{'SEMANTIC_RESET_WRITER_FENCE':'CONFIRMED_OFFLINE_STOPPED'})
run('h2-text-ddl-recovery',{'SEMANTIC_RESET_WRITER_FENCE':'CONFIRMED_OFFLINE_STOPPED','RETIREMENT_H2_TEXT_TYPE':'TEXT'})
info=json.loads(subprocess.check_output(['docker','inspect','mateclaw-mysql']))[0]
password=next(v.split('=',1)[1] for v in info['Config']['Env'] if v.startswith('MYSQL_ROOT_PASSWORD='))
port=info['NetworkSettings']['Ports']['3306/tcp'][0]['HostPort']
env=os.environ.copy();env['MYSQL_PWD']=password
for label,fence in [('mysql-fence-missing',False),('mysql-ddl-recovery',True)]:
    schema='semantic_reset_retire_'+secrets.token_hex(6)
    def sql(text):subprocess.run(['docker','exec','-i','-e','MYSQL_PWD','mateclaw-mysql','mysql','-h127.0.0.1','-uroot'],input=text.encode(),env=env,check=True,stdout=subprocess.DEVNULL)
    sql('CREATE DATABASE `'+schema+'`;')
    try:
        settings={'RETIREMENT_TEST_URL':'jdbc:mysql://127.0.0.1:'+port+'/'+schema+'?allowPublicKeyRetrieval=true&useSSL=false','SEMANTIC_RESET_DB_USER':'root','SEMANTIC_RESET_DB_PASSWORD':password}
        if fence:settings['SEMANTIC_RESET_WRITER_FENCE']='CONFIRMED_OFFLINE_STOPPED'
        run(label,settings)
    finally:sql('DROP DATABASE `'+schema+'`;')
(ROOT/'docs/validation/semantic-owl-execution/retirement-rehearsals.json').write_text(json.dumps({'results':results,'scope':'Synthetic tables in disposable databases; application databases untouched'},indent=2)+'\n')
print(json.dumps({'passed':len(results),'logs':str(out)}))
