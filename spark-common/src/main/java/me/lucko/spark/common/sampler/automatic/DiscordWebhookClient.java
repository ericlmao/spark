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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.lucko.spark.common.SparkPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

final class DiscordWebhookClient implements AutoCloseable {
    private static final Gson GSON = new Gson();
    private static final int MAX_ATTEMPTS = 3;
    private static final int CONNECT_TIMEOUT_MILLIS = 15_000;
    private static final int READ_TIMEOUT_MILLIS = 30_000;

    private final SparkPlugin plugin;
    private final URI webhookUri;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    DiscordWebhookClient(SparkPlugin plugin, URI webhookUri, ScheduledExecutorService executor) {
        this.plugin = plugin;
        this.webhookUri = webhookUri;
        this.executor = executor;
    }

    static URI parseWebhookUri(String input) {
        URI uri;
        try {
            uri = new URI(input);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Discord webhook URL is malformed");
        }

        String host = uri.getHost();
        String path = uri.getPath();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Discord webhook URL must be an HTTPS URL");
        }

        host = host.toLowerCase(Locale.ROOT);
        boolean discordHost = host.equals("discord.com") || host.endsWith(".discord.com")
                || host.equals("discordapp.com") || host.endsWith(".discordapp.com");
        String webhookPrefix = "/api/webhooks/";
        String[] webhookParts = path != null && path.startsWith(webhookPrefix)
                ? path.substring(webhookPrefix.length()).split("/")
                : new String[0];
        if (!discordHost || webhookParts.length < 2 || webhookParts[0].isEmpty() || webhookParts[1].isEmpty()) {
            throw new IllegalArgumentException("Discord webhook URL must point to Discord's webhook API");
        }
        return uri;
    }

    void send(Path file, String profilerName, ZonedDateTime completedAt) {
        if (!this.closed.get()) {
            this.executor.execute(() -> attempt(file, profilerName, completedAt, 1));
        }
    }

    private void attempt(Path file, String profilerName, ZonedDateTime completedAt, int attempt) {
        if (this.closed.get()) {
            return;
        }

        UploadResult result;
        try {
            result = upload(file, profilerName, completedAt);
        } catch (IOException ignored) {
            if (attempt < MAX_ATTEMPTS) {
                scheduleRetry(file, profilerName, completedAt, attempt, defaultRetryDelay(attempt));
            } else {
                this.plugin.log(Level.WARNING, "Failed to send automatic profiler '" + profilerName + "' to Discord after " + attempt + " attempts. The local file was retained.");
            }
            return;
        }

        if (result.statusCode >= 200 && result.statusCode < 300) {
            this.plugin.log(Level.INFO, "Sent automatic profiler '" + profilerName + "' to Discord.");
            return;
        }

        boolean retryable = result.statusCode == 429 || result.statusCode >= 500;
        if (retryable && attempt < MAX_ATTEMPTS) {
            long delay = result.retryAfterMillis > 0 ? result.retryAfterMillis : defaultRetryDelay(attempt);
            scheduleRetry(file, profilerName, completedAt, attempt, delay);
        } else {
            this.plugin.log(Level.WARNING, "Discord rejected automatic profiler '" + profilerName + "' with HTTP status " + result.statusCode + ". The local file was retained.");
        }
    }

    private void scheduleRetry(Path file, String profilerName, ZonedDateTime completedAt, int attempt, long delayMillis) {
        this.plugin.log(Level.WARNING, "Discord delivery for automatic profiler '" + profilerName + "' failed; retrying (attempt " + (attempt + 1) + " of " + MAX_ATTEMPTS + ").");
        if (!this.closed.get()) {
            this.executor.schedule(() -> attempt(file, profilerName, completedAt, attempt + 1), delayMillis, TimeUnit.MILLISECONDS);
        }
    }

    private UploadResult upload(Path file, String profilerName, ZonedDateTime completedAt) throws IOException {
        String boundary = "spark-" + UUID.randomUUID();
        HttpURLConnection connection = (HttpURLConnection) this.webhookUri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        connection.setDoOutput(true);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        connection.setRequestProperty("User-Agent", "spark/" + this.plugin.getVersion());
        connection.setChunkedStreamingMode(8192);

        try {
            try (OutputStream output = connection.getOutputStream()) {
                writeMultipart(output, boundary, file, createPayload(profilerName, completedAt));
            }

            int statusCode = connection.getResponseCode();
            long retryAfterMillis = parseRetryAfterMillis(connection.getHeaderField("Retry-After"));
            try {
                consume(statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream());
            } catch (IOException ignored) {
                // The response status is authoritative; a failed body read must not duplicate an accepted upload.
            }
            return new UploadResult(statusCode, retryAfterMillis);
        } finally {
            connection.disconnect();
        }
    }

    static String createPayload(String profilerName, ZonedDateTime completedAt) {
        JsonObject payload = new JsonObject();
        payload.addProperty("content", "Automatic profiler '" + profilerName + "' completed at " + DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(completedAt) + ".");

        JsonObject allowedMentions = new JsonObject();
        allowedMentions.add("parse", new JsonArray());
        payload.add("allowed_mentions", allowedMentions);
        return GSON.toJson(payload);
    }

    static long parseRetryAfterMillis(String value) {
        if (value == null || value.isEmpty()) {
            return -1;
        }
        try {
            return Math.max(0L, (long) (Double.parseDouble(value) * 1000L));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static long defaultRetryDelay(int attempt) {
        return TimeUnit.SECONDS.toMillis(5L << (attempt - 1));
    }

    static void writeMultipart(OutputStream output, String boundary, Path file, String payload) throws IOException {
        writePart(output, boundary, "payload_json", "application/json", null, payload.getBytes(StandardCharsets.UTF_8));

        String filename = file.getFileName().toString().replace("\\", "_").replace("\"", "_");
        write(output, "--" + boundary + "\r\n");
        write(output, "Content-Disposition: form-data; name=\"files[0]\"; filename=\"" + filename + "\"\r\n");
        write(output, "Content-Type: application/octet-stream\r\n\r\n");
        Files.copy(file, output);
        write(output, "\r\n--" + boundary + "--\r\n");
    }

    private static void writePart(OutputStream output, String boundary, String name, String contentType, String filename, byte[] value) throws IOException {
        write(output, "--" + boundary + "\r\n");
        write(output, "Content-Disposition: form-data; name=\"" + name + "\"" + (filename == null ? "" : "; filename=\"" + filename + "\"") + "\r\n");
        write(output, "Content-Type: " + contentType + "\r\n\r\n");
        output.write(value);
        write(output, "\r\n");
    }

    private static void write(OutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void consume(InputStream input) throws IOException {
        if (input == null) {
            return;
        }
        try (InputStream stream = input) {
            byte[] buffer = new byte[1024];
            while (stream.read(buffer) != -1) {
                // consume the response so the connection can be released
            }
        }
    }

    @Override
    public void close() {
        this.closed.set(true);
    }

    private static final class UploadResult {
        private final int statusCode;
        private final long retryAfterMillis;

        private UploadResult(int statusCode, long retryAfterMillis) {
            this.statusCode = statusCode;
            this.retryAfterMillis = retryAfterMillis;
        }
    }
}
