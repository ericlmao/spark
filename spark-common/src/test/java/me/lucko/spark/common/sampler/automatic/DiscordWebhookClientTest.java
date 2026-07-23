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

import com.sun.net.httpserver.HttpServer;
import me.lucko.spark.common.util.SparkScheduledThreadPoolExecutor;
import me.lucko.spark.test.plugin.TestSparkPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DiscordWebhookClientTest {

    @Test
    public void testDiscordWebhookValidation() {
        URI uri = DiscordWebhookClient.parseWebhookUri("https://discord.com/api/webhooks/123/token");
        assertEquals("discord.com", uri.getHost());

        assertThrows(IllegalArgumentException.class, () -> DiscordWebhookClient.parseWebhookUri("http://discord.com/api/webhooks/123/token"));
        assertThrows(IllegalArgumentException.class, () -> DiscordWebhookClient.parseWebhookUri("https://discord.com.example.org/api/webhooks/123/token"));
        assertThrows(IllegalArgumentException.class, () -> DiscordWebhookClient.parseWebhookUri("https://discord.com/channels/123"));
    }

    @Test
    public void testPayloadDisablesMentionsAndIdentifiesProfiler() {
        String payload = DiscordWebhookClient.createPayload(
                "normal-profiler @everyone",
                ZonedDateTime.of(2026, 7, 23, 12, 0, 0, 0, ZoneOffset.UTC)
        );

        assertTrue(payload.contains("normal-profiler @everyone"));
        assertTrue(payload.contains("\"allowed_mentions\""));
        assertTrue(payload.contains("\"parse\":[]"));
    }

    @Test
    public void testRetryAfterParsing() {
        assertEquals(1500, DiscordWebhookClient.parseRetryAfterMillis("1.5"));
        assertEquals(-1, DiscordWebhookClient.parseRetryAfterMillis(null));
        assertEquals(-1, DiscordWebhookClient.parseRetryAfterMillis("invalid"));
    }

    @Test
    public void testMultipartContainsProfileAttachment(@TempDir Path directory) throws Exception {
        Path profile = directory.resolve("MyServer Automatic Profiler - 23-July-2026.sparkprofile");
        byte[] profileData = "spark-profile-data".getBytes(StandardCharsets.UTF_8);
        Files.write(profile, profileData);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DiscordWebhookClient.writeMultipart(output, "test-boundary", profile, "{\"content\":\"complete\"}");
        String multipart = new String(output.toByteArray(), StandardCharsets.UTF_8);

        assertTrue(multipart.contains("name=\"payload_json\""));
        assertTrue(multipart.contains("name=\"files[0]\"; filename=\"MyServer Automatic Profiler - 23-July-2026.sparkprofile\""));
        assertTrue(multipart.contains("spark-profile-data"));
        assertTrue(multipart.endsWith("--test-boundary--\r\n"));
    }

    @Test
    public void testWebhookSendsAttachmentAndRetainsFile(@TempDir Path directory) throws Exception {
        Path profile = directory.resolve("MyServer Automatic Profiler - 23-July-2026.sparkprofile");
        Files.write(profile, "spark-profile-data".getBytes(StandardCharsets.UTF_8));

        CountDownLatch requestReceived = new CountDownLatch(1);
        AtomicReference<byte[]> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhook", exchange -> {
            try (InputStream input = exchange.getRequestBody(); ByteArrayOutputStream body = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    body.write(buffer, 0, read);
                }
                requestBody.set(body.toByteArray());
                exchange.sendResponseHeaders(204, -1);
            } finally {
                exchange.close();
                requestReceived.countDown();
            }
        });
        server.start();

        ScheduledExecutorService executor = new SparkScheduledThreadPoolExecutor(1);
        try (TestSparkPlugin plugin = new TestSparkPlugin(directory.resolve("spark"));
             DiscordWebhookClient client = new DiscordWebhookClient(
                     plugin,
                     URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/webhook"),
                     executor
             )) {
            client.send(profile, "normal-profiler", ZonedDateTime.now(ZoneOffset.UTC));
            assertTrue(requestReceived.await(5, TimeUnit.SECONDS));

            String body = new String(requestBody.get(), StandardCharsets.UTF_8);
            assertTrue(body.contains("name=\"files[0]\"; filename=\"MyServer Automatic Profiler - 23-July-2026.sparkprofile\""));
            assertTrue(body.contains("spark-profile-data"));
            assertTrue(Files.exists(profile));
        } finally {
            executor.shutdownNow();
            server.stop(0);
        }
    }
}
