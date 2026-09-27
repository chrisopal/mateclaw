package vip.mate.bidding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.ArrayDeque;
import java.util.Map;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class BiddingDependencies {
    private final BiddingAccess access;
    private final BiddingRepository repository;
    private final BiddingMaterials materials;
    private final ObjectMapper json;
    public BiddingDependencies(BiddingAccess access, BiddingRepository repository, BiddingMaterials materials,ObjectMapper json) { this.access=access; this.repository=repository; this.materials=materials; this.json=json; }

    public void validate(BiddingTypes.Scope scope, List<BiddingTypes.Ref> refs) {
        access.requireActor(scope, scope.actorId());
        validateRefs(scope,refs);
    }

    public void validateForRead(BiddingTypes.Scope scope, List<BiddingTypes.Ref> refs) {
        access.requireReaderActor(scope, scope.actorId());
        validateRefs(scope,refs);
    }

    /** Allows readable historical revisions to be compared after their selected heads move. */
    public void validateForComparisonRead(BiddingTypes.Scope scope,List<BiddingTypes.Ref> refs) {
        access.requireReaderActor(scope,scope.actorId());
        validateHistoricalRefs(scope,refs,new LinkedHashSet<>());
    }

    private void validateHistoricalRefs(BiddingTypes.Scope scope,List<BiddingTypes.Ref> refs,Set<String> visiting) {
        if(refs==null||refs.isEmpty()) throw BiddingAccess.error(422,"SOURCE_SET_INCOMPLETE","Fixed references are required");
        for(var ref:refs) {
            if(ref==null||ref.id()==null||ref.version()<1) throw BiddingAccess.error(422,"SOURCE_REF_INVALID","A source reference is invalid");
            String key=ref.kind()+":"+ref.id()+":"+ref.version()+":"+ref.digest();
            if(!visiting.add(key)) throw BiddingAccess.error(422,"DEPENDENCY_CYCLE","Revision dependencies contain a cycle");
            if("source".equals(ref.kind())) {
                var source=repository.source(scope.workspaceId(),scope.projectId(),ref.id(),ref.version());
                if(source==null||!source.digest().equals(ref.digest())) throw BiddingAccess.error(404,"NOT_FOUND","Source not found");
                if(!Set.of("READY","NEEDS_REVIEW").contains(source.status())) throw BiddingAccess.error(422,"SOURCE_NOT_READY","Source reading is incomplete");
            } else if("material".equals(ref.kind())) {
                var project=repository.findProject(scope.workspaceId(),scope.projectId());
                String agent=project==null?"":project.path("bindings").path("writer").path("agentId").asText("");
                materials.requireReadable(scope,agent,ref);
            } else if("sourceSet".equals(ref.kind())) {
                if(!repository.historicalRevisionExists(scope,ref)) throw BiddingAccess.error(404,"NOT_FOUND","Source set not found");
            } else if("chapter".equals(ref.kind())) {
                var row=repository.businessRevision(scope,ref);
                if(row==null || !Set.of("SELECTED","HUMAN_EDIT","NEEDS_RECONFIRMATION").contains(row.path("status").asText()))
                    throw BiddingAccess.error(404,"NOT_FOUND","Chapter revision not found");
                validateHistoricalRefs(scope,repository.businessRefs(row),visiting);
            } else if("manuscript".equals(ref.kind())) {
                var row=repository.businessRevision(scope,ref);
                if(row==null || !Set.of("CONFIRMED","NEEDS_RECONFIRMATION","CANDIDATE").contains(row.path("status").asText())) throw BiddingAccess.error(404,"NOT_FOUND","Manuscript not found");
                validateHistoricalRefs(scope,repository.businessRefs(row),visiting);
            } else if(Set.of("analysisBaseline","outline").contains(ref.kind())) {
                var row=repository.businessRevision(scope,ref);
                if(row==null) throw BiddingAccess.error(404,"NOT_FOUND","Business revision not found");
                Set<String> permitted=Set.of("CONFIRMED","NEEDS_RECONFIRMATION","CANDIDATE");
                if(!permitted.contains(row.path("status").asText())) throw BiddingAccess.error(422,"DEPENDENCY_NOT_CONFIRMED","Historical revision is not readable");
                validateHistoricalRefs(scope,repository.businessRefs(row),visiting);
            } else throw BiddingAccess.error(422,"SOURCE_REF_INVALID","Unsupported fixed reference kind");
            visiting.remove(key);
        }
    }

    private void validateRefs(BiddingTypes.Scope scope,List<BiddingTypes.Ref> refs) {
        validateRefs(scope,refs,new LinkedHashSet<>());
    }
    private void validateRefs(BiddingTypes.Scope scope,List<BiddingTypes.Ref> refs,Set<String> visiting) {
        if (refs == null || refs.isEmpty()) throw BiddingAccess.error(422,"SOURCE_SET_INCOMPLETE","Fixed references are required");
        for (var ref : refs) {
            if (ref == null || ref.id() == null || ref.version() < 1) throw BiddingAccess.error(422,"SOURCE_REF_INVALID","A source reference is invalid");
            String key=ref.kind()+":"+ref.id()+":"+ref.version()+":"+ref.digest();
            if(!visiting.add(key)) throw BiddingAccess.error(422,"DEPENDENCY_CYCLE","Revision dependencies contain a cycle");
            if ("source".equals(ref.kind())) {
                var source=repository.source(scope.workspaceId(),scope.projectId(),ref.id(),ref.version());
                if (source == null || !source.digest().equals(ref.digest())) throw BiddingAccess.error(404,"NOT_FOUND","Source not found");
                if (!Set.of("READY","NEEDS_REVIEW").contains(source.status())) throw BiddingAccess.error(422,"SOURCE_NOT_READY","Source reading is incomplete");
                if (!repository.sourceSetContains(scope,ref)) throw BiddingAccess.error(422,"SOURCE_NOT_CONFIRMED","Source is not part of the current confirmed source set");
            } else if ("sourceSet".equals(ref.kind())) {
                if (!repository.revisionExists(scope,ref)) throw BiddingAccess.error(404,"NOT_FOUND","Source set not found");
                if (!repository.isSelectedSourceSet(scope,ref)) throw BiddingAccess.error(409,"DEPENDENCY_STALE","Source set is no longer current");
            } else if ("chapter".equals(ref.kind())) {
                ObjectNode row=repository.businessRevision(scope,ref);
                if(row==null || !Set.of("SELECTED","HUMAN_EDIT").contains(row.path("status").asText()) || !repository.isSelectedBusinessRevision(scope,ref))
                    throw BiddingAccess.error(409,"DEPENDENCY_STALE","Selected chapter revision is no longer current");
                validateRefs(scope,repository.businessRefs(row),visiting);
            } else if ("manuscript".equals(ref.kind())) {
                ObjectNode row=repository.businessRevision(scope,ref);
                if(row==null) throw BiddingAccess.error(404,"NOT_FOUND","Manuscript not found");
                validateRefs(scope,repository.businessRefs(row),visiting);
            } else if ("material".equals(ref.kind())) {
                var project=repository.findProject(scope.workspaceId(),scope.projectId());
                String agentId=project==null?"":project.path("bindings").path("writer").path("agentId").asText("");
                materials.requireReadable(scope,agentId,ref);
            } else if (Set.of("analysisBaseline","outline").contains(ref.kind())) {
                var row=repository.businessRevision(scope,ref);
                if(row==null) throw BiddingAccess.error(404,"NOT_FOUND","Business revision not found");
                if(!"CONFIRMED".equals(row.path("status").asText())) throw BiddingAccess.error(422,"DEPENDENCY_NOT_CONFIRMED","Business revision is not confirmed");
                if(!repository.isSelectedBusinessRevision(scope,ref)) throw BiddingAccess.error(409,"DEPENDENCY_STALE","Business revision is no longer selected");
                validateRefs(scope,repository.businessRefs(row),visiting);
            } else throw BiddingAccess.error(422,"SOURCE_REF_INVALID","Unsupported fixed reference kind");
            visiting.remove(key);
        }
    }
    public boolean isCurrent(BiddingTypes.Scope scope, List<BiddingTypes.Ref> refs) { try { validate(scope,refs); return true; } catch(BiddingApiException e) { if (e.status()==404 || e.status()==409 || e.status()==422) return false; throw e; } }
    public boolean isCurrentForRead(BiddingTypes.Scope scope,List<BiddingTypes.Ref> refs) { try { validateForRead(scope,refs);return true; } catch(BiddingApiException e) { if(e.status()==404||e.status()==409||e.status()==422)return false;throw e; } }
    public void invalidate(BiddingTypes.Scope scope, BiddingTypes.Ref changed) { repository.invalidateDependencies(scope,changed); }

    public ObjectNode impact(BiddingTypes.Scope scope,BiddingTypes.Ref changed) {
        access.requireReaderActor(scope,scope.actorId());
        if(repository.findProject(scope.workspaceId(),scope.projectId())==null) throw BiddingAccess.error(404,"NOT_FOUND","Project not found");
        validateForComparisonRead(scope,List.of(changed));
        var queue=new ArrayDeque<BiddingTypes.Ref>(); var visited=new LinkedHashSet<BiddingTypes.Ref>(); queue.add(changed);
        while(!queue.isEmpty()) {
            var current=queue.removeFirst(); if(!visited.add(current)) continue;
            queue.addAll(repository.dependents(scope,current));
            if(Set.of("sourceSet","analysisBaseline","outline","chapter","manuscript").contains(current.kind())) {
                ObjectNode dependent=repository.businessRevision(scope,current);
                if(dependent==null) continue;
                List<BiddingTypes.Ref> refs=repository.businessRefs(dependent);
                if(!refs.isEmpty()) validateForComparisonRead(scope,refs);
            }
        }
        ObjectNode result=json.createObjectNode();
        var affected=result.putArray("affectedRefs"); var unaffected=result.putArray("unaffectedRefs");
        var unknown=result.putArray("unknownRefs"); var labels=result.putArray("refLabels");
        Classification classification=classifyBaselineTransition(scope,changed,visited);
        if(!"analysisBaseline".equals(changed.kind())) {
            BiddingTypes.Ref supporting=supportingBaselineTransition(scope,changed);
            if(supporting!=null) classification=classifyBaselineTransition(scope,supporting,graphFrom(scope,supporting));
        }
        classification.affected().forEach(ref->affected.add(json.valueToTree(ref)));
        classification.unaffected().forEach(ref->unaffected.add(json.valueToTree(ref)));
        classification.unknown().forEach(ref->unknown.add(json.valueToTree(ref)));
        visited.stream().filter(ref->!ref.equals(changed)).forEach(ref->{ObjectNode label=labels.addObject();label.set("ref",json.valueToTree(ref));label.put("title",repository.refTitle(scope,ref));});
        ObjectNode changedLabel=labels.addObject();changedLabel.set("ref",json.valueToTree(changed));changedLabel.put("title",repository.refTitle(scope,changed));
        result.put("formalBlocked",!classification.unknown().isEmpty()||!classification.affected().isEmpty());
        return result;
    }

    private record Classification(List<BiddingTypes.Ref> affected,List<BiddingTypes.Ref> unaffected,List<BiddingTypes.Ref> unknown) {}

    private Set<BiddingTypes.Ref> graphFrom(BiddingTypes.Scope scope,BiddingTypes.Ref start) {
        var queue=new ArrayDeque<BiddingTypes.Ref>();var visited=new LinkedHashSet<BiddingTypes.Ref>();queue.add(start);
        while(!queue.isEmpty()){BiddingTypes.Ref current=queue.removeFirst();if(!visited.add(current))continue;queue.addAll(repository.dependents(scope,current));}
        return visited;
    }

    /** Finds a confirmed baseline transition whose exact fixed input closure contains this old/new pair. */
    private BiddingTypes.Ref supportingBaselineTransition(BiddingTypes.Scope scope,BiddingTypes.Ref changed) {
        for(ObjectNode event:repository.changeEvents(scope)) {
            if(!"CONFIRMED".equals(event.path("status").asText()))continue;
            BiddingTypes.Ref old=parseRef(event.path("changedRef")),next=parseRef(event.path("replacementRef"));
            if(old==null||next==null||!"analysisBaseline".equals(old.kind())||!baselineTransitionReaches(scope,old,next,repository.selectedRef(scope,"analysisBaseline","current")))continue;
            BiddingTypes.Ref pendingReplacement=transitionReplacement(scope,changed);
            if(pendingReplacement!=null&&baselineContains(scope,old,changed)&&baselineContains(scope,next,pendingReplacement))return old;
        }
        return null;
    }
    private boolean baselineTransitionReaches(BiddingTypes.Scope scope,BiddingTypes.Ref old,BiddingTypes.Ref replacement,BiddingTypes.Ref selected) {
        if(old==null||replacement==null||selected==null||!"analysisBaseline".equals(old.kind()))return false;
        BiddingTypes.Ref current=old;
        Set<BiddingTypes.Ref> seen=new LinkedHashSet<>();
        while(seen.add(current)) {
            BiddingTypes.Ref next=null;
            for(ObjectNode event:repository.changeEvents(scope)) {
                if(current.equals(parseRef(event.path("changedRef")))&&"analysisBaseline".equals(current.kind())) { next=parseRef(event.path("replacementRef"));break; }
            }
            if(next==null)return false;
            if(current.equals(old)&&!next.equals(replacement))return false;
            if(next.equals(selected))return true;
            current=next;
        }
        return false;
    }
    private BiddingTypes.Ref transitionReplacement(BiddingTypes.Scope scope,BiddingTypes.Ref changed) {
        for(ObjectNode event:repository.changeEvents(scope)) if(changed.equals(parseRef(event.path("changedRef"))))return parseRef(event.path("replacementRef"));
        return null;
    }
    private boolean baselineContains(BiddingTypes.Scope scope,BiddingTypes.Ref baselineRef,BiddingTypes.Ref ref) {
        if(ref==null)return false;
        ObjectNode baseline=repository.businessRevision(scope,baselineRef);if(baseline==null)return false;
        if("sourceSet".equals(ref.kind()))return repository.businessRefs(baseline).contains(ref);
        if("source".equals(ref.kind()))return sourceReferences(scope,baselineRef).contains(ref);
        return false;
    }

    /** Only a direct, confirmed baseline replacement can establish a semantic chapter proof. */
    private Classification classifyBaselineTransition(BiddingTypes.Scope scope,BiddingTypes.Ref oldBaseline,Set<BiddingTypes.Ref> graph) {
        List<BiddingTypes.Ref> descendants=graph.stream().filter(ref->!ref.equals(oldBaseline)).toList();
        if(!"analysisBaseline".equals(oldBaseline.kind())) return new Classification(List.of(),List.of(),descendants);
        BiddingTypes.Ref newBaseline=repository.selectedRef(scope,"analysisBaseline","current"),newOutline=repository.selectedRef(scope,"outline","current");
        if(newBaseline==null||newOutline==null) return new Classification(List.of(),List.of(),descendants);
        ObjectNode newOutlineRow=repository.businessRevision(scope,newOutline);
        BiddingTypes.Ref outlineBaseline=newOutlineRow==null?null:repository.businessRefs(newOutlineRow).stream().filter(r->"analysisBaseline".equals(r.kind())).findFirst().orElse(null);
        if(!newBaseline.equals(outlineBaseline)) return new Classification(List.of(),List.of(),descendants);
        ObjectNode oldRow=repository.businessRevision(scope,oldBaseline),newRow=repository.businessRevision(scope,newBaseline);
        BiddingTypes.Ref oldOutline=descendants.stream().filter(r->"outline".equals(r.kind())).findFirst().orElse(null);
        ObjectNode oldOutlineRow=oldOutline==null?null:repository.businessRevision(scope,oldOutline);
        if(oldRow==null||newRow==null||oldOutlineRow==null||newOutlineRow==null
                ||!"CONFIRMED".equals(newRow.path("status").asText())||!"CONFIRMED".equals(newOutlineRow.path("status").asText()))
            return new Classification(List.of(),List.of(),descendants);
        validateForComparisonRead(scope,repository.businessRefs(newOutlineRow));
        Map<String,JsonNode> oldChapters=chapters(oldOutlineRow),newChapters=chapters(newOutlineRow);
        Map<String,JsonNode> oldReq=byId(oldRow.path("analyses").path("bidding-requirement-analysis").path("requirements"));
        Map<String,JsonNode> newReq=byId(newRow.path("analyses").path("bidding-requirement-analysis").path("requirements"));
        Map<String,JsonNode> oldCriteria=byId(oldRow.path("analyses").path("bidding-scoring-analysis").path("criteria"));
        Map<String,JsonNode> newCriteria=byId(newRow.path("analyses").path("bidding-scoring-analysis").path("criteria"));
        Map<String,JsonNode> oldMandatory=mandatory(oldRow),newMandatory=mandatory(newRow);
        List<BiddingTypes.Ref> newSources=sourceReferences(scope,newBaseline);
        List<BiddingTypes.Ref> affected=new java.util.ArrayList<>(),unaffected=new java.util.ArrayList<>(),unknown=new java.util.ArrayList<>();
        for(BiddingTypes.Ref chapterRef:repository.selectedChapterRefs(scope)) {
            if(!graph.contains(chapterRef)) continue;
            ObjectNode chapterRow=repository.businessRevision(scope,chapterRef);
            if(chapterRow==null||!repository.businessRefs(chapterRow).contains(oldOutline)) { unknown.add(chapterRef);continue; }
            JsonNode body=chapterRow; String chapterId=body.path("chapter").path("chapterId").asText(chapterRef.id());
            JsonNode oldMap=oldChapters.get(chapterId),newMap=newChapters.get(chapterId);
            if(oldMap==null||newMap==null||!sameArray(oldMap.path("requirementRefs"),newMap.path("requirementRefs"))
                    ||!sameArray(oldMap.path("scoringRefs"),newMap.path("scoringRefs"))
                    ||!sameArray(oldMap.path("materialRefs"),newMap.path("materialRefs"))) { unknown.add(chapterRef);continue; }
            boolean changed=!java.util.Objects.equals(oldMap.path("title").asText(),newMap.path("title").asText())
                    ||!java.util.Objects.equals(oldMap.path("parentId").asText(),newMap.path("parentId").asText())
                    ||oldMap.path("order").asInt(-1)!=newMap.path("order").asInt(-1),complete=true;
            for(String id:strings(newMap.path("requirementRefs"))) { JsonNode a=oldReq.get(id),b=newReq.get(id);if(a==null||b==null){complete=false;break;}if(!semanticEqual(a,b)||!evidenceEquivalent(a.path("evidenceRefs"),b.path("evidenceRefs")))changed=true; }
            for(String id:strings(newMap.path("scoringRefs"))) { JsonNode a=oldCriteria.get(id),b=newCriteria.get(id);if(a==null||b==null){complete=false;break;}if(!semanticEqual(a,b)||!evidenceEquivalent(a.path("evidenceRefs"),b.path("evidenceRefs")))changed=true; }
            List<String> oldMandatoryRefs=strings(oldMap.path("mandatoryOutlineRefs")),newMandatoryRefs=strings(newMap.path("mandatoryOutlineRefs"));
            if(oldMandatoryRefs.size()!=newMandatoryRefs.size())complete=false;
            for(int i=0;complete&&i<newMandatoryRefs.size();i++) {
                String oldId=oldMandatoryRefs.get(i),newId=newMandatoryRefs.get(i);
                if(!mandatoryIndex(oldId).equals(mandatoryIndex(newId))){complete=false;break;}
                JsonNode a=oldMandatory.get(oldId),b=newMandatory.get(newId);if(a==null||b==null){complete=false;break;}
                if(!semanticEqual(a,b)||!evidenceEquivalent(a.path("evidenceRefs"),b.path("evidenceRefs")))changed=true;
            }
            if(!complete||!citationsProven(scope,body.path("citations"),sourceReferences(scope,oldBaseline),newSources,newReq,newCriteria)
                    ||!evidenceProven(scope,evidenceRefs(newMap,newReq,newCriteria,newMandatory),newSources)) unknown.add(chapterRef);
            else if(changed) affected.add(chapterRef);else unaffected.add(chapterRef);
        }
        return new Classification(List.copyOf(affected),List.copyOf(unaffected),List.copyOf(unknown));
    }
    private Map<String,JsonNode> chapters(JsonNode payload) { Map<String,JsonNode> out=new java.util.LinkedHashMap<>();for(JsonNode c:payload.path("chapters"))if(c.path("id").isTextual())out.put(c.path("id").asText(),c);return out; }
    private Map<String,JsonNode> byId(JsonNode items) { Map<String,JsonNode> out=new java.util.LinkedHashMap<>();for(JsonNode item:items)if(item.path("id").isTextual())out.put(item.path("id").asText(),item);return out; }
    private Map<String,JsonNode> mandatory(JsonNode baseline) {
        Map<String,JsonNode> out=new java.util.LinkedHashMap<>();JsonNode items=baseline.path("analyses").path("bidding-tender-profile").path("mandatoryOutline");
        for(int i=0;i<items.size();i++){JsonNode item=items.get(i);out.put("mandatory-outline-"+i+"-"+sha(item),item);}return out;
    }
    private String sha(JsonNode node) { try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(node)));}catch(Exception e){throw new IllegalStateException(e);} }
    private boolean sameArray(JsonNode a,JsonNode b) { return a.isArray()&&b.isArray()&&a.equals(b); }
    private List<String> strings(JsonNode nodes) { List<String> out=new java.util.ArrayList<>();for(JsonNode n:nodes)if(n.isTextual())out.add(n.asText());else return List.of();return out; }
    private boolean semanticEqual(JsonNode a,JsonNode b) { JsonNode left=a.deepCopy(),right=b.deepCopy();if(left.isObject())((ObjectNode)left).remove("evidenceRefs");if(right.isObject())((ObjectNode)right).remove("evidenceRefs");return left.equals(right); }
    private boolean evidenceEquivalent(JsonNode oldRefs,JsonNode newRefs) {
        if(!oldRefs.isArray()||!newRefs.isArray()||oldRefs.size()!=newRefs.size())return false;
        List<String> oldQuotes=new java.util.ArrayList<>(),newQuotes=new java.util.ArrayList<>();
        for(JsonNode ref:oldRefs)oldQuotes.add(ref.path("quote").asText(""));
        for(JsonNode ref:newRefs)newQuotes.add(ref.path("quote").asText(""));
        java.util.Collections.sort(oldQuotes);java.util.Collections.sort(newQuotes);return oldQuotes.equals(newQuotes)&&!oldQuotes.contains("");
    }
    private String mandatoryIndex(String id) {
        if(id==null||!id.startsWith("mandatory-outline-"))return "";
        int end=id.indexOf('-',"mandatory-outline-".length());return end<0?"":id.substring("mandatory-outline-".length(),end);
    }
    private List<BiddingTypes.Ref> sourceReferences(BiddingTypes.Scope scope,BiddingTypes.Ref baseline) {
        ObjectNode row=repository.businessRevision(scope,baseline);if(row==null)return List.of();
        BiddingTypes.Ref sourceSet=repository.businessRefs(row).stream().filter(ref->"sourceSet".equals(ref.kind())).findFirst().orElse(null);
        ObjectNode set=sourceSet==null?null:repository.businessRevision(scope,sourceSet);if(set==null)return List.of();
        try{return json.convertValue(set.path("sourceRefs"),new com.fasterxml.jackson.core.type.TypeReference<List<BiddingTypes.Ref>>(){});}catch(IllegalArgumentException invalid){return List.of();}
    }
    private JsonNode evidenceRefs(JsonNode chapter,Map<String,JsonNode> requirements,Map<String,JsonNode> criteria,Map<String,JsonNode> mandatory) {
        ArrayNode all=json.createArrayNode();
        for(String id:strings(chapter.path("requirementRefs"))) { JsonNode n=requirements.get(id);if(n!=null&&n.path("evidenceRefs").isArray())n.path("evidenceRefs").forEach(v->all.add(v.deepCopy())); }
        for(String id:strings(chapter.path("scoringRefs"))) { JsonNode n=criteria.get(id);if(n!=null&&n.path("evidenceRefs").isArray())n.path("evidenceRefs").forEach(v->all.add(v.deepCopy())); }
        for(String id:strings(chapter.path("mandatoryOutlineRefs"))) { JsonNode n=mandatory.get(id);if(n!=null&&n.path("evidenceRefs").isArray())n.path("evidenceRefs").forEach(v->all.add(v.deepCopy())); }
        return all;
    }
    private boolean evidenceProven(BiddingTypes.Scope scope,JsonNode refs,List<BiddingTypes.Ref> sources) {
        if(refs==null||refs.isMissingNode()||refs.isNull())return true;if(!refs.isArray())return false;
        for(JsonNode evidence:refs) {
            String sourceId=evidence.path("sourceId").asText(""),block=evidence.path("blockId").asText(""),quote=evidence.path("quote").asText("");long version=evidence.path("version").asLong(-1);
            BiddingTypes.Ref source=sources.stream().filter(ref->ref.id().equals(sourceId)&&ref.version()==version).findFirst().orElse(null);
            if(source==null||block.isBlank()||quote.isBlank()||!repository.hasEvidenceBlock(scope,source,block,quote))return false;
        }
        return true;
    }
    private boolean citationsProven(BiddingTypes.Scope scope,JsonNode citations,List<BiddingTypes.Ref> oldSources,List<BiddingTypes.Ref> newSources,
            Map<String,JsonNode> requirements,Map<String,JsonNode> criteria) {
        if(citations==null||citations.isMissingNode()||citations.isNull())return true;if(!citations.isArray())return false;
        for(JsonNode citation:citations)if(!citationProven(scope,citation,oldSources)||matchingNewEvidence(scope,citation,newSources,requirements,criteria)==null)return false;
        return true;
    }
    private boolean citationProven(BiddingTypes.Scope scope,JsonNode citation,List<BiddingTypes.Ref> sources) {
        String id=citation.path("sourceId").asText(""),block=citation.path("blockId").asText(""),quote=citation.path("quote").asText("");long version=citation.path("version").asLong(-1);
        BiddingTypes.Ref source=sources.stream().filter(ref->ref.id().equals(id)&&ref.version()==version).findFirst().orElse(null);
        return source!=null&&!block.isBlank()&&!quote.isBlank()&&repository.hasEvidenceBlock(scope,source,block,quote);
    }
    private JsonNode matchingNewEvidence(BiddingTypes.Scope scope,JsonNode citation,List<BiddingTypes.Ref> sources,
            Map<String,JsonNode> requirements,Map<String,JsonNode> criteria) {
        JsonNode item=citation.path("requirementRef").isTextual()?requirements.get(citation.path("requirementRef").asText()):null;
        if(item==null&&citation.path("criterionRef").isTextual())item=criteria.get(citation.path("criterionRef").asText());
        if(item==null)return null;String quote=citation.path("quote").asText("");
        for(JsonNode evidence:item.path("evidenceRefs"))if(quote.equals(evidence.path("quote").asText())&&evidenceProven(scope,json.createArrayNode().add(evidence.deepCopy()),sources))return evidence;
        return null;
    }
    private JsonNode rebaseCitations(BiddingTypes.Scope scope,BiddingTypes.Ref chapterRef,BiddingTypes.Ref newBaseline) {
        ObjectNode chapter=repository.businessRevision(scope,chapterRef);
        BiddingTypes.Ref oldOutline=repository.businessRefs(chapter).stream().filter(ref->"outline".equals(ref.kind())).findFirst().orElse(null);
        ObjectNode oldOutlineRow=oldOutline==null?null:repository.businessRevision(scope,oldOutline);
        BiddingTypes.Ref oldBaseline=oldOutlineRow==null?null:repository.businessRefs(oldOutlineRow).stream().filter(ref->"analysisBaseline".equals(ref.kind())).findFirst().orElse(null);
        ObjectNode baseline=repository.businessRevision(scope,newBaseline);
        Map<String,JsonNode> requirements=byId(baseline.path("analyses").path("bidding-requirement-analysis").path("requirements"));
        Map<String,JsonNode> criteria=byId(baseline.path("analyses").path("bidding-scoring-analysis").path("criteria"));
        List<BiddingTypes.Ref> sources=sourceReferences(scope,newBaseline);ArrayNode output=json.createArrayNode();
        for(JsonNode citation:chapter.path("citations")) {
            if(!citationProven(scope,citation,sourceReferences(scope,oldBaseline)))throw BiddingAccess.error(422,"CHANGE_IMPACT_UNPROVEN","Historical chapter citation cannot be verified");
            JsonNode evidence=matchingNewEvidence(scope,citation,sources,requirements,criteria);
            if(evidence==null)throw BiddingAccess.error(422,"CHANGE_IMPACT_UNPROVEN","Replacement evidence does not support the chapter citation");
            ObjectNode copy=citation.deepCopy();for(String key:List.of("sourceId","version","blockId","quote"))if(evidence.has(key))copy.set(key,evidence.path(key).deepCopy());output.add(copy);
        }
        return output;
    }

    public ObjectNode readChangeImpact(BiddingTypes.Scope scope) {
        access.requireReaderActor(scope,scope.actorId());
        if(repository.findProject(scope.workspaceId(),scope.projectId())==null) throw BiddingAccess.error(404,"NOT_FOUND","Project not found");
        var out=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(); var events=out.putArray("events"); boolean blocked=false;
        for(ObjectNode row:repository.changeEvents(scope)) {
            BiddingTypes.Ref changed=parseRef(row.path("changedRef"));
            ObjectNode event=row.deepCopy();
            if(changed==null) { event.put("status","PENDING"); event.set("impact",unknownImpact()); blocked=true; }
            else {
                try { event.set("impact",impact(scope,changed)); }
                catch(BiddingApiException denied) { if(denied.status()==403||denied.status()==404) throw denied; event.set("impact",unknownImpact()); }
                BiddingTypes.Ref replacement=parseRef(event.path("replacementRef"));
                boolean directBaseline="analysisBaseline".equals(changed.kind())&&baselineTransitionReaches(scope,changed,replacement,repository.selectedRef(scope,"analysisBaseline","current"));
                boolean chainedInput=("source".equals(changed.kind())||"sourceSet".equals(changed.kind()))&&supportingBaselineTransition(scope,changed)!=null;
                boolean downstreamRepaired=("outline".equals(changed.kind())||"chapter".equals(changed.kind()))&&downstreamRepairComplete(scope,event);
                if(downstreamRepaired) event.set("impact",emptyImpact());
                if("PENDING".equals(event.path("status").asText())&&(directBaseline||chainedInput||downstreamRepaired)
                        &&event.path("impact").path("unknownRefs").isArray()&&event.path("impact").path("unknownRefs").isEmpty()
                        ) {
                    ObjectNode confirmation=event.putObject("confirmation");confirmation.put("ready",true);
                    ObjectNode payload=confirmation.putObject("payload");payload.put("eventId",event.path("eventId").asText());payload.set("changedRef",json.valueToTree(changed));
                    payload.set("unchangedRefs",event.path("impact").path("unaffectedRefs").deepCopy());payload.putArray("resolutions");
                }
                blocked |= !"CONFIRMED".equals(event.path("status").asText()) || event.path("impact").path("formalBlocked").asBoolean(true);
            }
            events.add(event);
        }
        out.put("formalBlocked",blocked); return out;
    }

    @org.springframework.transaction.annotation.Transactional
    public ObjectNode reconfirm(BiddingTypes.Scope scope,BiddingTypes.Command command) {
        access.requireApprover(scope);
        if(command==null||command.payload()==null) throw BiddingAccess.error(400,"INVALID_REQUEST","Change impact confirmation is required");
        if(command.expected()==null||command.operationId()==null||command.operationId().isBlank()) throw BiddingAccess.error(400,"INVALID_REQUEST","Expected project ref and operationId are required");
        if(!repository.lockProject(scope.workspaceId(),scope.projectId())) throw BiddingAccess.error(404,"NOT_FOUND","Project not found");
        ObjectNode project=repository.findProject(scope.workspaceId(),scope.projectId());
        BiddingTypes.Ref expected=command.expected();
        if(!"project".equals(expected.kind())||!scope.projectId().equals(expected.id())||expected.version()!=project.path("version").asLong()||!expected.digest().equals(project.path("ref").path("digest").asText()))
            throw BiddingAccess.error(409,"VERSION_CONFLICT","Project changed; reload before confirming impact");
        String requestDigest=sha(command.action()+":"+expected+":"+command.payload());
        BiddingRepository.StoredOperation replay=repository.findOperation(scope.workspaceId(),scope.actorId(),command.operationId());
        if(replay!=null) {
            if(!replay.digest().equals(requestDigest)) throw BiddingAccess.error(409,"IDEMPOTENCY_CONFLICT","Operation id was already used for a different command");
            return replay.result();
        }
        String eventId=command.payload().path("eventId").asText("");
        ObjectNode stored=repository.changeEvent(scope,eventId);
        if(stored==null) throw BiddingAccess.error(404,"NOT_FOUND","Change event not found");
        BiddingTypes.Ref requestedChanged=parseRef(command.payload().path("changedRef"));
        if(requestedChanged==null||!requestedChanged.equals(parseRef(stored.path("changedRef")))) throw BiddingAccess.error(409,"CHANGE_EVENT_STALE","The selected change event no longer matches this command");
        if(!command.payload().path("unchangedRefs").isArray()||!command.payload().path("resolutions").isArray()) throw BiddingAccess.error(400,"INVALID_REQUEST","unchangedRefs and resolutions arrays are required");
        if("CONFIRMED".equals(stored.path("status").asText())) return stored;
        ObjectNode impact=impact(scope,parseRef(stored.path("changedRef")));
        BiddingTypes.Ref oldBaseline=parseRef(stored.path("changedRef")),replacement=parseRef(stored.path("replacementRef"));
        BiddingTypes.Ref selectedBaseline=repository.selectedRef(scope,"analysisBaseline","current");
        boolean directBaseline=oldBaseline!=null&&"analysisBaseline".equals(oldBaseline.kind())&&baselineTransitionReaches(scope,oldBaseline,replacement,selectedBaseline);
        boolean chainedInput=oldBaseline!=null&&("source".equals(oldBaseline.kind())||"sourceSet".equals(oldBaseline.kind()))
                &&supportingBaselineTransition(scope,oldBaseline)!=null;
        boolean downstreamTransition=oldBaseline!=null&&("outline".equals(oldBaseline.kind())||"chapter".equals(oldBaseline.kind()))&&downstreamRepairComplete(scope,stored);
        if(oldBaseline==null||(!directBaseline&&!chainedInput&&!downstreamTransition)||(!downstreamTransition&&impact.path("unknownRefs").size()>0))
            throw BiddingAccess.error(422,"CHANGE_IMPACT_UNPROVEN","The selected replacement and its required downstream evidence are not complete");
        Set<BiddingTypes.Ref> proved=new LinkedHashSet<>();
        for(JsonNode ref:command.payload().path("unchangedRefs")) { BiddingTypes.Ref parsed=parseRef(ref);if(parsed==null)throw BiddingAccess.error(422,"CHANGE_IMPACT_UNPROVEN","unchangedRefs contains an invalid reference");proved.add(parsed); }
        Set<BiddingTypes.Ref> serverUnaffected=new LinkedHashSet<>();
        if(!downstreamTransition) for(JsonNode ref:impact.path("unaffectedRefs")) { BiddingTypes.Ref parsed=parseRef(ref);if(parsed!=null)serverUnaffected.add(parsed); }
        if(!proved.equals(serverUnaffected)) throw BiddingAccess.error(422,"CHANGE_IMPACT_UNPROVEN","unchangedRefs must exactly match the server-computed semantic proof");
        for(JsonNode resolution:command.payload().path("resolutions")) if("ACCEPT_RISK".equals(resolution.path("decision").asText()))
            throw BiddingAccess.error(422,"CHANGE_IMPACT_UNPROVEN","Accepting risk cannot clear a changed or missing requirement");
        BiddingTypes.Ref newOutline=repository.selectedRef(scope,"outline","current");
        if(newOutline==null) throw BiddingAccess.error(422,"CHANGE_IMPACT_UNPROVEN","A confirmed outline based on the replacement baseline is required");
        ArrayNode cloned=json.createArrayNode();
        for(BiddingTypes.Ref oldChapter:serverUnaffected) {
            if(repository.isSelectedBusinessRevision(scope,oldChapter)&&!repository.businessRefs(repository.businessRevision(scope,oldChapter)).contains(newOutline))
                cloned.add(json.valueToTree(repository.cloneChapterAssociation(scope,oldChapter,newOutline,rebaseCitations(scope,oldChapter,selectedBaseline))));
        }
        ObjectNode proof=stored.deepCopy();proof.put("status","CONFIRMED");proof.set("impact",downstreamTransition?emptyImpact():impact.deepCopy());proof.set("clonedRefs",cloned);
        repository.saveChangeEvent(scope,eventId,proof,List.of(oldBaseline,replacement), "CONFIRMED",java.sql.Timestamp.from(Instant.now()));
        repository.insertOperation(scope.workspaceId(),scope.actorId(),command.operationId(),requestDigest,proof.toString(),java.sql.Timestamp.from(Instant.now()));
        return proof;
    }
    private String sha(String value) { try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);} }

    /** A downstream event closes only after exact selected heads and the latest persisted manuscript agree. */
    private boolean downstreamRepairComplete(BiddingTypes.Scope scope,ObjectNode event) {
        BiddingTypes.Ref changed=parseRef(event.path("changedRef")),replacement=parseRef(event.path("replacementRef"));
        if(changed==null||replacement==null)return false;
        BiddingTypes.Ref outline=repository.selectedRef(scope,"outline","current");
        if(outline==null)return false;
        if("outline".equals(changed.kind())) {
            if(!transitionReaches(scope,changed,replacement,outline,"outline"))return false;
        } else if("chapter".equals(changed.kind())) {
            BiddingTypes.Ref selected=repository.selectedRef(scope,"chapter",changed.id());
            if(!transitionReaches(scope,changed,replacement,selected,"chapter"))return false;
        } else return false;
        ObjectNode outlineRow=repository.businessRevision(scope,outline);
        BiddingTypes.Ref baseline=repository.selectedRef(scope,"analysisBaseline","current");
        if(outlineRow==null||!"CONFIRMED".equals(outlineRow.path("status").asText())||baseline==null||!repository.businessRefs(outlineRow).contains(baseline))return false;
        List<BiddingTypes.Ref> selectedChapters=repository.selectedChapterRefs(scope);
        Set<String> required=leafChapterIds(outlineRow.path("chapters"));
        if(required.isEmpty()||selectedChapters.size()!=required.size())return false;
        for(BiddingTypes.Ref selected:selectedChapters) {
            if(!required.contains(selected.id()))return false;
            ObjectNode chapter=repository.businessRevision(scope,selected);
            if(chapter==null||!Set.of("SELECTED","HUMAN_EDIT").contains(chapter.path("status").asText())||!repository.businessRefs(chapter).contains(outline))return false;
            JsonNode responses=chapter.path("responses");
            if(!responses.isArray()||responses.isEmpty()||!chapter.path("missingMaterials").isArray()||!chapter.path("missingMaterials").isEmpty()
                    ||!chapter.path("unresolvedItems").isArray()||!chapter.path("unresolvedItems").isEmpty())return false;
            for(JsonNode response:responses)if(!"RESPONDED".equals(response.path("status").asText()))return false;
        }
        BiddingTypes.Ref manuscript=repository.latestBusinessRef(scope,"manuscript","manuscript");
        ObjectNode assembled=manuscript==null?null:repository.businessRevision(scope,manuscript);
        if(assembled==null||!"DRAFT_PENDING_REVIEW".equals(assembled.path("status").asText()))return false;
        List<BiddingTypes.Ref> expected=new java.util.ArrayList<>();expected.add(outline);
        Map<String,BiddingTypes.Ref> selectedById=new java.util.HashMap<>();selectedChapters.forEach(ref->selectedById.put(ref.id(),ref));
        for(String id:leafOrder(outlineRow.path("chapters")))expected.add(selectedById.get(id));
        List<BiddingTypes.Ref> assembledRefs=repository.businessRefs(assembled);
        if(!assembledRefs.equals(expected))return false;
        validateForRead(scope,assembledRefs);
        return true;
    }
    private boolean transitionReaches(BiddingTypes.Scope scope,BiddingTypes.Ref old,BiddingTypes.Ref replacement,BiddingTypes.Ref selected,String kind) {
        if(old==null||replacement==null||selected==null||!kind.equals(old.kind())||!kind.equals(selected.kind())||!old.id().equals(selected.id()))return false;
        BiddingTypes.Ref current=old;Set<BiddingTypes.Ref> seen=new LinkedHashSet<>();
        while(seen.add(current)) {
            BiddingTypes.Ref next=null;
            for(ObjectNode event:repository.changeEvents(scope))if(current.equals(parseRef(event.path("changedRef")))&&kind.equals(current.kind())){next=parseRef(event.path("replacementRef"));break;}
            if(next==null)return false;
            if(current.equals(old)&&!next.equals(replacement))return false;
            if(next.equals(selected))return true;
            current=next;
        }
        return false;
    }
    private Set<String> leafChapterIds(JsonNode chapters) {
        Set<String> parents=new LinkedHashSet<>(),ids=new LinkedHashSet<>();
        for(JsonNode chapter:chapters)if(chapter.path("id").isTextual()){ids.add(chapter.path("id").asText());if(chapter.path("parentId").isTextual())parents.add(chapter.path("parentId").asText());}
        ids.removeAll(parents);return ids;
    }
    private List<String> leafOrder(JsonNode chapters) {
        Map<String,List<JsonNode>> children=new java.util.LinkedHashMap<>();
        for(JsonNode chapter:chapters){String parent=chapter.path("parentId").isTextual()?chapter.path("parentId").asText():"";children.computeIfAbsent(parent,key->new java.util.ArrayList<>()).add(chapter);}
        children.values().forEach(items->items.sort(java.util.Comparator.comparingInt(item->item.path("order").asInt(Integer.MAX_VALUE))));
        List<String> out=new java.util.ArrayList<>();collectLeaves("",children,out);return out;
    }
    private void collectLeaves(String parent,Map<String,List<JsonNode>> children,List<String> out) {
        for(JsonNode chapter:children.getOrDefault(parent,List.of())){String id=chapter.path("id").asText();if(children.getOrDefault(id,List.of()).isEmpty())out.add(id);else collectLeaves(id,children,out);}
    }
    private ObjectNode emptyImpact() { ObjectNode impact=json.createObjectNode();impact.putArray("affectedRefs");impact.putArray("unaffectedRefs");impact.putArray("unknownRefs");impact.putArray("refLabels");impact.put("formalBlocked",false);return impact; }

    public void recordTransition(BiddingTypes.Scope scope,BiddingTypes.Ref oldRef,BiddingTypes.Ref newRef) {
        if(oldRef==null||oldRef.equals(newRef)) return;
        ObjectNode event=json.createObjectNode();
        event.set("changedRef",json.valueToTree(oldRef));
        event.set("replacementRef",json.valueToTree(newRef)); event.put("status","PENDING");
        event.set("impact",unknownImpact()); String id=java.util.UUID.randomUUID().toString(); event.put("eventId",id);
        repository.saveChangeEvent(scope,id,event,List.of(oldRef,newRef),"PENDING",java.sql.Timestamp.from(Instant.now()));
    }
    private BiddingTypes.Ref parseRef(com.fasterxml.jackson.databind.JsonNode node) {
        if(node==null||!node.isObject()||node.path("kind").asText().isBlank()) return null;
        try { return json.treeToValue(node,BiddingTypes.Ref.class); } catch(Exception ignored) { return null; }
    }
    private ObjectNode unknownImpact() { ObjectNode node=json.createObjectNode(); node.putArray("affectedRefs");node.putArray("unaffectedRefs");node.putArray("unknownRefs");node.put("formalBlocked",true);return node; }
}
