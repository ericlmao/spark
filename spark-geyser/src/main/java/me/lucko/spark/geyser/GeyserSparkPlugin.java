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

package me.lucko.spark.geyser;

import me.lucko.spark.common.SparkPlatform;
import me.lucko.spark.common.SparkPlugin;
import me.lucko.spark.common.monitor.ping.PlayerPingProvider;
import me.lucko.spark.common.platform.PlatformInfo;
import me.lucko.spark.common.sampler.source.ClassSourceLookup;
import me.lucko.spark.common.sampler.source.SourceMetadata;
import me.lucko.spark.common.util.SparkThreadFactory;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.command.Command;
import org.geysermc.geyser.api.command.CommandSource;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCommandsEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPreInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserRegisterPermissionsEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserShutdownEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.api.util.PlatformType;
import org.geysermc.geyser.api.util.TriState;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.stream.Stream;

public class GeyserSparkPlugin implements SparkPlugin, Extension {

    private ExecutorService asyncExecutor;
    private SparkPlatform platform;

    // Geyser registers the extension as a listener when it is enabled, which happens
    // before any of the lifecycle events below are fired.
    @Subscribe
    public void onPreInitialize(GeyserPreInitializeEvent e) {
        if (!PlatformType.STANDALONE.equals(this.geyserApi().platformType())) {
            this.logger().warning("spark for Geyser is only supported on Geyser Standalone; use the spark plugin/mod for the underlying server/proxy instead.");
            this.setEnabled(false);
            return;
        }

        this.asyncExecutor = Executors.newCachedThreadPool(new SparkThreadFactory("spark-geyser-async", true));
        // constructed early (but not enabled) so that the commands are known when GeyserDefineCommandsEvent fires
        this.platform = new SparkPlatform(this);
    }

    @Subscribe
    public void onDefineCommands(GeyserDefineCommandsEvent e) {
        if (this.platform == null) {
            return;
        }

        // extension commands are always registered as subcommands of the root command ("/spark <command>")
        for (me.lucko.spark.common.command.Command command : this.platform.getCommandManager().getCommands()) {
            String primaryAlias = command.primaryAlias();
            List<String> aliases = command.aliases().subList(1, command.aliases().size());

            e.register(Command.<CommandSource>builder(this)
                    .source(CommandSource.class)
                    .name(primaryAlias)
                    .description("spark " + primaryAlias)
                    .permission("spark." + primaryAlias)
                    .aliases(aliases)
                    .executor((source, cmd, args) -> {
                        String[] sparkArgs = Stream.concat(
                                Stream.of(primaryAlias),
                                Arrays.stream(args).filter(arg -> !arg.isEmpty())
                        ).toArray(String[]::new);
                        this.platform.executeCommand(new GeyserCommandSender(source), sparkArgs);
                    })
                    .build()
            );
        }
    }

    @Subscribe
    public void onRegisterPermissions(GeyserRegisterPermissionsEvent e) {
        if (this.platform == null) {
            return;
        }

        for (String permission : this.platform.getCommandManager().getAllSparkPermissions()) {
            e.register(permission, TriState.NOT_SET);
        }
    }

    @Subscribe
    public void onPostInitialize(GeyserPostInitializeEvent e) {
        if (this.platform == null) {
            return;
        }

        this.platform.enable();
    }

    @Subscribe
    public void onShutdown(GeyserShutdownEvent e) {
        if (this.platform == null) {
            return;
        }

        this.platform.disable();
        this.asyncExecutor.shutdown();
    }

    @Override
    public String getVersion() {
        return this.description().version();
    }

    @Override
    public Path getPluginDirectory() {
        return this.dataFolder();
    }

    @Override
    public String getCommandName() {
        return "spark";
    }

    @Override
    public Stream<GeyserCommandSender> getCommandSenders() {
        return Stream.<CommandSource>concat(
                this.geyserApi().onlineConnections().stream(),
                Stream.of(this.geyserApi().consoleCommandSource())
        ).map(GeyserCommandSender::new);
    }

    @Override
    public void executeAsync(Runnable task) {
        this.asyncExecutor.execute(task);
    }

    @Override
    public void log(Level level, String msg) {
        if (level.intValue() >= 1000) { // severe
            this.logger().severe(msg);
        } else if (level.intValue() >= 900) { // warning
            this.logger().warning(msg);
        } else {
            this.logger().info(msg);
        }
    }

    @Override
    public void log(Level level, String msg, Throwable throwable) {
        if (level.intValue() >= 1000) { // severe
            this.logger().severe(msg, throwable);
        } else if (level.intValue() >= 900) { // warning
            this.logger().warning(msg + "\n" + stackTraceToString(throwable));
        } else {
            this.logger().info(msg + "\n" + stackTraceToString(throwable));
        }
    }

    private static String stackTraceToString(Throwable throwable) {
        StringWriter stringWriter = new StringWriter();
        throwable.printStackTrace(new PrintWriter(stringWriter));
        return stringWriter.toString();
    }

    @Override
    public ClassSourceLookup createClassSourceLookup() {
        return new GeyserClassSourceLookup(this.extensionManager());
    }

    @Override
    public Collection<SourceMetadata> getKnownSources() {
        return SourceMetadata.gather(
                this.extensionManager().extensions(),
                extension -> extension.description().id(),
                extension -> extension.description().version(),
                extension -> String.join(", ", extension.description().authors()),
                extension -> null
        );
    }

    @Override
    public PlayerPingProvider createPlayerPingProvider() {
        return new GeyserPlayerPingProvider(this.geyserApi());
    }

    @Override
    public PlatformInfo getPlatformInfo() {
        return new GeyserPlatformInfo(this.geyserApi());
    }
}
