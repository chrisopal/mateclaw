#!/usr/bin/env python3
"""No Maven, services, original DB opens, credentials or network/model calls."""
from pathlib import Path
import subprocess,xml.etree.ElementTree as ET,tempfile,shutil,hashlib,json
root=Path(__file__).resolve().parents[4]; out=Path(__file__).resolve().parent
java=Path('/Users/guojiexie/Library/Java/JavaVirtualMachines/temurin-21/Contents/Home/bin')
def run(args):
 p=subprocess.run([str(x) for x in args],cwd=root,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
 print(p.stdout,end='');p.check_returncode()
with tempfile.TemporaryDirectory(prefix='isolated-',dir=out) as td:
 d=Path(td)
 report=root/'mateclaw-server/target/surefire-reports/TEST-vip.mate.semantic.SemanticExtractionLeaseIntegrationTest.xml'
 props={x.attrib['name']:x.attrib['value'] for x in ET.parse(report).findall('./properties/property')}
 cp=props['java.class.path']
 if not all(Path(x).exists() for x in cp.split(':')): raise RuntimeError('Existing compiled dependency classpath unavailable; parent must compile first')
 cp=str(d)+':'+cp+':'+str(root/'mateclaw-semantic-owl/target/test-classes')+':'+str(root/'mateclaw-semantic-application/target/test-classes')
 sources=[out/'SelectedTests.java',out/'SafeProbe.java',root/'mateclaw-server/src/test/java/vip/mate/semantic/SemanticExtractionLeaseIntegrationTest.java',root/'mateclaw-semantic-application/src/test/java/vip/mate/semantic/application/extraction/SourceChunkerTest.java',root/'mateclaw-semantic-owl/src/test/java/vip/mate/semantic/owl/HermitReasoningAdapterTest.java']
 run([java/'javac','-cp',cp,'-d',d,*sources])
 for cls,methods in [('vip.mate.semantic.SemanticExtractionLeaseIntegrationTest',['expiredWorkerCannotPublishAndRestartRecovers','cancellationDiscardsLateResult','capacityGateEnforcesWorkspaceAndInstanceLimits','expiredThirdAttemptBecomesFailedInsteadOfQueuedForever']),('vip.mate.semantic.application.extraction.SourceChunkerTest',['unicodeChunksCoverEntireSnapshotWithExactOverlap','rejectsInputBeforeItCouldExceedTwentyChunks','handlesEmptyAndExactBoundaryWithoutTrailingOverlapOnlyChunk']),('vip.mate.semantic.owl.HermitReasoningAdapterTest',['timeoutKillsTheChildAndReleasesTheSingleWorkerSlot','actualHeapExhaustionReturnsResourceStatusAndReleasesSlot','secondConcurrentRequestGetsAnExplicitResourceStatus'])]:
  run([java/'java','-cp',cp,'SelectedTests',cls,*methods])
 backup=root/'data/semantic-owl-runtime/backups/database-t11-verified.mv.db'
 before=hashlib.sha256(backup.read_bytes()).hexdigest();shutil.copyfile(backup,d/'copy.mv.db')
 run([java/'java','-cp',cp,'SafeProbe',d])
 assert hashlib.sha256(backup.read_bytes()).hexdigest()==before
 print('PASS backup unchanged SHA256='+before)
 print('PASS isolated copies and SQL dump removed on exit; original database never opened')
