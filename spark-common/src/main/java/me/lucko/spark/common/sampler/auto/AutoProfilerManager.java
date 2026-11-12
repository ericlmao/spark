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
import me.lucko.spark.common.command.Arguments;
import me.lucko.spark.common.platform.PlatformInfo;
import me.lucko.spark.common.sampler.Sampler;
import me.lucko.spark.common.sampler.SamplerStartOptions;
import me.lucko.spark.common.sampler.auto.AutoProfilerLauncher.SessionResult;
import me.lucko.spark.common.sampler.auto.AutoProfilerLauncher.StartResult;
import me.lucko.spark.common.sampler.auto.AutoProfilerLauncher.Trigger;
import me.lucko.spark.common.tick.TickHook;
import me.lucko.spark.common.tick.TickReporter;
import me.lucko.spark.common.util.SparkScheduledThreadPoolExecutor;
import me.lucko.spark.common.util.SparkThreadFactory;
import me.lucko.spark.common.util.config.Configuration;
import org.jetbrains.annotations.VisibleForTesting;
import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Level;

/**
 * Runs the scheduled and lag-triggered profilers.
 *
 * <p>All mutable state is guarded by this object's monitor. The poll normally runs on the
 * single {@code spark-auto-profiler} thread, but may also be called directly in tests.</p>
 */
public final class AutoProfilerManager implements AutoCloseable {

    public static final String THREAD_NAME_PREFIX = "spark-auto-profiler";

    private static final long POLL_INTERVAL_SECONDS = 1;
    private static final long FAILED_LAG_BACKOFF_SECONDS = 60;
    private static final int LONG_TIMEOUT_WARNING_SECONDS = 3600;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    private final SparkPlatform platform;
    private final Configuration configuration;
    private final Clock clock;
    private final LongSupplier nanoTime;

    private @Nullable AutoProfilerSettings settings;
    private volatile @Nullable ScheduledExecutorService executor;
    private @Nullable AutoProfilerLauncher launcher;
    private @Nullable TickActivityCollector collector;
    private @Nullable LagDetector lagDetector;
    private @Nullable ScheduledProfilerTrigger scheduledTrigger;
    private @Nullable ActiveSession session;
    private boolean hasScheduledStart;
    private long lastScheduledStartNanos;
    private @Nullable String lastLagCapture;
    private @Nullable String scheduledDisabledReason;
    private @Nullable String lagDisabledReason;
    private boolean started;
    private boolean closed;

    public AutoProfilerManager(SparkPlatform platform, Configuration configuration) {
        this(platform, configuration, Clock.systemDefaultZone(), System::nanoTime);
    }

    /**
     * Creates a manager with the given time sources.
     *
     * @param platform the platform
     * @param configuration the configuration to read settings from
     * @param clock the wall clock, used for the schedule
     * @param nanoTime the monotonic clock, used for lag detection and spacing
     */
    public AutoProfilerManager(SparkPlatform platform, Configuration configuration, Clock clock, LongSupplier nanoTime) {
        this.platform = platform;
        this.configuration = configuration;
        this.clock = clock;
        this.nanoTime = nanoTime;
    }

    /**
     * Loads the settings and starts whichever automatic profilers are enabled. Does
     * nothing at all if none are enabled.
     */
    public synchronized void start() {
        if (this.started || this.closed) {
            return;
        }
        this.started = true;

        if (this.platform.getPlugin().getPlatformInfo().getType() == PlatformInfo.Type.CLIENT) {
            return;
        }

        AutoProfilerSettings settings = AutoProfilerSettings.load(this.configuration, this::warn);
        if (!settings.scheduledEnabled() && !settings.lagEnabled()) {
            return;
        }
        this.settings = settings;

        boolean scheduled = settings.scheduledEnabled() && validateScheduled(settings);
        boolean lag = settings.lagEnabled() && validateLag(settings);
        if (!scheduled && !lag) {
            return;
        }

        this.executor = new SparkScheduledThreadPoolExecutor(1, new SparkThreadFactory(THREAD_NAME_PREFIX, true));
        this.launcher = new AutoProfilerLauncher(this.platform, this::executeCallback, settings.broadcast());

        if (lag) {
            startLagDetection(settings);
        }
        if (scheduled) {
            startSchedule(settings);
        }

        this.executor.scheduleWithFixedDelay(this::poll, POLL_INTERVAL_SECONDS, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
        for (String line : describeStatus()) {
            this.platform.getPlugin().log(Level.INFO, line);
        }
    }

    /**
     * Stops polling and removes the tick callbacks. Does not stop an active profiler.
     */
    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;

        if (this.collector != null) {
            TickHook tickHook = this.platform.getTickHook();
            if (tickHook != null) {
                tickHook.removeCallback(this.collector);
            }
            TickReporter tickReporter = this.platform.getTickReporter();
            if (tickReporter != null) {
                tickReporter.removeCallback(this.collector);
            }
        }
        if (this.executor != null) {
            this.executor.shutdown();
        }
    }

    /**
     * Gets if any automatic profiler is running.
     *
     * @return true if running
     */
    public synchronized boolean isRunning() {
        return this.executor != null && !this.closed;
    }

    /**
     * Describes the state of the automatic profilers in human readable lines.
     *
     * @return the lines
     */
    public synchronized List<String> describeStatus() {
        List<String> lines = new ArrayList<>();
        AutoProfilerSettings settings = this.settings;
        if (settings == null) {
            lines.add("Automatic profilers are disabled.");
            return lines;
        }

        if (this.scheduledTrigger != null) {
            List<String> times = new ArrayList<>();
            for (DailySchedule.Slot slot : this.scheduledTrigger.remainingToday()) {
                times.add(slot.formatLocalTime());
            }
            lines.add("Scheduled profiler enabled (" + settings.scheduledArgs() + "), remaining today ("
                    + settings.zone() + "): " + (times.isEmpty() ? "none" : String.join(", ", times)));
        } else {
            lines.add("Scheduled profiler disabled" + describeDisabledReason(this.scheduledDisabledReason));
        }
        if (this.lagDetector != null) {
            lines.add("Lag profiler enabled (" + settings.lagArgs() + "), rules: " + describeLagRules()
                    + ", last capture: " + (this.lastLagCapture == null ? "none" : this.lastLagCapture));
        } else {
            lines.add("Lag profiler disabled" + describeDisabledReason(this.lagDisabledReason));
        }
        if (this.session != null) {
            lines.add("An automatic profiler (" + this.session.trigger.label() + ") is running.");
        }
        return lines;
    }

    /**
     * Gets the completion of the active automatic session, if there is one.
     *
     * @return the completion
     */
    @VisibleForTesting
    public synchronized Optional<CompletableFuture<SessionResult>> activeSession() {
        return this.session == null ? Optional.<CompletableFuture<SessionResult>>empty() : Optional.of(this.session.completion);
    }

    /**
     * Runs one poll: the watchdog, a pending background restart, lag detection and the schedule.
     */
    @VisibleForTesting
    public synchronized void poll() {
        if (this.closed || this.launcher == null) {
            return;
        }
        try {
            checkStoppedExternally();
            this.launcher.retryPendingBackgroundRestart();
            pollLag();
            pollSchedule();
        } catch (Throwable e) {
            // an exception escaping a periodic task would cancel it
            this.platform.getPlugin().log(Level.SEVERE, "Error in the automatic profiler poll", e);
        }
    }

    private boolean validateScheduled(AutoProfilerSettings settings) {
        Optional<SamplerStartOptions> options = validateArgs(settings.scheduledArgs(), "scheduled");
        if (!options.isPresent()) {
            this.scheduledDisabledReason = "invalid " + AutoProfilerSettings.OPTION_SCHEDULED_ARGS;
            return false;
        }
        int timeout = options.get().timeoutSeconds();
        if (timeout >= settings.scheduledMinSpacingMinutes() * 60L) {
            warn("The timeout in " + AutoProfilerSettings.OPTION_SCHEDULED_ARGS + " (" + timeout + "s) is not shorter than "
                    + AutoProfilerSettings.OPTION_SCHEDULED_MIN_SPACING + "; a random slot that comes up while a session runs is skipped");
        }
        return true;
    }

    private boolean validateLag(AutoProfilerSettings settings) {
        if (this.platform.getTickHook() == null) {
            warn("The lag profiler is disabled: this platform does not report server ticks");
            this.lagDisabledReason = "this platform does not report server ticks";
            return false;
        }
        if (!validateArgs(settings.lagArgs(), "lag").isPresent()) {
            this.lagDisabledReason = "invalid " + AutoProfilerSettings.OPTION_LAG_ARGS;
            return false;
        }
        boolean longTickRule = settings.lagLongTickMs() > 0;
        if (longTickRule && this.platform.getTickReporter() == null) {
            warn("This platform does not report tick durations, so the " + AutoProfilerSettings.OPTION_LAG_LONG_TICK_MS
                    + " rule is disabled and only " + AutoProfilerSettings.OPTION_LAG_TPS + " is used");
            longTickRule = false;
        }
        if (settings.lagTps() <= 0 && !longTickRule) {
            warn("The lag profiler is disabled: neither " + AutoProfilerSettings.OPTION_LAG_TPS + " nor "
                    + AutoProfilerSettings.OPTION_LAG_LONG_TICK_MS + " is usable");
            this.lagDisabledReason = "no usable lag rule";
            return false;
        }
        return true;
    }

    private Optional<SamplerStartOptions> validateArgs(AutoProfilerSettings.ProfilerArgs args, String feature) {
        Optional<String> error = args.error();
        if (!error.isPresent()) {
            try {
                SamplerStartOptions options = SamplerStartOptions.parse(this.platform, new Arguments(args.tokens(), false));
                warnAboutArgs(args, options);
                return Optional.of(options);
            } catch (Arguments.ParseException e) {
                error = Optional.of(e.getMessage());
            }
        }
        warn("The " + feature + " profiler is disabled: invalid " + args.key() + ": " + error.get());
        return Optional.empty();
    }

    private void warnAboutArgs(AutoProfilerSettings.ProfilerArgs args, SamplerStartOptions options) {
        for (String token : args.tokens()) {
            if (token.startsWith("--") && !SamplerStartOptions.KNOWN_FLAGS.contains(token.substring(2).toLowerCase(Locale.ROOT))) {
                warn("Unrecognised flag " + token + " in " + args.key() + " (it will be ignored)");
            }
        }
        if (options.timeoutSeconds() > LONG_TIMEOUT_WARNING_SECONDS) {
            warn("The timeout in " + args.key() + " is " + options.timeoutSeconds() + "s; sessions longer than an hour make large profiles");
        }
    }

    private void startLagDetection(AutoProfilerSettings settings) {
        TickHook tickHook = this.platform.getTickHook();
        TickReporter tickReporter = this.platform.getTickReporter();
        int longTickMs = tickReporter != null ? settings.lagLongTickMs() : 0;

        this.lagDetector = new LagDetector(settings.lagTps(), settings.lagSustainSeconds(), longTickMs, settings.lagLongTickCount(),
                settings.lagCooldownMinutes(), settings.lagMaxPerDay(), settings.lagStartupGraceSeconds());
        this.collector = new TickActivityCollector(longTickMs, this.nanoTime.getAsLong());
        if (tickHook != null) {
            tickHook.addCallback(this.collector);
        }
        if (tickReporter != null) {
            tickReporter.addCallback(this.collector);
        }
    }

    private void startSchedule(AutoProfilerSettings settings) {
        long installSeed = this.platform.getPlugin().getPluginDirectory().toAbsolutePath().toString().hashCode();
        this.scheduledTrigger = new ScheduledProfilerTrigger(settings.zone(),
                date -> DailySchedule.plan(date, settings.zone(), settings.scheduledTimes(), settings.scheduledWindows(),
                        settings.scheduledRandomCount(), settings.scheduledMinSpacingMinutes(),
                        new Random(DailySchedule.seedFor(date, installSeed))),
                this::logPlan);
        // the first poll plans today and drops the times that have already passed
        this.scheduledTrigger.poll(this.clock.instant());
    }

    private void logPlan(LocalDate date, List<DailySchedule.Slot> plan) {
        List<String> times = new ArrayList<>();
        for (DailySchedule.Slot slot : plan) {
            times.add(slot.toString());
        }
        this.platform.getPlugin().log(Level.INFO, "Scheduled profiler times for " + date + ": "
                + (times.isEmpty() ? "none" : String.join(", ", times)));
    }

    private void checkStoppedExternally() {
        ActiveSession session = this.session;
        if (session == null) {
            return;
        }
        // read the slot before the future: the launcher only clears the slot after the future completes,
        // so a session that ended through its own timeout is never mistaken for one a user stopped
        Sampler active = this.platform.getSamplerContainer().getActiveSampler();
        if (active != session.sampler && !session.sampler.getFuture().isDone()) {
            this.platform.getPlugin().log(Level.INFO, "Automatic profiler (" + session.trigger.label() + ") was stopped or cancelled by a user");
            session.completion.complete(SessionResult.stoppedExternally());
            onSessionEnded(session);
        }
    }

    private void pollLag() {
        if (this.lagDetector == null || this.collector == null || this.settings == null) {
            return;
        }
        long now = this.nanoTime.getAsLong();
        LocalDate today = today();
        Optional<String> reason = this.lagDetector.evaluate(this.collector.drain(now), now, today);
        if (!reason.isPresent() || this.session != null) {
            return;
        }

        StartResult result = launch(Trigger.LAG, this.settings.lagArgs(), reason.get(), this.settings.lagUploadBackground());
        if (result.status() == StartResult.Status.STARTED) {
            this.lagDetector.onCaptureStarted(today);
            this.lastLagCapture = LocalDateTime.ofInstant(this.clock.instant(), this.settings.zone()).format(TIME_FORMAT) + " (" + reason.get() + ")";
        } else {
            this.lagDetector.backoff(now, FAILED_LAG_BACKOFF_SECONDS);
            this.platform.getPlugin().log(Level.INFO, "Lag detected (" + reason.get() + ") but the automatic profiler did not start: " + result.message());
        }
    }

    private void pollSchedule() {
        if (this.scheduledTrigger == null || this.settings == null) {
            return;
        }
        Optional<DailySchedule.Slot> due = this.scheduledTrigger.poll(this.clock.instant());
        if (!due.isPresent()) {
            return;
        }

        DailySchedule.Slot slot = due.get();
        long now = this.nanoTime.getAsLong();
        long spacingNanos = TimeUnit.MINUTES.toNanos(this.settings.scheduledMinSpacingMinutes());
        if (this.session != null) {
            this.platform.getPlugin().log(Level.INFO, "Skipping the scheduled profiler at " + slot.formatLocalTime() + ": an automatic profiler is already running");
            return;
        }
        if (slot.random() && this.hasScheduledStart && now - this.lastScheduledStartNanos < spacingNanos) {
            this.platform.getPlugin().log(Level.INFO, "Skipping the scheduled profiler at " + slot.formatLocalTime() + ": too soon after the previous one");
            return;
        }

        String reason = "scheduled at " + slot.formatLocalTime() + (slot.random() ? " (random)" : "");
        StartResult result = launch(Trigger.SCHEDULED, this.settings.scheduledArgs(), reason, false);
        if (result.status() == StartResult.Status.STARTED) {
            this.hasScheduledStart = true;
            this.lastScheduledStartNanos = now;
        } else {
            this.platform.getPlugin().log(Level.INFO, "Skipping the scheduled profiler at " + slot.formatLocalTime() + ": " + result.message());
        }
    }

    private StartResult launch(Trigger trigger, AutoProfilerSettings.ProfilerArgs args, String reason, boolean uploadBackground) {
        StartResult result = this.launcher.start(trigger, args.tokens(), reason, uploadBackground);
        Optional<Sampler> sampler = result.sampler();
        Optional<CompletableFuture<SessionResult>> completion = result.completion();
        if (result.status() == StartResult.Status.STARTED && sampler.isPresent() && completion.isPresent()) {
            ActiveSession session = new ActiveSession(trigger, sampler.get(), completion.get());
            this.session = session;
            completion.get().whenCompleteAsync((r, t) -> onSessionEnded(session), this::executeCallback);
        }
        return result;
    }

    private synchronized void onSessionEnded(ActiveSession session) {
        if (this.session != session) {
            return;
        }
        this.session = null;
        if (this.lagDetector != null) {
            this.lagDetector.onSessionEnded(this.nanoTime.getAsLong());
        }
    }

    /**
     * Runs a callback on the auto profiler thread, or on the plugin's async executor once that
     * thread has been shut down, so completing a future never throws.
     */
    private void executeCallback(Runnable task) {
        ScheduledExecutorService executor = this.executor;
        if (executor != null) {
            try {
                executor.execute(task);
                return;
            } catch (RejectedExecutionException e) {
                // shut down; fall through
            }
        }
        this.platform.getPlugin().executeAsync(task);
    }

    private static String describeDisabledReason(@Nullable String reason) {
        return reason == null ? "" : ": " + reason;
    }

    private LocalDate today() {
        Instant now = this.clock.instant();
        return now.atZone(this.settings == null ? this.clock.getZone() : this.settings.zone()).toLocalDate();
    }

    private void warn(String message) {
        this.platform.getPlugin().log(Level.WARNING, "[auto profiler] " + message);
    }

    private String describeLagRules() {
        List<String> rules = new ArrayList<>();
        if (this.settings.lagTps() > 0) {
            rules.add(String.format(Locale.ROOT, "tps below %.1f over %ds", this.settings.lagTps(), this.settings.lagSustainSeconds()));
        }
        if (this.settings.lagLongTickMs() > 0 && this.platform.getTickReporter() != null) {
            rules.add(this.settings.lagLongTickCount() + " tick(s) over " + this.settings.lagLongTickMs() + "ms in "
                    + this.settings.lagSustainSeconds() + "s");
        }
        return rules.isEmpty() ? "none" : String.join(" or ", rules);
    }

    private static final class ActiveSession {
        private final Trigger trigger;
        private final Sampler sampler;
        private final CompletableFuture<SessionResult> completion;

        private ActiveSession(Trigger trigger, Sampler sampler, CompletableFuture<SessionResult> completion) {
            this.trigger = trigger;
            this.sampler = sampler;
            this.completion = completion;
        }
    }
}
