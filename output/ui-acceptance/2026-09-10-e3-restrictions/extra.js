async(userPage)=>{
 const OUT='/Users/guojiexie/Development/mateclaw/.worktrees/semantic-m1/output/ui-acceptance/2026-09-10-e3-restrictions';
 const page=await userPage.context().newPage(),results=[];let originalTheme;
 const assert=(v,m)=>{if(!v)throw Error(m)};
 try{
 await page.goto(userPage.url());await page.locator('.model-term').first().waitFor();
 const fixture=await (async(page)=>{
 return page.evaluate(async()=>{
  const {ontologyApi:a}=await import('/src/features/semantic/api/ontologyApi.ts');const {useWorkspaceStore:w}=await import('/src/stores/useWorkspaceStore.ts');const ws=w().currentWorkspaceId;
  const imported='Ontology(<urn:e3b:lib> Declaration(Class(<urn:e3b:Imported>)) AnnotationAssertion(<http://www.w3.org/2000/01/rdf-schema#label> <urn:e3b:Imported> "导入定义"@zh))';
  const digest=[...new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(imported)))].map(x=>x.toString(16).padStart(2,'0')).join('');
  const text=`Prefix(rdfs:=<http://www.w3.org/2000/01/rdf-schema#>)
Prefix(xsd:=<http://www.w3.org/2001/XMLSchema#>)
Ontology(<urn:e3b:expressions> Import(<urn:e3b:lib>)
 Declaration(Class(<urn:e3b:Machine>)) Declaration(Class(<urn:e3b:Probe>)) Declaration(Class(<urn:e3b:Equipment>))
 Declaration(ObjectProperty(<urn:e3b:uses>)) Declaration(ObjectProperty(<urn:e3b:contains>)) Declaration(ObjectProperty(<urn:e3b:related>))
 Declaration(DataProperty(<urn:e3b:reading>))
 AnnotationAssertion(rdfs:label <urn:e3b:Machine> "机器"@zh)
 AnnotationAssertion(rdfs:label <urn:e3b:Machine> "Machine"@en)
 AnnotationAssertion(rdfs:label <urn:e3b:Probe> "测针"@zh)
 AnnotationAssertion(rdfs:label <urn:e3b:uses> "使用"@zh)
 SubClassOf(<urn:e3b:Machine> ObjectIntersectionOf(<urn:e3b:Equipment> ObjectSomeValuesFrom(<urn:e3b:uses> ObjectUnionOf(<urn:e3b:Probe> ObjectComplementOf(<urn:e3b:Equipment>)))))
 SubClassOf(<urn:e3b:Machine> ObjectAllValuesFrom(<urn:e3b:uses> ObjectSomeValuesFrom(<urn:e3b:contains> <urn:e3b:Probe>)))
 SubClassOf(<urn:e3b:Machine> DataSomeValuesFrom(<urn:e3b:reading> xsd:decimal))
 SubClassOf(<urn:e3b:Machine> DataAllValuesFrom(<urn:e3b:reading> xsd:decimal))
 SubClassOf(<urn:e3b:Machine> DataExactCardinality(1 <urn:e3b:reading> xsd:decimal))
 SubObjectPropertyOf(ObjectPropertyChain(<urn:e3b:uses> <urn:e3b:contains> <urn:e3b:uses>) <urn:e3b:related>)
 SubClassOf(Annotation(rdfs:comment "keep annotated") <urn:e3b:Machine> ObjectSomeValuesFrom(<urn:e3b:contains> <urn:e3b:Probe>))
 HasKey(<urn:e3b:Machine> (<urn:e3b:uses>) ())
)`;
  const o=await a.create(ws,{name:'E3b 规则编辑验收 '+Date.now(),description:'Isolated synthetic projection fixture'}),d=await a.createDraft(ws,o.id);
  const saved=await a.saveDraft(ws,o.id,{expectedDraftVersion:d.draftVersion,name:o.name,description:o.description,operationId:crypto.randomUUID(),document:{modelSchema:'owl-document-v1',syntax:'FUNCTIONAL',documentText:text,imports:[{requestedIri:'urn:e3b:lib',resolvedOntologyIri:'urn:e3b:lib',syntax:'FUNCTIONAL',documentText:imported,contentDigest:digest,artifactId:'e3b-pinned-library'}],policy:{version:'1',rules:[]}}});
  const report=await a.validate(ws,o.id,saved.draftVersion);if(!report.valid)throw Error(JSON.stringify(report));
  const revision=await a.publish(ws,o.id,{expectedDraftVersion:saved.draftVersion,operationId:crypto.randomUUID(),note:'Synthetic projection acceptance fixture'});
  const draft=await a.createDraft(ws,o.id,revision.id);
  return {ontologyId:o.id,revisionId:revision.id,draftId:draft.id,draftVersion:draft.draftVersion,documentDigest:draft.document.documentDigest,importLockDigest:draft.document.importLockDigest};
 });
})(page);const ID=fixture.ontologyId;const BASE='http://127.0.0.1:5189/semantic/ontologies/'+ID;
 await page.evaluate(async id=>{const {ontologyApi:a}=await import('/src/features/semantic/api/ontologyApi.ts');const {useWorkspaceStore:w}=await import('/src/stores/useWorkspaceStore.ts');const ws=w().currentWorkspaceId,d=await a.getDraft(ws,id);await a.editDraft(ws,id,{expectedDraftVersion:d.draftVersion,operationId:crypto.randomUUID(),changes:[{kind:'ADD',functionalSyntax:'SubClassOf(<urn:e3b:Machine> ObjectMaxCardinality(7 <urn:e3b:related> <urn:e3b:Probe>))'}]})},ID);
 await page.goto(BASE+'/edit');await page.locator('.model-term').filter({hasText:'机器'}).waitFor();
 originalTheme=await page.locator('.theme-btn.active').getAttribute('title');
 await page.setViewportSize({width:1500,height:1000});await page.getByTitle('深色',{exact:true}).click();await page.waitForFunction(()=>document.documentElement.classList.contains('dark'));
 const open=async()=>{await page.locator('.model-term').filter({hasText:'机器'}).click();await page.getByRole('button',{name:'编辑概念规则',exact:true}).click();await page.getByRole('dialog').waitFor();await page.waitForFunction(()=>!document.querySelector('[class*=dialog-fade-enter]'));};
 await open();await page.getByLabel('关系 IRI',{exact:true}).fill('urn:e3b:manual');await page.getByLabel('关系 IRI',{exact:true}).press('Tab');assert(await page.getByLabel('选择已有关系',{exact:true}).inputValue()==='','unmatched chooser not reset');await page.screenshot({path:OUT+'/dark.png',fullPage:true});
 const colors=await page.getByLabel('关系 IRI',{exact:true}).evaluate(el=>({color:getComputedStyle(el).color,background:getComputedStyle(el).backgroundColor}));assert(colors.color!==colors.background,'invisible input');
 results.push({id:'dark-manual-iri',status:'PASS',actual:colors});
 await page.getByRole('button',{name:'取消',exact:true}).click();await page.getByRole('dialog').waitFor({state:'hidden'});await page.getByTitle(originalTheme,{exact:true}).click();
 await page.setViewportSize({width:390,height:844});await page.waitForFunction(()=>document.querySelector('aside').getBoundingClientRect().right<=0);await open();
 const read=()=>page.evaluate(async id=>{const {ontologyApi:a}=await import('/src/features/semantic/api/ontologyApi.ts');const {useWorkspaceStore:w}=await import('/src/stores/useWorkspaceStore.ts');return a.getDraft(w().currentWorkspaceId,id)},ID);
 const before=await read();const original=before.document.axioms.find(a=>a.rendering==='SubClassOf(<urn:e3b:Machine> ObjectMaxCardinality(7 <urn:e3b:related> <urn:e3b:Probe>))');assert(original,'original missing');
 await page.getByLabel('编辑规则',{exact:true}).selectOption(original.axiomId);await page.getByLabel('限制类型',{exact:true}).selectOption('ObjectMinCardinality');await page.getByLabel('基数',{exact:true}).fill('9');await page.getByLabel('基数',{exact:true}).press('Tab');
 assert(await page.getByLabel('主体概念 IRI',{exact:true}).getAttribute('readonly')!==null,'subject mutable');
 await page.getByRole('button',{name:'预览规则变更',exact:true}).click();await page.getByRole('button',{name:'确认保存规则',exact:true}).scrollIntoViewIfNeeded();await page.screenshot({path:OUT+'/mobile-preview.png',fullPage:true});
 const box=await page.getByRole('button',{name:'确认保存规则',exact:true}).boundingBox();assert(box.x>=0&&box.x+box.width<=390&&box.y>=0&&box.y+box.height<=844,'mobile confirm unreachable');
 await page.getByRole('button',{name:'确认保存规则',exact:true}).click();await page.getByRole('dialog').waitFor({state:'hidden'});const d=await read();assert(d.draftVersion===before.draftVersion+1,'mobile save version');assert(!d.document.axioms.some(a=>a.axiomId===original.axiomId),'old op remains');assert(d.document.axioms.some(a=>a.rendering==='SubClassOf(<urn:e3b:Machine> ObjectMinCardinality(9 <urn:e3b:related> <urn:e3b:Probe>))'),'changed op missing');
 await page.reload();await page.locator('.model-term').filter({hasText:'机器'}).waitFor();const after=await read();assert(after.document.documentDigest===d.document.documentDigest,'reload digest');results.push({id:'mobile-operator-save',status:'PASS',actual:{before:before.draftVersion,after:after.draftVersion,box,digest:after.document.documentDigest}});
 return {fixture,results};
 }finally{
 if(originalTheme){await page.setViewportSize({width:1500,height:1000});if(await page.getByRole('dialog').isVisible())await page.keyboard.press('Escape');await page.getByTitle(originalTheme,{exact:true}).click();}
 await page.close();await userPage.bringToFront();
 }
}
