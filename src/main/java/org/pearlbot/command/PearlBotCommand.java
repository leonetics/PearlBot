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
package org.pearlbot.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.discord.Embed;
import com.zenith.feature.whitelist.PlayerListsManager;
import org.pearlbot.PearlBotConfig;
import org.pearlbot.module.AutoPearlModule;
import org.pearlbot.module.EnderPearlTrackerModule;

import java.util.UUID;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.zenith.Globals.MODULE;
import static com.zenith.command.brigadier.CustomStringArgumentType.getString;
import static com.zenith.command.brigadier.CustomStringArgumentType.wordWithChars;
import static com.zenith.command.brigadier.ToggleArgumentType.getToggle;
import static com.zenith.command.brigadier.ToggleArgumentType.toggle;
import static org.pearlbot.PearlBotPlugin.PLUGIN_CONFIG;

public class PearlBotCommand extends Command {
    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("pearlbot")
            .category(CommandCategory.MODULE)
            .description("Stasis chamber detection and pulling.")
            .usageLines(
                "<on/off>",
                "pull <playerName>",
                "cancel <playerName>",
                "list",
                "chamber remove <playerName>",
                "chamber prune <radius>",
                "chamber clear",
                "pending",
                "clearpending",
                "idle <on/off>",
                "idle here",
                "idle <x> <y> <z> [radius]",
                "viewdistance <blocks>",
                "scanradius <blocks>",
                "trigger <word>",
                "pulltimeout <seconds>",
                "waittimeout <seconds>",
                "whitelist <on/off>",
                "whitelist add <playerName>",
                "whitelist remove <playerName>",
                "whitelist list",
                "whitelist clear",
                "discord <on/off>",
                "discord channel <channelId>",
                "links",
                "unlink <playerName>",
                "maxchambers <count>"
            )
            .aliases("pb")
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        LiteralArgumentBuilder<CommandContext> builder = command("pearlbot")
            .requires(Command::validateAccountOwner);

        builder.then(argument("toggle", toggle()).executes(c -> {
            PLUGIN_CONFIG.enabled = getToggle(c, "toggle");
            MODULE.get(EnderPearlTrackerModule.class).syncEnabledFromConfig();
            MODULE.get(AutoPearlModule.class).syncEnabledFromConfig();
            c.getSource().getEmbed().title("PearlBot " + toggleStrCaps(PLUGIN_CONFIG.enabled));
            return OK;
        }));

        builder.then(literal("pull")
            .then(argument("playerName", wordWithChars()).executes(c -> {
                String name = getString(c, "playerName");
                UUID uuid = resolveUuid(name);
                if (uuid == null) {
                    c.getSource().getEmbed().title("Invalid username: " + name);
                    return ERROR;
                }
                AutoPearlModule mod = MODULE.get(AutoPearlModule.class);
                PearlBotConfig.StasisChamber chamber = mod.findChamberFor(uuid);
                if (chamber == null) {
                    c.getSource().getEmbed().title("No chamber registered for " + name);
                    return ERROR;
                }
                boolean queued = mod.enqueuePull(uuid, name, chamber);
                c.getSource().getEmbed().title(queued
                    ? "Pull queued for " + name
                    : "Pull already pending for " + name);
                return OK;
            })));

        builder.then(literal("cancel")
            .then(argument("playerName", wordWithChars()).executes(c -> {
                String name = getString(c, "playerName");
                UUID uuid = resolveUuid(name);
                if (uuid == null) {
                    c.getSource().getEmbed().title("Invalid username: " + name);
                    return ERROR;
                }
                boolean removed = MODULE.get(AutoPearlModule.class).cancelPull(uuid);
                c.getSource().getEmbed().title(removed
                    ? "Cancelled pending pull for " + name
                    : "No pending pull for " + name);
                return OK;
            })));

        builder.then(literal("list").executes(c -> {
            if (PLUGIN_CONFIG.chambers.isEmpty()) {
                c.getSource().getEmbed().title("No chambers registered");
                return OK;
            }
            var byOwner = new java.util.LinkedHashMap<UUID, java.util.List<PearlBotConfig.StasisChamber>>();
            for (var chamber : PLUGIN_CONFIG.chambers.values()) {
                byOwner.computeIfAbsent(chamber.ownerUuid, k -> new java.util.ArrayList<>()).add(chamber);
            }
            int max = PLUGIN_CONFIG.maxChambersPerPlayer;
            StringBuilder sb = new StringBuilder();
            for (var entry : byOwner.entrySet()) {
                var list = entry.getValue();
                String countStr = max > 0 ? list.size() + "/" + max : String.valueOf(list.size());
                sb.append("**").append(resolveName(entry.getKey())).append("** (").append(countStr).append(")\n");
                for (var ch : list) {
                    sb.append(" • ||").append(ch.x).append(' ').append(ch.y).append(' ').append(ch.z).append("||\n");
                }
            }
            c.getSource().getEmbed()
                .title("Chambers (" + PLUGIN_CONFIG.chambers.size() + ")")
                .description(sb.toString().trim());
            return OK;
        }));

        builder.then(literal("chamber")
            .then(literal("clear").executes(c -> {
                int n = PLUGIN_CONFIG.chambers.size();
                PLUGIN_CONFIG.chambers.clear();
                c.getSource().getEmbed().title("Cleared chambers (" + n + " removed)");
                return OK;
            }))
            .then(literal("remove")
                .then(argument("playerName", wordWithChars()).executes(c -> {
                    String name = getString(c, "playerName");
                    UUID uuid = resolveUuid(name);
                    if (uuid == null) {
                        c.getSource().getEmbed().title("Invalid username: " + name);
                        return ERROR;
                    }
                    boolean removed = PLUGIN_CONFIG.chambers.values()
                        .removeIf(ch -> uuid.equals(ch.ownerUuid));
                    c.getSource().getEmbed().title(removed
                        ? "Removed chamber for " + name
                        : "No chamber registered for " + name);
                    return OK;
                })))
            .then(literal("prune")
                .then(argument("radius", integer(1)).executes(c -> {
                    var player = com.zenith.Globals.CACHE.getPlayerCache().getThePlayer();
                    if (player == null) {
                        c.getSource().getEmbed().title("Not connected - cannot determine position");
                        return ERROR;
                    }
                    int radius = getInteger(c, "radius");
                    double px = player.getX(), py = player.getY(), pz = player.getZ();
                    long sq = (long) radius * radius;
                    int before = PLUGIN_CONFIG.chambers.size();
                    PLUGIN_CONFIG.chambers.values().removeIf(ch -> {
                        double dx = ch.x - px, dy = ch.y - py, dz = ch.z - pz;
                        return dx * dx + dy * dy + dz * dz > sq;
                    });
                    int pruned = before - PLUGIN_CONFIG.chambers.size();
                    c.getSource().getEmbed().title("Pruned " + pruned + " chamber(s) beyond "
                        + radius + " blocks (" + PLUGIN_CONFIG.chambers.size() + " remaining)");
                    return OK;
                }))));

        builder.then(literal("pending").executes(c -> {
            var pending = PLUGIN_CONFIG.pendingPulls;
            if (pending.isEmpty()) {
                c.getSource().getEmbed().title("No pending pulls");
                return OK;
            }
            StringBuilder sb = new StringBuilder();
            for (var p : pending) {
                sb.append("- ")
                    .append(p.ownerName == null ? p.ownerUuid.toString() : p.ownerName)
                    .append(" @ ||")
                    .append(p.blockX).append(' ').append(p.blockY).append(' ').append(p.blockZ)
                    .append("||\n");
            }
            c.getSource().getEmbed()
                .title("Pending Pulls (" + pending.size() + ")")
                .description(sb.toString().trim());
            return OK;
        }));

        builder.then(literal("clearpending").executes(c -> {
            AutoPearlModule mod = MODULE.get(AutoPearlModule.class);
            int n = PLUGIN_CONFIG.pendingPulls.size();
            for (var p : new java.util.ArrayList<>(PLUGIN_CONFIG.pendingPulls)) {
                mod.cancelPull(p.ownerUuid);
            }
            PLUGIN_CONFIG.pendingPulls.clear();
            c.getSource().getEmbed().title("Cleared pending pulls (" + n + " cancelled)");
            return OK;
        }));

        builder.then(literal("idle")
            .then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.idleGoal.enabled = getToggle(c, "toggle");
                c.getSource().getEmbed().title("Idle return " + toggleStrCaps(PLUGIN_CONFIG.idleGoal.enabled));
                return OK;
            }))
            .then(literal("here").executes(c -> {
                var player = com.zenith.Globals.CACHE.getPlayerCache().getThePlayer();
                PLUGIN_CONFIG.idleGoal.x = (int) Math.floor(player.getX());
                PLUGIN_CONFIG.idleGoal.y = (int) Math.floor(player.getY());
                PLUGIN_CONFIG.idleGoal.z = (int) Math.floor(player.getZ());
                PLUGIN_CONFIG.idleGoal.enabled = true;
                c.getSource().getEmbed().title("Idle goal set to current position");
                return OK;
            }))
            .then(argument("x", integer())
                .then(argument("y", integer())
                    .then(argument("z", integer()).executes(c -> {
                        PLUGIN_CONFIG.idleGoal.x = getInteger(c, "x");
                        PLUGIN_CONFIG.idleGoal.y = getInteger(c, "y");
                        PLUGIN_CONFIG.idleGoal.z = getInteger(c, "z");
                        PLUGIN_CONFIG.idleGoal.enabled = true;
                        c.getSource().getEmbed().title("Idle goal set");
                        return OK;
                    })))));

        builder.then(literal("viewdistance")
            .then(argument("blocks", integer(1)).executes(c -> {
                PLUGIN_CONFIG.pearlViewDistance = getInteger(c, "blocks");
                c.getSource().getEmbed().title("View distance set");
                return OK;
            })));

        builder.then(literal("scanradius")
            .then(argument("blocks", integer(1)).executes(c -> {
                PLUGIN_CONFIG.trapdoorScanRadius = getInteger(c, "blocks");
                c.getSource().getEmbed().title("Scan radius set");
                return OK;
            })));

        builder.then(literal("trigger")
            .then(argument("word", wordWithChars()).executes(c -> {
                String w = getString(c, "word").trim();
                if (w.isBlank()) {
                    c.getSource().getEmbed().title("Trigger word cannot be empty");
                    return ERROR;
                }
                if (w.startsWith("!")) w = w.substring(1);
                PLUGIN_CONFIG.triggerWord = w.toLowerCase();
                c.getSource().getEmbed().title("Trigger word set to '" + PLUGIN_CONFIG.triggerWord
                    + "' (whisper: " + PLUGIN_CONFIG.triggerWord
                    + ", discord: !" + PLUGIN_CONFIG.triggerWord + ")");
                return OK;
            })));

        builder.then(literal("pulltimeout")
            .then(argument("seconds", integer(0)).executes(c -> {
                PLUGIN_CONFIG.pullTimeoutSeconds = getInteger(c, "seconds");
                c.getSource().getEmbed().title("Pull timeout set to " + PLUGIN_CONFIG.pullTimeoutSeconds + "s");
                return OK;
            })));

        builder.then(literal("waittimeout")
            .then(argument("seconds", integer(0)).executes(c -> {
                PLUGIN_CONFIG.waitForOwnerSeconds = getInteger(c, "seconds");
                c.getSource().getEmbed().title("Wait-for-owner timeout set to " + PLUGIN_CONFIG.waitForOwnerSeconds + "s");
                return OK;
            })));

        builder.then(literal("whitelist")
            .then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.whitelist.enabled = getToggle(c, "toggle");
                c.getSource().getEmbed().title("Whitelist " + toggleStrCaps(PLUGIN_CONFIG.whitelist.enabled));
                return OK;
            }))
            .then(literal("add")
                .then(argument("playerName", wordWithChars()).executes(c -> {
                    String name = getString(c, "playerName");
                    UUID uuid = resolveUuid(name);
                    if (uuid == null) {
                        c.getSource().getEmbed().title("Invalid username: " + name);
                        return ERROR;
                    }
                    if (PLUGIN_CONFIG.whitelist.players.containsKey(uuid)) {
                        c.getSource().getEmbed().title(name + " is already whitelisted");
                        return OK;
                    }
                    PLUGIN_CONFIG.whitelist.players.put(uuid, new PearlBotConfig.WhitelistedPlayer(name, uuid));
                    c.getSource().getEmbed().title("Added " + name + " to whitelist");
                    return OK;
                })))
            .then(literal("remove")
                .then(argument("playerName", wordWithChars()).executes(c -> {
                    String name = getString(c, "playerName");
                    UUID uuid = resolveUuid(name);
                    if (uuid == null) {
                        c.getSource().getEmbed().title("Invalid username: " + name);
                        return ERROR;
                    }
                    if (PLUGIN_CONFIG.whitelist.players.remove(uuid) == null) {
                        c.getSource().getEmbed().title(name + " is not in the whitelist");
                        return OK;
                    }
                    c.getSource().getEmbed().title("Removed " + name + " from whitelist");
                    return OK;
                })))
            .then(literal("list").executes(c -> {
                if (PLUGIN_CONFIG.whitelist.players.isEmpty()) {
                    c.getSource().getEmbed().title("Whitelist is empty");
                    return OK;
                }
                StringBuilder sb = new StringBuilder();
                for (var p : PLUGIN_CONFIG.whitelist.players.values()) {
                    sb.append("- ").append(p.username).append(" (").append(p.uuid).append(")\n");
                }
                c.getSource().getEmbed()
                    .title("Whitelist (" + PLUGIN_CONFIG.whitelist.players.size() + ")")
                    .description(sb.toString().trim());
                return OK;
            }))
            .then(literal("clear").executes(c -> {
                int n = PLUGIN_CONFIG.whitelist.players.size();
                PLUGIN_CONFIG.whitelist.players.clear();
                c.getSource().getEmbed().title("Cleared whitelist (" + n + " removed)");
                return OK;
            })));

        builder.then(literal("discord")
            .then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.discordTrigger.enabled = getToggle(c, "toggle");
                c.getSource().getEmbed().title("Discord triggers " + toggleStrCaps(PLUGIN_CONFIG.discordTrigger.enabled));
                return OK;
            }))
            .then(literal("channel")
                .then(argument("channelId", wordWithChars()).executes(c -> {
                    String id = getString(c, "channelId");
                    PLUGIN_CONFIG.discordTrigger.channelId = id;
                    c.getSource().getEmbed().title("Discord trigger channel set to " + id);
                    return OK;
                }))));

        builder.then(literal("links").executes(c -> {
            if (PLUGIN_CONFIG.linkedAccounts.isEmpty()) {
                c.getSource().getEmbed().title("No linked accounts");
                return OK;
            }
            StringBuilder sb = new StringBuilder();
            for (var entry : PLUGIN_CONFIG.linkedAccounts.entrySet()) {
                var a = entry.getValue();
                sb.append("- ").append(a.mcUsername == null ? entry.getKey().toString() : a.mcUsername)
                    .append(" -> <@").append(a.discordUserId).append("> (")
                    .append(a.discordUsername == null ? "?" : a.discordUsername).append(")\n");
            }
            c.getSource().getEmbed()
                .title("Linked Accounts (" + PLUGIN_CONFIG.linkedAccounts.size() + ")")
                .description(sb.toString().trim());
            return OK;
        }));

        builder.then(literal("unlink")
            .then(argument("playerName", wordWithChars()).executes(c -> {
                String name = getString(c, "playerName");
                UUID uuid = resolveUuid(name);
                if (uuid == null) {
                    c.getSource().getEmbed().title("Invalid username: " + name);
                    return ERROR;
                }
                if (PLUGIN_CONFIG.linkedAccounts.remove(uuid) == null) {
                    c.getSource().getEmbed().title(name + " is not linked");
                    return OK;
                }
                c.getSource().getEmbed().title("Unlinked " + name);
                return OK;
            })));

        builder.then(literal("maxchambers")
            .then(argument("count", integer(0)).executes(c -> {
                PLUGIN_CONFIG.maxChambersPerPlayer = getInteger(c, "count");
                c.getSource().getEmbed().title(PLUGIN_CONFIG.maxChambersPerPlayer == 0
                    ? "Max chambers per player: unlimited"
                    : "Max chambers per player set to " + PLUGIN_CONFIG.maxChambersPerPlayer);
                return OK;
            })));

        return builder;
    }

    @Override
    public void defaultEmbed(Embed embed) {
        embed
            .primaryColor()
            .addField("Enabled", toggleStr(PLUGIN_CONFIG.enabled))
            .addField("Trigger Word", PLUGIN_CONFIG.triggerWord
                + " / !" + PLUGIN_CONFIG.triggerWord)
            .addField("Chambers", PLUGIN_CONFIG.chambers.size())
            .addField("Pending Pulls", PLUGIN_CONFIG.pendingPulls.size())
            .addField("View Distance", PLUGIN_CONFIG.pearlViewDistance + " blocks")
            .addField("Trapdoor Scan Radius", PLUGIN_CONFIG.trapdoorScanRadius + " blocks")
            .addField("Pull Timeout", PLUGIN_CONFIG.pullTimeoutSeconds + "s")
            .addField("Wait For Owner", PLUGIN_CONFIG.waitForOwnerSeconds + "s")
            .addField("Idle Return", PLUGIN_CONFIG.idleGoal.enabled
                ? "||(" + PLUGIN_CONFIG.idleGoal.x + ", " + PLUGIN_CONFIG.idleGoal.y + ", " + PLUGIN_CONFIG.idleGoal.z + ")||"
                : "off")
            .addField("Whitelist", toggleStr(PLUGIN_CONFIG.whitelist.enabled)
                + " (" + PLUGIN_CONFIG.whitelist.players.size() + ")")
            .addField("Discord Triggers", toggleStr(PLUGIN_CONFIG.discordTrigger.enabled))
            .addField("Discord Channel", PLUGIN_CONFIG.discordTrigger.channelId.isBlank()
                ? "unset" : PLUGIN_CONFIG.discordTrigger.channelId)
            .addField("Linked Accounts", PLUGIN_CONFIG.linkedAccounts.size())
            .addField("Max Chambers Per Player", PLUGIN_CONFIG.maxChambersPerPlayer == 0
                ? "unlimited" : String.valueOf(PLUGIN_CONFIG.maxChambersPerPlayer));
    }

    private UUID resolveUuid(String username) {
        return PlayerListsManager.getProfileFromUsername(username)
            .map(profile -> profile.uuid())
            .orElse(null);
    }

    private String resolveName(UUID uuid) {
        if (uuid == null) return "unknown";
        var linked = PLUGIN_CONFIG.linkedAccounts.get(uuid);
        if (linked != null && linked.mcUsername != null) return linked.mcUsername;
        var whitelisted = PLUGIN_CONFIG.whitelist.players.get(uuid);
        if (whitelisted != null && whitelisted.username != null) return whitelisted.username;
        return PlayerListsManager.getProfileFromUUID(uuid)
            .map(profile -> profile.name())
            .orElse(uuid.toString());
    }
}
