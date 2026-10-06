package com.namo.pvsound.audio;

import com.namo.pvsound.PvSound;
import net.minecraft.core.GlobalPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;

/**
 * One real-time thread that ticks every mixer session every 20ms (one opus frame).
 */
public final class AudioEngine {

    private static final long FRAME_NANOS = 20_000_000L;

    private final Map<GlobalPos, MixerSession> sessions = new ConcurrentHashMap<>();
    private volatile boolean running;
    private Thread thread;

    public void start() {
        running = true;
        thread = new Thread(this::loop, "pvsound-audio");
        thread.setDaemon(true);
        thread.setPriority(Thread.MAX_PRIORITY - 1);
        thread.start();
    }

    public void shutdown() {
        running = false;
        if (thread != null) thread.interrupt();
        sessions.values().forEach(MixerSession::close);
        sessions.clear();
    }

    public MixerSession getOrCreate(GlobalPos key) {
        return sessions.computeIfAbsent(key, MixerSession::new);
    }

    public MixerSession get(GlobalPos key) {
        return sessions.get(key);
    }

    public void remove(GlobalPos key) {
        MixerSession session = sessions.remove(key);
        if (session != null) session.close();
    }

    private void loop() {
        long start = System.nanoTime();
        long frame = 0;

        while (running) {
            for (MixerSession session : sessions.values()) {
                try {
                    session.tick();
                } catch (Throwable t) {
                    PvSound.LOGGER.error("Mixer {} audio tick failed", session.key(), t);
                }
            }

            frame++;
            long target = start + frame * FRAME_NANOS;
            long wait = target - System.nanoTime();
            if (wait < -10 * FRAME_NANOS) {
                // fell far behind (gc pause / lag spike): resync instead of bursting frames
                start = System.nanoTime();
                frame = 0;
                continue;
            }
            if (wait > 0) LockSupport.parkNanos(wait);
        }
    }
}
