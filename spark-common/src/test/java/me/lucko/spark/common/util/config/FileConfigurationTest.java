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

package me.lucko.spark.common.util.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FileConfigurationTest {

    @Test
    public void testAutomaticProfilerDefaults(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("config.json");

        FileConfiguration configuration = new FileConfiguration(file);

        assertTrue(Files.exists(file));
        assertFalse(configuration.getBoolean("automaticProfilers.enabled", true));
        assertEquals(ZoneId.systemDefault().getId(), configuration.getString("automaticProfilers.timezone", ""));
        assertEquals("", configuration.getString("automaticProfilers.discordWebhook", "missing"));
        assertEquals("MyServer Automatic Profiler - {date}", configuration.getString("automaticProfilers.profiles.normal-profiler.fileName", ""));
        assertEquals("--timeout 300 --only-ticks-over 100", configuration.getString("automaticProfilers.profiles.normal-profiler.flags", ""));
        assertEquals(Arrays.asList("00:00", "12:00"), configuration.getStringList("automaticProfilers.profiles.normal-profiler.timestamps"));
        assertEquals("MyServer Async Profiler - {date}", configuration.getString("automaticProfilers.profiles.async-profiler.fileName", ""));
        assertEquals(Arrays.asList("06:00", "18:00"), configuration.getStringList("automaticProfilers.profiles.async-profiler.timestamps"));
    }

    @Test
    public void testAutomaticProfilerDefaultsAreAddedToExistingConfiguration(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("config.json");
        Files.write(file, ("{\n" +
                "  \"_header\": \"spark configuration file - https://spark.lucko.me/docs/Configuration\",\n" +
                "  \"backgroundProfiler\": true\n" +
                "}").getBytes(StandardCharsets.UTF_8));

        FileConfiguration configuration = new FileConfiguration(file);
        FileConfiguration reloadedConfiguration = new FileConfiguration(file);

        assertTrue(configuration.getBoolean("backgroundProfiler", false));
        assertFalse(configuration.getBoolean("automaticProfilers.enabled", true));
        assertEquals("MyServer Automatic Profiler - {date}", reloadedConfiguration.getString("automaticProfilers.profiles.normal-profiler.fileName", ""));
        assertEquals(Arrays.asList("00:00", "12:00"), reloadedConfiguration.getStringList("automaticProfilers.profiles.normal-profiler.timestamps"));
    }

    @Test
    public void testNestedConfiguration(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("config.json");
        Files.write(file, ("{\n" +
                "  \"automaticProfilers\": {\n" +
                "    \"timezone\": \"America/Halifax\",\n" +
                "    \"profiles\": {\n" +
                "      \"normal-profiler\": {\n" +
                "        \"fileName\": \"MyServer Automatic Profiler - {date}\",\n" +
                "        \"flags\": \"--timeout 300\",\n" +
                "        \"timestamps\": [\"00:00\", \"12:00\"]\n" +
                "      },\n" +
                "      \"async-profiler\": {\"timestamps\": [\"06:00\"]}\n" +
                "    }\n" +
                "  }\n" +
                "}").getBytes(StandardCharsets.UTF_8));

        FileConfiguration configuration = new FileConfiguration(file);

        assertEquals("America/Halifax", configuration.getString("automaticProfilers.timezone", ""));
        assertEquals("MyServer Automatic Profiler - {date}", configuration.getString("automaticProfilers.profiles.normal-profiler.fileName", ""));
        assertEquals("--timeout 300", configuration.getString("automaticProfilers.profiles.normal-profiler.flags", ""));
        assertEquals(Arrays.asList("00:00", "12:00"), configuration.getStringList("automaticProfilers.profiles.normal-profiler.timestamps"));
        assertEquals(new LinkedHashSet<>(Arrays.asList("normal-profiler", "async-profiler")), configuration.getKeys("automaticProfilers.profiles"));
        assertTrue(configuration.contains("automaticProfilers.profiles.async-profiler"));
    }
}
