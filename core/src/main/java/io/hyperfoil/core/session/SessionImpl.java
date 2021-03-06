package io.hyperfoil.core.session;

import io.hyperfoil.api.config.Benchmark;
import io.hyperfoil.api.connection.HttpDestinationTable;
import io.hyperfoil.api.connection.HttpRequest;
import io.hyperfoil.api.connection.Request;
import io.hyperfoil.api.http.HttpCache;
import io.hyperfoil.api.session.SessionStopException;
import io.hyperfoil.api.session.SharedData;
import io.hyperfoil.api.statistics.SessionStatistics;
import io.hyperfoil.core.http.HttpCacheImpl;
import io.netty.util.concurrent.EventExecutor;
import io.hyperfoil.api.collection.LimitedPool;
import io.hyperfoil.api.config.Phase;
import io.hyperfoil.api.config.Scenario;
import io.hyperfoil.api.config.Sequence;
import io.hyperfoil.api.connection.HttpConnectionPool;
import io.hyperfoil.api.session.SequenceInstance;
import io.hyperfoil.api.session.Session;
import io.hyperfoil.api.statistics.Statistics;
import io.hyperfoil.api.session.PhaseInstance;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

class SessionImpl implements Session, Callable<Void> {
   private static final Logger log = LoggerFactory.getLogger(SessionImpl.class);
   private static final boolean trace = log.isTraceEnabled();

   // Note: HashMap.get() is allocation-free, so we can use it for direct lookups. Replacing put() is also
   // allocation-free, so vars are OK to write as long as we have them declared.
   private final Map<Object, Var> vars = new HashMap<>();
   private final Map<ResourceKey<?>, Object> resources = new HashMap<>();
   private final List<Var> allVars = new ArrayList<>();
   private final LimitedPool<SequenceInstance> sequencePool;
   private final LimitedPool<HttpRequest> requestPool;
   private final HttpRequest[] requests;
   private final HttpCacheImpl httpCache;
   private final SequenceInstance[] runningSequences;
   private final BitSet usedSequences;
   private PhaseInstance phase;
   private int lastRunningSequence = -1;
   private SequenceInstance currentSequence;
   private Request currentRequest;
   private boolean scheduled;

   private HttpDestinationTable httpDestinations;
   private EventExecutor executor;
   private SharedData sharedData;
   private SessionStatistics statistics;

   private final int agentId;
   private final int threadId;
   private final int uniqueId;

   private final Callable<Void> deferredStart = this::deferredStart;

   SessionImpl(Scenario scenario, int agentId, int threadId, int uniqueId, Clock clock) {
      this.sequencePool = new LimitedPool<>(scenario.maxSequences(), SequenceInstance::new);
      this.agentId = agentId;
      this.threadId = threadId;
      this.requests = new HttpRequest[scenario.maxRequests()];
      for (int i = 0; i < requests.length; ++i) {
         this.requests[i] = new HttpRequest(this);
      }
      this.requestPool = new LimitedPool<>(this.requests);
      this.runningSequences = new SequenceInstance[scenario.maxSequences()];
      this.usedSequences = new BitSet(scenario.sumConcurrency());
      this.uniqueId = uniqueId;
      this.httpCache = new HttpCacheImpl(clock);
   }

   @Override
   public void reserve(Scenario scenario) {
      Sequence[] sequences = scenario.sequences();
      for (int i = 0; i < sequences.length; i++) {
         // We set current sequence so that we know the concurrency of current context in declareResource()
         Sequence sequence = sequences[i];
         currentSequence(sequencePool.acquire().reset(sequence, 0, null));
         sequence.reserve(this);
         sequencePool.release(currentSequence);
         currentSequence = null;
      }
      for (String var : scenario.objectVars()) {
         declareObject(var);
      }
      for (String var : scenario.intVars()) {
         declareInt(var);
      }
   }

   @Override
   public int uniqueId() {
      return uniqueId;
   }

   @Override
   public int agentThreadId() {
      return threadId;
   }

   @Override
   public int agentThreads() {
      return phase.agentThreads();
   }

   @Override
   public int globalThreadId() {
      return phase.agentFirstThreadId() + threadId;
   }

   @Override
   public int globalThreads() {
      Benchmark benchmark = phase.definition().benchmark();
      return benchmark.totalThreads();
   }

   @Override
   public int agentId() {
      return agentId;
   }

   @Override
   public HttpConnectionPool httpConnectionPool(String authority) {
      return httpDestinations.getConnectionPool(authority);
   }

   @Override
   public HttpDestinationTable httpDestinations() {
      return httpDestinations;
   }

   @Override
   public EventExecutor executor() {
      return executor;
   }

   @Override
   public SharedData sharedData() {
      return sharedData;
   }

   @Override
   public Phase phase() {
      return phase != null ? phase.definition() : null;
   }

   void registerVar(Var var) {
      allVars.add(var);
   }

   public Session declareObject(Object key) {
      ObjectVar var = new ObjectVar(this);
      vars.putIfAbsent(key, var);
      return this;
   }

   public Object getObject(Object key) {
      return ((ObjectVar) requireSet(key)).get();
   }

   public Session setObject(Object key, Object value) {
      if (trace) {
         log.trace("#{} {} <- {}", uniqueId, key, value);
      }
      ObjectVar var = getVar(key);
      var.value = value;
      var.set = true;
      return this;
   }

   public Session declareInt(Object key) {
      IntVar var = new IntVar(this);
      vars.put(key, var);
      return this;
   }

   public int getInt(Object key) {
      IntVar var = requireSet(key);
      return var.get();
   }

   public void setInt(Object key, int value) {
      if (trace) {
         log.trace("#{} {} <- {}", uniqueId, key, value);
      }
      this.<IntVar>getVar(key).set(value);
   }

   public int addToInt(Object key, int delta) {
      IntVar var = requireSet(key);
      int prev = var.get();
      if (trace) {
         log.trace("#{} {} <- {}", uniqueId, key, prev + delta);
      }
      var.set(prev + delta);
      return prev;
   }

   @Override
   public <R extends Resource> void declareResource(ResourceKey<R> key, Supplier<R> resourceSupplier) {
      declareResource(key, resourceSupplier, false);
   }

   @Override
   public <R extends Resource> void declareResource(ResourceKey<R> key, Supplier<R> resourceSupplier, boolean singleton) {
      // Current sequence should be null only during unit testing
      int concurrency = currentSequence == null ? 0 : currentSequence.definition().concurrency();
      if (!singleton && concurrency > 0) {
         Resource[] array = new Resource[concurrency];
         for (int i = 0; i < concurrency; ++i) {
            array[i] = resourceSupplier.get();
         }
         resources.put(key, array);
      } else {
         resources.put(key, resourceSupplier.get());
      }
   }

   @SuppressWarnings("unchecked")
   @Override
   public <R extends Resource> R getResource(ResourceKey<R> key) {
      Object res = resources.get(key);
      if (res == null) {
         return null;
      } else if (res instanceof Resource[]) {
         Resource[] array = (Resource[]) res;
         return (R) array[currentSequence.index()];
      } else {
         return (R) res;
      }
   }

   @SuppressWarnings("unchecked")
   public <V extends Var> V getVar(Object key) {
      Var var = vars.get(key);
      if (var == null) {
         throw new IllegalStateException("Variable " + key + " was not defined!");
      }
      return (V) var;
   }

   @SuppressWarnings("unchecked")
   private <V extends Var> V requireSet(Object key) {
      Var var = vars.get(key);
      if (var == null) {
         throw new IllegalStateException("Variable " + key + " was not defined!");
      } else if (!var.isSet()) {
         throw new IllegalStateException("Variable " + key + " was not set yet!");
      }
      return (V) var;
   }

   @Override
   public Void call() {
      scheduled = false;
      try {
         runSession();
      } catch (SessionStopException e) {
         log.trace("#{} Session was stopped.", uniqueId);
         // this one is OK
      } catch (Throwable t) {
         log.error("#{} Uncaught error", t, uniqueId);
         if (phase != null) {
            phase.fail(t);
         }
      }
      return null;
   }

   public void runSession() {
      if (phase.status() == PhaseInstance.Status.TERMINATED) {
         if (trace) {
            log.trace("#{} Phase is terminated", uniqueId);
         }
         return;
      }
      if (lastRunningSequence < 0) {
         if (trace) {
            log.trace("#{} No sequences to run, ignoring.", uniqueId);
         }
         return;
      }
      if (trace) {
         log.trace("#{} Run ({} runnning sequences)", uniqueId, lastRunningSequence + 1);
      }
      int lastProgressedSequence = -1;
      while (lastRunningSequence >= 0) {
         boolean progressed = false;
         for (int i = 0; i <= lastRunningSequence; ++i) {
            if (phase.status() == PhaseInstance.Status.TERMINATING) {
               if (trace) {
                  log.trace("#{} Phase {} is terminating", uniqueId, phase.definition().name());
               }
               stop();
               return;
            } else if (lastProgressedSequence == i) {
               break;
            }
            SequenceInstance sequence = runningSequences[i];
            if (sequence == null) {
               // This may happen when the session.stop() is called
               continue;
            }
            if (sequence.progress(this)) {
               progressed = true;
               lastProgressedSequence = i;
               if (sequence.isCompleted()) {
                  if (trace) {
                     log.trace("#{} Completed {}", uniqueId, sequence);
                  }
                  if (lastRunningSequence == -1) {
                     log.trace("#{} was stopped.");
                     return;
                  }
                  usedSequences.clear(sequence.definition().offset() + sequence.index());
                  sequencePool.release(sequence);
                  if (i >= lastRunningSequence) {
                     runningSequences[i] = null;
                  } else {
                     runningSequences[i] = runningSequences[lastRunningSequence];
                     runningSequences[lastRunningSequence] = null;
                  }
                  --lastRunningSequence;
                  lastProgressedSequence = -1;
               }
            }
         }
         if (!progressed && lastRunningSequence >= 0) {
            if (trace) {
               log.trace("#{} ({}) no progress, not finished.", uniqueId, phase.definition().name());
            }
            return;
         }
      }
      if (trace) {
         log.trace("#{} Session finished", uniqueId);
      }
      if (!requestPool.isFull()) {
         // We can't guarantee that requests will be back in session's requestPool when it terminates
         // because if the requests did timeout (calling handlers and eventually letting the session terminate)
         // it might still be held in the connection.
         for (HttpRequest request : requests) {
            if (!request.isCompleted()) {
               log.warn("#{} Session completed with requests in-flight!", uniqueId);
               break;
            }
         }
         cancelRequests();
      }
      reset();
      phase.notifyFinished(this);
   }

   private void cancelRequests() {
      // We need to close all connections used to ongoing requests, despite these might
      // carry requests from independent phases/sessions
      if (!requestPool.isFull()) {
         for (HttpRequest request : requests) {
            if (!request.isCompleted()) {
               if (trace) {
                  log.trace("Canceling request on {}", request.connection());
               }
               request.connection().close();
               if (!request.isCompleted()) {
                  // Connection.close() cancels everything in flight but if this is called
                  // from handleEnd() the request is not in flight anymore
                  log.trace("#{} Connection close did not completed the request.", request.session != null ? request.session.uniqueId() : 0);
                  request.setCompleted();
                  request.release();
               }
            }
         }
      }
   }

   @Override
   public void currentSequence(SequenceInstance current) {
      if (trace) {
         log.trace("#{} Changing sequence {} -> {}", uniqueId, currentSequence, current);
      }
      currentSequence = current;
   }

   public SequenceInstance currentSequence() {
      return currentSequence;
   }

   @Override
   public void attach(EventExecutor executor, SharedData sharedData, HttpDestinationTable httpDestinations, SessionStatistics statistics) {
      assert this.executor == null;
      this.executor = executor;
      this.sharedData = sharedData;
      this.httpDestinations = httpDestinations;
      this.statistics = statistics;
   }

   @Override
   public void start(PhaseInstance phase) {
      if (trace) {
         log.trace("#{} Session starting in {}", uniqueId, phase.definition().name);
      }
      resetPhase(phase);
      executor.submit(deferredStart);
   }

   private Void deferredStart() {
      for (Sequence sequence : phase.definition().scenario().initialSequences()) {
         startSequence(sequence, ConcurrencyPolicy.FAIL);
      }
      call();
      return null;
   }

   @Override
   public SequenceInstance startSequence(String name, ConcurrencyPolicy policy) {
      return startSequence(phase.definition().scenario().sequence(name), policy);
   }

   private SequenceInstance startSequence(Sequence sequence, ConcurrencyPolicy policy) {
      SequenceInstance instance = sequencePool.acquire();
      // Lookup first unused index
      int index = 0;
      for (; ; ) {
         if (sequence.concurrency() == 0) {
            if (index > 1) {
               log.error("Cannot start sequence {} as it has already started and it is not marked as concurrent", sequence.name());
               if (sequence == currentSequence.definition()) {
                  log.info("Hint: maybe you intended only to restart the current sequence?");
               }
               fail(new IllegalStateException("Sequence is not concurrent"));
            }
         } else if (index >= sequence.concurrency()) {
            if (instance != null) {
               sequencePool.release(instance);
            }
            if (policy == ConcurrencyPolicy.WARN) {
               log.warn("Cannot start sequence {}, exceeded maximum concurrency ({})", sequence.name(), sequence.concurrency());
            } else {
               log.error("Cannot start sequence {}, exceeded maximum concurrency ({})", sequence.name(), sequence.concurrency());
               fail(new IllegalStateException("Concurrency limit exceeded"));
            }
            return null;
         }
         if (!usedSequences.get(sequence.offset() + index)) {
            break;
         }
         ++index;
      }
      if (instance == null) {
         log.error("Cannot instantiate sequence {}, no free instances.", sequence.name());
         fail(new IllegalStateException("No free sequence instances"));
      } else {
         log.trace("#{} starting sequence {}({})", uniqueId(), sequence.name(), index);
         usedSequences.set(sequence.offset() + index);
         instance.reset(sequence, index, sequence.steps());

         if (lastRunningSequence >= runningSequences.length - 1) {
            throw new IllegalStateException("Maximum number of scheduled sequences exceeded!");
         }
         lastRunningSequence++;
         assert runningSequences[lastRunningSequence] == null;
         runningSequences[lastRunningSequence] = instance;
      }
      return instance;
   }

   @Override
   public void proceed() {
      if (!scheduled) {
         scheduled = true;
         executor.submit(this);
      }
   }

   @Override
   public Statistics statistics(int stepId, String name) {
      return statistics.getOrCreate(phase.definition(), stepId, name, phase.absoluteStartTime());
   }

   @Override
   public void pruneStats(Phase phase) {
      statistics.prune(phase);
   }

   @Override
   public void reset() {
      assert usedSequences.isEmpty();
      assert sequencePool.isFull();
      assert requestPool.isFull();
      for (int i = 0; i < allVars.size(); ++i) {
         allVars.get(i).unset();
      }
      httpCache.clear();
      httpDestinations.onSessionReset();
   }

   public void resetPhase(PhaseInstance newPhase) {
      // I dislike having non-final phase but it helps not reallocating the resources...
      if (phase == newPhase) {
         return;
      }
      assert phase == null || newPhase.definition().scenario() == phase.definition().scenario();
      assert phase == null || newPhase.definition().sharedResources.equals(phase.definition().sharedResources);
      assert phase == null || phase.status() == PhaseInstance.Status.TERMINATED;
      phase = newPhase;
   }

   @Override
   public void stop() {
      for (int i = 0; i <= lastRunningSequence; ++i) {
         SequenceInstance sequence = runningSequences[i];
         usedSequences.clear(sequence.definition().offset() + sequence.index());
         sequencePool.release(sequence);
         runningSequences[i] = null;
      }
      lastRunningSequence = -1;
      currentSequence = null;
      if (trace) {
         log.trace("#{} Session stopped.", uniqueId);
      }
      cancelRequests();
      reset();
      phase.notifyFinished(this);
      throw SessionStopException.INSTANCE;
   }

   @Override
   public void fail(Throwable t) {
      try {
         stop();
      } finally {
         phase.fail(t);
      }
   }

   @Override
   public boolean isActive() {
      return lastRunningSequence >= 0;
   }

   @Override
   public LimitedPool<HttpRequest> httpRequestPool() {
      return requestPool;
   }

   @Override
   public HttpCache httpCache() {
      return httpCache;
   }

   @Override
   public Request currentRequest() {
      return currentRequest;
   }

   @Override
   public void currentRequest(Request request) {
      this.currentRequest = request;
   }

   @Override
   public String toString() {
      StringBuilder sb = new StringBuilder("#").append(uniqueId)
            .append(" (").append(phase != null ? phase.definition().name : null).append(") ")
            .append(lastRunningSequence + 1).append(" sequences:");
      for (int i = 0; i <= lastRunningSequence; ++i) {
         sb.append(' ');
         runningSequences[i].appendTo(sb);
      }
      return sb.toString();
   }

}
