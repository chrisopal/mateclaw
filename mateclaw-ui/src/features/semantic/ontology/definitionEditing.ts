import type { AxiomDescriptor, AxiomEdit } from '../api/types'
import type { OntologyNodeKind } from './ontologyProjection'
import { editableLabel, replaceLabel } from './labelEditing'
import { validOntologyIri } from './ontologyForm'
export type DefinitionField = 'label' | 'comment' | 'SubClassOf' | 'ObjectPropertyDomain' | 'ObjectPropertyRange' | 'DataPropertyDomain' | 'DataPropertyRange'
export interface EditableDefinition { axiomId: string; kind: DefinitionField; value: string; language: string }
export function definitionFields(kind: OntologyNodeKind): DefinitionField[] {
  return ['label', 'comment', ...(kind === 'class' ? ['SubClassOf'] : kind === 'objectProperty' ? ['ObjectPropertyDomain','ObjectPropertyRange'] : kind === 'dataProperty' ? ['DataPropertyDomain','DataPropertyRange'] : [])] as DefinitionField[]
}
function parseDefinition(axiom: AxiomDescriptor, subject: string): EditableDefinition | null {
  if (axiom.annotations.length) return null
  const label = editableLabel(axiom)
  if (label?.subject === subject) return { axiomId: axiom.axiomId, kind: 'label', value: label.value, language: label.language }
  const comment = /^AnnotationAssertion\((?:rdfs:comment|<http:\/\/www\.w3\.org\/2000\/01\/rdf-schema#comment>) <([^<>\s]+)> "((?:[^"\\]|\\["\\])*)"(?:@([A-Za-z]+(?:-[A-Za-z0-9]+)*))?\)$/.exec(axiom.rendering)
  if (axiom.axiomType === 'AnnotationAssertion' && comment?.[1] === subject) return { axiomId: axiom.axiomId, kind:'comment',value:comment[2]!.replace(/\\(["\\])/g,'$1'),language:comment[3]??'' }
  const named = /^(SubClassOf|ObjectPropertyDomain|ObjectPropertyRange|DataPropertyDomain|DataPropertyRange)\(<([^<>\s]+)> (<[^<>\s]+>|(?:xsd|rdfs|owl|rdf):[\w.-]+)\)$/.exec(axiom.rendering)
  if (!named || named[1] !== axiom.axiomType || named[2] !== subject) return null
  const target=named[3]!
  const prefixes:Record<string,string>={xsd:'http://www.w3.org/2001/XMLSchema#',rdfs:'http://www.w3.org/2000/01/rdf-schema#',owl:'http://www.w3.org/2002/07/owl#',rdf:'http://www.w3.org/1999/02/22-rdf-syntax-ns#'}
  const value=target.startsWith('<')?target.slice(1,-1):prefixes[target.split(':')[0]!]!+target.split(':')[1]
  return { axiomId:axiom.axiomId,kind:named[1] as DefinitionField,value,language:'' }
}
export function editableDefinition(axiom:AxiomDescriptor,subject:string,kind:OntologyNodeKind):EditableDefinition|null {
  const parsed=parseDefinition(axiom,subject)
  return parsed && definitionFields(kind).includes(parsed.kind) ? parsed : null
}
export function definitionEdits(subject:string,kind:DefinitionField,value:string,language:string,original?:AxiomDescriptor):AxiomEdit[] {
  if (!validOntologyIri(subject)) throw new Error('无效的定义 IRI')
  const previous=original?parseDefinition(original,subject):null
  if(original && previous?.kind!==kind) throw new Error('该公理不能由简单表单保真替换，请使用高级视图')
  let functionalSyntax:string
  if(kind==='label'||kind==='comment') {
    const generated=replaceLabel({axiomId:'',subject,value:'',language:''},value,language)[1]!.functionalSyntax!
    functionalSyntax=kind==='label'?generated:generated.replace('rdf-schema#label>','rdf-schema#comment>')
  } else {
    if(!validOntologyIri(value))throw new Error('请选择或输入有效的目标 IRI')
    functionalSyntax=`${kind}(<${subject}> <${value}>)`
  }
  if(previous?.value===value && (!(kind==='label'||kind==='comment') || previous.language.toLowerCase()===language.toLowerCase()))return []
  return [...(original?[{kind:'REMOVE' as const,axiomId:original.axiomId}]:[]),{kind:'ADD',functionalSyntax}]
}
