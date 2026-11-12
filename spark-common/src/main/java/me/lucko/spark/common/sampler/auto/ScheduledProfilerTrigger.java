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

import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Decides when a scheduled profiler is due, based on the plan for the current day.
 *
 * <p>Not thread-safe: an instance must be confined to one thread or externally synchronized.</p>
 */
public final class ScheduledProfilerTrigger {

    /** The longest a slot may be overdue and still fire */
    public static final Duration MAX_LATENESS = Duration.ofSeconds(120);

    private final ZoneId zone;
    private final Function<LocalDate, List<DailySchedule.Slot>> planner;
    private final BiConsumer<LocalDate, List<DailySchedule.Slot>> planListener;

    private final Deque<DailySchedule.Slot> pending = new ArrayDeque<>();
    private @Nullable LocalDate currentDate;

    /**
     * Creates a new trigger.
     *
     * @param zone the zone that defines the local date
     * @param planner plans the slots for a date
     * @param planListener notified of each new plan
     */
    public ScheduledProfilerTrigger(ZoneId zone, Function<LocalDate, List<DailySchedule.Slot>> planner,
                                    BiConsumer<LocalDate, List<DailySchedule.Slot>> planListener) {
        this.zone = zone;
        this.planner = planner;
        this.planListener = planListener;
    }

    /**
     * Returns the slot that is due now, if any. At most one slot is returned per call.
     *
     * @param now the current time
     * @return the due slot
     */
    public Optional<DailySchedule.Slot> poll(Instant now) {
        LocalDate date = now.atZone(this.zone).toLocalDate();
        if (this.currentDate == null) {
            replan(date);
            dropUntil(now);
            return Optional.empty();
        }
        if (!date.equals(this.currentDate)) {
            replanKeepingOverdue(date, now);
        }
        return takeDue(now);
    }

    /**
     * Gets the slots still to come in the current plan.
     *
     * @return the remaining slots
     */
    public List<DailySchedule.Slot> remainingToday() {
        return Collections.unmodifiableList(new ArrayList<>(this.pending));
    }

    private void replan(LocalDate date) {
        List<DailySchedule.Slot> plan = this.planner.apply(date);
        this.currentDate = date;
        this.pending.clear();
        this.pending.addAll(plan);
        this.planListener.accept(date, plan);
    }

    private void replanKeepingOverdue(LocalDate date, Instant now) {
        List<DailySchedule.Slot> overdue = new ArrayList<>();
        for (DailySchedule.Slot slot : this.pending) {
            if (!slot.time().isAfter(now)) {
                overdue.add(slot);
            }
        }
        replan(date);
        for (int i = overdue.size() - 1; i >= 0; i--) {
            this.pending.addFirst(overdue.get(i));
        }
    }

    private void dropUntil(Instant now) {
        while (!this.pending.isEmpty() && !this.pending.peekFirst().time().isAfter(now)) {
            this.pending.pollFirst();
        }
    }

    private Optional<DailySchedule.Slot> takeDue(Instant now) {
        DailySchedule.Slot fire = null;
        while (!this.pending.isEmpty() && !this.pending.peekFirst().time().isAfter(now)) {
            DailySchedule.Slot slot = this.pending.pollFirst();
            if (Duration.between(slot.time(), now).compareTo(MAX_LATENESS) <= 0) {
                // keep the latest slot that is still on time; any earlier ones are dropped
                fire = slot;
            }
        }
        return Optional.ofNullable(fire);
    }
}
