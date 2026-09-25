package vip.mate.bidding;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Controlled runtime for task lifecycle tests; never registered in an application profile. */
final class BiddingFakeRuntime extends BiddingEmployeeRuntime {
    private final Queue<BiddingTypes.Execution> executions=new ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();

    BiddingFakeRuntime(BiddingAccess access,BiddingDependencies dependencies,BiddingEmployeeBindings bindings,
            org.springframework.jdbc.core.JdbcTemplate jdbc,vip.mate.agent.AgentService agents,
            vip.mate.workspace.conversation.ConversationService conversations,vip.mate.agent.repository.AgentMapper mapper) {
        super(access,dependencies,bindings,jdbc,agents,conversations,mapper);
    }

    void enqueue(BiddingTypes.Execution execution) { executions.add(execution); }
    int calls() { return calls.get(); }

    @Override public BiddingTypes.Execution execute(BiddingTypes.Claim claim) {
        requireActive(claim);
        calls.incrementAndGet();
        BiddingTypes.Execution execution=executions.poll();
        if(execution==null) throw new IllegalStateException("No fake bidding execution queued");
        return execution;
    }
}
