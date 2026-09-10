#!/usr/bin/env python3
"""Run conditional MySQL acceptance against two newly created disposable schemas only."""
import argparse,json,os,re,secrets,subprocess
from pathlib import Path
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--tests',default='SemanticMySql*',help='MySQL acceptance class, optionally #method')
args=parser.parse_args()
if not re.fullmatch(r'SemanticMySql[A-Za-z0-9*]*(?:#[A-Za-z0-9_]+)?',args.tests):
    parser.error('--tests must select a SemanticMySql test class, optionally one method')
ROOT=Path(__file__).resolve().parents[2]
container='mateclaw-mysql'
info=json.loads(subprocess.check_output(['docker','inspect',container]))[0]
password=next((v.split('=',1)[1] for v in info['Config']['Env'] if v.startswith('MYSQL_ROOT_PASSWORD=')),None)
ports=info['NetworkSettings']['Ports'].get('3306/tcp') or []
if not password or not ports: raise SystemExit('MySQL test container credentials or published port unavailable')
port=ports[0]['HostPort']
run='semantic_owl_test_'+secrets.token_hex(6)
schemas=[run,run+'_upgrade'];created=[]
env=os.environ.copy();env['MYSQL_PWD']=password

def sql(text):
    subprocess.run(['docker','exec','-i','-e','MYSQL_PWD',container,'mysql','-h127.0.0.1','-uroot'],input=text.encode(),env=env,check=True,stdout=subprocess.DEVNULL)
try:
    for schema in schemas:
        sql('CREATE DATABASE `'+schema+'`;');created.append(schema)
    env.update(SEMANTIC_MYSQL_TEST_URL='jdbc:mysql://127.0.0.1:'+port+'/'+schemas[0]+'?allowPublicKeyRetrieval=true&useSSL=false',SEMANTIC_MYSQL_UPGRADE_URL='jdbc:mysql://127.0.0.1:'+port+'/'+schemas[1]+'?allowPublicKeyRetrieval=true&useSSL=false',SEMANTIC_MYSQL_TEST_USER='root',SEMANTIC_MYSQL_TEST_PASSWORD=password,JAVA_HOME='/Users/guojiexie/Library/Java/JavaVirtualMachines/temurin-21/Contents/Home')
    log=Path('/tmp/mateclaw-owl-mysql-acceptance.log')
    with log.open('w') as output:
        result=subprocess.run(['mvn','-q','-pl','mateclaw-server','-am','-Dtest='+args.tests,'-Dsurefire.failIfNoSpecifiedTests=false','test'],cwd=ROOT,env=env,stdout=output,stderr=subprocess.STDOUT)
    print(json.dumps({'exitCode':result.returncode,'tests':args.tests,'log':str(log),'isolatedSchemas':len(created)}))
finally:
    for schema in created: sql('DROP DATABASE `'+schema+'`;')
if result.returncode: raise SystemExit(result.returncode)
