package com.collabcode.app.core;

import java.util.concurrent.atomic.AtomicBoolean;

/** Global kill switch. Once triggered, every checkpoint throws until reset. */
public final class EmergencyStop {
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    public void trigger() {
        stopped.set(true);
    }

    public void reset() {
        stopped.set(false);
    }

    public boolean isStopped() {
        return stopped.get();
    }

    /** Call before every model call and every tool call. */
    public void checkpoint() {
        if (stopped.get()) {
            throw new StoppedException();
        }
    }

    public static final class StoppedException extends RuntimeException {
        public StoppedException() {
            super("Stopped by the user.");
        }
    }
}
