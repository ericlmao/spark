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

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Plans the start times of scheduled profilers for a single day.
 */
public final class DailySchedule {

    private DailySchedule() {
    }

    /**
     * Plans the slots for a day.
     *
     * <p>Fixed times are always included. Random slots are then drawn one at a time,
     * uniformly from the window minutes that are at least {@code minSpacingMinutes} away
     * from every slot placed so far. If fewer than {@code randomCount} fit, the slots
     * that fit are returned.</p>
     *
     * @param date the local date
     * @param zone the time zone of the date
     * @param fixed the fixed times
     * @param windows the windows random slots are drawn from
     * @param randomCount the number of random slots wanted
     * @param minSpacingMinutes the minimum spacing between a random slot and any other slot
     * @param random the source of randomness
     * @return the slots, sorted by time
     */
    public static List<Slot> plan(LocalDate date, ZoneId zone, List<LocalTime> fixed, List<TimeWindow> windows,
                                  int randomCount, int minSpacingMinutes, Random random) {
        List<Integer> placed = new ArrayList<>();
        List<Slot> slots = new ArrayList<>();

        for (LocalTime time : fixed) {
            int minute = time.getHour() * 60 + time.getMinute();
            if (!placed.contains(minute)) {
                placed.add(minute);
                slots.add(createSlot(date, zone, minute, false));
            }
        }

        boolean[] candidates = windowMinutes(windows);
        for (int i = 0; i < randomCount; i++) {
            List<Integer> eligible = eligibleMinutes(candidates, placed, minSpacingMinutes);
            if (eligible.isEmpty()) {
                break;
            }
            int minute = eligible.get(random.nextInt(eligible.size()));
            placed.add(minute);
            slots.add(createSlot(date, zone, minute, true));
        }

        slots.sort(Comparator.comparing(Slot::time).thenComparingInt(Slot::minuteOfDay));
        return Collections.unmodifiableList(slots);
    }

    /**
     * Derives the random seed for a day, so that a restart reproduces the same plan.
     *
     * @param date the local date
     * @param installSeed a seed stable for this installation
     * @return the seed
     */
    public static long seedFor(LocalDate date, long installSeed) {
        return installSeed * 1_000_003L + date.toEpochDay() * 0x9E3779B97F4A7C15L;
    }

    private static boolean[] windowMinutes(List<TimeWindow> windows) {
        boolean[] minutes = new boolean[TimeWindow.MINUTES_PER_DAY];
        for (TimeWindow window : windows) {
            for (int minute : window.minutes()) {
                minutes[minute] = true;
            }
        }
        return minutes;
    }

    private static List<Integer> eligibleMinutes(boolean[] candidates, List<Integer> placed, int minSpacingMinutes) {
        List<Integer> eligible = new ArrayList<>();
        for (int minute = 0; minute < candidates.length; minute++) {
            if (candidates[minute] && isSpaced(minute, placed, minSpacingMinutes)) {
                eligible.add(minute);
            }
        }
        return eligible;
    }

    private static boolean isSpaced(int minute, List<Integer> placed, int minSpacingMinutes) {
        for (int other : placed) {
            if (Math.abs(minute - other) < Math.max(minSpacingMinutes, 1)) {
                return false;
            }
        }
        return true;
    }

    private static Slot createSlot(LocalDate date, ZoneId zone, int minuteOfDay, boolean random) {
        LocalTime time = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60);
        // ZonedDateTime.of moves a local time inside a DST gap forward by the length of the gap
        Instant instant = ZonedDateTime.of(date, time, zone).toInstant();
        return new Slot(instant, minuteOfDay, random);
    }

    /**
     * A planned profiler start.
     */
    public static final class Slot {
        private final Instant time;
        private final int minuteOfDay;
        private final boolean random;

        public Slot(Instant time, int minuteOfDay, boolean random) {
            this.time = time;
            this.minuteOfDay = minuteOfDay;
            this.random = random;
        }

        public Instant time() {
            return this.time;
        }

        public int minuteOfDay() {
            return this.minuteOfDay;
        }

        public boolean random() {
            return this.random;
        }

        /**
         * Formats the planned local time as {@code HH:mm}.
         *
         * @return the formatted time
         */
        public String formatLocalTime() {
            return String.format(Locale.ROOT, "%02d:%02d", this.minuteOfDay / 60, this.minuteOfDay % 60);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Slot)) {
                return false;
            }
            Slot slot = (Slot) o;
            return this.minuteOfDay == slot.minuteOfDay && this.random == slot.random && this.time.equals(slot.time);
        }

        @Override
        public int hashCode() {
            return (this.time.hashCode() * 31 + this.minuteOfDay) * 31 + (this.random ? 1 : 0);
        }

        @Override
        public String toString() {
            return formatLocalTime() + (this.random ? " (random)" : " (fixed)");
        }
    }
}
