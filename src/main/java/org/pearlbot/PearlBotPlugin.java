/*
 * PearlBot — a ZenithProxy plugin for on-demand stasis chamber pulls.
 * Copyright (C) 2026 Leonetic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.pearlbot;

import com.zenith.plugin.api.Plugin;
import com.zenith.plugin.api.PluginAPI;
import com.zenith.plugin.api.ZenithProxyPlugin;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.pearlbot.command.PearlBotCommand;
import org.pearlbot.module.AutoPearlModule;
import org.pearlbot.module.EnderPearlTrackerModule;

@Plugin(
    id = BuildConstants.PLUGIN_ID,
    version = BuildConstants.VERSION,
    description = "Stasis chamber detection and pulling, with chat + Discord triggers",
    url = "",
    authors = {"Leonetic"},
    mcVersions = {BuildConstants.MC_VERSION}
)
public class PearlBotPlugin implements ZenithProxyPlugin {
    public static PluginAPI API;
    public static PearlBotConfig PLUGIN_CONFIG;
    public static PearlBotMessages PLUGIN_MESSAGES;
    public static ComponentLogger LOG;

    @Override
    public void onLoad(PluginAPI pluginAPI) {
        API = pluginAPI;
        LOG = pluginAPI.getLogger();
        LOG.info("PearlBot loading...");
        PLUGIN_CONFIG = API.registerConfig(BuildConstants.PLUGIN_ID, PearlBotConfig.class);
        PLUGIN_MESSAGES = API.registerConfig(BuildConstants.PLUGIN_ID + "-messages", PearlBotMessages.class);
        API.registerModule(new EnderPearlTrackerModule());
        API.registerModule(new AutoPearlModule());
        API.registerCommand(new PearlBotCommand());
        LOG.info("PearlBot loaded!");
    }
}
