/*
 * PearlBot.
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
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.discord.Embed;
import com.zenith.feature.whitelist.PlayerListsManager;
import org.pearlbot.PearlBotConfig;
import org.pearlbot.module.AutoPearlModule;
import org.pearlbot.module.EnderPearlTrackerModule;

import java.util.Map;
import java.util.UUID;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.zenith.Globals.MODULE;
import static com.zenith.command.brigadier.CustomStringArgumentType.getString;
import static com.zenith.command.brigadier.CustomStringArgumentType.wordWithChars;
import static com.zenith.command.brigadier.ToggleArgumentType.getToggle;
import static com.zenith.command.brigadier.ToggleArgumentType.toggle;
import static org.pearlbot.PearlBotPlugin.PLUGIN_CONFIG;
import static org.pearlbot.PearlBotPlugin.resolvePlayerName;

public class PearlBotCommand extends Command {
    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("pearlbot")
            .category(CommandCategory.MODULE)
            .description("Stasis chamber detection and pulling.")
            .usageLines(
                "<on/off>",
                "pull <player>",
                "cancel <player>",
                "list",
                "chamber list",
                "chamber assign <unknown-id> <player>",
                "chamber remove <player>",
                "chamber prune <radius>",
                "chamber clear",
                "pending",
                "clearpending",
                "idle <on/off>",
                "idle here",
                "idle <x> <y> <z> [radius]",
                "viewdistance <blocks>",
                "scanradius <blocks>",
                "trigger add <word>",
                "trigger remove <word>",
                "pulltimeout <seconds>",
                "waittimeout <seconds>",
                "whitelist <off|friends|on>",
                "whitelist add <player>",
                "whitelist remove <player>",
                "whitelist list",
                "whitelist clear",
                "discord <on/off>",
                "discord channel <channelId>",
                "discord prefix <prefix>",
                "notifications <none|simple|verbose>",
                "links",
                "unlink <player>",
                "maxchambers <count>",
                "pearldrop <on/off>",
                "restock <on/off>",
                "restock <x> <y> <z>",
                "restock min <count>",
                "restock target <count>",
                "restock now",
                "restock status",
                "reopentrapdoors <on/off>",
                "reopentrapdoors delay <ms>",
                "stats",
                "stats clear",
                "history <on/off>",
                "history max <count>",
                "history",
                "history <player>",
                "history clear"
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
            int n = PLUGIN_CONFIG.chambers.size();
            c.getSource().getEmbed().title(n == 0 ? "No chambers registered" : n + " chamber(s) registered");
            return OK;
        }));

        builder.then(literal("chamber")
            .then(literal("list").executes(c -> {
                if (PLUGIN_CONFIG.chambers.isEmpty()) {
                    c.getSource().getEmbed().title("No chambers registered");
                    return OK;
                }
                var byOwner = new java.util.LinkedHashMap<UUID, java.util.List<PearlBotConfig.StasisChamber>>();
                var unknowns = new java.util.ArrayList<java.util.Map.Entry<UUID, PearlBotConfig.StasisChamber>>();
                for (var entry : PLUGIN_CONFIG.chambers.entrySet()) {
                    if (entry.getValue().ownerUuid != null) {
                        byOwner.computeIfAbsent(entry.getValue().ownerUuid, k -> new java.util.ArrayList<>()).add(entry.getValue());
                    } else {
                        unknowns.add(entry);
                    }
                }
                int max = PLUGIN_CONFIG.maxChambersPerPlayer;
                StringBuilder sb = new StringBuilder();
                for (var entry : byOwner.entrySet()) {
                    var list = entry.getValue();
                    String countStr = max > 0 ? list.size() + "/" + max : String.valueOf(list.size());
                    sb.append("**").append(resolveName(entry.getKey())).append("** (").append(countStr).append(")\n");
                    for (var ch : list) {
                        sb.append(" • ||").append(ch.x).append(' ').append(ch.y).append(' ').append(ch.z).append("||\n");
                    }
                }
                for (var entry : unknowns) {
                    sb.append("**unknown-").append(entry.getKey().toString(), 0, 5).append("**\n");
                    var ch = entry.getValue();
                    sb.append(" • ||").append(ch.x).append(' ').append(ch.y).append(' ').append(ch.z).append("||\n");
                }
                c.getSource().getEmbed()
                    .title("Chambers (" + PLUGIN_CONFIG.chambers.size() + ")")
                    .description(sb.toString().trim());
                return OK;
            }))
            .then(literal("assign")
                .then(argument("unknownId", wordWithChars())
                    .then(argument("playerName", wordWithChars()).executes(c -> {
                        String unknownId = getString(c, "unknownId").toLowerCase();
                        if (!unknownId.startsWith("unknown-") || unknownId.length() != 13) {
                            c.getSource().getEmbed().title("Usage: chamber assign <unknown-XXXXX> <playerName>");
                            return ERROR;
                        }
                        String shortId = unknownId.substring(8);
                        java.util.Map.Entry<UUID, PearlBotConfig.StasisChamber> target = null;
                        for (var entry : PLUGIN_CONFIG.chambers.entrySet()) {
                            if (entry.getValue().ownerUuid == null
                                    && entry.getKey().toString().startsWith(shortId)) {
                                target = entry;
                                break;
                            }
                        }
                        if (target == null) {
                            c.getSource().getEmbed().title("No unknown chamber found with id " + unknownId);
                            return ERROR;
                        }
                        String playerName = getString(c, "playerName");
                        UUID uuid = resolveUuidWithApiLookup(playerName);
                        if (uuid == null) {
                            c.getSource().getEmbed().title("Unknown player: " + playerName + " (not in cache or Mojang API)");
                            return ERROR;
                        }
                        target.getValue().ownerUuid = uuid;
                        c.getSource().getEmbed().title("Assigned " + unknownId + " to " + playerName);
                        return OK;
                    }))))
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
                if (player == null) {
                    c.getSource().getEmbed().title("Not connected - cannot determine position");
                    return ERROR;
                }
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
            .then(literal("add")
                .then(argument("word", wordWithChars()).executes(c -> {
                    String w = getString(c, "word").trim().toLowerCase();
                    if (w.isBlank()) {
                        c.getSource().getEmbed().title("Trigger word cannot be empty");
                        return ERROR;
                    }
                    if (w.startsWith("!")) w = w.substring(1);
                    if (PLUGIN_CONFIG.triggerWords.contains(w)) {
                        c.getSource().getEmbed().title("'" + w + "' is already a trigger word");
                        return OK;
                    }
                    PLUGIN_CONFIG.triggerWords.add(w);
                    c.getSource().getEmbed().title("Added trigger word '" + w + "' ("
                        + PLUGIN_CONFIG.triggerWords.size() + " total)");
                    return OK;
                })))
            .then(literal("remove")
                .then(argument("word", wordWithChars()).executes(c -> {
                    String w = getString(c, "word").trim().toLowerCase();
                    if (w.startsWith("!")) w = w.substring(1);
                    boolean removed = PLUGIN_CONFIG.triggerWords.remove(w);
                    c.getSource().getEmbed().title(removed
                        ? "Removed trigger word '" + w + "' (" + PLUGIN_CONFIG.triggerWords.size() + " remaining)"
                        : "'" + w + "' is not a trigger word");
                    return OK;
                }))));

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
            .then(argument("mode", wordWithChars()).executes(c -> {
                String raw = getString(c, "mode").toUpperCase();
                PearlBotConfig.WhitelistMode mode;
                try {
                    mode = PearlBotConfig.WhitelistMode.valueOf(raw);
                } catch (IllegalArgumentException e) {
                    c.getSource().getEmbed().title("Unknown mode — use off, friends, or on");
                    return ERROR;
                }
                PLUGIN_CONFIG.whitelistMode = mode;
                c.getSource().getEmbed().title("Whitelist set to " + mode.name().toLowerCase());
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
                    if (PLUGIN_CONFIG.whitelistedPlayers.containsKey(uuid)) {
                        c.getSource().getEmbed().title(name + " is already whitelisted");
                        return OK;
                    }
                    PLUGIN_CONFIG.whitelistedPlayers.put(uuid, new PearlBotConfig.WhitelistedPlayer(name, uuid));
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
                    if (PLUGIN_CONFIG.whitelistedPlayers.remove(uuid) == null) {
                        c.getSource().getEmbed().title(name + " is not in the whitelist");
                        return OK;
                    }
                    c.getSource().getEmbed().title("Removed " + name + " from whitelist");
                    return OK;
                })))
            .then(literal("list").executes(c -> {
                if (PLUGIN_CONFIG.whitelistedPlayers.isEmpty()) {
                    c.getSource().getEmbed().title("Whitelist is empty");
                    return OK;
                }
                StringBuilder sb = new StringBuilder();
                for (var p : PLUGIN_CONFIG.whitelistedPlayers.values()) {
                    sb.append("- ").append(p.username).append("\n");
                }
                c.getSource().getEmbed()
                    .title("Whitelist (" + PLUGIN_CONFIG.whitelistedPlayers.size() + ")")
                    .description(sb.toString().trim());
                return OK;
            }))
            .then(literal("clear").executes(c -> {
                int n = PLUGIN_CONFIG.whitelistedPlayers.size();
                PLUGIN_CONFIG.whitelistedPlayers.clear();
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
                })))
            .then(literal("prefix")
                .then(argument("prefix", wordWithChars()).executes(c -> {
                    String p = getString(c, "prefix");
                    PLUGIN_CONFIG.discordTrigger.prefix = p;
                    c.getSource().getEmbed().title("Discord prefix set to '" + p + "'");
                    return OK;
                }))));

        builder.then(literal("notifications")
            .then(argument("level", wordWithChars()).executes(c -> {
                String raw = getString(c, "level").toUpperCase();
                PearlBotConfig.NotificationLevel level;
                try {
                    level = PearlBotConfig.NotificationLevel.valueOf(raw);
                } catch (IllegalArgumentException e) {
                    c.getSource().getEmbed().title("Unknown level — use none, simple, or verbose");
                    return ERROR;
                }
                PLUGIN_CONFIG.notificationLevel = level;
                c.getSource().getEmbed().title("Notifications set to " + level.name().toLowerCase());
                return OK;
            })));

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

        builder.then(literal("restock")
            .then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.restock.enabled = getToggle(c, "toggle");
                c.getSource().getEmbed().title("Pearl restock " + toggleStrCaps(PLUGIN_CONFIG.restock.enabled));
                if (PLUGIN_CONFIG.restock.enabled && !PLUGIN_CONFIG.restock.containerSet) {
                    c.getSource().getEmbed().description("No container set yet - use `pearlbot restock <x> <y> <z>`");
                }
                return OK;
            }))
            .then(literal("min")
                .then(argument("count", integer(0)).executes(c -> {
                    PLUGIN_CONFIG.restock.minPearls = getInteger(c, "count");
                    c.getSource().getEmbed().title("Restock when below " + PLUGIN_CONFIG.restock.minPearls + " pearl(s)");
                    return OK;
                })))
            .then(literal("target")
                .then(argument("count", integer(1)).executes(c -> {
                    PLUGIN_CONFIG.restock.targetPearls = getInteger(c, "count");
                    c.getSource().getEmbed().title("Restock up to " + PLUGIN_CONFIG.restock.targetPearls + " pearl(s)");
                    return OK;
                })))
            .then(literal("now").executes(c -> {
                if (!PLUGIN_CONFIG.restock.containerSet) {
                    c.getSource().getEmbed().title("No container set - use `pearlbot restock <x> <y> <z>`");
                    return ERROR;
                }
                MODULE.get(AutoPearlModule.class).requestRestock();
                c.getSource().getEmbed().title("Restock queued");
                return OK;
            }))
            .then(literal("status").executes(c -> {
                var r = PLUGIN_CONFIG.restock;
                var mod = MODULE.get(AutoPearlModule.class);
                c.getSource().getEmbed().title("Pearl Restock")
                    .addField("Enabled", toggleStr(r.enabled))
                    .addField("Container", r.containerSet ? "||" + r.x + " " + r.y + " " + r.z + "||" : "unset")
                    .addField("Min / Target", r.minPearls + " / " + r.targetPearls)
                    .addField("Bot Pearls", mod.countBotPearls())
                    .addField("Restocking Now", mod.isRestocking() ? "yes" : "no");
                return OK;
            }))
            .then(argument("x", integer())
                .then(argument("y", integer())
                    .then(argument("z", integer()).executes(c -> {
                        PLUGIN_CONFIG.restock.x = getInteger(c, "x");
                        PLUGIN_CONFIG.restock.y = getInteger(c, "y");
                        PLUGIN_CONFIG.restock.z = getInteger(c, "z");
                        PLUGIN_CONFIG.restock.containerSet = true;
                        c.getSource().getEmbed().title("Restock container set")
                            .description("||" + PLUGIN_CONFIG.restock.x + " " + PLUGIN_CONFIG.restock.y
                                + " " + PLUGIN_CONFIG.restock.z + "||");
                        return OK;
                    })))));

        builder.then(literal("pearldrop")
            .then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.pearlDrop = getToggle(c, "toggle");
                c.getSource().getEmbed().title("Pearl drop " + toggleStrCaps(PLUGIN_CONFIG.pearlDrop));
                return OK;
            })));

        builder.then(literal("reopentrapdoors")
            .then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.reopenTrapdoors = getToggle(c, "toggle");
                c.getSource().getEmbed().title("Reopen trapdoors " + toggleStrCaps(PLUGIN_CONFIG.reopenTrapdoors));
                return OK;
            }))
            .then(literal("delay")
                .then(argument("ms", integer(0)).executes(c -> {
                    PLUGIN_CONFIG.reopenTrapdoorsDelayMs = getInteger(c, "ms");
                    c.getSource().getEmbed().title("Reopen trapdoors delay set to " + PLUGIN_CONFIG.reopenTrapdoorsDelayMs + "ms");
                    return OK;
                }))));

        builder.then(literal("stats")
            .executes(c -> {
                var ps = PLUGIN_CONFIG.playerStats;
                if (ps.isEmpty()) {
                    c.getSource().getEmbed().title("No pull stats recorded yet");
                    return OK;
                }
                long totalSuccessful = ps.values().stream().mapToLong(s -> s.successful).sum();
                long totalAborted = ps.values().stream().mapToLong(s -> s.aborted).sum();
                String sourceStr = PLUGIN_CONFIG.statsBySource.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .map(e -> e.getKey() + " " + e.getValue())
                    .collect(java.util.stream.Collectors.joining(", "));
                StringBuilder players = new StringBuilder();
                var topPlayers = ps.values().stream()
                    .sorted((a, b) -> Long.compare(b.successful, a.successful))
                    .limit(5)
                    .collect(java.util.stream.Collectors.toList());
                for (int i = 0; i < topPlayers.size(); i++) {
                    var s = topPlayers.get(i);
                    players.append(i + 1).append(". ")
                        .append(s.playerName != null ? s.playerName : "?")
                        .append(": ").append(s.successful);
                    if (s.bySource != null && !s.bySource.isEmpty()) {
                        String breakdown = s.bySource.entrySet().stream()
                            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                            .map(e -> e.getKey() + " " + e.getValue())
                            .collect(java.util.stream.Collectors.joining(", "));
                        players.append("  (").append(breakdown).append(")");
                    }
                    players.append("\n");
                }
                c.getSource().getEmbed()
                    .title("Pull Stats")
                    .primaryColor()
                    .addField("Pulls", totalSuccessful)
                    .addField("Aborted", totalAborted)
                    .addField("Sources", sourceStr.isEmpty() ? "-" : sourceStr)
                    .description(players.toString().trim());
                return OK;
            })
            .then(literal("clear").executes(c -> {
                int n = PLUGIN_CONFIG.playerStats.size();
                PLUGIN_CONFIG.playerStats.clear();
                PLUGIN_CONFIG.statsBySource.clear();
                c.getSource().getEmbed().title("Pull stats cleared (" + n + " player(s) reset)");
                return OK;
            })));

        builder.then(literal("history")
            .executes(c -> {
                var h = PLUGIN_CONFIG.pullHistory;
                if (h.isEmpty()) {
                    c.getSource().getEmbed().title("No history recorded yet"
                        + (PLUGIN_CONFIG.historyEnabled ? "" : " (history is off — use 'pb history on')"));
                    return OK;
                }
                int show = Math.min(15, h.size());
                StringBuilder sb = new StringBuilder();
                for (int i = h.size() - 1; i >= h.size() - show; i--) {
                    var r = h.get(i);
                    String time = java.time.Instant.ofEpochMilli(r.completedAtMs)
                        .atZone(java.time.ZoneOffset.UTC)
                        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"));
                    sb.append(r.ownerName != null ? r.ownerName : "?").append("  ")
                        .append(r.source != null ? r.source : "?").append("  ")
                        .append(time);
                    if (!r.success) sb.append("  (aborted)");
                    sb.append("\n");
                }
                c.getSource().getEmbed()
                    .title("Recent History")
                    .primaryColor()
                    .description(sb.toString().trim());
                return OK;
            })
            .then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.historyEnabled = getToggle(c, "toggle");
                c.getSource().getEmbed().title("Pull history " + toggleStrCaps(PLUGIN_CONFIG.historyEnabled)
                    + " (max: " + PLUGIN_CONFIG.historyMax + ")");
                return OK;
            }))
            .then(literal("max")
                .then(argument("count", integer(1)).executes(c -> {
                    PLUGIN_CONFIG.historyMax = getInteger(c, "count");
                    int excess = PLUGIN_CONFIG.pullHistory.size() - PLUGIN_CONFIG.historyMax;
                    if (excess > 0)
                        PLUGIN_CONFIG.pullHistory.subList(0, excess).clear();
                    c.getSource().getEmbed().title("History max set to " + PLUGIN_CONFIG.historyMax);
                    return OK;
                })))
            .then(literal("clear").executes(c -> {
                int n = PLUGIN_CONFIG.pullHistory.size();
                PLUGIN_CONFIG.pullHistory.clear();
                c.getSource().getEmbed().title("History cleared (" + n + " records removed)");
                return OK;
            }))
            .then(argument("playerName", wordWithChars()).executes(c -> {
                String name = getString(c, "playerName");
                var filtered = PLUGIN_CONFIG.pullHistory.stream()
                    .filter(r -> r.ownerName != null && r.ownerName.equalsIgnoreCase(name))
                    .collect(java.util.stream.Collectors.toList());
                if (filtered.isEmpty()) {
                    c.getSource().getEmbed().title("No history for " + name);
                    return OK;
                }
                int show = Math.min(15, filtered.size());
                StringBuilder sb = new StringBuilder();
                for (int i = filtered.size() - 1; i >= filtered.size() - show; i--) {
                    var r = filtered.get(i);
                    String time = java.time.Instant.ofEpochMilli(r.completedAtMs)
                        .atZone(java.time.ZoneOffset.UTC)
                        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"));
                    sb.append(r.source != null ? r.source : "?").append("  ").append(time);
                    if (!r.success) sb.append("  (aborted)");
                    sb.append("\n");
                }
                // also show lifetime total from playerStats if available
                var lifetime = PLUGIN_CONFIG.playerStats.values().stream()
                    .filter(s -> name.equalsIgnoreCase(s.playerName))
                    .findFirst();
                String titleSuffix = lifetime.map(s -> " — " + s.successful + " total").orElse("");
                c.getSource().getEmbed()
                    .title("History: " + name + titleSuffix)
                    .primaryColor()
                    .description(sb.toString().trim());
                return OK;
            })));

        return builder;
    }

    @Override
    public void defaultErrorHandler(Map<CommandNode<CommandContext>, CommandSyntaxException> exceptions, CommandContext context) {
        for (var ex : exceptions.values())
            context.getEmbed().addField("Error", ex.getMessage());
        defaultEmbed(context.getEmbed());
        if (!context.getEmbed().isTitlePresent())
            context.getEmbed().title("Invalid command usage");
        String prefix = context.getSource().commandPrefix() + commandUsage().getName() + " ";
        var lines = commandUsage().getUsageLines();
        var chunks = new java.util.ArrayList<StringBuilder>();
        var current = new StringBuilder();
        for (var line : lines) {
            String entry = prefix + line + "\n";
            if (current.length() + entry.length() > 1024) {
                chunks.add(current);
                current = new StringBuilder();
            }
            current.append(entry);
        }
        if (!current.isEmpty()) chunks.add(current);
        if (chunks.size() == 1) {
            context.getEmbed().addField("Usage", chunks.get(0).toString().trim());
        } else {
            for (int i = 0; i < chunks.size(); i++)
                context.getEmbed().addField("Usage (" + (i + 1) + "/" + chunks.size() + ")", chunks.get(i).toString().trim());
        }
        context.getEmbed().errorColor();
    }

    @Override
    public void defaultEmbed(Embed embed) {
        if (embed.isTitlePresent() && embed.title().startsWith("Chambers (")) return;
        embed
            .primaryColor()
            .addField("Enabled", toggleStr(PLUGIN_CONFIG.enabled))
            .addField("Trigger Words", PLUGIN_CONFIG.triggerWords.isEmpty() ? "(none)" : String.join(", ", PLUGIN_CONFIG.triggerWords))
            .addField("Discord Prefix", PLUGIN_CONFIG.discordTrigger.prefix)
            .addField("Chambers", PLUGIN_CONFIG.chambers.size())
            .addField("Pending Pulls", PLUGIN_CONFIG.pendingPulls.size())
            .addField("View Distance", PLUGIN_CONFIG.pearlViewDistance + " blocks")
            .addField("Trapdoor Scan Radius", PLUGIN_CONFIG.trapdoorScanRadius + " blocks")
            .addField("Pull Timeout", PLUGIN_CONFIG.pullTimeoutSeconds + "s")
            .addField("Wait For Owner", PLUGIN_CONFIG.waitForOwnerSeconds + "s")
            .addField("Idle Return", PLUGIN_CONFIG.idleGoal.enabled
                ? "||(" + PLUGIN_CONFIG.idleGoal.x + ", " + PLUGIN_CONFIG.idleGoal.y + ", " + PLUGIN_CONFIG.idleGoal.z + ")||"
                : "off")
            .addField("Whitelist", PLUGIN_CONFIG.whitelistMode.name().toLowerCase())
            .addField("Discord Triggers", toggleStr(PLUGIN_CONFIG.discordTrigger.enabled))
            .addField("Discord Channel", PLUGIN_CONFIG.discordTrigger.channelId.isBlank()
                ? "unset" : PLUGIN_CONFIG.discordTrigger.channelId)
            .addField("Linked Accounts", PLUGIN_CONFIG.linkedAccounts.size())
            .addField("Max Chambers Per Player", PLUGIN_CONFIG.maxChambersPerPlayer == 0
                ? "unlimited" : String.valueOf(PLUGIN_CONFIG.maxChambersPerPlayer))
            .addField("Notifications", PLUGIN_CONFIG.notificationLevel.name().toLowerCase())
            .addField("Pearl Drop", toggleStr(PLUGIN_CONFIG.pearlDrop))
            .addField("Restock", PLUGIN_CONFIG.restock.enabled && PLUGIN_CONFIG.restock.containerSet
                ? "on (<" + PLUGIN_CONFIG.restock.minPearls + " -> " + PLUGIN_CONFIG.restock.targetPearls + ")" : "off")
            .addField("Reopen Trapdoors", PLUGIN_CONFIG.reopenTrapdoors
                ? "on (" + PLUGIN_CONFIG.reopenTrapdoorsDelayMs + "ms)" : "off")
            .addField("Total Pulls", PLUGIN_CONFIG.playerStats.values().stream().mapToLong(s -> s.successful).sum())
            .addField("History", PLUGIN_CONFIG.historyEnabled
                ? "on (" + PLUGIN_CONFIG.pullHistory.size() + "/" + PLUGIN_CONFIG.historyMax + ")" : "off");
    }

    private static final java.net.http.HttpClient MOJANG_HTTP = java.net.http.HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(5))
        .build();

    private UUID resolveUuid(String username) {
        return PlayerListsManager.getProfileFromUsername(username)
            .map(profile -> profile.uuid())
            .orElse(null);
    }

    private UUID resolveUuidWithApiLookup(String username) {
        UUID local = resolveUuid(username);
        if (local != null) return local;
        try {
            var request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("https://api.mojang.com/users/profiles/minecraft/"
                    + java.net.URLEncoder.encode(username, java.nio.charset.StandardCharsets.UTF_8)))
                .timeout(java.time.Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .GET()
                .build();
            var response = MOJANG_HTTP.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body().isBlank()) return null;
            var json = com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject();
            if (!json.has("id")) return null;
            String raw = json.get("id").getAsString().replace("-", "");
            if (raw.length() != 32) return null;
            return UUID.fromString(raw.substring(0, 8) + "-" + raw.substring(8, 12) + "-"
                + raw.substring(12, 16) + "-" + raw.substring(16, 20) + "-" + raw.substring(20));
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveName(UUID uuid) {
        if (uuid == null) return "unknown";
        String name = resolvePlayerName(uuid);
        return name != null ? name : uuid.toString();
    }
}
