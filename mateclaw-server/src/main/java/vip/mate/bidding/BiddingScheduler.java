package vip.mate.bidding;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Single-instance durable task poller. The database remains the queue of record. */
@Component
public class BiddingScheduler {
    private final BiddingTaskService tasks;
    private final BiddingSourceService sources;
    private final BiddingProperties properties;
    private final ThreadPoolExecutor executor;
    private final ThreadPoolExecutor sourceExecutor;
    private final Executor testExecutor;
    private final String bootId;
    private final ConcurrentHashMap<String,Future<?>> runningTasks=new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public BiddingScheduler(BiddingTaskService tasks,BiddingSourceService sources,BiddingProperties properties) {
        this(tasks,sources,properties,new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(2),r->{
            Thread thread=new Thread(r,"bidding-task"); thread.setDaemon(true); return thread;
        },new ThreadPoolExecutor.AbortPolicy()),new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),r->{
            Thread thread=new Thread(r,"bidding-source-read"); thread.setDaemon(true); return thread;
        },new ThreadPoolExecutor.AbortPolicy()),null,UUID.randomUUID().toString());
    }

    BiddingScheduler(BiddingTaskService tasks,BiddingSourceService sources,BiddingProperties properties,Executor testExecutor,String bootId) {
        this(tasks,sources,properties,null,null,testExecutor,bootId);
    }

    private BiddingScheduler(BiddingTaskService tasks,BiddingSourceService sources,BiddingProperties properties,ThreadPoolExecutor executor,ThreadPoolExecutor sourceExecutor,Executor testExecutor,String bootId) {
        this.tasks=tasks; this.sources=sources; this.properties=properties; this.executor=executor; this.sourceExecutor=sourceExecutor; this.testExecutor=testExecutor; this.bootId=bootId;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        if(properties.isEnabled() && properties.isSchedulerEnabled()) tasks.recoverInterrupted(bootId);
    }

    @Scheduled(fixedDelayString="${mateclaw.bidding.task-scan-delay-ms:1000}")
    public int scan() { return dispatchDue(Instant.now()); }

    public int dispatchDue(Instant now) {
        if(!properties.isEnabled() || !properties.isSchedulerEnabled()) return 0;
        tasks.recoverInterrupted(bootId,now);
        scheduleSourceRead();
        runningTasks.keySet().stream().filter(taskId->!tasks.isTaskRunning(taskId)).toList().forEach(this::cancelTask);
        int capacity=executor==null?Math.max(0,2-runningTasks.size()):Math.max(0,2-executor.getActiveCount()-executor.getQueue().size());
        if(capacity==0) return 0;
        int submitted=0;
        for(var claim:tasks.claimDue(now,bootId,capacity)) {
            try {
                AtomicReference<Future<?>> reference=new AtomicReference<>();
                Future<?> future;
                if(executor!=null) future=executor.submit(()->{ try { tasks.run(claim); } finally { Future<?> current=reference.get(); if(current!=null) runningTasks.remove(claim.taskId(),current); } });
                else future=new FutureTask<>(()->{ try { tasks.run(claim); } finally { Future<?> current=reference.get(); if(current!=null) runningTasks.remove(claim.taskId(),current); } },null);
                reference.set(future); runningTasks.put(claim.taskId(),future); submitted++;
                if(executor==null) testExecutor.execute((Runnable)future);
                if(future.isDone()) runningTasks.remove(claim.taskId(),future);
            }
            catch(java.util.concurrent.RejectedExecutionException full) { break; }
        }
        return submitted;
    }

    public void cancelTask(String taskId) {
        Future<?> future=runningTasks.remove(taskId);
        if(future!=null) future.cancel(true);
    }

    private void scheduleSourceRead() {
        if(testExecutor!=null) { testExecutor.execute(()->sources.readPending(2)); return; }
        try { sourceExecutor.execute(()->sources.readPending(2)); }
        catch(java.util.concurrent.RejectedExecutionException busy) { /* The bounded source queue will retry on the next scan. */ }
    }
}
