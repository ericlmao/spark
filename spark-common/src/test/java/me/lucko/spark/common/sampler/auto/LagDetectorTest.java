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

import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LagDetectorTest {

    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);
    private static final LocalDate DAY = LocalDate.of(2026, 6, 1);

    /** Feeds one-second samples to a detector with a fake monotonic clock. */
    private static final class Feeder {
        private final LagDetector detector;
        private long now = 1_000 * SECOND;
        private LocalDate date = DAY;

        private Feeder(LagDetector detector) {
            this.detector = detector;
        }

        private Optional<String> tick(long ticks) {
            return sample(ticks, 0, ticks > 0 ? 50 : 0);
        }

        private Optional<String> sample(long ticks, long longTicks, double maxMillis) {
            this.now += SECOND;
            return this.detector.evaluate(new TickSample(SECOND, ticks, longTicks, maxMillis), this.now, this.date);
        }

        /** Feeds samples for the given number of seconds, returning the first reason seen. */
        private Optional<String> run(int seconds, long ticksPerSecond) {
            Optional<String> first = Optional.empty();
            for (int i = 0; i < seconds; i++) {
                Optional<String> reason = tick(ticksPerSecond);
                if (!first.isPresent() && reason.isPresent()) {
                    first = reason;
                }
            }
            return first;
        }
    }

    /** tps 15, sustain 20s, long ticks 2000ms x1, cooldown 30m, 5 per day, no grace */
    private static LagDetector detector() {
        return new LagDetector(15, 20, 2000, 1, 30, 5, 0);
    }

    @Test
    public void testHealthyServerNeverTriggers() {
        Feeder feeder = new Feeder(detector());
        assertFalse(feeder.run(300, 20).isPresent());
    }

    @Test
    public void testSustainedLowTpsTriggers() {
        Feeder feeder = new Feeder(detector());
        assertFalse(feeder.run(60, 20).isPresent());
        // the window needs to fill with low tps before it triggers
        assertFalse(feeder.run(10, 10).isPresent());
        Optional<String> reason = feeder.run(15, 10);
        assertTrue(reason.isPresent());
        assertTrue(reason.get().startsWith("tps "), reason.get());
        assertTrue(reason.get().contains("over 20s (threshold 15.0)"), reason.get());
    }

    @Test
    public void testExactTpsInReason() {
        Feeder feeder = new Feeder(detector());
        Optional<String> reason = feeder.run(20, 12);
        assertEquals(Optional.of("tps 12.0 over 20s (threshold 15.0)"), reason);
    }

    @Test
    public void testWindowNotFullDoesNotTrigger() {
        Feeder feeder = new Feeder(detector());
        assertFalse(feeder.run(19, 1).isPresent());
        assertTrue(feeder.run(1, 1).isPresent());
    }

    @Test
    public void testShortDipDoesNotTrigger() {
        Feeder feeder = new Feeder(detector());
        assertFalse(feeder.run(30, 20).isPresent());
        assertFalse(feeder.run(3, 5).isPresent());
        assertFalse(feeder.run(30, 20).isPresent());
    }

    @Test
    public void testShortStallIsMergedIntoWindow() {
        // 4 s with no ticks at all, then slow ticking: the stall counts towards the lag
        LagDetector detector = new LagDetector(15, 20, 0, 1, 30, 5, 0);
        Feeder feeder = new Feeder(detector);
        assertFalse(feeder.run(1, 14).isPresent());
        assertFalse(feeder.run(4, 0).isPresent());
        Optional<String> reason = feeder.run(15, 14);
        assertTrue(reason.isPresent());
        // (16 * 14) ticks over 20 s
        assertEquals("tps 11.2 over 20s (threshold 15.0)", reason.get());
    }

    @Test
    public void testLongPauseNeverTriggers() {
        Feeder feeder = new Feeder(detector());
        assertFalse(feeder.run(30, 20).isPresent());
        // the server pauses (empty server, /tick freeze, shutdown)
        assertFalse(feeder.run(600, 0).isPresent());
        // the first partial second after resuming has few ticks, then normal ticking
        assertFalse(feeder.tick(3).isPresent());
        assertFalse(feeder.run(60, 20).isPresent());
    }

    @Test
    public void testLongPauseClearsEarlierLowTps() {
        Feeder feeder = new Feeder(detector());
        assertFalse(feeder.run(15, 10).isPresent());
        assertFalse(feeder.run(10, 0).isPresent());
        // the earlier slow samples were discarded with the pause, so the window starts again
        assertFalse(feeder.run(19, 10).isPresent());
        assertTrue(feeder.run(1, 10).isPresent());
    }

    @Test
    public void testVeryLowTpsTriggers() {
        Feeder feeder = new Feeder(detector());
        // one tick roughly every three seconds from startup: 0.3 tps
        Optional<String> reason = Optional.empty();
        for (int i = 0; i < 30 && !reason.isPresent(); i++) {
            reason = feeder.tick(i % 3 == 2 ? 1 : 0);
        }
        assertTrue(reason.isPresent());
        double tps = Double.parseDouble(reason.get().split(" ")[1]);
        assertTrue(tps > 0.2 && tps < 0.5, reason.get());
    }

    @Test
    public void testLongTickRule() {
        Feeder feeder = new Feeder(detector());
        assertFalse(feeder.run(30, 20).isPresent());
        assertFalse(feeder.sample(20, 0, 1999).isPresent());
        Optional<String> reason = feeder.sample(18, 1, 3412);
        assertEquals(Optional.of("1 ticks over 2000ms in 20s (max 3412ms)"), reason);
    }

    @Test
    public void testLongTickCount() {
        Feeder feeder = new Feeder(new LagDetector(0, 20, 2000, 2, 30, 5, 0));
        assertFalse(feeder.sample(18, 1, 2500).isPresent());
        assertFalse(feeder.run(5, 20).isPresent());
        assertEquals(Optional.of("2 ticks over 2000ms in 20s (max 3000ms)"), feeder.sample(18, 1, 3000));
    }

    @Test
    public void testLongTickAfterFreeze() {
        // a frozen tick: no tick starts while it runs, then the long tick is reported
        Feeder feeder = new Feeder(detector());
        assertFalse(feeder.run(30, 20).isPresent());
        assertFalse(feeder.run(30, 0).isPresent());
        Optional<String> reason = feeder.sample(0, 1, 30_500);
        assertTrue(reason.isPresent());
        assertTrue(reason.get().startsWith("1 ticks over 2000ms"), reason.get());
    }

    @Test
    public void testStartupGrace() {
        Feeder feeder = new Feeder(new LagDetector(15, 20, 0, 1, 30, 5, 120));
        // the server is not ticking yet: grace has not started
        assertFalse(feeder.run(300, 0).isPresent());
        assertFalse(feeder.run(119, 5).isPresent());
        assertTrue(feeder.run(2, 5).isPresent());
    }

    @Test
    public void testCooldownAndSessionState() {
        Feeder feeder = new Feeder(detector());
        assertTrue(feeder.run(20, 5).isPresent());
        feeder.detector.onCaptureStarted(feeder.date);

        // nothing triggers while the session runs
        assertFalse(feeder.run(120, 5).isPresent());
        feeder.detector.onSessionEnded(feeder.now);

        // 30 minute cooldown
        assertFalse(feeder.run(30 * 60 - 1, 5).isPresent());
        assertTrue(feeder.run(2, 5).isPresent());
    }

    @Test
    public void testBackoff() {
        Feeder feeder = new Feeder(detector());
        assertTrue(feeder.run(20, 5).isPresent());
        feeder.detector.backoff(feeder.now, 60);
        assertFalse(feeder.run(59, 5).isPresent());
        assertTrue(feeder.run(2, 5).isPresent());
    }

    @Test
    public void testDailyCapAndRollover() {
        Feeder feeder = new Feeder(new LagDetector(15, 20, 0, 1, 0, 2, 0));
        for (int i = 0; i < 2; i++) {
            assertTrue(feeder.run(20, 5).isPresent());
            feeder.detector.onCaptureStarted(feeder.date);
            feeder.detector.onSessionEnded(feeder.now);
        }
        assertFalse(feeder.run(120, 5).isPresent());

        feeder.date = DAY.plusDays(1);
        assertTrue(feeder.run(1, 5).isPresent());
    }

    @Test
    public void testUnlimitedPerDay() {
        Feeder feeder = new Feeder(new LagDetector(15, 20, 0, 1, 0, 0, 0));
        for (int i = 0; i < 20; i++) {
            assertTrue(feeder.run(20, 5).isPresent());
            feeder.detector.onCaptureStarted(feeder.date);
            feeder.detector.onSessionEnded(feeder.now);
        }
    }

    @Test
    public void testRulesDisabledAtZero() {
        Feeder feeder = new Feeder(new LagDetector(0, 20, 0, 1, 30, 5, 0));
        assertFalse(feeder.run(60, 1).isPresent());
        assertFalse(feeder.sample(1, 5, 10_000).isPresent());
    }
}
