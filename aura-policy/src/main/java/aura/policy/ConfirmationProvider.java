package aura.policy;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asks the user whether a dangerous call may proceed.
 *
 * <p>In M1 there is a single implementation - a modal dialog from the tray. In
 * M4 a voice implementation will connect to the same interface, and the dialog
 * will remain the fallback path.
 */
@FunctionalInterface
public interface ConfirmationProvider {

    /** Returns only {@link Decision#ALLOW} or {@link Decision#DENY}. */
    Decision confirm(ToolRequest request, Duration timeout);

    /**
     * Wraps an implementation so that a timeout, an exception, and any answer
     * other than an explicit {@link Decision#ALLOW} become a refusal. Deny by
     * default is not an implementation detail but a safety requirement, so it
     * lives here rather than in every implementation separately.
     */
    static ConfirmationProvider guarded(ConfirmationProvider delegate) {
        return guarded(delegate, Duration.ofSeconds(2));
    }

    /**
     * The same, with an explicit grace period on top of the timeout. The grace
     * period exists for implementations that close themselves (the dialog
     * dismisses its window exactly at the timeout): without it, the wrapper and
     * the implementation would race for the same millisecond. A zero grace
     * period means a hard cutoff.
     */
    static ConfirmationProvider guarded(ConfirmationProvider delegate, Duration grace) {
        Logger log = LoggerFactory.getLogger(ConfirmationProvider.class);
        return (request, timeout) -> {
            ExecutorService executor = null;
            try {
                executor = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "aura-confirm");
                    t.setDaemon(true);
                    return t;
                });
                Callable<Decision> task = () -> delegate.confirm(request, timeout);
                Future<Decision> future = executor.submit(task);
                Decision answer = future.get(
                    timeout.plus(grace).toMillis(), TimeUnit.MILLISECONDS);
                if (answer == Decision.ALLOW) {
                    return Decision.ALLOW;
                }
                if (answer != Decision.DENY) {
                    log.warn("confirmation returned {} for {} - treating it as a refusal",
                        answer, request.toolName());
                }
                return Decision.DENY;
            } catch (InterruptedException e) {
                // Restore the flag before returning. Swallowing it would leave a caller
                // that is itself being cancelled unable to see its own shutdown.
                Thread.currentThread().interrupt();
                log.info("confirmation interrupted for {} - denying", request.toolName());
                return Decision.DENY;
            } catch (Exception e) {
                log.info("no confirmation obtained for {} ({}) - denying",
                    request.toolName(), e.getClass().getSimpleName());
                return Decision.DENY;
            } finally {
                if (executor != null) {
                    executor.shutdownNow();
                }
            }
        };
    }
}
