package ma.onda.rag.agent.infra.retrieval;

import ma.onda.rag.agent.application.retrieval.DocumentReranker;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/** No queued calls. Timed-out workers retain their bulkhead slot until they actually exit. */
public final class ResilientDocumentReranker implements DocumentReranker, AutoCloseable {
    private final DocumentReranker delegate;
    private final ThreadPoolExecutor executor;
    private final long timeoutNanos;
    private final long openNanos;
    private final int failureThreshold;
    private final LongSupplier clock;
    private int failures;
    private long generation;
    private long openedAt;
    private boolean open;
    private boolean probing;

    public ResilientDocumentReranker(DocumentReranker delegate, Duration timeout, int concurrency,
                                    int failureThreshold, Duration openDuration) {
        this(delegate, timeout, concurrency, failureThreshold, openDuration, System::nanoTime);
    }

    ResilientDocumentReranker(DocumentReranker delegate, Duration timeout, int concurrency,
                             int failureThreshold, Duration openDuration, LongSupplier clock) {
        this.delegate = delegate;
        this.timeoutNanos = timeout.toNanos();
        this.openNanos = openDuration.toNanos();
        this.failureThreshold = failureThreshold;
        this.clock = clock;
        this.executor = new ThreadPoolExecutor(0, concurrency, 30, TimeUnit.SECONDS,
                new SynchronousQueue<>(), Thread.ofVirtual().name("reranker-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public Result rerank(String query, List<Candidate> candidates) {
        if (candidates.isEmpty()) return new Result(java.util.Map.of(), Status.SUCCESS);
        Permit permit = acquire();
        if (permit == null) return Result.unavailable(Status.CIRCUIT_OPEN);
        Future<Result> task = null;
        try {
            List<Candidate> batch = List.copyOf(candidates);
            task = executor.submit(() -> {
                Result result = delegate.rerank(query, batch);
                if (result == null || result.status() != Status.SUCCESS || result.scores().size() != batch.size()
                        || batch.stream().anyMatch(candidate -> !result.scores().containsKey(candidate.chunkId()))
                        || result.scores().values().stream().anyMatch(score -> !Double.isFinite(score))) {
                    throw new IllegalStateException("Incomplete reranking scores");
                }
                return result;
            });
            Result result = task.get(timeoutNanos, TimeUnit.NANOSECONDS);
            completed(permit, true);
            return result;
        } catch (TimeoutException failure) {
            completed(permit, false);
            return Result.unavailable(Status.TIMEOUT);
        } catch (InterruptedException failure) {
            abandon(permit);
            Thread.currentThread().interrupt();
            return Result.unavailable(Status.INTERRUPTED);
        } catch (RejectedExecutionException failure) {
            abandon(permit);
            return Result.unavailable(Status.BULKHEAD_FULL);
        } catch (ExecutionException | RuntimeException failure) {
            completed(permit, false);
            return Result.unavailable(Status.ERROR);
        } finally {
            if (task != null && !task.isDone()) task.cancel(true);
        }
    }

    private synchronized Permit acquire() {
        if (!open) return new Permit(generation, false);
        if (probing || clock.getAsLong() - openedAt < openNanos) return null;
        probing = true;
        return new Permit(generation, true);
    }

    private synchronized void completed(Permit permit, boolean success) {
        if (permit.generation() != generation) return; // Ignore older in-flight outcomes.
        if (success) {
            failures = 0;
            if (permit.probe()) {
                open = false;
                probing = false;
                generation++;
            }
        } else if (permit.probe() || ++failures >= failureThreshold) {
            open = true;
            probing = false;
            openedAt = clock.getAsLong();
            generation++;
        }
    }

    private synchronized void abandon(Permit permit) {
        if (permit.generation() == generation && permit.probe()) probing = false;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private record Permit(long generation, boolean probe) {}
}
