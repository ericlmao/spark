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

/**
 * The tick activity observed over one sampling period.
 */
public final class TickSample {
    private final long elapsedNanos;
    private final long ticks;
    private final long longTicks;
    private final double maxTickMillis;

    public TickSample(long elapsedNanos, long ticks, long longTicks, double maxTickMillis) {
        this.elapsedNanos = elapsedNanos;
        this.ticks = ticks;
        this.longTicks = longTicks;
        this.maxTickMillis = maxTickMillis;
    }

    /**
     * Gets the length of the sampling period.
     *
     * @return the length in nanoseconds
     */
    public long elapsedNanos() {
        return this.elapsedNanos;
    }

    /**
     * Gets the number of ticks that started during the period.
     *
     * @return the tick count
     */
    public long ticks() {
        return this.ticks;
    }

    /**
     * Gets the number of ticks that ended during the period and took at least the long tick threshold.
     *
     * @return the long tick count
     */
    public long longTicks() {
        return this.longTicks;
    }

    /**
     * Gets the duration of the longest tick that ended during the period.
     *
     * @return the duration in milliseconds, or 0 if unknown
     */
    public double maxTickMillis() {
        return this.maxTickMillis;
    }

    /**
     * Gets if any tick activity was seen during the period.
     *
     * @return true if the server ticked
     */
    public boolean isTicking() {
        return this.ticks > 0 || this.longTicks > 0;
    }

    @Override
    public String toString() {
        return "TickSample{elapsedNanos=" + this.elapsedNanos + ", ticks=" + this.ticks +
                ", longTicks=" + this.longTicks + ", maxTickMillis=" + this.maxTickMillis + '}';
    }
}
