package vip.mate.bidding;

import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class BiddingDependencies {
    private final BiddingAccess access;
    private final BiddingRepository repository;
    public BiddingDependencies(BiddingAccess access, BiddingRepository repository) { this.access=access; this.repository=repository; }

    public void validate(BiddingTypes.Scope scope, List<BiddingTypes.Ref> refs) {
        access.requireActor(scope, scope.actorId());
        validateRefs(scope,refs);
    }

    public void validateForRead(BiddingTypes.Scope scope, List<BiddingTypes.Ref> refs) {
        access.requireReaderActor(scope, scope.actorId());
        validateRefs(scope,refs);
    }

    private void validateRefs(BiddingTypes.Scope scope,List<BiddingTypes.Ref> refs) {
        if (refs == null || refs.isEmpty()) throw BiddingAccess.error(422,"SOURCE_SET_INCOMPLETE","Fixed references are required");
        for (var ref : refs) {
            if (ref == null || ref.id() == null || ref.version() < 1) throw BiddingAccess.error(422,"SOURCE_REF_INVALID","A source reference is invalid");
            if ("source".equals(ref.kind())) {
                var source=repository.source(scope.workspaceId(),scope.projectId(),ref.id(),ref.version());
                if (source == null || !source.digest().equals(ref.digest())) throw BiddingAccess.error(404,"NOT_FOUND","Source not found");
                if (!Set.of("READY","NEEDS_REVIEW").contains(source.status())) throw BiddingAccess.error(422,"SOURCE_NOT_READY","Source reading is incomplete");
                if (!repository.sourceSetContains(scope,ref)) throw BiddingAccess.error(422,"SOURCE_NOT_CONFIRMED","Source is not part of the current confirmed source set");
            } else if ("sourceSet".equals(ref.kind())) {
                if (!repository.revisionExists(scope,ref)) throw BiddingAccess.error(404,"NOT_FOUND","Source set not found");
                if (!repository.isSelectedSourceSet(scope,ref)) throw BiddingAccess.error(409,"DEPENDENCY_STALE","Source set is no longer current");
            } else throw BiddingAccess.error(422,"SOURCE_REF_INVALID","Unsupported fixed reference kind");
        }
    }
    public boolean isCurrent(BiddingTypes.Scope scope, List<BiddingTypes.Ref> refs) { try { validate(scope,refs); return true; } catch(BiddingApiException e) { if (e.status()==404 || e.status()==409 || e.status()==422) return false; throw e; } }
    public void invalidate(BiddingTypes.Scope scope, BiddingTypes.Ref changed) { repository.invalidateDependencies(scope,changed); }
}
