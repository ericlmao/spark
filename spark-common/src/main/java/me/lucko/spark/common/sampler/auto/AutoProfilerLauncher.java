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
import me.lucko.spark.common.command.Arguments;
import me.lucko.spark.common.command.CommandResponseHandler;
import me.lucko.spark.common.command.sender.CommandSender;
import me.lucko.spark.common.sampler.Sampler;
import me.lucko.spark.common.sampler.SamplerContainer;
import me.lucko.spark.common.sampler.SamplerStartOptions;
import me.lucko.spark.common.sampler.java.MergeStrategy;
import me.lucko.spark.common.sampler.source.ClassSourceLookup;
import me.lucko.spark.common.util.MediaTypes;
import me.lucko.spark.proto.SparkSamplerProtos;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.format.NamedTextColor.GOLD;
import static net.kyori.adventure.text.format.NamedTextColor.GRAY;

/**
 * Starts automatic profiler sessions and exports their results when they finish.
 *
 * <p>Samplers are only ever started or stopped while holding the command lock, so an
 * automatic session never races a {@code /spark profiler} command.</p>
 */
public final class AutoProfilerLauncher {

    private static final long LOCK_TIMEOUT_SECONDS = 5;

    private static final String BEFORE_LAG_TYPE = "Profiler (before lag)";
    private static final String BEFORE_LAG_PREFIX = "profile-before-lag";

    private final SparkPlatform platform;
    private final Executor callbackExecutor;
    private final boolean broadcast;
    private final AtomicBoolean pendingBackgroundRestart = new AtomicBoolean(false);

    /**
     * Creates a new launcher.
     *
     * @param platform the platform
     * @param callbackExecutor runs the handler when a session's sampler finishes
     * @param broadcast if start and result messages should be sent to players with spark permissions
     */
    public AutoProfilerLauncher(SparkPlatform platform, Executor callbackExecutor, boolean broadcast) {
        this.platform = platform;
        this.callbackExecutor = callbackExecutor;
        this.broadcast = broadcast;
    }

    /**
     * Starts an automatic profiler session.
     *
     * @param trigger what triggered the session
     * @param argTokens the profiler arguments, in {@code /spark profiler start} syntax
     * @param reason why the session is starting, for logs and the profile comment
     * @param uploadReplacedBackground for lag sessions, if a background profiler that is
     *                                 replaced should be exported rather than discarded
     * @return the outcome
     */
    public StartResult start(Trigger trigger, List<String> argTokens, String reason, boolean uploadReplacedBackground) {
        AtomicReference<StartResult> result = new AtomicReference<>();
        boolean ran = this.platform.getCommandManager().runExclusive(
                () -> result.set(startLocked(trigger, argTokens, reason, uploadReplacedBackground)),
                LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS
        );
        if (!ran) {
            return StartResult.notStarted(StartResult.Status.SKIPPED_BUSY, "a spark command is still running");
        }
        return result.get();
    }

    /**
     * Retries restarting the background profiler after an earlier attempt could not get
     * the command lock. Gives up as soon as any profiler is active.
     */
    public void retryPendingBackgroundRestart() {
        if (!this.pendingBackgroundRestart.get()) {
            return;
        }
        if (this.platform.getSamplerContainer().getActiveSampler() != null) {
            this.pendingBackgroundRestart.set(false);
            return;
        }
        if (this.platform.getCommandManager().runExclusive(this::restartBackgroundIfIdle, LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            this.pendingBackgroundRestart.set(false);
        }
    }

    private StartResult startLocked(Trigger trigger, List<String> argTokens, String reason, boolean uploadReplacedBackground) {
        SamplerStartOptions options;
        try {
            options = SamplerStartOptions.parse(this.platform, new Arguments(argTokens, false));
        } catch (Arguments.ParseException e) {
            return StartResult.notStarted(StartResult.Status.FAILED, "invalid arguments: " + e.getMessage());
        }

        SamplerContainer container = this.platform.getSamplerContainer();
        Sampler previous = container.getActiveSampler();
        if (previous != null && !previous.isRunningInBackground()) {
            return StartResult.notStarted(StartResult.Status.SKIPPED_PROFILER_ACTIVE, "another profiler is already running");
        }

        boolean replacedBackground = previous != null;
        if (previous != null) {
            stopBackground(previous, trigger == Trigger.LAG && uploadReplacedBackground, options, reason);
        }

        Sampler sampler;
        try {
            sampler = options.createBuilder().start(this.platform);
        } catch (RuntimeException e) {
            if (replacedBackground) {
                restartBackgroundIfIdle();
            }
            this.platform.getPlugin().log(Level.WARNING, "Failed to start an automatic profiler (" + trigger.label() + ")", e);
            return StartResult.notStarted(StartResult.Status.FAILED, String.valueOf(e.getMessage()));
        }

        try {
            container.setActiveSampler(sampler);
        } catch (IllegalStateException e) {
            sampler.stop(true);
            return StartResult.notStarted(StartResult.Status.SKIPPED_PROFILER_ACTIVE, "another profiler is already running");
        }

        Session session = new Session(trigger, sampler, options, reason, replacedBackground);
        sampler.getFuture().whenCompleteAsync((s, throwable) -> onSamplerFinished(session, throwable), this.callbackExecutor);

        this.platform.getPlugin().log(Level.INFO, "Automatic profiler started (" + trigger.label() + "): " + reason
                + " [" + String.join(" ", argTokens) + "]");
        broadcast(text("An automatic profiler has started (" + trigger.label() + "): " + reason));

        return StartResult.started(sampler, session.completion);
    }

    private void stopBackground(Sampler background, boolean export, SamplerStartOptions options, String reason) {
        SamplerContainer container = this.platform.getSamplerContainer();
        if (export) {
            // the background profiler is the only data that covers the start of the lag
            container.unsetActiveSampler(background);
            background.stop(false);
            this.platform.getPlugin().executeAsync(() -> exportBeforeLag(background, options, reason));
        } else {
            background.stop(true);
            container.unsetActiveSampler(background);
        }
    }

    private void exportBeforeLag(Sampler background, SamplerStartOptions options, String reason) {
        try {
            Sampler.ExportProps props = exportProps(Trigger.LAG.creator(), "before lag: " + reason, MergeStrategy.SAME_METHOD);
            SparkSamplerProtos.SamplerData data = background.toProto(this.platform, props);
            publish(data, BEFORE_LAG_TYPE, BEFORE_LAG_PREFIX, "before lag", Trigger.LAG.creator(), options.saveToFile());
        } catch (Throwable e) {
            this.platform.getPlugin().log(Level.SEVERE, "Failed to export the background profiler before a lag profile", e);
        }
    }

    private void onSamplerFinished(Session session, @Nullable Throwable throwable) {
        if (session.completion.isDone()) {
            // already resolved, e.g. a user stopped it
            return;
        }
        this.platform.getSamplerContainer().unsetActiveSampler(session.sampler);
        restartBackgroundAfter(session);

        if (throwable != null) {
            this.platform.getPlugin().log(Level.SEVERE, "Automatic profiler (" + session.trigger.label() + ") failed unexpectedly", throwable);
            session.completion.complete(SessionResult.failed());
            return;
        }

        this.platform.getPlugin().executeAsync(() -> session.completion.complete(exportSession(session)));
    }

    private void restartBackgroundAfter(Session session) {
        if (!session.replacedBackground) {
            return;
        }
        boolean ran = this.platform.getCommandManager().runExclusive(this::restartBackgroundIfIdle, LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!ran) {
            this.pendingBackgroundRestart.set(true);
        }
    }

    /** Must be called while holding the command lock. */
    private void restartBackgroundIfIdle() {
        if (this.platform.getSamplerContainer().getActiveSampler() != null) {
            return;
        }
        try {
            this.platform.getBackgroundSamplerManager().restartBackgroundSampler();
        } catch (Throwable e) {
            this.platform.getPlugin().log(Level.WARNING, "Failed to restart the background profiler", e);
        }
    }

    private SessionResult exportSession(Session session) {
        try {
            String comment = session.options.comment()
                    .map(configured -> configured + " | " + session.reason)
                    .orElse(session.reason);
            Sampler.ExportProps props = exportProps(session.trigger.creator(), comment, session.options.mergeStrategy());
            SparkSamplerProtos.SamplerData data = session.sampler.toProto(this.platform, props);
            return publish(data, session.trigger.activityType(), session.trigger.filePrefix(), session.trigger.label(),
                    session.trigger.creator(), session.options.saveToFile());
        } catch (Throwable e) {
            this.platform.getPlugin().log(Level.SEVERE, "Failed to export automatic profiler (" + session.trigger.label() + ") results", e);
            return SessionResult.failed();
        }
    }

    private Sampler.ExportProps exportProps(CommandSender.Data creator, String comment, MergeStrategy mergeStrategy) {
        return new Sampler.ExportProps()
                .creator(creator)
                .comment(comment)
                .mergeStrategy(mergeStrategy)
                .classSourceLookup(() -> ClassSourceLookup.create(this.platform));
    }

    private SessionResult publish(SparkSamplerProtos.SamplerData data, String activityType, String filePrefix,
                                  String label, CommandSender.Data creator, boolean saveToFile) {
        if (!saveToFile) {
            Optional<String> url = upload(data, label);
            if (url.isPresent()) {
                this.platform.getActivityLog().addToLog(Activity.urlActivity(creator, System.currentTimeMillis(), activityType, url.get()));
                this.platform.getPlugin().log(Level.INFO, "Automatic profiler (" + label + ") complete: " + url.get());
                broadcast(text()
                        .append(text("Automatic profiler (" + label + ") complete: ", GOLD))
                        .append(text().content(url.get()).color(GRAY).clickEvent(ClickEvent.openUrl(url.get())).build())
                        .build());
                return SessionResult.uploaded(url.get());
            }
        }
        return save(data, activityType, filePrefix, label, creator);
    }

    private Optional<String> upload(SparkSamplerProtos.SamplerData data, String label) {
        try {
            String key = this.platform.getBytebinClient().postContent(data, MediaTypes.SPARK_SAMPLER_MEDIA_TYPE).key();
            return Optional.of(this.platform.getViewerUrl() + key);
        } catch (Exception e) {
            this.platform.getPlugin().log(Level.WARNING, "Error whilst uploading automatic profiler (" + label + ") results, saving to disk instead", e);
            return Optional.empty();
        }
    }

    private SessionResult save(SparkSamplerProtos.SamplerData data, String activityType, String filePrefix, String label, CommandSender.Data creator) {
        Path file = this.platform.resolveSaveFile(filePrefix, "sparkprofile");
        try {
            Files.write(file, data.toByteArray());
        } catch (IOException e) {
            this.platform.getPlugin().log(Level.WARNING, "Error whilst saving automatic profiler (" + label + ") results", e);
            return SessionResult.failed();
        }
        this.platform.getActivityLog().addToLog(Activity.fileActivity(creator, System.currentTimeMillis(), activityType, file.toString()));
        this.platform.getPlugin().log(Level.INFO, "Automatic profiler (" + label + ") complete, saved to: " + file);
        broadcast(text("Automatic profiler (" + label + ") complete, saved to: " + file, GOLD));
        return SessionResult.saved(file);
    }

    private void broadcast(Component message) {
        if (!this.broadcast || !this.platform.getCommandManager().shouldBroadcastResponse()) {
            return;
        }
        Component prefixed = CommandResponseHandler.applyPrefix(message);
        this.platform.getPlugin().getCommandSenders()
                .filter(sender -> sender.getUniqueId() != null)
                .filter(sender -> sender.hasPermission("spark") || sender.hasPermission("spark.profiler"))
                .forEach(sender -> sender.sendMessage(prefixed));
    }

    /**
     * What started an automatic profiler.
     */
    public enum Trigger {
        SCHEDULED("scheduled", "Profiler (scheduled)", "profile-scheduled"),
        LAG("lag", "Profiler (lag)", "profile-lag");

        private final String label;
        private final String activityType;
        private final String filePrefix;

        private Trigger(String label, String activityType, String filePrefix) {
            this.label = label;
            this.activityType = activityType;
            this.filePrefix = filePrefix;
        }

        public String label() {
            return this.label;
        }

        public String activityType() {
            return this.activityType;
        }

        public String filePrefix() {
            return this.filePrefix;
        }

        public CommandSender.Data creator() {
            return new CommandSender.Data("spark (" + this.label + ")", null);
        }
    }

    /**
     * The outcome of {@link #start}.
     */
    public static final class StartResult {
        public enum Status {
            STARTED,
            SKIPPED_PROFILER_ACTIVE,
            SKIPPED_BUSY,
            FAILED
        }

        private final Status status;
        private final String message;
        private final @Nullable Sampler sampler;
        private final @Nullable CompletableFuture<SessionResult> completion;

        private StartResult(Status status, String message, @Nullable Sampler sampler, @Nullable CompletableFuture<SessionResult> completion) {
            this.status = status;
            this.message = message;
            this.sampler = sampler;
            this.completion = completion;
        }

        private static StartResult started(Sampler sampler, CompletableFuture<SessionResult> completion) {
            return new StartResult(Status.STARTED, "started", sampler, completion);
        }

        private static StartResult notStarted(Status status, String message) {
            return new StartResult(status, message, null, null);
        }

        public Status status() {
            return this.status;
        }

        public String message() {
            return this.message;
        }

        public Optional<Sampler> sampler() {
            return Optional.ofNullable(this.sampler);
        }

        /**
         * Gets a future completed when the session has ended and its results were exported.
         *
         * @return the completion, present when the session started
         */
        public Optional<CompletableFuture<SessionResult>> completion() {
            return Optional.ofNullable(this.completion);
        }
    }

    /**
     * The outcome of a session that started.
     */
    public static final class SessionResult {
        public enum Outcome {
            UPLOADED,
            SAVED,
            FAILED,
            STOPPED_EXTERNALLY
        }

        private final Outcome outcome;
        private final @Nullable String url;
        private final @Nullable Path file;

        private SessionResult(Outcome outcome, @Nullable String url, @Nullable Path file) {
            this.outcome = outcome;
            this.url = url;
            this.file = file;
        }

        public static SessionResult uploaded(String url) {
            return new SessionResult(Outcome.UPLOADED, url, null);
        }

        public static SessionResult saved(Path file) {
            return new SessionResult(Outcome.SAVED, null, file);
        }

        public static SessionResult failed() {
            return new SessionResult(Outcome.FAILED, null, null);
        }

        public static SessionResult stoppedExternally() {
            return new SessionResult(Outcome.STOPPED_EXTERNALLY, null, null);
        }

        public Outcome outcome() {
            return this.outcome;
        }

        public Optional<String> url() {
            return Optional.ofNullable(this.url);
        }

        public Optional<Path> file() {
            return Optional.ofNullable(this.file);
        }

        @Override
        public String toString() {
            return "SessionResult{outcome=" + this.outcome + ", url=" + this.url + ", file=" + this.file + '}';
        }
    }

    private static final class Session {
        private final Trigger trigger;
        private final Sampler sampler;
        private final SamplerStartOptions options;
        private final String reason;
        private final boolean replacedBackground;
        private final CompletableFuture<SessionResult> completion = new CompletableFuture<>();

        private Session(Trigger trigger, Sampler sampler, SamplerStartOptions options, String reason, boolean replacedBackground) {
            this.trigger = trigger;
            this.sampler = sampler;
            this.options = options;
            this.reason = reason;
            this.replacedBackground = replacedBackground;
        }
    }
}
