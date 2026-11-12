/*
 * This file is part of spark.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package me.lucko.spark.common.sampler.auto;

import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Decides from a stream of {@link TickSample}s when the server is lagging enough to
 * start a profiler.
 *
 * <p>Pure logic with no threads or platform access. Not thread-safe: an instance must be
 * confined to one thread or externally synchronized.</p>
 */
public final class LagDetector {

    /** The longest run of tick-less samples that is treated as part of the lag rather than a pause */
    public static final long MAX_MERGED_PAUSE_NANOS = TimeUnit.SECONDS.toNanos(5);

    private final double tpsThreshold;
    private final int sustainSeconds;
    private final long sustainNanos;
    private final int longTickMillis;
    private final int longTickCount;
    private final long cooldownNanos;
    private final int maxPerDay;
    private final long startupGraceNanos;

    private final Deque<TickSample> window = new ArrayDeque<>();
    private long windowNanos;
    private long windowTicks;
    private long windowLongTicks;
    private double windowMaxTickMillis;

    private final Deque<TickSample> pause = new ArrayDeque<>();
    private long pauseNanos;

    private boolean seenTick;
    private long firstTickNanos;
    private boolean capturing;
    private boolean hasCooldown;
    private long cooldownUntilNanos;
    private boolean hasBackoff;
    private long backoffUntilNanos;
    private @Nullable LocalDate captureDate;
    private int capturesOnDate;

    /**
     * Creates a new detector.
     *
     * @param tpsThreshold the TPS below which the server is lagging, 0 to disable the TPS rule
     * @param sustainSeconds the length of the evaluation window
     * @param longTickMillis the long tick threshold, 0 to disable the long tick rule
     * @param longTickCount the number of long ticks in the window that triggers a capture
     * @param cooldownMinutes the minimum time between the end of a session and the next capture
     * @param maxPerDay the maximum number of captures per local date, 0 for unlimited
     * @param startupGraceSeconds the time after the first tick during which nothing triggers
     */
    public LagDetector(double tpsThreshold, int sustainSeconds, int longTickMillis, int longTickCount,
                       int cooldownMinutes, int maxPerDay, int startupGraceSeconds) {
        this.tpsThreshold = tpsThreshold;
        this.sustainSeconds = sustainSeconds;
        this.sustainNanos = TimeUnit.SECONDS.toNanos(sustainSeconds);
        this.longTickMillis = longTickMillis;
        this.longTickCount = Math.max(longTickCount, 1);
        this.cooldownNanos = TimeUnit.MINUTES.toNanos(cooldownMinutes);
        this.maxPerDay = maxPerDay;
        this.startupGraceNanos = TimeUnit.SECONDS.toNanos(startupGraceSeconds);
    }

    /**
     * Adds a sample and decides if a capture should start.
     *
     * @param sample the sample
     * @param nowNanos the current monotonic time
     * @param today the current local date
     * @return the reason to start a capture, if one should start
     */
    public Optional<String> evaluate(TickSample sample, long nowNanos, LocalDate today) {
        if (!sample.isTicking()) {
            this.pause.addLast(sample);
            this.pauseNanos += sample.elapsedNanos();
            return Optional.empty();
        }

        if (!this.seenTick) {
            this.seenTick = true;
            this.firstTickNanos = nowNanos;
        }
        resolvePause();
        addToWindow(sample);

        if (!canCapture(nowNanos, today)) {
            return Optional.empty();
        }
        Optional<String> reason = checkTps();
        return reason.isPresent() ? reason : checkLongTicks();
    }

    /**
     * Records that a capture started.
     *
     * @param today the current local date
     */
    public void onCaptureStarted(LocalDate today) {
        rollDate(today);
        this.capturesOnDate++;
        this.capturing = true;
        clearWindow();
    }

    /**
     * Records that an automatic profiler session ended. Starts the cooldown.
     *
     * @param nowNanos the current monotonic time
     */
    public void onSessionEnded(long nowNanos) {
        this.capturing = false;
        this.hasCooldown = true;
        this.cooldownUntilNanos = nowNanos + this.cooldownNanos;
        clearWindow();
    }

    /**
     * Prevents captures for a while, e.g. after a failed attempt.
     *
     * @param nowNanos the current monotonic time
     * @param seconds the length of the backoff
     */
    public void backoff(long nowNanos, long seconds) {
        long until = nowNanos + TimeUnit.SECONDS.toNanos(seconds);
        if (!this.hasBackoff || until - this.backoffUntilNanos > 0) {
            this.backoffUntilNanos = until;
        }
        this.hasBackoff = true;
    }

    private void resolvePause() {
        if (this.pause.isEmpty()) {
            return;
        }
        if (this.pauseNanos <= MAX_MERGED_PAUSE_NANOS) {
            // a short stall is part of the lag
            for (TickSample paused : this.pause) {
                addToWindow(paused);
            }
        } else {
            // a long pause (empty server, frozen tick rate, shutdown) is not lag
            clearWindow();
        }
        this.pause.clear();
        this.pauseNanos = 0;
    }

    private void addToWindow(TickSample sample) {
        this.window.addLast(sample);
        this.windowNanos += sample.elapsedNanos();
        this.windowTicks += sample.ticks();
        this.windowLongTicks += sample.longTicks();
        trimWindow();
        this.windowMaxTickMillis = 0;
        for (TickSample s : this.window) {
            this.windowMaxTickMillis = Math.max(this.windowMaxTickMillis, s.maxTickMillis());
        }
    }

    private void trimWindow() {
        // keep the smallest suffix of samples that still covers the sustain window
        while (!this.window.isEmpty() && this.windowNanos - this.window.peekFirst().elapsedNanos() >= this.sustainNanos) {
            TickSample removed = this.window.pollFirst();
            this.windowNanos -= removed.elapsedNanos();
            this.windowTicks -= removed.ticks();
            this.windowLongTicks -= removed.longTicks();
        }
    }

    private void clearWindow() {
        this.window.clear();
        this.windowNanos = 0;
        this.windowTicks = 0;
        this.windowLongTicks = 0;
        this.windowMaxTickMillis = 0;
    }

    private void rollDate(LocalDate today) {
        if (!today.equals(this.captureDate)) {
            this.captureDate = today;
            this.capturesOnDate = 0;
        }
    }

    private boolean canCapture(long nowNanos, LocalDate today) {
        rollDate(today);
        if (this.capturing) {
            return false;
        }
        if (nowNanos - this.firstTickNanos < this.startupGraceNanos) {
            return false;
        }
        if (this.hasCooldown && nowNanos - this.cooldownUntilNanos < 0) {
            return false;
        }
        if (this.hasBackoff && nowNanos - this.backoffUntilNanos < 0) {
            return false;
        }
        return this.maxPerDay <= 0 || this.capturesOnDate < this.maxPerDay;
    }

    private Optional<String> checkTps() {
        if (this.tpsThreshold <= 0 || this.windowNanos < this.sustainNanos) {
            return Optional.empty();
        }
        double tps = this.windowTicks / (this.windowNanos / 1e9d);
        if (tps >= this.tpsThreshold) {
            return Optional.empty();
        }
        return Optional.of(String.format(Locale.ROOT, "tps %.1f over %ds (threshold %.1f)", tps, this.sustainSeconds, this.tpsThreshold));
    }

    private Optional<String> checkLongTicks() {
        if (this.longTickMillis <= 0 || this.windowLongTicks < this.longTickCount) {
            return Optional.empty();
        }
        return Optional.of(String.format(Locale.ROOT, "%d ticks over %dms in %ds (max %.0fms)",
                this.windowLongTicks, this.longTickMillis, this.sustainSeconds, this.windowMaxTickMillis));
    }
}
