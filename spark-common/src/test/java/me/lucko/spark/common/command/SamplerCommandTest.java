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

package me.lucko.spark.common.command;

import me.lucko.spark.common.SparkPlatform;
import me.lucko.spark.common.sampler.Sampler;
import me.lucko.spark.test.plugin.TestCommandSender;
import me.lucko.spark.test.plugin.TestSparkPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class SamplerCommandTest {

    @Test
    public void testInvalidTimeoutDoesNotStart(@TempDir Path directory) throws Exception {
        try (TestSparkPlugin plugin = new TestSparkPlugin(directory)) {
            SparkPlatform platform = plugin.platform();
            platform.executeCommand(TestCommandSender.INSTANCE, new String[]{"profiler", "start", "--timeout", "5"}).get(30, TimeUnit.SECONDS);
            assertNull(platform.getSamplerContainer().getActiveSampler());
        }
    }

    @Test
    public void testStartAndCancel(@TempDir Path directory) throws Exception {
        try (TestSparkPlugin plugin = new TestSparkPlugin(directory)) {
            SparkPlatform platform = plugin.platform();
            platform.executeCommand(TestCommandSender.INSTANCE, new String[]{"profiler", "start", "--force-java-sampler", "--timeout", "60"}).get(30, TimeUnit.SECONDS);

            Sampler sampler = platform.getSamplerContainer().getActiveSampler();
            assertNotNull(sampler);
            assertFalse(sampler.isRunningInBackground());
            assertNotEquals(-1, sampler.getAutoEndTime());

            platform.executeCommand(TestCommandSender.INSTANCE, new String[]{"profiler", "cancel"}).get(30, TimeUnit.SECONDS);
            assertNull(platform.getSamplerContainer().getActiveSampler());
        }
    }
}
