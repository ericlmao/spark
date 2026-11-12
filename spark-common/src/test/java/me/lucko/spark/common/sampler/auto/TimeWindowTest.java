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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TimeWindowTest {

    @Test
    public void testSimpleWindow() {
        TimeWindow window = TimeWindow.parse("10:00-14:00");
        assertFalse(window.contains(9 * 60 + 59));
        assertTrue(window.contains(10 * 60));
        assertTrue(window.contains(13 * 60 + 59));
        assertFalse(window.contains(14 * 60));
        assertEquals(240, window.minutes().size());
        assertEquals("10:00-14:00", window.toString());
    }

    @Test
    public void testFullDayWithEndOfDay() {
        TimeWindow window = TimeWindow.parse("00:00-24:00");
        assertEquals(TimeWindow.MINUTES_PER_DAY, window.minutes().size());
        assertTrue(window.contains(0));
        assertTrue(window.contains(1439));
    }

    @Test
    public void testOvernightWindow() {
        TimeWindow window = TimeWindow.parse("22:00-02:00");
        assertTrue(window.contains(22 * 60));
        assertTrue(window.contains(23 * 60 + 59));
        assertTrue(window.contains(0));
        assertTrue(window.contains(60 + 59));
        assertFalse(window.contains(2 * 60));
        assertFalse(window.contains(12 * 60));

        List<Integer> minutes = window.minutes();
        assertEquals(240, minutes.size());
        assertEquals(0, minutes.get(0));
        assertEquals(1439, minutes.get(minutes.size() - 1));
    }

    @Test
    public void testParseTime() {
        assertEquals(LocalTime.of(4, 0), TimeWindow.parseTime("04:00"));
        assertEquals(LocalTime.of(9, 5), TimeWindow.parseTime(" 9:05 "));
        assertThrows(IllegalArgumentException.class, () -> TimeWindow.parseTime("24:00"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "10:00", "10:00-", "-10:00", "10-12", "25:00-26:00", "10:60-11:00",
            "10:00-10:00", "24:00-10:00", "10:00-12:00-14:00", "ab:cd-ef:gh"})
    public void testInvalid(String value) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> TimeWindow.parse(value));
        assertTrue(e.getMessage().startsWith("Invalid time"), e.getMessage());
    }
}
