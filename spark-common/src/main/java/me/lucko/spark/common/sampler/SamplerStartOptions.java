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

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import me.lucko.spark.common.SparkPlatform;
import me.lucko.spark.common.command.Arguments;
import me.lucko.spark.common.sampler.java.MergeStrategy;
import me.lucko.spark.common.tick.TickHook;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The options accepted by {@code /spark profiler start}, parsed from command arguments.
 *
 * <p>A {@link ThreadDumper} may cache the threads it resolved when it was created, so a
 * parsed instance should be used for a single profiler session only.</p>
 */
public final class SamplerStartOptions {

    /** Every flag understood by the profiler start and export logic */
    public static final Set<String> KNOWN_FLAGS = ImmutableSet.of(
            "timeout", "alloc", "lock", "alloc-live-only", "interval", "ignore-sleeping",
            "force-java-sampler", "thread", "regex", "combine-all", "not-combined", "only-ticks-over",
            "comment", "save-to-file", "separate-parent-calls"
    );

    private final int timeoutSeconds;
    private final SamplerMode mode;
    private final boolean allocLiveOnly;
    private final double interval;
    private final boolean ignoreSleeping;
    private final boolean forceJavaSampler;
    private final ThreadDumper threadDumper;
    private final Supplier<ThreadGrouper> threadGrouper;
    private final int ticksOver;
    private final @Nullable TickHook tickHook;
    private final boolean saveToFile;
    private final @Nullable String comment;
    private final MergeStrategy mergeStrategy;

    private SamplerStartOptions(Builder builder) {
        this.timeoutSeconds = builder.timeoutSeconds;
        this.mode = builder.mode;
        this.allocLiveOnly = builder.allocLiveOnly;
        this.interval = builder.interval;
        this.ignoreSleeping = builder.ignoreSleeping;
        this.forceJavaSampler = builder.forceJavaSampler;
        this.threadDumper = builder.threadDumper;
        this.threadGrouper = builder.threadGrouper;
        this.ticksOver = builder.ticksOver;
        this.tickHook = builder.tickHook;
        this.saveToFile = builder.saveToFile;
        this.comment = builder.comment;
        this.mergeStrategy = builder.mergeStrategy;
    }

    /**
     * Parses the profiler start options from the given arguments.
     *
     * @param platform the platform
     * @param arguments the arguments
     * @return the parsed options
     * @throws Arguments.ParseException if the arguments are invalid
     */
    public static SamplerStartOptions parse(SparkPlatform platform, Arguments arguments) throws Arguments.ParseException {
        Builder builder = new Builder();
        builder.timeoutSeconds = parseTimeout(arguments);
        builder.mode = parseMode(arguments);
        builder.allocLiveOnly = arguments.boolFlag("alloc-live-only");
        builder.interval = parseInterval(arguments, builder.mode);
        builder.ignoreSleeping = arguments.boolFlag("ignore-sleeping");
        builder.forceJavaSampler = arguments.boolFlag("force-java-sampler");
        builder.threadDumper = parseThreadDumper(platform, arguments);
        builder.threadGrouper = parseThreadGrouper(arguments);
        builder.ticksOver = arguments.intFlag("only-ticks-over");
        if (builder.ticksOver != -1) {
            builder.tickHook = platform.getTickHook();
            if (builder.tickHook == null) {
                throw new Arguments.ParseException("Tick counting is not supported!");
            }
        }
        builder.saveToFile = arguments.boolFlag("save-to-file");
        builder.comment = Iterables.getFirst(arguments.stringFlag("comment"), null);
        builder.mergeStrategy = arguments.boolFlag("separate-parent-calls") ? MergeStrategy.SEPARATE_PARENT_CALLS : MergeStrategy.SAME_METHOD;
        return new SamplerStartOptions(builder);
    }

    private static int parseTimeout(Arguments arguments) {
        int timeoutSeconds = arguments.intFlag("timeout");
        if (timeoutSeconds != -1 && timeoutSeconds <= 10) {
            throw new Arguments.ParseException("The specified timeout is not long enough for accurate results to be formed. " +
                    "Please choose a value greater than 10.");
        }
        return timeoutSeconds;
    }

    private static SamplerMode parseMode(Arguments arguments) {
        boolean alloc = arguments.boolFlag("alloc");
        boolean lock = arguments.boolFlag("lock");
        if (alloc && lock) {
            throw new Arguments.ParseException("The --alloc and --lock flags cannot be used together. Please choose one profiling mode.");
        }
        return alloc ? SamplerMode.ALLOCATION : lock ? SamplerMode.LOCK : SamplerMode.EXECUTION;
    }

    private static double parseInterval(Arguments arguments, SamplerMode mode) {
        double interval = arguments.doubleFlag("interval");
        if (interval <= 0) {
            interval = mode.defaultInterval();
        }
        return interval;
    }

    private static ThreadDumper parseThreadDumper(SparkPlatform platform, Arguments arguments) {
        Set<String> threads = arguments.stringFlag("thread");
        if (threads.isEmpty()) {
            // use the server thread
            return platform.getPlugin().getDefaultThreadDumper();
        } else if (threads.contains("*")) {
            return ThreadDumper.ALL;
        } else if (arguments.boolFlag("regex")) {
            return new ThreadDumper.Regex(threads);
        } else {
            // specific matches
            return new ThreadDumper.Specific(threads);
        }
    }

    private static Supplier<ThreadGrouper> parseThreadGrouper(Arguments arguments) {
        if (arguments.boolFlag("combine-all")) {
            return ThreadGrouper.AS_ONE;
        } else if (arguments.boolFlag("not-combined")) {
            return ThreadGrouper.BY_NAME;
        } else {
            return ThreadGrouper.BY_POOL;
        }
    }

    /**
     * Creates a new sampler builder with these options applied. The timeout, if any,
     * is measured from the time of this call.
     *
     * @return a new builder
     */
    public SamplerBuilder createBuilder() {
        SamplerBuilder builder = new SamplerBuilder();
        builder.mode(this.mode);
        builder.threadDumper(this.threadDumper);
        builder.threadGrouper(this.threadGrouper);
        if (this.timeoutSeconds != -1) {
            builder.completeAfter(this.timeoutSeconds, TimeUnit.SECONDS);
        }
        builder.samplingInterval(this.interval);
        builder.ignoreSleeping(this.ignoreSleeping);
        builder.forceJavaSampler(this.forceJavaSampler);
        builder.allocLiveOnly(this.allocLiveOnly);
        if (this.ticksOver != -1) {
            builder.ticksOver(this.ticksOver, this.tickHook);
        }
        return builder;
    }

    public int timeoutSeconds() {
        return this.timeoutSeconds;
    }

    public SamplerMode mode() {
        return this.mode;
    }

    public double interval() {
        return this.interval;
    }

    public ThreadDumper threadDumper() {
        return this.threadDumper;
    }

    public int ticksOver() {
        return this.ticksOver;
    }

    public boolean saveToFile() {
        return this.saveToFile;
    }

    public Optional<String> comment() {
        return Optional.ofNullable(this.comment);
    }

    public MergeStrategy mergeStrategy() {
        return this.mergeStrategy;
    }

    private static final class Builder {
        private int timeoutSeconds = -1;
        private SamplerMode mode = SamplerMode.EXECUTION;
        private boolean allocLiveOnly;
        private double interval;
        private boolean ignoreSleeping;
        private boolean forceJavaSampler;
        private ThreadDumper threadDumper = ThreadDumper.ALL;
        private Supplier<ThreadGrouper> threadGrouper = ThreadGrouper.BY_POOL;
        private int ticksOver = -1;
        private @Nullable TickHook tickHook;
        private boolean saveToFile;
        private @Nullable String comment;
        private MergeStrategy mergeStrategy = MergeStrategy.SAME_METHOD;
    }
}
