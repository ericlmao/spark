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

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A daily window of local time in {@code HH:mm-HH:mm} form, at minute resolution.
 *
 * <p>The end is exclusive and may be {@code 24:00}. A window whose end is before its
 * start wraps past midnight and covers the end of the day plus the start of the day.</p>
 */
public final class TimeWindow {

    public static final int MINUTES_PER_DAY = 24 * 60;

    private static final Pattern TIME_PATTERN = Pattern.compile("^(\\d{1,2}):(\\d{2})$");

    private final String text;
    /** Covered minutes of day, as [start, end) pairs */
    private final int[][] ranges;

    private TimeWindow(String text, int[][] ranges) {
        this.text = text;
        this.ranges = ranges;
    }

    /**
     * Parses a window such as {@code 10:00-14:00} or {@code 22:00-02:00}.
     *
     * @param value the window
     * @return the parsed window
     * @throws IllegalArgumentException if the value is not a valid window
     */
    public static TimeWindow parse(String value) throws IllegalArgumentException {
        String trimmed = value.trim();
        String[] parts = trimmed.split("-", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid time window '" + value + "': expected HH:mm-HH:mm");
        }

        int start = parseMinuteOfDay(parts[0], false);
        int end = parseMinuteOfDay(parts[1], true);
        if (start == end) {
            throw new IllegalArgumentException("Invalid time window '" + value + "': start and end are the same");
        }

        int[][] ranges = start < end
                ? new int[][]{{start, end}}
                : new int[][]{{start, MINUTES_PER_DAY}, {0, end}};
        return new TimeWindow(trimmed, ranges);
    }

    /**
     * Parses a local time in {@code HH:mm} form.
     *
     * @param value the time
     * @return the time
     * @throws IllegalArgumentException if the value is not a valid time
     */
    public static LocalTime parseTime(String value) throws IllegalArgumentException {
        int minute = parseMinuteOfDay(value, false);
        return LocalTime.of(minute / 60, minute % 60);
    }

    private static int parseMinuteOfDay(String value, boolean allowEndOfDay) {
        Matcher matcher = TIME_PATTERN.matcher(value.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid time '" + value + "': expected HH:mm");
        }

        int hour = Integer.parseInt(matcher.group(1));
        int minute = Integer.parseInt(matcher.group(2));
        if (allowEndOfDay && hour == 24 && minute == 0) {
            return MINUTES_PER_DAY;
        }
        if (hour > 23 || minute > 59) {
            throw new IllegalArgumentException("Invalid time '" + value + "': out of range");
        }
        return hour * 60 + minute;
    }

    /**
     * Gets if the given minute of the day is inside this window.
     *
     * @param minuteOfDay the minute of the day, 0 to 1439
     * @return true if inside
     */
    public boolean contains(int minuteOfDay) {
        for (int[] range : this.ranges) {
            if (minuteOfDay >= range[0] && minuteOfDay < range[1]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gets every minute of the day covered by this window, in ascending order.
     *
     * @return the covered minutes
     */
    public List<Integer> minutes() {
        List<Integer> minutes = new ArrayList<>();
        for (int minute = 0; minute < MINUTES_PER_DAY; minute++) {
            if (contains(minute)) {
                minutes.add(minute);
            }
        }
        return Collections.unmodifiableList(minutes);
    }

    @Override
    public String toString() {
        return this.text;
    }
}
