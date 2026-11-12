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

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Records what the test plugin logs while open.
 */
public final class LogCapture extends Handler implements AutoCloseable {

    /** The logger the test plugin writes to */
    private static final Logger LOGGER = Logger.getLogger("spark-test");

    private final List<LogRecord> records = new ArrayList<>();

    public LogCapture() {
        LOGGER.addHandler(this);
    }

    @Override
    public synchronized void publish(LogRecord record) {
        this.records.add(record);
    }

    /**
     * Gets the messages logged at the given level that contain the given text.
     *
     * @param level the level
     * @param text the text
     * @return the messages
     */
    public synchronized List<String> messages(Level level, String text) {
        List<String> messages = new ArrayList<>();
        for (LogRecord record : this.records) {
            if (record.getLevel().equals(level) && record.getMessage().contains(text)) {
                messages.add(record.getMessage());
            }
        }
        return messages;
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
        LOGGER.removeHandler(this);
    }
}
