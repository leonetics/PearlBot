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
package org.pearlbot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;


public class PearlBotConfig {
    public enum NotificationLevel { NONE, SIMPLE, VERBOSE }
    public enum WhitelistMode { OFF, FRIENDS, ON }

    public boolean enabled = true;
    public boolean pearlDrop = false;
    public boolean reopenTrapdoors = false;
    public int reopenTrapdoorsDelayMs = 300;
    public List<String> triggerWords = new ArrayList<>(java.util.List.of("warp"));
    public int pearlViewDistance = 64;
    public int trapdoorScanRadius = 5;
    public int pullTimeoutSeconds = 30;
    public int waitForOwnerSeconds = 180;
    public int maxChambersPerPlayer = 0;
    public NotificationLevel notificationLevel = NotificationLevel.SIMPLE;
    public WhitelistMode whitelistMode = WhitelistMode.FRIENDS;
    public Map<UUID, WhitelistedPlayer> whitelistedPlayers = new LinkedHashMap<>();

    public static class WhitelistedPlayer {
        public String username;
        public UUID uuid;

        public WhitelistedPlayer() {}
        public WhitelistedPlayer(String username, UUID uuid) {
            this.username = username;
            this.uuid = uuid;
        }
    }

    public final IdleGoal idleGoal = new IdleGoal();
    public static class IdleGoal {
        public boolean enabled = false;
        public int x = 0;
        public int y = 0;
        public int z = 0;
        public int radius = 3;
    }

    public final Restock restock = new Restock();
    public static class Restock {
        public boolean enabled = false;
        // Position of the chest / barrel / shulker box holding spare pearls.
        public boolean containerSet = false;
        public int x = 0;
        public int y = 0;
        public int z = 0;
        // Restock when the bot holds fewer than this many pearls...
        public int minPearls = 4;
        // ...and withdraw until it holds about this many.
        public int targetPearls = 16;
    }

    public Map<UUID, StasisChamber> chambers = new LinkedHashMap<>();
    public static class StasisChamber {
        public int x;
        public int y;
        public int z;
        public transient int entityId;
        public UUID ownerUuid;
        public Integer pendingOwnerEntityId;
    }

    public final DiscordTrigger discordTrigger = new DiscordTrigger();
    public static class DiscordTrigger {
        public boolean enabled = true;
        public String channelId = "";
        public String prefix = "!";
        public int authCodeTtlMinutes = 5;
        public boolean aristoisLinkingEnabled = true;
    }

    public Map<UUID, LinkedAccount> linkedAccounts = new LinkedHashMap<>();
    public static class LinkedAccount {
        public String discordUserId;
        public String discordUsername;
        public String mcUsername;
        public long linkedAtMs;

        public LinkedAccount() {}
        public LinkedAccount(String discordUserId, String discordUsername, String mcUsername, long linkedAtMs) {
            this.discordUserId = discordUserId;
            this.discordUsername = discordUsername;
            this.mcUsername = mcUsername;
            this.linkedAtMs = linkedAtMs;
        }
    }

    public Map<UUID, PlayerPullStats> playerStats = new LinkedHashMap<>();
    public Map<String, Long> statsBySource = new LinkedHashMap<>();

    public static class PlayerPullStats {
        public String playerName;
        public long successful;
        public long aborted;
        public Map<String, Long> bySource = new java.util.concurrent.ConcurrentHashMap<>();

        public PlayerPullStats() {}
        public PlayerPullStats(String playerName) {
            this.playerName = playerName;
        }
    }

    public boolean historyEnabled = false;
    public int historyMax = 50;
    public List<PullRecord> pullHistory = new ArrayList<>();

    public static class PullRecord {
        public String ownerName;
        public UUID ownerUuid;
        public String source;
        public long requestedAtMs;
        public long completedAtMs;
        public boolean success;

        public PullRecord() {}
        public PullRecord(String ownerName, UUID ownerUuid, String source,
                          long requestedAtMs, long completedAtMs, boolean success) {
            this.ownerName = ownerName;
            this.ownerUuid = ownerUuid;
            this.source = source;
            this.requestedAtMs = requestedAtMs;
            this.completedAtMs = completedAtMs;
            this.success = success;
        }
    }

    public List<PendingPull> pendingPulls = new ArrayList<>();
    public static class PendingPull {
        public UUID ownerUuid;
        public String ownerName;
        public int blockX;
        public int blockY;
        public int blockZ;
        public long queuedAtMs;
        public String source;

        public PendingPull() {}
        public PendingPull(UUID ownerUuid, String ownerName, int x, int y, int z, long queuedAtMs, String source) {
            this.ownerUuid = ownerUuid;
            this.ownerName = ownerName;
            this.blockX = x;
            this.blockY = y;
            this.blockZ = z;
            this.queuedAtMs = queuedAtMs;
            this.source = source;
        }
    }
}
