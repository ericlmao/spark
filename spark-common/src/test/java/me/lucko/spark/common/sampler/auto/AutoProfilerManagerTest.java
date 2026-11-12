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

import me.lucko.spark.common.SparkPlatform;
import me.lucko.spark.common.sampler.Sampler;
import me.lucko.spark.common.sampler.auto.AutoProfilerLauncher.SessionResult;
import me.lucko.spark.common.tick.AbstractTickHook;
import me.lucko.spark.common.tick.TickHook;
import me.lucko.spark.common.util.config.Configuration;
import me.lucko.spark.common.util.config.FileConfiguration;
import me.lucko.spark.test.plugin.TestSparkPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AutoProfilerManagerTest {

    private static final String ARGS = "--force-java-sampler --save-to-file --thread * --timeout 60";
    private static final Instant START = Instant.parse("2026-06-01T10:00:30Z");

    private final MutableClock clock = new MutableClock(START);
    private final AtomicLong nanoTime = new AtomicLong(TimeUnit.HOURS.toNanos(1));

    private static Set<String> autoProfilerThreads() {
        Set<String> names = new HashSet<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && thread.getName().startsWith(AutoProfilerManager.THREAD_NAME_PREFIX)) {
                names.add(thread.getName());
            }
        }
        return names;
    }

    private static Configuration configFile(Path directory, String json) throws IOException {
        Path file = directory.resolve("auto-profiler-test.json");
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
        return new FileConfiguration(file);
    }

    private AutoProfilerManager manager(SparkPlatform platform, Configuration config) {
        return new AutoProfilerManager(platform, config, this.clock, this.nanoTime::get);
    }

    private static Configuration scheduledAtTenOhOne(Path directory) throws IOException {
        return configFile(directory, "{\"scheduledProfiler\": true, \"scheduledProfilerArgs\": \"" + ARGS + "\","
                + " \"scheduledProfilerRandomCount\": 0, \"scheduledProfilerTimes\": [\"10:01\"],"
                + " \"autoProfilerTimezone\": \"UTC\"}");
    }

    /** Advances the fake clock past the 10:01 slot and waits for the session to start. */
    private CompletableFuture<SessionResult> reachFixedTime(AutoProfilerManager manager) {
        this.clock.advance(Duration.ofSeconds(60));
        manager.poll();
        Await.until("the scheduled session to start", () -> manager.activeSession().isPresent());
        return manager.activeSession().orElseThrow(AssertionError::new);
    }

    @Test
    public void testNothingRunsWithoutConfiguration(@TempDir Path directory) {
        Set<String> before = autoProfilerThreads();
        try (LogCapture logs = new LogCapture();
             TestSparkPlugin plugin = new TestSparkPlugin(directory)) {
            AutoProfilerManager manager = plugin.platform().getAutoProfilerManager();

            assertFalse(manager.isRunning());
            assertEquals(Collections.singletonList("Automatic profilers are disabled."), manager.describeStatus());
            Set<String> after = autoProfilerThreads();
            after.removeAll(before);
            assertEquals(Collections.emptySet(), after);
            assertEquals(Collections.emptyList(), logs.messages(Level.WARNING, "[auto profiler]"));
            assertEquals(Collections.emptyList(), logs.messages(Level.INFO, "profiler times"));
        }
    }

    @Test
    public void testLagProfilerWithoutTickHook(@TempDir Path directory) {
        try (LogCapture logs = new LogCapture();
             TestSparkPlugin plugin = new TestSparkPlugin(directory, Collections.singletonMap("lagProfiler", "true"))) {
            AutoProfilerManager manager = plugin.platform().getAutoProfilerManager();

            assertFalse(manager.isRunning());
            List<String> status = manager.describeStatus();
            assertTrue(status.contains("Lag profiler disabled: this platform does not report server ticks"), status.toString());
            assertEquals(1, logs.messages(Level.WARNING, "[auto profiler]").size());
        }
    }

    @Test
    public void testFixedTimeStartsSession(@TempDir Path directory) throws Exception {
        try (LogCapture logs = new LogCapture();
             TestSparkPlugin plugin = new TestSparkPlugin(directory);
             AutoProfilerManager manager = manager(plugin.platform(), scheduledAtTenOhOne(directory))) {
            SparkPlatform platform = plugin.platform();
            manager.start();
            assertTrue(manager.isRunning());
            assertEquals(1, logs.messages(Level.INFO, "Scheduled profiler times for 2026-06-01: 10:01 (fixed)").size());
            assertFalse(manager.activeSession().isPresent());

            CompletableFuture<SessionResult> completion = reachFixedTime(manager);

            Sampler sampler = platform.getSamplerContainer().getActiveSampler();
            assertNotNull(sampler);
            assertFalse(sampler.isRunningInBackground());
            assertFalse(completion.isDone());
            assertEquals(1, logs.messages(Level.INFO, "Automatic profiler started (scheduled): scheduled at 10:01").size());
        }
    }

    @Test
    public void testExternalStopDetected(@TempDir Path directory) throws Exception {
        try (TestSparkPlugin plugin = new TestSparkPlugin(directory);
             AutoProfilerManager manager = manager(plugin.platform(), scheduledAtTenOhOne(directory))) {
            SparkPlatform platform = plugin.platform();
            manager.start();
            CompletableFuture<SessionResult> completion = reachFixedTime(manager);

            platform.getSamplerContainer().stopActiveSampler(true);
            manager.poll();

            assertEquals(SessionResult.Outcome.STOPPED_EXTERNALLY, completion.get(30, TimeUnit.SECONDS).outcome());
            assertFalse(manager.activeSession().isPresent());
            assertNull(platform.getSamplerContainer().getActiveSampler());
        }
    }

    @Test
    public void testInvalidScheduledArgsDisableOnlyScheduled(@TempDir Path directory) throws Exception {
        Configuration config = configFile(directory, "{\"scheduledProfiler\": true, \"scheduledProfilerArgs\": \"stop now\","
                + " \"lagProfiler\": true, \"lagProfilerArgs\": \"" + ARGS + "\", \"lagProfilerLongTickMs\": 0}");
        try (LogCapture logs = new LogCapture();
             TickingTestSparkPlugin plugin = new TickingTestSparkPlugin(directory);
             AutoProfilerManager manager = manager(plugin.platform(), config)) {
            manager.start();

            assertTrue(manager.isRunning());
            List<String> warnings = logs.messages(Level.WARNING, "[auto profiler]");
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.get(0).contains(AutoProfilerSettings.OPTION_SCHEDULED_ARGS));

            List<String> status = manager.describeStatus();
            assertTrue(status.contains("Scheduled profiler disabled: invalid " + AutoProfilerSettings.OPTION_SCHEDULED_ARGS), status.toString());
            assertTrue(status.get(1).startsWith("Lag profiler enabled"), status.toString());
        }
    }

    @Test
    public void testLowTpsStartsLagSession(@TempDir Path directory) throws Exception {
        Configuration config = configFile(directory, "{\"lagProfiler\": true, \"lagProfilerArgs\": \"" + ARGS + "\","
                + " \"lagProfilerSustainSeconds\": 5, \"lagProfilerStartupGraceSeconds\": 0, \"lagProfilerLongTickMs\": 0,"
                + " \"autoProfilerTimezone\": \"UTC\"}");
        try (TickingTestSparkPlugin plugin = new TickingTestSparkPlugin(directory);
             AutoProfilerManager manager = manager(plugin.platform(), config)) {
            manager.start();
            TestTickHook tickHook = (TestTickHook) plugin.platform().getTickHook();
            assertNotNull(tickHook);

            // one tick per simulated second is far below the threshold of 15 tps
            for (int i = 0; i < 30 && !manager.activeSession().isPresent(); i++) {
                tickHook.tick();
                this.nanoTime.addAndGet(TimeUnit.SECONDS.toNanos(1));
                manager.poll();
            }

            assertTrue(manager.activeSession().isPresent());
            Sampler sampler = plugin.platform().getSamplerContainer().getActiveSampler();
            assertNotNull(sampler);
            assertFalse(sampler.isRunningInBackground());
            assertTrue(manager.describeStatus().get(1).contains("last capture: 2026-06-01 10:00:30 (tps"), manager.describeStatus().toString());
        }
    }

    /**
     * A test plugin whose server ticks are driven by the test.
     */
    public static final class TickingTestSparkPlugin extends TestSparkPlugin {
        public TickingTestSparkPlugin(Path directory) {
            super(directory);
        }

        @Override
        public TickHook createTickHook() {
            return new TestTickHook();
        }
    }

    /**
     * A tick hook that ticks when told to.
     */
    public static final class TestTickHook extends AbstractTickHook {
        public void tick() {
            onTick();
        }

        @Override
        public void start() {
        }

        @Override
        public void close() {
        }
    }

    /**
     * A clock that only moves when told to.
     */
    public static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        public MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        public void advance(Duration duration) {
            this.now.updateAndGet(instant -> instant.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return this.now.get();
        }
    }
}
