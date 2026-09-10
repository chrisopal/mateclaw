import type { AxiomDescriptor, AxiomEdit } from '../api/types'

const labelIri = 'http://www.w3.org/2000/01/rdf-schema#label'
export interface EditableLabel { axiomId: string; subject: string; value: string; language: string }

// Deliberately recognize only plain, unannotated literal label assertions.
// Other OWL constructs remain editable through the authoritative document editor.
export function editableLabel(axiom: AxiomDescriptor): EditableLabel | null {
  const match = /^AnnotationAssertion\((?:rdfs:label|<http:\/\/www\.w3\.org\/2000\/01\/rdf-schema#label>) <([^<>\s]+)> "((?:[^"\\]|\\["\\])*)"(?:@([A-Za-z]+(?:-[A-Za-z0-9]+)*))?\)$/.exec(axiom.rendering)
  if (!match || axiom.axiomType !== 'AnnotationAssertion' || axiom.annotations.length) return null
  return { axiomId: axiom.axiomId, subject: match[1]!, value: match[2]!.replace(/\\(["\\])/g, '$1'), language: match[3] ?? '' }
}

export function replaceLabel(label: EditableLabel, value: string, language: string): AxiomEdit[] {
  if (!value.trim() || /[\u0000-\u001f\u007f]/.test(value)) throw new Error('标签不能为空或包含控制字符')
  if (language && !/^[A-Za-z]+(?:-[A-Za-z0-9]+)*$/.test(language)) throw new Error('请输入有效的语言标签，例如 zh-CN')
  if (/[<>\s]/.test(label.subject)) throw new Error('无效的标签对象 IRI')
  const literal = value.replace(/\\/g, '\\\\').replace(/"/g, '\\"')
  return [{ kind: 'REMOVE', axiomId: label.axiomId }, { kind: 'ADD', functionalSyntax: `AnnotationAssertion(<${labelIri}> <${label.subject}> "${literal}"${language ? `@${language}` : ''})` }]
}
