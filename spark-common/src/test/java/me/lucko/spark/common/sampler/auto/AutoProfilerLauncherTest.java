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
import me.lucko.spark.common.activitylog.Activity;
import me.lucko.spark.common.sampler.Sampler;
import me.lucko.spark.common.sampler.auto.AutoProfilerLauncher.SessionResult;
import me.lucko.spark.common.sampler.auto.AutoProfilerLauncher.StartResult;
import me.lucko.spark.common.sampler.auto.AutoProfilerLauncher.Trigger;
import me.lucko.spark.common.util.SparkScheduledThreadPoolExecutor;
import me.lucko.spark.common.util.SparkThreadFactory;
import me.lucko.spark.test.plugin.TestCommandSender;
import me.lucko.spark.test.plugin.TestSparkPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AutoProfilerLauncherTest {

    private static final List<String> ARGS = Arrays.asList("--force-java-sampler", "--save-to-file", "--thread", "*", "--timeout", "60");

    private final ScheduledExecutorService callbackExecutor =
            new SparkScheduledThreadPoolExecutor(1, new SparkThreadFactory("spark-test-auto-callback", true));

    @AfterEach
    public void shutdownExecutor() {
        this.callbackExecutor.shutdownNow();
    }

    private static Map<String, String> config(boolean background) {
        Map<String, String> config = new HashMap<>();
        // nothing listens here, so an upload fails at once and the result is saved to a file
        config.put("bytebinUrl", "http://127.0.0.1:1/");
        if (background) {
            config.put("backgroundProfiler", "true");
            config.put("backgroundProfilerEngine", "java");
        }
        return config;
    }

    private AutoProfilerLauncher launcher(SparkPlatform platform) {
        return new AutoProfilerLauncher(platform, this.callbackExecutor, true);
    }

    /** Ends the sampler the way its own timeout does. */
    private static void finishLikeTimeout(Sampler sampler) throws InterruptedException {
        Thread.sleep(1000);
        sampler.stop(false);
        sampler.getFuture().complete(sampler);
    }

    private static Optional<Activity> findActivity(SparkPlatform platform, String type) {
        for (Activity activity : platform.getActivityLog().getLog()) {
            if (activity.getType().equals(type)) {
                return Optional.of(activity);
            }
        }
        return Optional.empty();
    }

    @Test
    public void testScheduledSessionSavesResult(@TempDir Path directory) throws Exception {
        try (TestSparkPlugin plugin = new TestSparkPlugin(directory, config(false));
             LogCapture logs = new LogCapture()) {
            SparkPlatform platform = plugin.platform();

            StartResult result = launcher(platform).start(Trigger.SCHEDULED, ARGS, "test reason", false);
            assertEquals(StartResult.Status.STARTED, result.status(), result.message());
            Sampler sampler = result.sampler().orElseThrow(AssertionError::new);
            assertSame(sampler, platform.getSamplerContainer().getActiveSampler());
            assertFalse(sampler.isRunningInBackground());
            assertEquals(1, logs.messages(Level.INFO, "Automatic profiler started (scheduled): test reason").size());

            finishLikeTimeout(sampler);
            SessionResult session = result.completion().orElseThrow(AssertionError::new).get(30, TimeUnit.SECONDS);

            assertEquals(SessionResult.Outcome.SAVED, session.outcome(), session.toString());
            Path file = session.file().orElseThrow(AssertionError::new);
            assertTrue(Files.exists(file));
            assertEquals(directory.toAbsolutePath(), file.toAbsolutePath().getParent());
            assertTrue(file.getFileName().toString().startsWith("profile-scheduled-"));

            Activity activity = findActivity(platform, "Profiler (scheduled)").orElseThrow(AssertionError::new);
            assertEquals(Activity.DATA_TYPE_FILE, activity.getDataType());
            assertEquals(file.toString(), activity.getDataValue());
            assertEquals("spark (scheduled)", activity.getUser().getName());

            assertEquals(1, logs.messages(Level.INFO, file.toString()).size());
            assertNull(platform.getSamplerContainer().getActiveSampler());
        }
    }

    @Test
    public void testSkippedWhenUserProfilerActive(@TempDir Path directory) throws Exception {
        try (TestSparkPlugin plugin = new TestSparkPlugin(directory, config(false))) {
            SparkPlatform platform = plugin.platform();
            platform.executeCommand(TestCommandSender.INSTANCE,
                    new String[]{"profiler", "start", "--force-java-sampler", "--timeout", "60"}).get(30, TimeUnit.SECONDS);
            Sampler userSampler = platform.getSamplerContainer().getActiveSampler();
            assertNotNull(userSampler);

            StartResult result = launcher(platform).start(Trigger.SCHEDULED, ARGS, "test", false);

            assertEquals(StartResult.Status.SKIPPED_PROFILER_ACTIVE, result.status());
            assertFalse(result.sampler().isPresent());
            assertFalse(result.completion().isPresent());
            assertSame(userSampler, platform.getSamplerContainer().getActiveSampler());
            assertFalse(userSampler.getFuture().isDone());
        }
    }

    @Test
    public void testReplacesAndRestartsBackgroundProfiler(@TempDir Path directory) throws Exception {
        try (TestSparkPlugin plugin = new TestSparkPlugin(directory, config(true))) {
            SparkPlatform platform = plugin.platform();
            Sampler background = platform.getSamplerContainer().getActiveSampler();
            assertNotNull(background);
            assertTrue(background.isRunningInBackground());

            StartResult result = launcher(platform).start(Trigger.SCHEDULED, ARGS, "test", false);
            assertEquals(StartResult.Status.STARTED, result.status(), result.message());
            Sampler sampler = result.sampler().orElseThrow(AssertionError::new);
            assertSame(sampler, platform.getSamplerContainer().getActiveSampler());

            finishLikeTimeout(sampler);
            SessionResult session = result.completion().orElseThrow(AssertionError::new).get(30, TimeUnit.SECONDS);
            assertEquals(SessionResult.Outcome.SAVED, session.outcome(), session.toString());

            Sampler restarted = platform.getSamplerContainer().getActiveSampler();
            assertNotNull(restarted);
            assertTrue(restarted.isRunningInBackground());
            assertNotSame(background, restarted);
            assertFalse(findActivity(platform, "Profiler (before lag)").isPresent());
        }
    }

    @Test
    public void testLagSessionExportsReplacedBackgroundProfiler(@TempDir Path directory) throws Exception {
        try (TestSparkPlugin plugin = new TestSparkPlugin(directory, config(true))) {
            SparkPlatform platform = plugin.platform();
            assertNotNull(platform.getSamplerContainer().getActiveSampler());

            StartResult result = launcher(platform).start(Trigger.LAG, ARGS, "tps 3.0 over 20s", true);
            assertEquals(StartResult.Status.STARTED, result.status(), result.message());
            Sampler sampler = result.sampler().orElseThrow(AssertionError::new);

            Await.until("the before lag export", () -> findActivity(platform, "Profiler (before lag)").isPresent());
            Activity beforeLag = findActivity(platform, "Profiler (before lag)").orElseThrow(AssertionError::new);
            assertTrue(Files.exists(Paths.get(beforeLag.getDataValue())));
            assertTrue(beforeLag.getDataValue().contains("profile-before-lag-"));

            finishLikeTimeout(sampler);
            SessionResult session = result.completion().orElseThrow(AssertionError::new).get(30, TimeUnit.SECONDS);
            assertEquals(SessionResult.Outcome.SAVED, session.outcome(), session.toString());
            assertTrue(findActivity(platform, "Profiler (lag)").isPresent());
            assertTrue(session.file().orElseThrow(AssertionError::new).getFileName().toString().startsWith("profile-lag-"));
        }
    }

    @Test
    public void testInvalidArgumentsFail(@TempDir Path directory) throws Exception {
        try (TestSparkPlugin plugin = new TestSparkPlugin(directory, config(true))) {
            SparkPlatform platform = plugin.platform();
            Sampler background = platform.getSamplerContainer().getActiveSampler();
            assertNotNull(background);

            StartResult result = launcher(platform).start(Trigger.SCHEDULED, Arrays.asList("--timeout", "5"), "test", false);

            assertEquals(StartResult.Status.FAILED, result.status());
            assertTrue(result.message().contains("timeout"), result.message());
            assertFalse(result.sampler().isPresent());
            assertSame(background, platform.getSamplerContainer().getActiveSampler());
            assertFalse(background.getFuture().isDone());
        }
    }
}
