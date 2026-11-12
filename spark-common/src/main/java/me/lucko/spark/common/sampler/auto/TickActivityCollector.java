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

import me.lucko.spark.common.tick.TickHook;
import me.lucko.spark.common.tick.TickReporter;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counts ticks and long ticks between drains.
 *
 * <p>The tick callbacks run on the server thread and only update counters, so they never
 * block. {@link #drain(long)} is called from a single other thread.</p>
 */
public final class TickActivityCollector implements TickHook.Callback, TickReporter.Callback {

    private final double longTickMillis;

    private final LongAdder ticks = new LongAdder();
    private final LongAdder longTicks = new LongAdder();
    private final LongAccumulator maxTickMicros = new LongAccumulator(Math::max, 0);
    private final AtomicLong lastDrainNanos;

    /**
     * Creates a new collector.
     *
     * @param longTickMillis the duration at or above which a tick counts as long, 0 to disable
     * @param startNanos the monotonic time the first sampling period starts at
     */
    public TickActivityCollector(int longTickMillis, long startNanos) {
        this.longTickMillis = longTickMillis;
        this.lastDrainNanos = new AtomicLong(startNanos);
    }

    @Override
    public void onTick(int currentTick) {
        this.ticks.increment();
    }

    @Override
    public void onTick(double duration) {
        if (this.longTickMillis > 0 && duration >= this.longTickMillis) {
            this.longTicks.increment();
        }
        this.maxTickMicros.accumulate((long) (duration * 1000d));
    }

    /**
     * Returns the activity since the previous drain and starts a new sampling period.
     *
     * @param nowNanos the current monotonic time
     * @return the sample
     */
    public TickSample drain(long nowNanos) {
        long elapsed = nowNanos - this.lastDrainNanos.getAndSet(nowNanos);
        long ticks = this.ticks.sumThenReset();
        long longTicks = this.longTicks.sumThenReset();
        double maxMillis = this.maxTickMicros.getThenReset() / 1000d;
        return new TickSample(Math.max(elapsed, 0), ticks, longTicks, maxMillis);
    }
}
