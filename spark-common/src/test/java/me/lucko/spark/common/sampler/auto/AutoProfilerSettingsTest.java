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

import me.lucko.spark.common.util.config.Configuration;
import me.lucko.spark.common.util.config.FileConfiguration;
import me.lucko.spark.common.util.config.RuntimeConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AutoProfilerSettingsTest {

    @TempDir
    public Path directory;

    private final List<String> warnings = new ArrayList<>();

    private Configuration file(String json) throws IOException {
        Path file = this.directory.resolve("config.json");
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
        return new FileConfiguration(file);
    }

    private AutoProfilerSettings load(Configuration config) {
        return AutoProfilerSettings.load(config, this.warnings::add);
    }

    @Test
    public void testDefaultsAreOff() {
        AutoProfilerSettings settings = load(new FileConfiguration(this.directory.resolve("missing.json")));

        assertFalse(settings.scheduledEnabled());
        assertFalse(settings.lagEnabled());
        assertEquals(Arrays.asList("--timeout", "300"), settings.scheduledArgs().tokens());
        assertEquals(Arrays.asList("--timeout", "120"), settings.lagArgs().tokens());
        assertEquals(3, settings.scheduledRandomCount());
        assertEquals("00:00-24:00", settings.scheduledWindows().get(0).toString());
        assertTrue(settings.scheduledTimes().isEmpty());
        assertEquals(60, settings.scheduledMinSpacingMinutes());
        assertEquals(15d, settings.lagTps());
        assertEquals(20, settings.lagSustainSeconds());
        assertEquals(2000, settings.lagLongTickMs());
        assertEquals(1, settings.lagLongTickCount());
        assertEquals(30, settings.lagCooldownMinutes());
        assertEquals(5, settings.lagMaxPerDay());
        assertEquals(120, settings.lagStartupGraceSeconds());
        assertTrue(settings.lagUploadBackground());
        assertEquals(ZoneId.systemDefault(), settings.zone());
        assertTrue(settings.broadcast());
        assertEquals(Collections.emptyList(), this.warnings);
    }

    @Test
    public void testListsFromJsonArray() throws IOException {
        AutoProfilerSettings settings = load(file("{\"scheduledProfiler\": true,"
                + " \"scheduledProfilerWindows\": [\"10:00-14:00\", \"17:00-23:30\"],"
                + " \"scheduledProfilerTimes\": [\"04:00\"]}"));

        assertEquals(2, settings.scheduledWindows().size());
        assertEquals("10:00-14:00", settings.scheduledWindows().get(0).toString());
        assertEquals("17:00-23:30", settings.scheduledWindows().get(1).toString());
        assertEquals(Collections.singletonList(LocalTime.of(4, 0)), settings.scheduledTimes());
        assertEquals(Collections.emptyList(), this.warnings);
    }

    @Test
    public void testListsFromCommaString() throws IOException {
        AutoProfilerSettings settings = load(file("{\"scheduledProfiler\": true,"
                + " \"scheduledProfilerWindows\": \"10:00-14:00, 17:00-23:30\","
                + " \"scheduledProfilerTimes\": \" 04:00 ,05:30\"}"));

        assertEquals(2, settings.scheduledWindows().size());
        assertEquals("17:00-23:30", settings.scheduledWindows().get(1).toString());
        assertEquals(Arrays.asList(LocalTime.of(4, 0), LocalTime.of(5, 30)), settings.scheduledTimes());
    }

    @Test
    public void testListFromSystemPropertyAndFileArrayWins() throws IOException {
        String property = "spark." + AutoProfilerSettings.OPTION_SCHEDULED_TIMES;
        System.setProperty(property, "06:00, 07:15");
        try {
            AutoProfilerSettings fromProperty = load(RuntimeConfiguration.SYSTEM_PROPERTIES);
            assertEquals(Arrays.asList(LocalTime.of(6, 0), LocalTime.of(7, 15)), fromProperty.scheduledTimes());

            Configuration combined = Configuration.combining(
                    RuntimeConfiguration.SYSTEM_PROPERTIES,
                    file("{\"scheduledProfilerTimes\": [\"04:00\"]}")
            );
            assertEquals(Collections.singletonList(LocalTime.of(4, 0)), load(combined).scheduledTimes());
        } finally {
            System.clearProperty(property);
        }
    }

    @Test
    public void testDecimalTpsFromString() throws IOException {
        AutoProfilerSettings settings = load(file("{\"lagProfiler\": true, \"lagProfilerTps\": \"17.5\"}"));
        assertEquals(17.5d, settings.lagTps());
    }

    @Test
    public void testDecimalTpsFromNumber() throws IOException {
        assertEquals(16.5d, load(file("{\"lagProfiler\": true, \"lagProfilerTps\": 16.5}")).lagTps());
        assertEquals(16d, load(file("{\"lagProfiler\": true, \"lagProfilerTps\": 16}")).lagTps());
        assertEquals(Collections.emptyList(), this.warnings);
    }

    @Test
    public void testInvalidTpsFallsBackWithWarning() throws IOException {
        AutoProfilerSettings settings = load(file("{\"lagProfiler\": true, \"lagProfilerTps\": \"fast\"}"));
        assertEquals(15d, settings.lagTps());
        assertEquals(1, this.warnings.size());
        assertTrue(this.warnings.get(0).contains(AutoProfilerSettings.OPTION_LAG_TPS));
    }

    @Test
    public void testDefaultTimeoutAppendedOnce() throws IOException {
        AutoProfilerSettings settings = load(file("{\"scheduledProfiler\": true, \"scheduledProfilerArgs\": \"--thread *\"}"));

        assertEquals(Arrays.asList("--thread", "*", "--timeout", "300"), settings.scheduledArgs().tokens());
        assertFalse(settings.scheduledArgs().error().isPresent());
        assertEquals(1, this.warnings.size());
        assertTrue(this.warnings.get(0).contains(AutoProfilerSettings.OPTION_SCHEDULED_ARGS));
    }

    @Test
    public void testLeadingStartStripped() throws IOException {
        AutoProfilerSettings settings = load(file("{\"lagProfiler\": true, \"lagProfilerArgs\": \"start --only-ticks-over 100 --timeout 180\"}"));

        assertEquals(Arrays.asList("--only-ticks-over", "100", "--timeout", "180"), settings.lagArgs().tokens());
        assertFalse(settings.lagArgs().error().isPresent());
        assertEquals(Collections.emptyList(), this.warnings);
    }

    @Test
    public void testOtherSubcommandRejected() throws IOException {
        AutoProfilerSettings settings = load(file("{\"scheduledProfiler\": true, \"scheduledProfilerArgs\": \"stop --timeout 60\"}"));

        AutoProfilerSettings.ProfilerArgs args = settings.scheduledArgs();
        assertTrue(args.error().isPresent());
        assertTrue(args.error().get().contains("stop"));
        assertTrue(args.tokens().isEmpty());
        assertEquals(AutoProfilerSettings.OPTION_SCHEDULED_ARGS, args.key());
    }

    @Test
    public void testBadZoneFallsBack() throws IOException {
        AutoProfilerSettings settings = load(file("{\"scheduledProfiler\": true, \"autoProfilerTimezone\": \"Mars/Olympus\"}"));

        assertEquals(ZoneId.systemDefault(), settings.zone());
        assertEquals(1, this.warnings.size());
        assertTrue(this.warnings.get(0).contains(AutoProfilerSettings.OPTION_TIMEZONE));
    }

    @Test
    public void testValidZone() throws IOException {
        AutoProfilerSettings settings = load(file("{\"scheduledProfiler\": true, \"autoProfilerTimezone\": \"Europe/London\"}"));
        assertEquals(ZoneId.of("Europe/London"), settings.zone());
    }

    @Test
    public void testBadWindowSkippedWithWarning() throws IOException {
        AutoProfilerSettings settings = load(file("{\"scheduledProfiler\": true,"
                + " \"scheduledProfilerWindows\": [\"10:00-14:00\", \"25:00-26:00\"],"
                + " \"scheduledProfilerTimes\": [\"nope\", \"08:00\"]}"));

        assertEquals(1, settings.scheduledWindows().size());
        assertEquals("10:00-14:00", settings.scheduledWindows().get(0).toString());
        assertEquals(Collections.singletonList(LocalTime.of(8, 0)), settings.scheduledTimes());
        assertEquals(2, this.warnings.size());
        assertTrue(this.warnings.get(0).contains(AutoProfilerSettings.OPTION_SCHEDULED_WINDOWS));
        assertTrue(this.warnings.get(1).contains(AutoProfilerSettings.OPTION_SCHEDULED_TIMES));
    }

    @Test
    public void testNoWarningsForDisabledFeatures() throws IOException {
        load(file("{\"scheduledProfilerWindows\": [\"bad\"], \"lagProfilerTps\": \"bad\"}"));
        assertEquals(Collections.emptyList(), this.warnings);
    }

    @Test
    public void testSustainSecondsClamped() throws IOException {
        assertEquals(5, load(file("{\"lagProfilerSustainSeconds\": 1}")).lagSustainSeconds());
        assertEquals(600, load(file("{\"lagProfilerSustainSeconds\": 10000}")).lagSustainSeconds());
        assertEquals(45, load(file("{\"lagProfilerSustainSeconds\": 45}")).lagSustainSeconds());
    }
}
