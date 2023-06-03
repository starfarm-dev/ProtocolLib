/**
 * ProtocolLib - Bukkit server library that allows access to the Minecraft protocol. Copyright (C) 2012 Kristian S.
 * Stangeland
 * <p>
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either version 2 of the License, or (at your option) any later
 * version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * <p>
 * You should have received a copy of the GNU General Public License along with this program; if not, write to the Free
 * Software Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */
package com.comphenix.protocol;

import com.comphenix.protocol.async.AsyncFilterManager;
import com.comphenix.protocol.error.*;
import com.comphenix.protocol.injector.InternalManager;
import com.comphenix.protocol.injector.PacketFilterManager;
import com.comphenix.protocol.utility.*;
import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import org.bukkit.Server;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The main entry point for ProtocolLib.
 *
 * @author Kristian
 */
public class ProtocolLib extends JavaPlugin {

    // Every possible error or warning report type
    public static final ReportType REPORT_CANNOT_DELETE_CONFIG = new ReportType(
            "Cannot delete old ProtocolLib configuration.");

    public static final ReportType REPORT_PLUGIN_LOAD_ERROR = new ReportType("Cannot load ProtocolLib.");
    public static final ReportType REPORT_CANNOT_LOAD_CONFIG = new ReportType("Cannot load configuration");
    public static final ReportType REPORT_PLUGIN_ENABLE_ERROR = new ReportType("Cannot enable ProtocolLib.");

    public static final ReportType REPORT_METRICS_IO_ERROR = new ReportType(
            "Unable to enable metrics due to network problems.");
    public static final ReportType REPORT_METRICS_GENERIC_ERROR = new ReportType(
            "Unable to enable metrics due to network problems.");

    public static final ReportType REPORT_CANNOT_PARSE_MINECRAFT_VERSION = new ReportType(
            "Unable to retrieve current Minecraft version. Assuming %s");
    public static final ReportType REPORT_CANNOT_DETECT_CONFLICTING_PLUGINS = new ReportType(
            "Unable to detect conflicting plugin versions.");
    public static final ReportType REPORT_CANNOT_REGISTER_COMMAND = new ReportType("Cannot register command %s: %s");

    public static final ReportType REPORT_CANNOT_CREATE_TIMEOUT_TASK = new ReportType(
            "Unable to create packet timeout task.");
    public static final ReportType REPORT_CANNOT_UPDATE_PLUGIN = new ReportType("Cannot perform automatic updates.");

    /**
     * The number of milliseconds per second.
     */
    static final long MILLI_PER_SECOND = TimeUnit.SECONDS.toMillis(1);

    private static final int ASYNC_MANAGER_DELAY = 1;

    // these fields are only existing once, we can make them static
    private static Logger logger;

    private static InternalManager protocolManager;
    private static ErrorReporter reporter = new BasicErrorReporter();

    private int packetTask = -1;
    private int tickCounter = 0;
    private int configExpectedMod = -1;

    // Whether disabling field resetting is needed
    private boolean skipDisable;

    @Override
    public void onLoad() {
        // Logging
        logger = this.getLogger();
        ProtocolLogger.init(this);

        JavaInternals.init();

        // Initialize enhancer factory
        ByteBuddyFactory.getInstance().setClassLoader(this.getClassLoader());

        // Add global parameters
        DetailedErrorReporter detailedReporter = new DetailedErrorReporter(this);
        reporter = this.getFilteredReporter(detailedReporter);
        try {
            // Check for other versions
            this.checkConflictingVersions();

            // Handle unexpected Minecraft versions
            MinecraftVersion version = this.verifyMinecraftVersion();

            // api init
            protocolManager = PacketFilterManager.newBuilder()
                    .server(this.getServer())
                    .library(this)
                    .minecraftVersion(version)
                    .reporter(reporter)
                    .build();
            ProtocolLibrary.init(this, protocolManager, reporter);

            // Setup error reporter
            detailedReporter.addGlobalParameter("manager", protocolManager);

        } catch (Exception e) {
            reporter.reportDetailed(this, Report.newBuilder(REPORT_PLUGIN_LOAD_ERROR).error(e).callerParam(protocolManager));
            this.disablePlugin();
        }
    }

    /**
     * Retrieve a error reporter that may be filtered by the configuration.
     *
     * @return The new default error reporter.
     */
    private ErrorReporter getFilteredReporter(ErrorReporter reporter) {
        return new DelegatedErrorReporter(reporter) {
            private int lastModCount = -1;
            private Set<String> reports = new HashSet<>();

            @Override
            protected Report filterReport(Object sender, Report report, boolean detailed) {
                try {
                    String canonicalName = ReportType.getReportName(sender, report.getType());
                    String reportName = Iterables.getLast(Splitter.on("#").split(canonicalName)).toUpperCase();

                    // Cancel reports either on the full canonical name, or just the report name
                    if (this.reports.contains(canonicalName) || this.reports.contains(reportName)) {
                        return null;
                    }

                } catch (Exception e) {
                    // Only report this with a minor message
                    logger.warning("Error filtering reports: " + e);
                }
                // Don't filter anything
                return report;
            }
        };
    }

    @Override
    public void onEnable() {
        try {
            Server server = this.getServer();
            PluginManager manager = server.getPluginManager();

            // Silly plugin reloaders!
            if (protocolManager == null) {
                Logger directLogging = Logger.getLogger("Minecraft");
                String[] message = new String[]{
                        " ProtocolLib does not support plugin reloaders! ", " Please use the built-in reload command! "
                };

                // Print as severe
                for (String line : ChatExtensions.toFlowerBox(message, "*", 3, 1)) {
                    directLogging.severe(line);
                }

                this.disablePlugin();
                return;
            }

            // Player login and logout events
            protocolManager.registerEvents(manager, this);

            // Worker that ensures that async packets are eventually sent
            // It also performs the update check.
            this.createPacketTask(server);
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable e) {
            reporter.reportDetailed(this, Report.newBuilder(REPORT_PLUGIN_ENABLE_ERROR).error(e));
            this.disablePlugin();
            return;
        }
    }

    // Used to check Minecraft version
    private MinecraftVersion verifyMinecraftVersion() {
        MinecraftVersion minimum = new MinecraftVersion(ProtocolLibrary.MINIMUM_MINECRAFT_VERSION);
        MinecraftVersion maximum = new MinecraftVersion(ProtocolLibrary.MAXIMUM_MINECRAFT_VERSION);

        try {
            MinecraftVersion current = new MinecraftVersion(this.getServer());

            return current;
        } catch (Exception e) {
            reporter.reportWarning(this,
                    Report.newBuilder(REPORT_CANNOT_PARSE_MINECRAFT_VERSION).error(e).messageParam(maximum));

            // Unknown version - just assume it is the latest
            return maximum;
        }
    }

    private void checkConflictingVersions() {
        Pattern ourPlugin = Pattern.compile("ProtocolLib-(.*)\\.jar");
        MinecraftVersion currentVersion = new MinecraftVersion(this.getDescription().getVersion());
        MinecraftVersion newestVersion = null;

        // Skip the file that contains this current instance however
        File loadedFile = this.getFile();

        try {
            // Scan the plugin folder for newer versions of ProtocolLib
            // The plugin folder isn't always plugins/
            File pluginFolder = this.getDataFolder().getParentFile();

            File[] candidates = pluginFolder.listFiles();
            if (candidates != null) {
                for (File candidate : candidates) {
                    if (candidate.isFile() && !candidate.equals(loadedFile)) {
                        Matcher match = ourPlugin.matcher(candidate.getName());
                        if (match.matches()) {
                            MinecraftVersion version = new MinecraftVersion(match.group(1));

                            if (candidate.length() == 0) {
                                // Delete and inform the user
                                logger.info((candidate.delete() ? "Deleted " : "Could not delete ") + candidate);
                            } else if (newestVersion == null || newestVersion.compareTo(version) < 0) {
                                newestVersion = version;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // TODO This shows [ProtocolLib] and [ProtocolLibrary] in the message
            reporter.reportWarning(this, Report.newBuilder(REPORT_CANNOT_DETECT_CONFLICTING_PLUGINS).error(e));
        }

        // See if the newest version is actually higher
        if (newestVersion != null && currentVersion.compareTo(newestVersion) < 0) {
            // We don't need to set internal classes or instances to NULL - that would break the other loaded plugin
            this.skipDisable = true;

            throw new IllegalStateException(String.format(
                    "Detected a newer version of ProtocolLib (%s) in plugin folder than the current (%s). Disabling.",
                    newestVersion.getVersion(), currentVersion.getVersion()));
        }
    }

    private void registerCommand(String name, CommandExecutor executor) {
        try {
            // Ignore these - they must have printed an error already
            if (executor == null) {
                return;
            }

            PluginCommand command = this.getCommand(name);

            // Try to load the command
            if (command != null) {
                command.setExecutor(executor);
            } else {
                throw new RuntimeException("plugin.yml might be corrupt.");
            }
        } catch (RuntimeException e) {
            reporter.reportWarning(this,
                    Report.newBuilder(REPORT_CANNOT_REGISTER_COMMAND).messageParam(name, e.getMessage()).error(e));
        }
    }

    /**
     * Disable the current plugin.
     */
    private void disablePlugin() {
        this.getServer().getPluginManager().disablePlugin(this);
    }

    private void createPacketTask(Server server) {
        try {
            if (this.packetTask >= 0) {
                throw new IllegalStateException("Packet task has already been created");
            }

            // Attempt to create task
            this.packetTask = server.getScheduler().scheduleSyncRepeatingTask(this, () -> {
                AsyncFilterManager manager = (AsyncFilterManager) protocolManager.getAsynchronousManager();

                // We KNOW we're on the main thread at the moment
                manager.sendProcessedPackets(ProtocolLib.this.tickCounter++, true);
            }, ASYNC_MANAGER_DELAY, ASYNC_MANAGER_DELAY);
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable e) {
            if (this.packetTask == -1) {
                reporter.reportDetailed(this, Report.newBuilder(REPORT_CANNOT_CREATE_TIMEOUT_TASK).error(e));
            }
        }
    }

    @Override
    public void onDisable() {
        if (this.skipDisable) {
            return;
        }

        // that reloading the server might break ProtocolLib / plugins depending on it
        if (Util.isCurrentlyReloading()) {
            logger.severe("╔══════════════════════════════════════════════════════════════════╗");
            logger.severe("║                               WARNING                            ║");
            logger.severe("║     RELOADING THE SERVER WHILE PROTOCOL LIB IS ENABLED MIGHT     ║");
            logger.severe("║                    LEAD TO UNEXPECTED ERRORS!                    ║");
            logger.severe("║                                                                  ║");
            logger.severe("║     Consider to cleanly restart your server if you encounter     ║");
            logger.severe("║    any issues related to Protocol Lib before opening an issue    ║");
            logger.severe("║                            on GitHub!                            ║");
            logger.severe("╚══════════════════════════════════════════════════════════════════╝");
        }

        // Clean up
        if (this.packetTask >= 0) {
            this.getServer().getScheduler().cancelTask(this.packetTask);
            this.packetTask = -1;
        }

        if (protocolManager != null) {
            protocolManager.close();
        } else {
            return; // Plugin reloaders!
        }

        protocolManager = null;

        // To clean up global parameters
        reporter = new BasicErrorReporter();
    }

}
