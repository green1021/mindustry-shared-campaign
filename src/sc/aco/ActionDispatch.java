package sc.aco;

import arc.Core;
import arc.util.Log;
import sc.ActionResult;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runs a blocking campaign action off the caller's thread and delivers exactly
 * one terminal {@link ActionResult} back on the application thread.
 *
 * <p>Every path terminates the callback:
 * <ul>
 *   <li>task returns a result -&gt; delivered as-is;</li>
 *   <li>task throws -&gt; {@code EXCEPTION:&lt;type&gt;} (logged with the trace);</li>
 *   <li>executor is shut down / saturated -&gt; {@code REJECTED} (logged);</li>
 *   <li>callback already completed -&gt; second delivery is dropped.</li>
 * </ul>
 *
 * <p>The callback is marshalled through {@code Core.app.post} when a graphical
 * application exists, because Mindustry UI widgets are only safe to touch on the
 * render thread. In headless test runs {@code Core.app} is null and the callback
 * runs inline on the worker thread.
 */
public final class ActionDispatch {

    private ActionDispatch() {}

    public static void submit(ExecutorService executor, String what, Supplier<ActionResult> task,
                       Consumer<ActionResult> callback) {
        if (callback == null) {
            Log.warn("ACO_ACTION_NO_CALLBACK task=@", what);
            return;
        }
        Consumer<ActionResult> once = once(callback);

        if (executor == null) {
            Log.warn("ACO_ACTION_NO_EXECUTOR task=@", what);
            deliver(once, ActionResult.fail("NO_EXECUTOR"));
            return;
        }

        try {
            executor.execute(() -> {
                ActionResult result;
                try {
                    result = task.get();
                    if (result == null) result = ActionResult.fail("NULL_RESULT");
                } catch (Throwable t) {
                    Log.err("ACO_ACTION_FAILED task=@", what, t);
                    result = ActionResult.fail("EXCEPTION:" + t.getClass().getSimpleName());
                }
                deliver(once, result);
            });
        } catch (RejectedExecutionException e) {
            Log.warn("ACO_ACTION_REJECTED task=@", what);
            deliver(once, ActionResult.fail("REJECTED"));
        }
    }

    /** Deliver on the app thread when one exists; the wrapper keeps it single-shot. */
    private static void deliver(Consumer<ActionResult> callback, ActionResult result) {
        if (Core.app == null) {
            callback.accept(result);
            return;
        }
        Core.app.post(() -> callback.accept(result));
    }

    private static Consumer<ActionResult> once(Consumer<ActionResult> delegate) {
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        return result -> {
            if (done.compareAndSet(false, true)) {
                delegate.accept(result);
            } else {
                Log.warn("ACO_ACTION_DUPLICATE_CALLBACK result=@", result);
            }
        };
    }
}
