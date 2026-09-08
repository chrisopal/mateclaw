package vip.mate.semantic.extraction;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import jakarta.annotation.*;
import vip.mate.semantic.application.extraction.ExtractionCoordinator;

/** Four execution slots plus independent 15-second heartbeats, never a model transaction. */
public final class ExtractionScheduler {
    private final JdbcExtractionRepository repo;private final ExtractionCoordinator coordinator;private final ExtractionConfiguration.Feature feature;private final boolean enabled;
    private final ScheduledExecutorService timer=Executors.newScheduledThreadPool(2,r->{Thread t=new Thread(r,"semantic-extraction-heartbeat");t.setDaemon(true);return t;});
    private final ExecutorService workers=Executors.newFixedThreadPool(4,r->{Thread t=new Thread(r,"semantic-extraction-worker");t.setDaemon(true);return t;});
    private final Semaphore slots=new Semaphore(4);private final String worker="semantic-"+UUID.randomUUID();
    public ExtractionScheduler(JdbcExtractionRepository repo,ExtractionCoordinator coordinator,ExtractionConfiguration.Feature feature,boolean enabled){this.repo=repo;this.coordinator=coordinator;this.feature=feature;this.enabled=enabled;}
    @PostConstruct void start(){if(enabled)timer.scheduleWithFixedDelay(this::tick,3,3,TimeUnit.SECONDS);}
    public void tick(){
        try{
            if(!feature.enabled()){repo.cancelActive();return;}
            while(slots.tryAcquire()){
                var attempt=repo.claim(worker,Instant.now(),Instant.now().plusSeconds(60),2,4);
                if(attempt.isEmpty()){slots.release();break;}
                workers.submit(()->{
                    Thread owner=Thread.currentThread();
                    var heartbeat=timer.scheduleAtFixedRate(()->{try{if(!feature.enabled())repo.cancelActive();else if(!repo.heartbeat(attempt.get().lease(),Instant.now().plusSeconds(60)))owner.interrupt();}catch(RuntimeException ignored){owner.interrupt();}},15,15,TimeUnit.SECONDS);
                    try{coordinator.execute(attempt.get());}finally{heartbeat.cancel(false);Thread.interrupted();slots.release();}
                });
            }
        }catch(RuntimeException ignored){/* next tick retries database availability; no source/provider data logged */}
    }
    @PreDestroy void stop(){timer.shutdownNow();workers.shutdownNow();}
}
