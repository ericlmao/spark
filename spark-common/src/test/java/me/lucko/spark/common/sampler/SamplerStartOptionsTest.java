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

package me.lucko.spark.common.sampler;

import me.lucko.spark.common.command.Arguments;
import me.lucko.spark.common.sampler.java.MergeStrategy;
import me.lucko.spark.test.plugin.TestSparkPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SamplerStartOptionsTest {

    private static Arguments args(String... args) {
        return new Arguments(Arrays.asList(args), false);
    }

    @Test
    public void testParseAllThreads(@TempDir Path directory) {
        try (TestSparkPlugin plugin = new TestSparkPlugin(directory)) {
            SamplerStartOptions options = SamplerStartOptions.parse(plugin.platform(), args("--thread", "*", "--timeout", "300"));
            assertEquals(300, options.timeoutSeconds());
            assertSame(ThreadDumper.ALL, options.threadDumper());
            assertEquals(SamplerMode.EXECUTION, options.mode());
            assertEquals(SamplerMode.EXECUTION.defaultInterval(), options.interval());
            assertFalse(options.saveToFile());
            assertFalse(options.comment().isPresent());
            assertEquals(MergeStrategy.SAME_METHOD, options.mergeStrategy());
        }
    }

    @Test
    public void testParseDefaultsAndExportFlags(@TempDir Path directory) {
        try (TestSparkPlugin plugin = new TestSparkPlugin(directory)) {
            SamplerStartOptions options = SamplerStartOptions.parse(plugin.platform(),
                    args("--alloc", "--interval", "2", "--save-to-file", "--comment", "hello world", "--separate-parent-calls", "--thread", "Server thread"));
            assertEquals(-1, options.timeoutSeconds());
            assertEquals(SamplerMode.ALLOCATION, options.mode());
            assertEquals(2d, options.interval());
            assertTrue(options.saveToFile());
            assertEquals("hello world", options.comment().orElse(null));
            assertEquals(MergeStrategy.SEPARATE_PARENT_CALLS, options.mergeStrategy());
            assertInstanceOf(ThreadDumper.Specific.class, options.threadDumper());
            assertEquals(-1, options.ticksOver());
        }
    }

    @Test
    public void testTimeoutTooShort(@TempDir Path directory) {
        try (TestSparkPlugin plugin = new TestSparkPlugin(directory)) {
            Arguments.ParseException e = assertThrowsExactly(Arguments.ParseException.class,
                    () -> SamplerStartOptions.parse(plugin.platform(), args("--timeout", "10")));
            assertEquals("The specified timeout is not long enough for accurate results to be formed. " +
                    "Please choose a value greater than 10.", e.getMessage());
        }
    }

    @Test
    public void testAllocAndLock(@TempDir Path directory) {
        try (TestSparkPlugin plugin = new TestSparkPlugin(directory)) {
            Arguments.ParseException e = assertThrowsExactly(Arguments.ParseException.class,
                    () -> SamplerStartOptions.parse(plugin.platform(), args("--alloc", "--lock")));
            assertEquals("The --alloc and --lock flags cannot be used together. Please choose one profiling mode.", e.getMessage());
        }
    }

    @Test
    public void testOnlyTicksOverWithoutTickHook(@TempDir Path directory) {
        try (TestSparkPlugin plugin = new TestSparkPlugin(directory)) {
            Arguments.ParseException e = assertThrowsExactly(Arguments.ParseException.class,
                    () -> SamplerStartOptions.parse(plugin.platform(), args("--only-ticks-over", "50")));
            assertEquals("Tick counting is not supported!", e.getMessage());
        }
    }

    @Test
    public void testCreateBuilderReturnsDistinctBuilders(@TempDir Path directory) {
        try (TestSparkPlugin plugin = new TestSparkPlugin(directory)) {
            SamplerStartOptions options = SamplerStartOptions.parse(plugin.platform(), args("--timeout", "60"));
            assertNotSame(options.createBuilder(), options.createBuilder());
        }
    }

    @Test
    public void testKnownFlags() {
        assertTrue(SamplerStartOptions.KNOWN_FLAGS.contains("thread"));
        assertTrue(SamplerStartOptions.KNOWN_FLAGS.contains("save-to-file"));
        assertFalse(SamplerStartOptions.KNOWN_FLAGS.contains("threads"));
    }
}
