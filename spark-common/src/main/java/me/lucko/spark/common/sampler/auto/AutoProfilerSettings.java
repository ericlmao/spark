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

import me.lucko.spark.common.command.Arguments;
import me.lucko.spark.common.util.config.Configuration;
import org.jspecify.annotations.Nullable;

import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The configuration of the scheduled and lag-triggered profilers. Immutable.
 */
public final class AutoProfilerSettings {

    public static final String OPTION_SCHEDULED = "scheduledProfiler";
    public static final String OPTION_SCHEDULED_ARGS = "scheduledProfilerArgs";
    public static final String OPTION_SCHEDULED_RANDOM_COUNT = "scheduledProfilerRandomCount";
    public static final String OPTION_SCHEDULED_WINDOWS = "scheduledProfilerWindows";
    public static final String OPTION_SCHEDULED_TIMES = "scheduledProfilerTimes";
    public static final String OPTION_SCHEDULED_MIN_SPACING = "scheduledProfilerMinSpacingMinutes";

    public static final String OPTION_LAG = "lagProfiler";
    public static final String OPTION_LAG_ARGS = "lagProfilerArgs";
    public static final String OPTION_LAG_TPS = "lagProfilerTps";
    public static final String OPTION_LAG_SUSTAIN = "lagProfilerSustainSeconds";
    public static final String OPTION_LAG_LONG_TICK_MS = "lagProfilerLongTickMs";
    public static final String OPTION_LAG_LONG_TICK_COUNT = "lagProfilerLongTickCount";
    public static final String OPTION_LAG_COOLDOWN = "lagProfilerCooldownMinutes";
    public static final String OPTION_LAG_MAX_PER_DAY = "lagProfilerMaxPerDay";
    public static final String OPTION_LAG_STARTUP_GRACE = "lagProfilerStartupGraceSeconds";
    public static final String OPTION_LAG_UPLOAD_BACKGROUND = "lagProfilerUploadBackground";

    public static final String OPTION_TIMEZONE = "autoProfilerTimezone";
    public static final String OPTION_BROADCAST = "autoProfilerBroadcast";

    public static final int DEFAULT_SCHEDULED_TIMEOUT = 300;
    public static final int DEFAULT_LAG_TIMEOUT = 120;

    private static final String DEFAULT_WINDOW = "00:00-24:00";
    private static final Consumer<String> NO_WARNINGS = message -> { };

    private final boolean scheduledEnabled;
    private final ProfilerArgs scheduledArgs;
    private final int scheduledRandomCount;
    private final List<TimeWindow> scheduledWindows;
    private final List<LocalTime> scheduledTimes;
    private final int scheduledMinSpacingMinutes;

    private final boolean lagEnabled;
    private final ProfilerArgs lagArgs;
    private final double lagTps;
    private final int lagSustainSeconds;
    private final int lagLongTickMs;
    private final int lagLongTickCount;
    private final int lagCooldownMinutes;
    private final int lagMaxPerDay;
    private final int lagStartupGraceSeconds;
    private final boolean lagUploadBackground;

    private final ZoneId zone;
    private final boolean broadcast;

    private AutoProfilerSettings(Configuration config, Consumer<String> warn) {
        this.scheduledEnabled = config.getBoolean(OPTION_SCHEDULED, false);
        this.lagEnabled = config.getBoolean(OPTION_LAG, false);

        // only report problems with settings of features that are switched on
        Consumer<String> scheduledWarn = this.scheduledEnabled ? warn : NO_WARNINGS;
        Consumer<String> lagWarn = this.lagEnabled ? warn : NO_WARNINGS;
        Consumer<String> commonWarn = this.scheduledEnabled || this.lagEnabled ? warn : NO_WARNINGS;

        this.scheduledArgs = ProfilerArgs.parse(OPTION_SCHEDULED_ARGS,
                config.getString(OPTION_SCHEDULED_ARGS, ""), DEFAULT_SCHEDULED_TIMEOUT, scheduledWarn);
        this.scheduledRandomCount = Math.max(config.getInteger(OPTION_SCHEDULED_RANDOM_COUNT, 3), 0);
        this.scheduledWindows = parseWindows(readList(config, OPTION_SCHEDULED_WINDOWS), scheduledWarn);
        this.scheduledTimes = parseTimes(readList(config, OPTION_SCHEDULED_TIMES), scheduledWarn);
        this.scheduledMinSpacingMinutes = Math.max(config.getInteger(OPTION_SCHEDULED_MIN_SPACING, 60), 0);

        this.lagArgs = ProfilerArgs.parse(OPTION_LAG_ARGS,
                config.getString(OPTION_LAG_ARGS, ""), DEFAULT_LAG_TIMEOUT, lagWarn);
        this.lagTps = parseDecimal(config, OPTION_LAG_TPS, 15, lagWarn);
        this.lagSustainSeconds = Math.min(Math.max(config.getInteger(OPTION_LAG_SUSTAIN, 20), 5), 600);
        this.lagLongTickMs = Math.max(config.getInteger(OPTION_LAG_LONG_TICK_MS, 2000), 0);
        this.lagLongTickCount = Math.max(config.getInteger(OPTION_LAG_LONG_TICK_COUNT, 1), 1);
        this.lagCooldownMinutes = Math.max(config.getInteger(OPTION_LAG_COOLDOWN, 30), 0);
        this.lagMaxPerDay = Math.max(config.getInteger(OPTION_LAG_MAX_PER_DAY, 5), 0);
        this.lagStartupGraceSeconds = Math.max(config.getInteger(OPTION_LAG_STARTUP_GRACE, 120), 0);
        this.lagUploadBackground = config.getBoolean(OPTION_LAG_UPLOAD_BACKGROUND, true);

        this.zone = parseZone(config.getString(OPTION_TIMEZONE, ""), commonWarn);
        this.broadcast = config.getBoolean(OPTION_BROADCAST, true);
    }

    /**
     * Loads the settings from the given configuration.
     *
     * @param config the configuration
     * @param warn receives a message for each setting that was invalid or adjusted
     * @return the settings
     */
    public static AutoProfilerSettings load(Configuration config, Consumer<String> warn) {
        return new AutoProfilerSettings(config, warn);
    }

    /**
     * Reads a list setting given either as a list or as a comma separated string.
     */
    private static List<String> readList(Configuration config, String key) {
        List<String> values = config.getStringList(key);
        if (values.isEmpty()) {
            values = Arrays.asList(config.getString(key, "").split(","));
        }

        List<String> result = new ArrayList<>();
        for (String value : values) {
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static List<TimeWindow> parseWindows(List<String> values, Consumer<String> warn) {
        if (values.isEmpty()) {
            return Collections.singletonList(TimeWindow.parse(DEFAULT_WINDOW));
        }
        List<TimeWindow> windows = new ArrayList<>();
        for (String value : values) {
            try {
                windows.add(TimeWindow.parse(value));
            } catch (IllegalArgumentException e) {
                warn.accept(e.getMessage() + " in " + OPTION_SCHEDULED_WINDOWS + ", skipping it");
            }
        }
        return Collections.unmodifiableList(windows);
    }

    private static List<LocalTime> parseTimes(List<String> values, Consumer<String> warn) {
        List<LocalTime> times = new ArrayList<>();
        for (String value : values) {
            try {
                times.add(TimeWindow.parseTime(value));
            } catch (IllegalArgumentException e) {
                warn.accept(e.getMessage() + " in " + OPTION_SCHEDULED_TIMES + ", skipping it");
            }
        }
        return Collections.unmodifiableList(times);
    }

    private static double parseDecimal(Configuration config, String key, double def, Consumer<String> warn) {
        // FileConfiguration#getString returns the text of a JSON number, so numbers and strings both work
        String value = config.getString(key, null);
        if (value == null || value.trim().isEmpty()) {
            return def;
        }
        try {
            double parsed = Double.parseDouble(value.trim());
            if (Double.isNaN(parsed) || Double.isInfinite(parsed) || parsed < 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException e) {
            warn.accept("Invalid number '" + value + "' for " + key + ", using " + def);
            return def;
        }
    }

    private static ZoneId parseZone(String value, Consumer<String> warn) {
        if (value.trim().isEmpty()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(value.trim());
        } catch (DateTimeException e) {
            ZoneId fallback = ZoneId.systemDefault();
            warn.accept("Invalid time zone '" + value + "' for " + OPTION_TIMEZONE + ", using the system zone " + fallback);
            return fallback;
        }
    }

    public boolean scheduledEnabled() {
        return this.scheduledEnabled;
    }

    public ProfilerArgs scheduledArgs() {
        return this.scheduledArgs;
    }

    public int scheduledRandomCount() {
        return this.scheduledRandomCount;
    }

    public List<TimeWindow> scheduledWindows() {
        return this.scheduledWindows;
    }

    public List<LocalTime> scheduledTimes() {
        return this.scheduledTimes;
    }

    public int scheduledMinSpacingMinutes() {
        return this.scheduledMinSpacingMinutes;
    }

    public boolean lagEnabled() {
        return this.lagEnabled;
    }

    public ProfilerArgs lagArgs() {
        return this.lagArgs;
    }

    public double lagTps() {
        return this.lagTps;
    }

    public int lagSustainSeconds() {
        return this.lagSustainSeconds;
    }

    public int lagLongTickMs() {
        return this.lagLongTickMs;
    }

    public int lagLongTickCount() {
        return this.lagLongTickCount;
    }

    public int lagCooldownMinutes() {
        return this.lagCooldownMinutes;
    }

    public int lagMaxPerDay() {
        return this.lagMaxPerDay;
    }

    public int lagStartupGraceSeconds() {
        return this.lagStartupGraceSeconds;
    }

    public boolean lagUploadBackground() {
        return this.lagUploadBackground;
    }

    public ZoneId zone() {
        return this.zone;
    }

    public boolean broadcast() {
        return this.broadcast;
    }

    /**
     * Profiler arguments configured in {@code /spark profiler start} syntax.
     */
    public static final class ProfilerArgs {
        private final String key;
        private final List<String> tokens;
        private final @Nullable String error;

        private ProfilerArgs(String key, List<String> tokens, @Nullable String error) {
            this.key = key;
            this.tokens = tokens;
            this.error = error;
        }

        /**
         * Parses an argument string.
         *
         * @param key the config key the arguments came from
         * @param raw the argument string, blank for the default
         * @param defaultTimeout the timeout appended when none is given
         * @param warn receives a message when the default timeout is appended
         * @return the parsed arguments
         */
        public static ProfilerArgs parse(String key, String raw, int defaultTimeout, Consumer<String> warn) {
            List<String> tokens = new ArrayList<>();
            for (String token : raw.trim().split("\\s+")) {
                if (!token.isEmpty()) {
                    tokens.add(token);
                }
            }
            if (!tokens.isEmpty() && tokens.get(0).equalsIgnoreCase("start")) {
                tokens.remove(0);
            }
            if (!tokens.isEmpty() && !tokens.get(0).startsWith("--")) {
                return new ProfilerArgs(key, Collections.<String>emptyList(),
                        "expected profiler start flags such as '--timeout 300' but got '" + tokens.get(0) + "'");
            }

            if (!containsFlag(tokens, "timeout")) {
                tokens.add("--timeout");
                tokens.add(Integer.toString(defaultTimeout));
                if (!raw.trim().isEmpty()) {
                    warn.accept("No --timeout given in " + key + ", using --timeout " + defaultTimeout);
                }
            }

            try {
                new Arguments(tokens, false);
            } catch (Arguments.ParseException e) {
                return new ProfilerArgs(key, Collections.<String>emptyList(), e.getMessage());
            }
            return new ProfilerArgs(key, Collections.unmodifiableList(tokens), null);
        }

        private static boolean containsFlag(List<String> tokens, String flag) {
            for (String token : tokens) {
                if (token.toLowerCase(Locale.ROOT).equals("--" + flag)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Gets the config key these arguments came from.
         *
         * @return the key
         */
        public String key() {
            return this.key;
        }

        /**
         * Gets the argument tokens, empty if invalid.
         *
         * @return the tokens
         */
        public List<String> tokens() {
            return this.tokens;
        }

        /**
         * Gets why the arguments are invalid, if they are.
         *
         * @return the error
         */
        public Optional<String> error() {
            return Optional.ofNullable(this.error);
        }

        @Override
        public String toString() {
            return String.join(" ", this.tokens);
        }
    }
}
