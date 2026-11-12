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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ScheduledProfilerTriggerTest {

    private static final ZoneId ZONE = ZoneOffset.UTC;
    private static final LocalDate DAY = LocalDate.of(2026, 6, 1);

    private final List<LocalDate> plannedDates = new ArrayList<>();

    private ScheduledProfilerTrigger trigger(LocalTime... fixed) {
        List<LocalTime> times = Arrays.asList(fixed);
        return new ScheduledProfilerTrigger(ZONE,
                date -> DailySchedule.plan(date, ZONE, times, Collections.emptyList(), 0, 0, new Random(0)),
                (date, plan) -> this.plannedDates.add(date));
    }

    private static Instant at(LocalDate date, int hour, int minute, int second) {
        return ZonedDateTime.of(date, LocalTime.of(hour, minute, second), ZONE).toInstant();
    }

    @Test
    public void testFiresAtSlot() {
        FakeClock clock = new FakeClock(at(DAY, 9, 58, 0));
        ScheduledProfilerTrigger trigger = trigger(LocalTime.of(10, 0));

        assertFalse(trigger.poll(clock.now()).isPresent());
        assertEquals(Collections.singletonList(DAY), this.plannedDates);
        assertEquals(1, trigger.remainingToday().size());

        clock.advance(Duration.ofSeconds(119));
        assertFalse(trigger.poll(clock.now()).isPresent());

        clock.advance(Duration.ofSeconds(1));
        Optional<DailySchedule.Slot> slot = trigger.poll(clock.now());
        assertTrue(slot.isPresent());
        assertEquals(10 * 60, slot.get().minuteOfDay());
        assertTrue(trigger.remainingToday().isEmpty());

        assertFalse(trigger.poll(clock.now()).isPresent());
    }

    @Test
    public void testDropsPastSlotsAtStartup() {
        ScheduledProfilerTrigger trigger = trigger(LocalTime.of(8, 0), LocalTime.of(10, 0), LocalTime.of(12, 0));
        Instant now = at(DAY, 10, 0, 0);
        assertFalse(trigger.poll(now).isPresent());
        assertEquals(1, trigger.remainingToday().size());
        assertEquals(12 * 60, trigger.remainingToday().get(0).minuteOfDay());
        assertFalse(trigger.poll(now.plusSeconds(1)).isPresent());
    }

    @Test
    public void testDateRolloverReplans() {
        ScheduledProfilerTrigger trigger = trigger(LocalTime.of(0, 1), LocalTime.of(23, 0));
        assertFalse(trigger.poll(at(DAY, 23, 30, 0)).isPresent());
        assertTrue(trigger.remainingToday().isEmpty());

        LocalDate nextDay = DAY.plusDays(1);
        assertFalse(trigger.poll(at(nextDay, 0, 0, 5)).isPresent());
        assertEquals(Arrays.asList(DAY, nextDay), this.plannedDates);
        assertEquals(2, trigger.remainingToday().size());

        Optional<DailySchedule.Slot> slot = trigger.poll(at(nextDay, 0, 1, 1));
        assertTrue(slot.isPresent());
        assertEquals(at(nextDay, 0, 1, 0), slot.get().time());
    }

    @Test
    public void testLateSlotDropped() {
        ScheduledProfilerTrigger trigger = trigger(LocalTime.of(10, 0));
        assertFalse(trigger.poll(at(DAY, 9, 0, 0)).isPresent());
        assertFalse(trigger.poll(at(DAY, 10, 2, 1)).isPresent());
        assertTrue(trigger.remainingToday().isEmpty());
    }

    @Test
    public void testSlotExactlyAtLatenessLimitFires() {
        ScheduledProfilerTrigger trigger = trigger(LocalTime.of(10, 0));
        assertFalse(trigger.poll(at(DAY, 9, 0, 0)).isPresent());
        assertTrue(trigger.poll(at(DAY, 10, 2, 0)).isPresent());
    }

    @Test
    public void testForwardClockJumpFiresAtMostOne() {
        ScheduledProfilerTrigger trigger = trigger(LocalTime.of(10, 0), LocalTime.of(10, 1), LocalTime.of(10, 2), LocalTime.of(11, 0));
        assertFalse(trigger.poll(at(DAY, 9, 0, 0)).isPresent());

        // jump to just after 10:02: all three slots are due, the first two within the lateness limit
        Optional<DailySchedule.Slot> slot = trigger.poll(at(DAY, 10, 2, 30));
        assertTrue(slot.isPresent());
        assertEquals(10 * 60 + 2, slot.get().minuteOfDay());
        assertFalse(trigger.poll(at(DAY, 10, 2, 31)).isPresent());
        assertEquals(1, trigger.remainingToday().size());
    }

    @Test
    public void testOneSlotPerPoll() {
        ScheduledProfilerTrigger trigger = trigger(LocalTime.of(10, 0), LocalTime.of(10, 0), LocalTime.of(10, 1));
        assertFalse(trigger.poll(at(DAY, 9, 0, 0)).isPresent());
        assertTrue(trigger.poll(at(DAY, 10, 0, 0)).isPresent());
        assertFalse(trigger.poll(at(DAY, 10, 0, 1)).isPresent());
        assertTrue(trigger.poll(at(DAY, 10, 1, 0)).isPresent());
    }

    @Test
    public void testDayJumpDropsStaleSlots() {
        ScheduledProfilerTrigger trigger = trigger(LocalTime.of(10, 0));
        assertFalse(trigger.poll(at(DAY, 9, 0, 0)).isPresent());
        // the clock jumps forward by a day and a few hours: yesterday's slot and today's are both stale
        assertFalse(trigger.poll(at(DAY.plusDays(1), 15, 0, 0)).isPresent());
        assertTrue(trigger.remainingToday().isEmpty());
    }

    private static final class FakeClock {
        private Instant now;

        private FakeClock(Instant now) {
            this.now = now;
        }

        private Instant now() {
            return this.now;
        }

        private void advance(Duration duration) {
            this.now = this.now.plus(duration);
        }
    }
}
