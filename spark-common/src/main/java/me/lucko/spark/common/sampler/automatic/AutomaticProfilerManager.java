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

import me.lucko.spark.common.SparkPlatform;
import me.lucko.spark.common.command.sender.CommandSender;
import me.lucko.spark.common.command.sender.ProfileOutputHandler;
import me.lucko.spark.common.platform.PlatformInfo;
import me.lucko.spark.common.sampler.Sampler;
import me.lucko.spark.common.util.SparkScheduledThreadPoolExecutor;
import me.lucko.spark.common.util.SparkThreadFactory;
import me.lucko.spark.common.util.config.Configuration;
import net.kyori.adventure.text.Component;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/**
 * Starts configured profiler jobs at fixed wall-clock times.
 */
public final class AutomaticProfilerManager implements AutoCloseable {
    private static final String CONFIG_ROOT = "automaticProfilers";
    private static final int DEFAULT_TIMEOUT_SECONDS = 300;
    private static final String PROFILE_EXTENSION = ".sparkprofile";
    private static final DateTimeFormatter FILE_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd-MMMM-yyyy", Locale.ENGLISH);

    private final SparkPlatform platform;
    private final ZoneId timezone;
    private final Clock clock;
    private final List<ScheduledProfiler> profilers;
    private final ScheduledExecutorService executor;
    private final DiscordWebhookClient webhookClient;
    private final AtomicReference<AutomaticRun> activeRun = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public AutomaticProfilerManager(SparkPlatform platform, Configuration configuration) {
        this.platform = platform;

        ZoneId timezone = null;
        String timezoneName = configuration.getString(CONFIG_ROOT + ".timezone", ZoneId.systemDefault().getId()).trim();
        try {
            timezone = ZoneId.of(timezoneName);
        } catch (RuntimeException e) {
            this.platform.getPlugin().log(Level.SEVERE, "Invalid automatic profiler timezone '" + timezoneName + "'. Automatic profilers are disabled.");
        }
        this.timezone = timezone;
        this.clock = timezone == null ? Clock.systemUTC() : Clock.system(timezone);
        this.profilers = loadProfilers(configuration);
        this.executor = new SparkScheduledThreadPoolExecutor(2, new SparkThreadFactory("spark-automatic-profiler", true));

        DiscordWebhookClient webhookClient = null;
        String webhook = configuration.getString(CONFIG_ROOT + ".discordWebhook", "").trim();
        if (!webhook.isEmpty()) {
            try {
                URI webhookUri = DiscordWebhookClient.parseWebhookUri(webhook);
                webhookClient = new DiscordWebhookClient(this.platform.getPlugin(), webhookUri, this.executor);
            } catch (IllegalArgumentException e) {
                this.platform.getPlugin().log(Level.WARNING, e.getMessage() + ". Automatic profiler files will only be saved locally.");
            }
        }
        this.webhookClient = webhookClient;
    }

    public void initialise() {
        if (this.platform.getPlugin().getPlatformInfo().getType() == PlatformInfo.Type.CLIENT || this.timezone == null || this.profilers.isEmpty()) {
            return;
        }

        int scheduledTimes = 0;
        for (ScheduledProfiler profiler : this.profilers) {
            for (LocalTime timestamp : profiler.timestamps) {
                scheduleNext(profiler, timestamp);
                scheduledTimes++;
            }
        }
        this.platform.getPlugin().log(Level.INFO, "Scheduled " + scheduledTimes + " automatic profiler job(s) in timezone " + this.timezone.getId() + ".");
    }

    private List<ScheduledProfiler> loadProfilers(Configuration configuration) {
        List<ScheduledProfiler> profilers = new ArrayList<>();
        String profilesPath = CONFIG_ROOT + ".profiles";
        for (String name : configuration.getKeys(profilesPath)) {
            if (name.trim().isEmpty() || name.contains(".")) {
                this.platform.getPlugin().log(Level.WARNING, "Ignoring automatic profiler with invalid name '" + name + "'. Names cannot be blank or contain periods.");
                continue;
            }

            String path = profilesPath + "." + name;
            List<String> commandArguments = commandArguments(configuration.getString(path + ".flags", ""));
            String fileNameTemplate = loadFileNameTemplate(configuration.getString(path + ".fileName", ""), name);
            Set<LocalTime> timestamps = new LinkedHashSet<>();
            for (String configuredTimestamp : configuration.getStringList(path + ".timestamps")) {
                LocalTime timestamp = parseTimestamp(configuredTimestamp);
                if (timestamp == null) {
                    this.platform.getPlugin().log(Level.WARNING, "Ignoring invalid timestamp '" + configuredTimestamp + "' for automatic profiler '" + name + "'. Expected HH:mm.");
                } else {
                    timestamps.add(timestamp);
                }
            }

            if (timestamps.isEmpty()) {
                this.platform.getPlugin().log(Level.WARNING, "Automatic profiler '" + name + "' has no valid timestamps and will not be scheduled.");
                continue;
            }
            profilers.add(new ScheduledProfiler(name, fileNameTemplate, commandArguments, new ArrayList<>(timestamps)));
        }
        return Collections.unmodifiableList(profilers);
    }

    private String loadFileNameTemplate(String configuredTemplate, String profilerName) {
        String template = configuredTemplate.trim();
        if (template.isEmpty()) {
            return null;
        }

        if (!isValidFileNameTemplate(template)) {
            this.platform.getPlugin().log(Level.WARNING, "Ignoring invalid fileName for automatic profiler '" + profilerName + "'. Only the {date} placeholder is supported, paths are not allowed, and the filename must not be empty.");
            return null;
        }
        return template;
    }

    private void scheduleNext(ScheduledProfiler profiler, LocalTime timestamp) {
        if (this.closed.get()) {
            return;
        }

        ZonedDateTime next = nextOccurrence(this.clock, this.timezone, timestamp);
        long delayMillis = Math.max(0L, Duration.between(this.clock.instant(), next.toInstant()).toMillis());
        this.executor.schedule(() -> {
            try {
                launch(profiler);
            } finally {
                scheduleNext(profiler, timestamp);
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    boolean launch(String profilerName) {
        for (ScheduledProfiler profiler : this.profilers) {
            if (profiler.name.equals(profilerName)) {
                launch(profiler);
                return true;
            }
        }
        return false;
    }

    private void launch(ScheduledProfiler profiler) {
        AutomaticRun run = new AutomaticRun(profiler);
        if (!this.activeRun.compareAndSet(null, run)) {
            logSkipped(profiler.name, "another automatic profiler is starting or exporting");
            return;
        }

        Sampler activeSampler = this.platform.getSamplerContainer().getActiveSampler();
        if (activeSampler != null) {
            if (!activeSampler.isRunningInBackground()) {
                logSkipped(profiler.name, "another profiler is already active");
                this.activeRun.compareAndSet(run, null);
                return;
            }

            run.displacedBackground = true;
            activeSampler.stop(true);
            this.platform.getSamplerContainer().unsetActiveSampler(activeSampler);
        }

        this.platform.getPlugin().log(Level.INFO, "Starting automatic profiler '" + profiler.name + "'.");
        this.platform.executeCommand(run.sender, profiler.commandArguments.toArray(new String[0])).whenComplete((ignored, throwable) -> {
            Sampler sampler = run.sender.sampler.get();
            if (throwable != null || sampler == null) {
                if (throwable != null) {
                    this.platform.getPlugin().log(Level.WARNING, "Automatic profiler '" + profiler.name + "' failed to start.", throwable);
                } else {
                    this.platform.getPlugin().log(Level.WARNING, "Automatic profiler '" + profiler.name + "' did not start. Check its configured flags.");
                }
                restoreBackground(run, null);
                this.activeRun.compareAndSet(run, null);
                return;
            }

            run.sampler = sampler;
            sampler.getFuture().whenComplete((completedSampler, failure) -> {
                restoreBackground(run, completedSampler);
                if (failure != null) {
                    this.activeRun.compareAndSet(run, null);
                }
            });
        });
    }

    private void restoreBackground(AutomaticRun run, Sampler sampler) {
        if (!run.displacedBackground || !run.backgroundRestored.compareAndSet(false, true)) {
            return;
        }
        if (sampler != null) {
            this.platform.getSamplerContainer().unsetActiveSampler(sampler);
        }
        if (!this.closed.get() && this.platform.getSamplerContainer().getActiveSampler() == null) {
            try {
                this.platform.getBackgroundSamplerManager().restartBackgroundSampler();
            } catch (RuntimeException e) {
                this.platform.getPlugin().log(Level.WARNING, "Failed to restore the background profiler after automatic profiler '" + run.profiler.name + "'.", e);
            }
        }
    }

    private void profileSaved(AutomaticRun run, Path file) {
        if (this.webhookClient != null && !this.closed.get()) {
            this.webhookClient.send(file, run.profiler.name, ZonedDateTime.now(this.clock));
        }
        this.activeRun.compareAndSet(run, null);
    }

    private void profileSaveFailed(AutomaticRun run) {
        this.activeRun.compareAndSet(run, null);
    }

    private void logSkipped(String profilerName, String reason) {
        this.platform.getPlugin().log(Level.WARNING, "Skipping automatic profiler '" + profilerName + "' because " + reason + ".");
    }

    static List<String> commandArguments(String flags) {
        List<String> command = new ArrayList<>();
        command.add("profiler");
        command.add("start");

        boolean hasTimeout = false;
        boolean hasSaveToFile = false;
        String trimmedFlags = flags.trim();
        if (!trimmedFlags.isEmpty()) {
            for (String argument : trimmedFlags.split("\\s+")) {
                if (argument.equals("--timeout")) {
                    hasTimeout = true;
                }
                if (argument.equals("--save-to-file")) {
                    if (hasSaveToFile) {
                        continue;
                    }
                    hasSaveToFile = true;
                }
                command.add(argument);
            }
        }
        if (!hasTimeout) {
            command.add("--timeout");
            command.add(Integer.toString(DEFAULT_TIMEOUT_SECONDS));
        }
        if (!hasSaveToFile) {
            command.add("--save-to-file");
        }
        return Collections.unmodifiableList(command);
    }

    static LocalTime parseTimestamp(String value) {
        if (value == null || !value.matches("(?:[01]\\d|2[0-3]):[0-5]\\d")) {
            return null;
        }
        return LocalTime.of(Integer.parseInt(value.substring(0, 2)), Integer.parseInt(value.substring(3, 5)));
    }

    static ZonedDateTime nextOccurrence(Clock clock, ZoneId timezone, LocalTime timestamp) {
        ZonedDateTime now = ZonedDateTime.ofInstant(clock.instant(), timezone);
        LocalDate date = now.toLocalDate();
        ZonedDateTime next = date.atTime(timestamp).atZone(timezone);
        if (!next.isAfter(now)) {
            next = date.plusDays(1).atTime(timestamp).atZone(timezone);
        }
        return next;
    }

    static String resolveProfileFileName(String template, ZonedDateTime completedAt) {
        String fileName = template.replace("{date}", FILE_DATE_FORMATTER.format(completedAt)).trim();
        if (fileName.toLowerCase(Locale.ROOT).endsWith(PROFILE_EXTENSION)) {
            fileName = fileName.substring(0, fileName.length() - PROFILE_EXTENSION.length());
        }

        fileName = fileName
                .replaceAll("[\\x00-\\x1f<>:\"/\\\\|?*]", "_")
                .replaceAll("[ .]+$", "")
                .trim();
        if (fileName.isEmpty() || fileName.equals(".") || fileName.equals("..")) {
            return null;
        }

        String upperCaseName = fileName.toUpperCase(Locale.ROOT);
        if (upperCaseName.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) {
            fileName = "_" + fileName;
        }
        return fileName + PROFILE_EXTENSION;
    }

    static boolean isValidFileNameTemplate(String template) {
        String withoutDatePlaceholder = template.replace("{date}", "");
        return !template.contains("/")
                && !template.contains("\\")
                && !withoutDatePlaceholder.contains("{")
                && !withoutDatePlaceholder.contains("}")
                && resolveProfileFileName(template, ZonedDateTime.of(2026, 7, 23, 0, 0, 0, 0, ZoneId.of("UTC"))) != null;
    }

    static Path nextAvailableFile(Path requestedFile) {
        if (!Files.exists(requestedFile)) {
            return requestedFile;
        }

        String fileName = requestedFile.getFileName().toString();
        int extensionStart = fileName.toLowerCase(Locale.ROOT).endsWith(PROFILE_EXTENSION)
                ? fileName.length() - PROFILE_EXTENSION.length()
                : fileName.length();
        String baseName = fileName.substring(0, extensionStart);
        String extension = fileName.substring(extensionStart);
        for (int suffix = 2; ; suffix++) {
            Path candidate = requestedFile.resolveSibling(baseName + " (" + suffix + ")" + extension);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }

    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        if (this.webhookClient != null) {
            this.webhookClient.close();
        }
        this.activeRun.set(null);
        this.executor.shutdownNow();
    }

    private static final class ScheduledProfiler {
        private final String name;
        private final String fileNameTemplate;
        private final List<String> commandArguments;
        private final List<LocalTime> timestamps;

        private ScheduledProfiler(String name, String fileNameTemplate, List<String> commandArguments, List<LocalTime> timestamps) {
            this.name = name;
            this.fileNameTemplate = fileNameTemplate;
            this.commandArguments = commandArguments;
            this.timestamps = timestamps;
        }
    }

    private final class AutomaticRun {
        private final ScheduledProfiler profiler;
        private final AutomaticCommandSender sender;
        private final AtomicBoolean backgroundRestored = new AtomicBoolean(false);
        private volatile boolean displacedBackground;
        private volatile Sampler sampler;

        private AutomaticRun(ScheduledProfiler profiler) {
            this.profiler = profiler;
            this.sender = new AutomaticCommandSender(this);
        }
    }

    private final class AutomaticCommandSender implements CommandSender, ProfileOutputHandler {
        private final AutomaticRun run;
        private final AtomicReference<Sampler> sampler = new AtomicReference<>();

        private AutomaticCommandSender(AutomaticRun run) {
            this.run = run;
        }

        @Override
        public String getName() {
            return "Automatic Profiler (" + this.run.profiler.name + ")";
        }

        @Override
        public UUID getUniqueId() {
            return null;
        }

        @Override
        public void sendMessage(Component message) {
            // Automatic command output is broadcast to permitted command senders.
        }

        @Override
        public boolean hasPermission(String permission) {
            return true;
        }

        @Override
        public void profilerStarted(Sampler sampler) {
            this.sampler.set(sampler);
        }

        @Override
        public Path resolveProfileFile(Path defaultFile) {
            Path requestedFile = defaultFile;
            String template = this.run.profiler.fileNameTemplate;
            if (template != null) {
                String fileName = resolveProfileFileName(template, ZonedDateTime.now(AutomaticProfilerManager.this.clock));
                if (fileName != null) {
                    requestedFile = defaultFile.resolveSibling(fileName);
                }
            }
            return nextAvailableFile(requestedFile);
        }

        @Override
        public void profileSaved(Path file) {
            AutomaticProfilerManager.this.profileSaved(this.run, file);
        }

        @Override
        public void profileSaveFailed() {
            AutomaticProfilerManager.this.profileSaveFailed(this.run);
        }
    }
}
