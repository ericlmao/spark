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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TickActivityCollectorTest {

    @Test
    public void testCountsAndReset() {
        TickActivityCollector collector = new TickActivityCollector(100, 1_000L);
        for (int i = 0; i < 20; i++) {
            collector.onTick(i);
        }
        collector.onTick(50d);
        collector.onTick(100d);
        collector.onTick(250.5d);
        collector.onTick(99.9d);

        TickSample sample = collector.drain(1_000_001_000L);
        assertEquals(1_000_000_000L, sample.elapsedNanos());
        assertEquals(20, sample.ticks());
        assertEquals(2, sample.longTicks());
        assertEquals(250.5d, sample.maxTickMillis(), 0.001);
        assertTrue(sample.isTicking());

        TickSample empty = collector.drain(1_500_001_000L);
        assertEquals(500_000_000L, empty.elapsedNanos());
        assertEquals(0, empty.ticks());
        assertEquals(0, empty.longTicks());
        assertEquals(0d, empty.maxTickMillis());
        assertFalse(empty.isTicking());
    }

    @Test
    public void testLongTicksDisabled() {
        TickActivityCollector collector = new TickActivityCollector(0, 0L);
        collector.onTick(10_000d);
        TickSample sample = collector.drain(10L);
        assertEquals(0, sample.longTicks());
        assertEquals(10_000d, sample.maxTickMillis(), 0.001);
    }
}
