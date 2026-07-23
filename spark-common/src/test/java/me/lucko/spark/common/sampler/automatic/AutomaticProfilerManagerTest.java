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

package me.lucko.spark.common.sampler.automatic;

import me.lucko.spark.common.activitylog.Activity;
import me.lucko.spark.test.plugin.TestSparkPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AutomaticProfilerManagerTest {

    @Test
    public void testAutomaticArgumentsAddRequiredFlags() {
        assertEquals(
                Arrays.asList("profiler", "start", "--thread", "*", "--ignore-sleeping", "--timeout", "300", "--save-to-file"),
                AutomaticProfilerManager.commandArguments("--thread * --ignore-sleeping")
        );
    }

    @Test
    public void testAutomaticArgumentsPreserveTimeoutAndDeduplicateSaveFlag() {
        List<String> arguments = AutomaticProfilerManager.commandArguments("--timeout 600 --save-to-file --only-ticks-over 100 --save-to-file");
        assertEquals(
                Arrays.asList("profiler", "start", "--timeout", "600", "--save-to-file", "--only-ticks-over", "100"),
                arguments
        );
    }

    @Test
    public void testTimestampParsingIsStrict() {
        assertEquals(LocalTime.of(6, 5), AutomaticProfilerManager.parseTimestamp("06:05"));
        assertNull(AutomaticProfilerManager.parseTimestamp("6:05"));
        assertNull(AutomaticProfilerManager.parseTimestamp("24:00"));
        assertNull(AutomaticProfilerManager.parseTimestamp("12:60"));
    }

    @Test
    public void testNextOccurrenceUsesConfiguredTimezone() {
        ZoneId timezone = ZoneId.of("America/Halifax");
        Clock beforeTimestamp = Clock.fixed(Instant.parse("2026-07-23T13:00:00Z"), ZoneOffset.UTC);
        Clock afterTimestamp = Clock.fixed(Instant.parse("2026-07-23T16:00:00Z"), ZoneOffset.UTC);

        assertEquals(
                ZonedDateTime.of(2026, 7, 23, 12, 0, 0, 0, timezone),
                AutomaticProfilerManager.nextOccurrence(beforeTimestamp, timezone, LocalTime.NOON)
        );
        assertEquals(
                ZonedDateTime.of(2026, 7, 24, 12, 0, 0, 0, timezone),
                AutomaticProfilerManager.nextOccurrence(afterTimestamp, timezone, LocalTime.NOON)
        );
    }

    @Test
    public void testNextOccurrenceHandlesDaylightSavingGap() {
        ZoneId timezone = ZoneId.of("America/Halifax");
        Clock clock = Clock.fixed(Instant.parse("2026-03-08T05:00:00Z"), ZoneOffset.UTC);

        ZonedDateTime next = AutomaticProfilerManager.nextOccurrence(clock, timezone, LocalTime.of(2, 30));

        assertEquals(3, next.getHour());
        assertEquals(30, next.getMinute());
        assertEquals(Instant.parse("2026-03-08T06:30:00Z"), next.toInstant());
    }

    @Test
    public void testCustomProfileFileName() {
        ZonedDateTime completedAt = ZonedDateTime.of(2026, 7, 23, 12, 0, 0, 0, ZoneOffset.UTC);

        assertEquals(
                "MyServer Automatic Profiler - 23-July-2026.sparkprofile",
                AutomaticProfilerManager.resolveProfileFileName("MyServer Automatic Profiler - {date}", completedAt)
        );
        assertEquals(
                "MyServer Automatic Profiler - 23-July-2026.sparkprofile",
                AutomaticProfilerManager.resolveProfileFileName("MyServer Automatic Profiler - {date}.sparkprofile", completedAt)
        );
        assertEquals("My_Server _ 23-July-2026.sparkprofile", AutomaticProfilerManager.resolveProfileFileName("My:Server ? {date}", completedAt));
        assertEquals("_CON.sparkprofile", AutomaticProfilerManager.resolveProfileFileName("CON", completedAt));
    }

    @Test
    public void testCustomProfileFileNameUsesConfiguredTimezoneDate() {
        ZonedDateTime halifaxCompletion = ZonedDateTime.ofInstant(
                Instant.parse("2026-07-24T01:00:00Z"),
                ZoneId.of("America/Halifax")
        );

        assertEquals(
                "MyServer - 23-July-2026.sparkprofile",
                AutomaticProfilerManager.resolveProfileFileName("MyServer - {date}", halifaxCompletion)
        );
    }

    @Test
    public void testCustomProfileFileNameValidation() {
        assertTrue(AutomaticProfilerManager.isValidFileNameTemplate("MyServer - {date}"));
        assertFalse(AutomaticProfilerManager.isValidFileNameTemplate("../outside-{date}"));
        assertFalse(AutomaticProfilerManager.isValidFileNameTemplate("profile-{time}"));
        assertFalse(AutomaticProfilerManager.isValidFileNameTemplate(".sparkprofile"));
    }

    @Test
    public void testCustomProfileFileNameCollision(@TempDir Path directory) throws Exception {
        Path first = directory.resolve("MyServer Automatic Profiler - 23-July-2026.sparkprofile");
        Path second = directory.resolve("MyServer Automatic Profiler - 23-July-2026 (2).sparkprofile");
        Files.write(first, new byte[]{1});
        Files.write(second, new byte[]{2});

        assertEquals(
                directory.resolve("MyServer Automatic Profiler - 23-July-2026 (3).sparkprofile"),
                AutomaticProfilerManager.nextAvailableFile(first)
        );
        assertEquals(1, Files.size(first));
        assertEquals(1, Files.size(second));
    }

    @Test
    public void testAutomaticProfilerSavesFile(@TempDir Path directory) throws Exception {
        Files.write(directory.resolve("config.json"), ("{\n" +
                "  \"automaticProfilers\": {\n" +
                "    \"timezone\": \"UTC\",\n" +
                "    \"profiles\": {\n" +
                "      \"file-profiler\": {\n" +
                "        \"fileName\": \"MyServer Automatic Profiler - {date}\",\n" +
                "        \"flags\": \"--timeout 11 --force-java-sampler\",\n" +
                "        \"timestamps\": [\"23:59\"]\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}").getBytes(StandardCharsets.UTF_8));

        Map<String, String> runtimeConfiguration = new HashMap<>();
        runtimeConfiguration.put("backgroundProfiler", "true");
        runtimeConfiguration.put("backgroundProfilerEngine", "java");

        try (TestSparkPlugin plugin = new TestSparkPlugin(directory, runtimeConfiguration)) {
            assertTrue(plugin.platform().getSamplerContainer().getActiveSampler().isRunningInBackground());
            assertTrue(plugin.platform().getAutomaticProfilerManager().launch("file-profiler"));

            Path profileFile = awaitProfileFile(directory);
            assertTrue(Files.size(profileFile) > 0);
            assertTrue(profileFile.getFileName().toString().matches("MyServer Automatic Profiler - \\d{2}-[A-Za-z]+-\\d{4}\\.sparkprofile"));
            assertTrue(plugin.platform().getSamplerContainer().getActiveSampler().isRunningInBackground());

            Activity activity = plugin.platform().getActivityLog().getLog().get(0);
            assertEquals(Activity.DATA_TYPE_FILE, activity.getDataType());
            assertEquals("Automatic Profiler (file-profiler)", activity.getUser().getName());
            assertEquals(profileFile.toString(), activity.getDataValue());
        }
    }

    private static Path awaitProfileFile(Path directory) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000L;
        while (System.currentTimeMillis() < deadline) {
            try (Stream<Path> files = Files.list(directory)) {
                Path profile = files
                        .filter(path -> path.getFileName().toString().endsWith(".sparkprofile"))
                        .findFirst()
                        .orElse(null);
                if (profile != null) {
                    return profile;
                }
            }
            Thread.sleep(100L);
        }
        throw new AssertionError("Automatic profiler did not create a .sparkprofile file");
    }
}
