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

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DailyScheduleTest {

    private static final LocalDate DATE = LocalDate.of(2026, 6, 1);
    private static final ZoneId UTC = ZoneOffset.UTC;

    private static List<DailySchedule.Slot> plan(List<LocalTime> fixed, List<TimeWindow> windows, int count, int spacing, long seed) {
        return DailySchedule.plan(DATE, UTC, fixed, windows, count, spacing, new Random(seed));
    }

    @Test
    public void testCountAndWindowMembership() {
        List<TimeWindow> windows = Arrays.asList(TimeWindow.parse("10:00-14:00"), TimeWindow.parse("17:00-23:30"));
        for (long seed = 0; seed < 50; seed++) {
            List<DailySchedule.Slot> slots = plan(Collections.emptyList(), windows, 4, 45, seed);
            assertEquals(4, slots.size());
            for (DailySchedule.Slot slot : slots) {
                assertTrue(slot.random());
                assertTrue(windows.get(0).contains(slot.minuteOfDay()) || windows.get(1).contains(slot.minuteOfDay()), slot.toString());
                assertEquals(ZonedDateTime.of(DATE, LocalTime.of(slot.minuteOfDay() / 60, slot.minuteOfDay() % 60), UTC).toInstant(), slot.time());
            }
        }
    }

    @Test
    public void testSortedAndSpaced() {
        List<TimeWindow> windows = Collections.singletonList(TimeWindow.parse("00:00-24:00"));
        List<LocalTime> fixed = Arrays.asList(LocalTime.of(4, 0), LocalTime.of(12, 0));
        for (long seed = 0; seed < 50; seed++) {
            List<DailySchedule.Slot> slots = plan(fixed, windows, 6, 90, seed);
            assertEquals(8, slots.size());
            for (int i = 1; i < slots.size(); i++) {
                assertTrue(slots.get(i).time().isAfter(slots.get(i - 1).time()));
            }
            for (DailySchedule.Slot a : slots) {
                for (DailySchedule.Slot b : slots) {
                    if (a != b && (a.random() || b.random())) {
                        assertTrue(Math.abs(a.minuteOfDay() - b.minuteOfDay()) >= 90, a + " vs " + b);
                    }
                }
            }
        }
    }

    @Test
    public void testFixedTimesAlwaysKept() {
        // fixed times closer together than the spacing, and outside the window
        List<LocalTime> fixed = Arrays.asList(LocalTime.of(4, 0), LocalTime.of(4, 10));
        List<DailySchedule.Slot> slots = plan(fixed, Collections.singletonList(TimeWindow.parse("10:00-11:00")), 0, 60, 1);
        assertEquals(2, slots.size());
        assertEquals(4 * 60, slots.get(0).minuteOfDay());
        assertEquals(4 * 60 + 10, slots.get(1).minuteOfDay());
        assertFalse(slots.get(0).random());
    }

    @Test
    public void testRandomSlotsAvoidFixed() {
        List<LocalTime> fixed = Collections.singletonList(LocalTime.of(10, 30));
        // window is 10:00-11:00 and spacing 60: no random slot can fit
        List<DailySchedule.Slot> slots = plan(fixed, Collections.singletonList(TimeWindow.parse("10:00-11:00")), 3, 60, 7);
        assertEquals(1, slots.size());
        assertFalse(slots.get(0).random());
    }

    @Test
    public void testInfeasibleRequestReturnsFewer() {
        List<DailySchedule.Slot> slots = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> plan(Collections.emptyList(), Collections.singletonList(TimeWindow.parse("10:00-12:00")), 100, 60, 3));
        // a two hour window with an hour of spacing always fits exactly two slots
        assertEquals(2, slots.size());
    }

    @Test
    public void testSeedReproducibility() {
        List<TimeWindow> windows = Collections.singletonList(TimeWindow.parse("00:00-24:00"));
        long seed = DailySchedule.seedFor(DATE, 12345L);
        assertEquals(seed, DailySchedule.seedFor(DATE, 12345L));
        assertEquals(plan(Collections.emptyList(), windows, 5, 30, seed), plan(Collections.emptyList(), windows, 5, 30, seed));

        LocalDate nextDay = DATE.plusDays(1);
        long nextSeed = DailySchedule.seedFor(nextDay, 12345L);
        assertNotEquals(seed, nextSeed);

        List<Integer> today = minutes(DailySchedule.plan(DATE, UTC, Collections.emptyList(), windows, 5, 30, new Random(seed)));
        List<Integer> tomorrow = minutes(DailySchedule.plan(nextDay, UTC, Collections.emptyList(), windows, 5, 30, new Random(nextSeed)));
        assertNotEquals(today, tomorrow);
    }

    @Test
    public void testOvernightAndEndOfDayWindows() {
        TimeWindow overnight = TimeWindow.parse("23:00-01:00");
        for (long seed = 0; seed < 20; seed++) {
            List<DailySchedule.Slot> slots = plan(Collections.emptyList(), Collections.singletonList(overnight), 2, 10, seed);
            assertEquals(2, slots.size());
            for (DailySchedule.Slot slot : slots) {
                assertTrue(slot.minuteOfDay() >= 23 * 60 || slot.minuteOfDay() < 60, slot.toString());
            }
        }

        TimeWindow lateEvening = TimeWindow.parse("23:59-24:00");
        List<DailySchedule.Slot> slots = plan(Collections.emptyList(), Collections.singletonList(lateEvening), 1, 10, 1);
        assertEquals(1, slots.size());
        assertEquals(1439, slots.get(0).minuteOfDay());
    }

    @Test
    public void testDstGapShiftsForward() {
        ZoneId newYork = ZoneId.of("America/New_York");
        LocalDate dstDay = LocalDate.of(2026, 3, 8);
        List<DailySchedule.Slot> slots = DailySchedule.plan(dstDay, newYork, Collections.singletonList(LocalTime.of(2, 30)),
                Collections.emptyList(), 0, 60, new Random(1));
        assertEquals(1, slots.size());
        assertEquals(2 * 60 + 30, slots.get(0).minuteOfDay());
        assertEquals(LocalTime.of(3, 30), slots.get(0).time().atZone(newYork).toLocalTime());
    }

    @Test
    public void testZeroCountAndNoWindows() {
        assertTrue(plan(Collections.emptyList(), Collections.emptyList(), 5, 60, 1).isEmpty());
        assertTrue(plan(Collections.emptyList(), Collections.singletonList(TimeWindow.parse("00:00-24:00")), 0, 60, 1).isEmpty());
    }

    private static List<Integer> minutes(List<DailySchedule.Slot> slots) {
        Integer[] result = new Integer[slots.size()];
        for (int i = 0; i < slots.size(); i++) {
            result[i] = slots.get(i).minuteOfDay();
        }
        return Arrays.asList(result);
    }
}
