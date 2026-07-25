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
package org.pearlbot.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.Proxy;
import com.zenith.cache.data.entity.Entity;
import com.zenith.discord.Embed;
import com.zenith.event.chat.PublicChatEvent;
import com.zenith.event.chat.WhisperChatEvent;
import com.zenith.event.client.ClientBotTick;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.DropItem;
import com.zenith.feature.inventory.util.InventoryUtil;
import com.zenith.feature.pathfinder.goals.GoalNear;
import com.zenith.feature.whitelist.PlayerListsManager;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.block.BlockState;
import com.zenith.mc.block.properties.Half;
import com.zenith.mc.block.properties.api.BlockStateProperties;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.module.api.Module;
import com.zenith.util.ChatUtil;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.DropItemAction;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.EventListener;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;
import org.pearlbot.PearlBotConfig;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import com.zenith.feature.player.InputRequest;
import com.zenith.feature.player.RotationHelper;
import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.BLOCK_DATA;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.CONFIG;
import static com.zenith.Globals.DISCORD;
import static com.zenith.Globals.INPUTS;
import static com.zenith.Globals.INVENTORY;
import static com.zenith.Globals.PLAYER_LISTS;
import org.pearlbot.PearlBotPlugin;

import static org.pearlbot.PearlBotPlugin.PLUGIN_CONFIG;
import static org.pearlbot.PearlBotPlugin.PLUGIN_MESSAGES;

public class AutoPearlModule extends Module {
    private static final long PULL_RETRY_INTERVAL_MS = 1_000L;
    private static final long IDLE_RETURN_DELAY_MS = 1500L;
    private static final long GHOST_PRUNE_GRACE_MS = 5_000L;
    private static final long CLICK_CONFIRM_TIMEOUT_MS = 3_000L;
    private static final long CLICK_SETTLE_DELAY_MS = 150L;
    private static final int FACE_TRAPDOOR_PRIORITY = 3000;
    private static final String DISCORD_AUTH_CMD = "!auth";
    private static final String INGAME_AUTH_CMD = "!auth";
    private static final String INGAME_LIST_CMD = "list";
    private static final String ARISTOIS_SUBCMD = "aristois";
    private static final String ARISTOIS_TOKEN_URL = "https://auth.aristois.net/token/";
    private static final String MOJANG_PROFILE_URL = "https://sessionserver.mojang.com/session/minecraft/profile/";

    private static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .build();

    private long lastAttemptMs = 0L;
    private volatile PearlBotConfig.PendingPull activePull = null;
    private long activePullStartMs = 0L;
    private volatile boolean readyAtTrapdoor = false;
    private volatile long readyAtMs = 0L;
    private volatile long clickReadyAtMs = 0L;
    private long idleReturnAtMs = 0L;

    private int reopenStep = 0;
    private long reopenAtMs = 0L;
    private int reopenTx, reopenTy, reopenTz;

    private volatile boolean awaitingClickConfirmation = false;
    private volatile long clickAttemptStartMs = 0L;

    private final Map<UUID, Long> chamberEmptySinceMs = new HashMap<>();

    private final Map<String, PendingAuth> pendingAuthCodes = new ConcurrentHashMap<>();
    private final EventListener jdaListener = this::onJdaEvent;
    private boolean jdaListenerRegistered = false;

    private record PendingAuth(String discordUserId, String discordUsername, long expiresAtMs) {}

    @Override
    public boolean enabledSetting() {
        return PLUGIN_CONFIG.enabled;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(ClientBotTick.class, e -> tickPending()),
            of(WhisperChatEvent.class, this::onWhisper),
            of(PublicChatEvent.class, this::onPublicChat)
        );
    }

    @Override
    public void onEnable() {
        registerJdaListener();
    }

    @Override
    public void onDisable() {
        unregisterJdaListener();
    }

    private void registerJdaListener() {
        if (jdaListenerRegistered) return;
        if (DISCORD == null) return;
        var jda = DISCORD.jda();
        if (jda == null) return;
        jda.addEventListener(jdaListener);
        jdaListenerRegistered = true;
        debug("Registered JDA message listener");
    }

    private void unregisterJdaListener() {
        if (!jdaListenerRegistered) return;
        if (DISCORD != null && DISCORD.jda() != null) {
            DISCORD.jda().removeEventListener(jdaListener);
        }
        jdaListenerRegistered = false;
    }

    private void onJdaEvent(net.dv8tion.jda.api.events.GenericEvent event) {
        if (event instanceof MessageReceivedEvent m) onDiscordMessage(m);
    }

    private void onWhisper(WhisperChatEvent event) {
        if (!PLUGIN_CONFIG.enabled || event.outgoing()) return;
        String msg = event.message().trim();
        if (msg.isEmpty()) return;

        var sender = event.sender();
        UUID uuid = sender.getProfileId();
        String name = sender.getName();
        if (uuid == null) return;

        String[] parts = msg.split("\\s+", 2);
        String firstWord = parts[0].toLowerCase();

        if (firstWord.equals(INGAME_AUTH_CMD)) {
            String code = parts.length > 1 ? parts[1].trim() : "";
            handleAuthWhisper(uuid, name, code);
            return;
        }

        if (firstWord.equals(INGAME_LIST_CMD)) {
            if (!isAllowed(uuid)) return;
            long count = PLUGIN_CONFIG.chambers.values().stream()
                .filter(c -> uuid.equals(c.ownerUuid))
                .count();
            int max = PLUGIN_CONFIG.maxChambersPerPlayer;
            String countStr = max > 0 ? count + "/" + max : String.valueOf(count);
            sendWhisper(name, PLUGIN_MESSAGES.format(PLUGIN_MESSAGES.pearlCount, "count", countStr));
            return;
        }

        if (!matchesInGameTrigger(firstWord)) return;

        if (!isAllowed(uuid)) return;

        PearlBotConfig.StasisChamber chamber = findChamberFor(uuid);
        if (chamber == null) {
            sendWhisper(name, PLUGIN_MESSAGES.noPearlFound);
            return;
        }

        enqueuePull(uuid, name, chamber, "in-game whisper");
    }

    private void onPublicChat(PublicChatEvent event) {
        if (!PLUGIN_CONFIG.enabled) return;
        String[] parts = event.message().trim().split("\\s+");
        if (parts.length < 2) return;

        String firstWord = parts[0].toLowerCase();
        if (!matchesInGameTrigger(firstWord)) return;

        if (!parts[1].equalsIgnoreCase(CONFIG.authentication.username)) return;

        var sender = event.sender();
        UUID uuid = sender.getProfileId();
        String name = sender.getName();
        if (uuid == null) return;

        if (!isAllowed(uuid)) return;

        PearlBotConfig.StasisChamber chamber = findChamberFor(uuid);
        if (chamber == null) {
            sendWhisper(name, PLUGIN_MESSAGES.noPearlFound);
            return;
        }

        enqueuePull(uuid, name, chamber, "public chat");
    }

    private void handleAuthWhisper(UUID mcUuid, String mcUsername, String code) {
        if (code.isBlank()) {
            sendWhisper(mcUsername, PLUGIN_MESSAGES.authUsage);
            return;
        }
        purgeExpiredAuthCodes();
        PendingAuth pending = pendingAuthCodes.remove(code.toUpperCase());
        if (pending == null) {
            sendWhisper(mcUsername, PLUGIN_MESSAGES.authInvalidCode);
            return;
        }
        PLUGIN_CONFIG.linkedAccounts.put(mcUuid, new PearlBotConfig.LinkedAccount(
            pending.discordUserId, pending.discordUsername, mcUsername, System.currentTimeMillis()));
        info("Linked MC {} ({}) to Discord {} ({})", mcUsername, mcUuid, pending.discordUsername, pending.discordUserId);
        sendWhisper(mcUsername, PLUGIN_MESSAGES.format(PLUGIN_MESSAGES.authLinked, "discordUsername", pending.discordUsername));
        notifyAuthSuccess(pending.discordUserId, mcUsername);
    }

    private void notifyAuthSuccess(String discordUserId, String mcUsername) {
        String channelId = PLUGIN_CONFIG.discordTrigger.channelId;
        if (channelId == null || channelId.isBlank()) return;
        if (DISCORD == null || DISCORD.jda() == null) return;
        var channel = DISCORD.jda().getTextChannelById(channelId);
        if (channel == null) {
            warn("Cannot send auth-success ping: channel {} not found", channelId);
            return;
        }
        channel.sendMessage("<@" + discordUserId + "> " + PLUGIN_MESSAGES.format(PLUGIN_MESSAGES.discordAuthLinked, "mcUsername", mcUsername)).queue();
    }

    private void onDiscordMessage(MessageReceivedEvent jdaEvent) {
        if (!PLUGIN_CONFIG.enabled || !PLUGIN_CONFIG.discordTrigger.enabled) return;
        String wantedChannel = PLUGIN_CONFIG.discordTrigger.channelId;
        if (wantedChannel == null || wantedChannel.isBlank()) return;
        if (!wantedChannel.equals(jdaEvent.getChannel().getId())) return;
        if (jdaEvent.getAuthor().isBot()) return;

        String content = jdaEvent.getMessage().getContentRaw().trim();
        if (content.isEmpty()) return;
        String firstWord = content.split("\\s+", 2)[0].toLowerCase();

        String discordUserId = jdaEvent.getAuthor().getId();
        String discordUsername = jdaEvent.getAuthor().getName();
        var channel = jdaEvent.getChannel();

        if (firstWord.equals(DISCORD_AUTH_CMD)) {
            String[] authParts = content.split("\\s+");
            if (authParts.length >= 2 && authParts[1].equalsIgnoreCase(ARISTOIS_SUBCMD)) {
                String token = authParts.length >= 3 ? authParts[2].trim() : "";
                handleAristoisLink(channel.getId(), discordUserId, discordUsername, token);
                return;
            }
            String code = newAuthCode(discordUserId, discordUsername);
            long ttl = (long) PLUGIN_CONFIG.discordTrigger.authCodeTtlMinutes;
            String reply = PLUGIN_MESSAGES.format(PLUGIN_MESSAGES.discordAuthCode, "code", code, "ttl", ttl);
            if (PLUGIN_CONFIG.discordTrigger.aristoisLinkingEnabled) {
                reply += "\n" + PLUGIN_MESSAGES.discordAuthAristoisHint;
            }
            channel.sendMessage("<@" + discordUserId + "> " + reply).queue();
            return;
        }

        if (!matchesDiscordTrigger(firstWord)) return;

        String targetName = content.split("\\s+", 2).length > 1
            ? content.split("\\s+", 2)[1].trim().toLowerCase()
            : null;

        Map<UUID, PearlBotConfig.LinkedAccount> linked = PLUGIN_CONFIG.linkedAccounts.entrySet().stream()
            .filter(e -> discordUserId.equals(e.getValue().discordUserId))
            .collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, java.util.LinkedHashMap::new));
        if (linked.isEmpty()) {
            channel.sendMessage("<@" + discordUserId + "> " + PLUGIN_MESSAGES.discordNoAccountsLinked).queue();
            return;
        }

        if (linked.size() > 1 && targetName == null) {
            String names = linked.values().stream()
                .map(a -> a.mcUsername != null ? a.mcUsername : "?")
                .collect(java.util.stream.Collectors.joining(", "));
            channel.sendMessage("<@" + discordUserId + "> " + PLUGIN_MESSAGES.format(PLUGIN_MESSAGES.discordMultipleAccounts, "accounts", names, "trigger", discordTriggerExample())).queue();
            return;
        }

        List<String> queued = new java.util.ArrayList<>();
        List<String> noChamber = new java.util.ArrayList<>();
        List<String> alreadyPending = new java.util.ArrayList<>();
        for (var entry : linked.entrySet()) {
            var account = entry.getValue();
            if (targetName != null && !targetName.equalsIgnoreCase(account.mcUsername)) continue;
            UUID mcUuid = entry.getKey();
            PearlBotConfig.StasisChamber chamber = findChamberFor(mcUuid);
            if (chamber == null) {
                noChamber.add(account.mcUsername);
                continue;
            }
            if (enqueuePull(mcUuid, account.mcUsername, chamber, discordUsername)) {
                queued.add(account.mcUsername);
            } else {
                alreadyPending.add(account.mcUsername);
            }
        }

        if (targetName != null && queued.isEmpty() && noChamber.isEmpty() && alreadyPending.isEmpty()) {
            String names = linked.values().stream()
                .map(a -> a.mcUsername != null ? a.mcUsername : "?")
                .collect(java.util.stream.Collectors.joining(", "));
            channel.sendMessage("<@" + discordUserId + "> " + PLUGIN_MESSAGES.format(PLUGIN_MESSAGES.discordAccountNotFound, "name", targetName, "accounts", names)).queue();
            return;
        }

        StringBuilder reply = new StringBuilder("<@").append(discordUserId).append("> ");
        if (!queued.isEmpty()) reply.append(PLUGIN_MESSAGES.format(PLUGIN_MESSAGES.discordQueued, "names", String.join(", ", queued)));
        if (!alreadyPending.isEmpty()) reply.append(PLUGIN_MESSAGES.format(PLUGIN_MESSAGES.discordAlreadyPending, "names", String.join(", ", alreadyPending)));
        if (!noChamber.isEmpty()) reply.append(PLUGIN_MESSAGES.format(PLUGIN_MESSAGES.discordNoChamber, "names", String.join(", ", noChamber)));
        if (queued.isEmpty() && alreadyPending.isEmpty() && noChamber.isEmpty()) {
            reply.append(PLUGIN_MESSAGES.discordNothingToPull);
        }
        channel.sendMessage(reply.toString()).queue();
    }

    public String newAuthCode(String discordUserId, String discordUsername) {
        purgeExpiredAuthCodes();
        String code;
        do {
            code = String.format("%06X", ThreadLocalRandom.current().nextInt(0x1000000));
        } while (pendingAuthCodes.containsKey(code));
        long expiresAt = System.currentTimeMillis() + (long) PLUGIN_CONFIG.discordTrigger.authCodeTtlMinutes * 60_000L;
        pendingAuthCodes.put(code, new PendingAuth(discordUserId, discordUsername, expiresAt));
        return code;
    }

    private void purgeExpiredAuthCodes() {
        long now = System.currentTimeMillis();
        pendingAuthCodes.entrySet().removeIf(e -> e.getValue().expiresAtMs < now);
    }

    private void handleAristoisLink(String channelId, String discordUserId, String discordUsername, String token) {
        if (!PLUGIN_CONFIG.discordTrigger.aristoisLinkingEnabled) {
            sendDiscordReply(channelId, discordUserId, PLUGIN_MESSAGES.discordAuthAristoisDisabled);
            return;
        }
        if (token.isBlank()) {
            sendDiscordReply(channelId, discordUserId,
                PLUGIN_MESSAGES.format(PLUGIN_MESSAGES.discordAuthAristoisFailed, "reason", "missing code"));
            return;
        }
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                UUID mcUuid = fetchAristoisUuid(token);
                if (mcUuid == null) {
                    sendDiscordReply(channelId, discordUserId,
                        PLUGIN_MESSAGES.format(PLUGIN_MESSAGES.discordAuthAristoisFailed, "reason", "invalid or expired code"));
                    return;
                }
                String mcUsername = resolveUsernameFromUuid(mcUuid);
                if (mcUsername == null) {
                    sendDiscordReply(channelId, discordUserId,
                        PLUGIN_MESSAGES.format(PLUGIN_MESSAGES.discordAuthAristoisFailed, "reason", "could not resolve username"));
                    return;
                }
                PLUGIN_CONFIG.linkedAccounts.put(mcUuid, new PearlBotConfig.LinkedAccount(
                    discordUserId, discordUsername, mcUsername, System.currentTimeMillis()));
                info("Linked MC {} ({}) to Discord {} ({}) via Aristois", mcUsername, mcUuid, discordUsername, discordUserId);
                sendDiscordReply(channelId, discordUserId,
                    PLUGIN_MESSAGES.format(PLUGIN_MESSAGES.discordAuthAristoisLinked, "mcUsername", mcUsername));
            } catch (Exception e) {
                warn("Aristois link request failed: {}", e.toString());
                sendDiscordReply(channelId, discordUserId,
                    PLUGIN_MESSAGES.format(PLUGIN_MESSAGES.discordAuthAristoisFailed, "reason", "service unreachable"));
            }
        });
    }

    private UUID fetchAristoisUuid(String token) throws Exception {
        var request = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create(ARISTOIS_TOKEN_URL + java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8)))
            .timeout(java.time.Duration.ofSeconds(10))
            .header("Accept", "application/json")
            .GET()
            .build();
        var response = HTTP.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        var json = com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject();
        if (!json.has("uuid") || json.get("uuid").isJsonNull()) return null;
        return parseUuid(json.get("uuid").getAsString());
    }

    private String resolveUsernameFromUuid(UUID uuid) {
        var cached = PlayerListsManager.getProfileFromUUID(uuid);
        if (cached.isPresent()) return cached.get().name();
        try {
            var request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(MOJANG_PROFILE_URL + uuid.toString().replace("-", "")))
                .timeout(java.time.Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET()
                .build();
            var response = HTTP.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body().isBlank()) return null;
            var json = com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject();
            return json.has("name") ? json.get("name").getAsString() : null;
        } catch (Exception e) {
            warn("Failed to resolve username for {}: {}", uuid, e.toString());
            return null;
        }
    }

    private static UUID parseUuid(String raw) {
        String s = raw.trim().replace("-", "");
        if (s.length() != 32) return null;
        try {
            return UUID.fromString(
                s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16)
                    + "-" + s.substring(16, 20) + "-" + s.substring(20));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void pullNotification(Embed embed, boolean verboseOnly) {
        pullNotification(embed, verboseOnly, false);
    }

    private void pullNotification(Embed embed, boolean verboseOnly, boolean ownerOffline) {
        var level = PLUGIN_CONFIG.notificationLevel;
        if (level == PearlBotConfig.NotificationLevel.NONE) return;
        if (verboseOnly && level != PearlBotConfig.NotificationLevel.VERBOSE && !ownerOffline) return;
        discordAndIngameNotification(embed);
    }

    private void sendDiscordReply(String channelId, String discordUserId, String message) {
        if (channelId == null || channelId.isBlank()) return;
        if (DISCORD == null || DISCORD.jda() == null) return;
        var channel = DISCORD.jda().getTextChannelById(channelId);
        if (channel == null) {
            warn("Cannot send Discord reply: channel {} not found", channelId);
            return;
        }
        channel.sendMessage("<@" + discordUserId + "> " + message).queue();
    }

    private boolean isAllowed(UUID uuid) {
        return switch (PLUGIN_CONFIG.whitelistMode) {
            case OFF -> true;
            case FRIENDS -> PLAYER_LISTS.getFriendsList().contains(uuid);
            case ON -> PLUGIN_CONFIG.whitelistedPlayers.containsKey(uuid);
        };
    }

    private boolean matchesInGameTrigger(String word) {
        if (PLUGIN_CONFIG.triggerWords.stream().anyMatch(t -> t.equalsIgnoreCase(word))) return true;
        String prefix = PLUGIN_CONFIG.discordTrigger.prefix;
        if (prefix == null || prefix.isEmpty()) return false;
        if (!word.startsWith(prefix.toLowerCase())) return false;
        String stripped = word.substring(prefix.length());
        return !stripped.isEmpty() && PLUGIN_CONFIG.triggerWords.stream().anyMatch(t -> t.equalsIgnoreCase(stripped));
    }

    private boolean matchesDiscordTrigger(String word) {
        String prefix = PLUGIN_CONFIG.discordTrigger.prefix;
        if (prefix == null) prefix = "";
        final String p = prefix;
        return PLUGIN_CONFIG.triggerWords.stream()
            .anyMatch(t -> (p + t).equalsIgnoreCase(word));
    }

    private String discordTriggerExample() {
        String prefix = PLUGIN_CONFIG.discordTrigger.prefix != null ? PLUGIN_CONFIG.discordTrigger.prefix : "!";
        return prefix + (PLUGIN_CONFIG.triggerWords.isEmpty() ? "warp" : PLUGIN_CONFIG.triggerWords.get(0));
    }

    private void sendWhisper(String name, String message) {
        if (name == null || name.isBlank()) return;
        String suffix = String.format("%016x", ThreadLocalRandom.current().nextLong())
            + String.format("%08x", ThreadLocalRandom.current().nextInt());
        sendClientPacketAsync(ChatUtil.getWhisperChatPacket(name, message + " [" + suffix + "]"));
    }

    public void checkAndEnforceMaxChambers(UUID ownerUuid) {
        int max = PLUGIN_CONFIG.maxChambersPerPlayer;
        if (max <= 0) return;
        long count = PLUGIN_CONFIG.chambers.values().stream()
            .filter(c -> ownerUuid.equals(c.ownerUuid))
            .count();
        if (count <= max) return;
        String name = PearlBotPlugin.resolvePlayerName(ownerUuid);
        info("Player {} has {} chamber(s), exceeding max of {}; auto-pulling pearl",
            name != null ? name : ownerUuid, count, max);
        PearlBotConfig.StasisChamber chamber = findChamberFor(ownerUuid);
        if (chamber == null) return;
        if (name != null) {
            sendWhisper(name, PLUGIN_MESSAGES.format(PLUGIN_MESSAGES.maxPearlsExceeded, "count", count, "max", max));
        }
        enqueuePull(ownerUuid, name, chamber, "max chambers exceeded");
    }

    public boolean enqueuePull(UUID ownerUuid, String requesterName, PearlBotConfig.StasisChamber chamber) {
        return enqueuePull(ownerUuid, requesterName, chamber, null);
    }

    public boolean enqueuePull(UUID ownerUuid, String requesterName, PearlBotConfig.StasisChamber chamber, String source) {
        boolean already = PLUGIN_CONFIG.pendingPulls.stream()
            .anyMatch(p -> ownerUuid.equals(p.ownerUuid));
        if (already) return false;
        PLUGIN_CONFIG.pendingPulls.add(new PearlBotConfig.PendingPull(
            ownerUuid, requesterName, chamber.x, chamber.y, chamber.z, System.currentTimeMillis(), source));
        return true;
    }

    public boolean cancelPull(UUID ownerUuid) {
        boolean removed = PLUGIN_CONFIG.pendingPulls.removeIf(p -> ownerUuid.equals(p.ownerUuid));
        if (activePull != null && ownerUuid.equals(activePull.ownerUuid)) {
            clearActivePullState();
            if (BARITONE.isActive()) BARITONE.stop();
            removed = true;
        }
        return removed;
    }

    public PearlBotConfig.StasisChamber findChamberFor(UUID ownerUuid) {
        return PLUGIN_CONFIG.chambers.values().stream()
            .filter(c -> ownerUuid.equals(c.ownerUuid))
            .findFirst()
            .orElse(null);
    }

    private void tickPending() {
        if (!PLUGIN_CONFIG.enabled) return;
        if (!jdaListenerRegistered) registerJdaListener();

        pruneGhostChambers();
        tickReopenTrapdoor();

        long now = System.currentTimeMillis();
        if (activePull != null) {
            if (!readyAtTrapdoor) {
                long timeoutMs = (long) PLUGIN_CONFIG.pullTimeoutSeconds * 1000L;
                if (timeoutMs > 0 && now - activePullStartMs > timeoutMs) {
                    warn("Positioning for {} timed out after {}s; cancelling",
                        labelOf(activePull), PLUGIN_CONFIG.pullTimeoutSeconds);
                    abortActivePull(PLUGIN_MESSAGES.format(PLUGIN_MESSAGES.pullTimedOut, "timeout", PLUGIN_CONFIG.pullTimeoutSeconds));
                    return;
                }
            } else if (awaitingClickConfirmation) {
                if (now - clickAttemptStartMs > CLICK_CONFIRM_TIMEOUT_MS) {
                    warn("Interact confirmation for {} timed out after {}ms; reporting failure",
                        labelOf(activePull), CLICK_CONFIRM_TIMEOUT_MS);
                    if (BARITONE.isActive()) BARITONE.stop();
                    abortActivePull(PLUGIN_MESSAGES.pullFailed);
                    return;
                }
            } else if (!pearlPresentNear(activePull.blockX, activePull.blockY, activePull.blockZ)) {
                abortForEmptyChamber(activePull);
                return;
            } else if (clickReadyAtMs > 0L) {
                submitTrapdoorFacingRotation(activePull);
                if (now >= clickReadyAtMs) {
                    clickReadyAtMs = 0L;
                    beginVerifiedClickAttempt(activePull);
                }
            } else if (isOwnerOnline(activePull.ownerUuid)) {
                fireClick();
            } else {
                submitTrapdoorFacingRotation(activePull);
                long waitMs = (long) PLUGIN_CONFIG.waitForOwnerSeconds * 1000L;
                if (waitMs > 0 && now - readyAtMs > waitMs) {
                    warn("{} did not come online within {}s; expiring pull",
                        labelOf(activePull), PLUGIN_CONFIG.waitForOwnerSeconds);
                    abortActivePull(PLUGIN_MESSAGES.format(PLUGIN_MESSAGES.ownerTimedOut, "timeout", PLUGIN_CONFIG.waitForOwnerSeconds));
                    return;
                }
            }
            return;
        }

        if (idleReturnAtMs > 0 && now >= idleReturnAtMs && !BARITONE.isActive()) {
            startIdleReturn();
        }

        if (PLUGIN_CONFIG.pendingPulls.isEmpty()) return;
        if (BARITONE.isActive()) return;

        Proxy proxy = Proxy.getInstance();
        if (proxy == null || !proxy.isConnected() || proxy.isInQueue() || proxy.hasActivePlayer()) return;

        if (now - lastAttemptMs < PULL_RETRY_INTERVAL_MS) return;
        lastAttemptMs = now;

        var pullsIter = PLUGIN_CONFIG.pendingPulls.iterator();
        if (!pullsIter.hasNext()) return;
        PearlBotConfig.PendingPull next = pullsIter.next();
        if (!isChamberInRange(next.blockX, next.blockY, next.blockZ)) {
            debug("Chamber for {} at ({}, {}, {}) is outside loaded chunks; deferring pull",
                labelOf(next), next.blockX, next.blockY, next.blockZ);
            return;
        }
        executePull(next);
    }

    private String labelOf(PearlBotConfig.PendingPull pull) {
        return pull.ownerName == null ? pull.ownerUuid.toString() : pull.ownerName;
    }

    private void startIdleReturn() {
        idleReturnAtMs = 0L;
        if (!PLUGIN_CONFIG.idleGoal.enabled) return;
        var goal = PLUGIN_CONFIG.idleGoal;
        info("Returning to idle position ({}, {}, {})", goal.x, goal.y, goal.z);
        BARITONE.pathTo(goal.x, goal.y, goal.z);
    }

    private void abortActivePull(String whisperReason) {
        PearlBotConfig.PendingPull pull = activePull;
        clearActivePullState();
        idleReturnAtMs = 0L;
        if (BARITONE.isActive()) BARITONE.stop();
        if (pull == null) return;

        PLUGIN_CONFIG.pendingPulls.removeIf(p -> pull.ownerUuid.equals(p.ownerUuid));
        recordPull(pull, false);
        pullNotification(Embed.builder()
            .title("Pearl Pull Cancelled")
            .addField("Owner", labelOf(pull))
            .description(whisperReason)
            .errorColor(), false);

        if (pull.ownerName != null && whisperReason != null) {
            sendWhisper(pull.ownerName, whisperReason);
        }

        if (PLUGIN_CONFIG.idleGoal.enabled) {
            idleReturnAtMs = System.currentTimeMillis() + IDLE_RETURN_DELAY_MS;
        }
    }

    private void clearActivePullState() {
        activePull = null;
        activePullStartMs = 0L;
        readyAtTrapdoor = false;
        readyAtMs = 0L;
        clickReadyAtMs = 0L;
        awaitingClickConfirmation = false;
        clickAttemptStartMs = 0L;
    }

    private boolean isChamberInRange(int x, int y, int z) {
        if (CACHE == null) return false;
        var chunkCache = CACHE.getChunkCache();
        return chunkCache != null && chunkCache.getChunkSection(x, y, z) != null;
    }

    private boolean isOwnerOnline(UUID ownerUuid) {
        if (ownerUuid == null || CACHE == null || CACHE.getTabListCache() == null) return false;
        return CACHE.getTabListCache().get(ownerUuid).isPresent();
    }

    private void pruneGhostChambers() {
        if (PLUGIN_CONFIG.chambers.isEmpty()) {
            if (!chamberEmptySinceMs.isEmpty()) chamberEmptySinceMs.clear();
            return;
        }
        if (CACHE == null || CACHE.getChunkCache() == null || CACHE.getEntityCache() == null) return;
        var player = CACHE.getPlayerCache() != null ? CACHE.getPlayerCache().getThePlayer() : null;
        if (player == null) return;

        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();
        double viewSq = (double) PLUGIN_CONFIG.pearlViewDistance * PLUGIN_CONFIG.pearlViewDistance;
        long now = System.currentTimeMillis();

        var it = PLUGIN_CONFIG.chambers.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            UUID key = entry.getKey();
            PearlBotConfig.StasisChamber c = entry.getValue();

            double dx = (c.x + 0.5) - px;
            double dy = (c.y + 0.5) - py;
            double dz = (c.z + 0.5) - pz;
            boolean inRange = dx * dx + dy * dy + dz * dz <= viewSq;
            boolean loaded = CACHE.getChunkCache().getChunkSection(c.x, c.y, c.z) != null;

            if (!inRange || !loaded || pearlPresentNear(c.x, c.y, c.z)) {
                chamberEmptySinceMs.remove(key);
                continue;
            }

            Long since = chamberEmptySinceMs.get(key);
            if (since == null) {
                chamberEmptySinceMs.put(key, now);
                continue;
            }
            if (now - since < GHOST_PRUNE_GRACE_MS) continue;

            chamberEmptySinceMs.remove(key);
            it.remove();
            info("Pruned empty chamber for owner {} (pearl {}) at ({}, {}, {}): loaded and in render distance with no pearl present",
                c.ownerUuid, key, c.x, c.y, c.z);
            cancelPullsForChamber(c);
        }
    }

    private boolean pearlPresentNear(int x, int y, int z) {
        if (CACHE == null || CACHE.getEntityCache() == null) return true;
        int radius = PLUGIN_CONFIG.trapdoorScanRadius;
        for (Entity e : CACHE.getEntityCache().getEntities().values()) {
            if (e.getEntityType() != EntityType.ENDER_PEARL) continue;
            if ((int) Math.floor(e.getX()) != x) continue;
            if ((int) Math.floor(e.getZ()) != z) continue;
            if (Math.abs(Math.round(e.getY()) - (long) y) <= radius) return true;
        }
        return false;
    }

    private void removeChamberAt(int x, int y, int z) {
        PLUGIN_CONFIG.chambers.entrySet().removeIf(e -> {
            var c = e.getValue();
            if (c.x != x || c.y != y || c.z != z) return false;
            chamberEmptySinceMs.remove(e.getKey());
            return true;
        });
    }

    private void abortForEmptyChamber(PearlBotConfig.PendingPull pull) {
        warn("Chamber for {} at ({}, {}, {}) is empty at the trapdoor; pruning and cancelling instead of clicking",
            labelOf(pull), pull.blockX, pull.blockY, pull.blockZ);
        int cx = pull.blockX, cy = pull.blockY, cz = pull.blockZ;
        removeChamberAt(cx, cy, cz);
        abortActivePull(PLUGIN_MESSAGES.chamberEmpty);
        PLUGIN_CONFIG.pendingPulls.removeIf(p -> {
            if (p.blockX != cx || p.blockY != cy || p.blockZ != cz) return false;
            if (p.ownerName != null) sendWhisper(p.ownerName, PLUGIN_MESSAGES.chamberEmpty);
            return true;
        });
    }

    private void cancelPullsForChamber(PearlBotConfig.StasisChamber c) {
        if (activePull != null
            && activePull.blockX == c.x && activePull.blockY == c.y && activePull.blockZ == c.z) {
            abortActivePull(PLUGIN_MESSAGES.chamberEmpty);
        }
        PLUGIN_CONFIG.pendingPulls.removeIf(p -> {
            if (p.blockX != c.x || p.blockY != c.y || p.blockZ != c.z) return false;
            if (p.ownerName != null) sendWhisper(p.ownerName, PLUGIN_MESSAGES.chamberEmpty);
            return true;
        });
    }

    private void executePull(PearlBotConfig.PendingPull pull) {
        int tx = pull.blockX;
        int ty = pull.blockY;
        int tz = pull.blockZ;
        String label = labelOf(pull);
        boolean ownerOnlineAtStart = isOwnerOnline(pull.ownerUuid);

        info("Positioning for {} at {} {} {} (owner currently {})",
            label, tx, ty, tz, ownerOnlineAtStart ? "online" : "OFFLINE");
        pullNotification(Embed.builder()
            .title("Positioning for Pull")
            .addField("Owner", label)
            .addField("Owner Online", ownerOnlineAtStart ? "yes" : "no")
            .primaryColor(), true, !ownerOnlineAtStart);

        var pf = CONFIG.client.extra.pathfinder;
        boolean prevAllowBreak = pf.allowBreak;
        boolean prevAllowPlace = pf.allowPlace;
        pf.allowBreak = false;
        pf.allowPlace = false;

        activePull = pull;
        activePullStartMs = System.currentTimeMillis();
        readyAtTrapdoor = false;
        clickReadyAtMs = 0L;

        BARITONE.pathTo(new GoalNear(new BlockPos(tx, ty, tz), 9)).addExecutedListener(req -> {
            pf.allowBreak = prevAllowBreak;
            pf.allowPlace = prevAllowPlace;

            if (activePull == null || !pull.ownerUuid.equals(activePull.ownerUuid)) {
                debug("Path completed for {} but active pull changed; ignoring", label);
                return;
            }
            readyAtTrapdoor = true;
            readyAtMs = System.currentTimeMillis();

            if (isOwnerOnline(pull.ownerUuid)) {
                info("Ready at trapdoor for {} - owner online, settling before interact", label);
                clickReadyAtMs = readyAtMs + CLICK_SETTLE_DELAY_MS;
            } else {
                info("Ready at trapdoor for {} - waiting for owner online", label);
            }
        });
    }

    /**
     * Fast path: fires the moment an owner who was offline logs back in. Must not add
     * latency here, so this skips InputManager/rightClickBlock's verification and just
     * fires the packet directly - correctness instead comes from continuously re-facing
     * the trapdoor throughout the wait via {@link #submitTrapdoorFacingRotation}.
     */
    private void fireClick() {
        PearlBotConfig.PendingPull pull = activePull;
        if (pull == null) return;
        sendUseItemOn(pull.blockX, pull.blockY, pull.blockZ);
        completePullSuccess(pull);
    }

    /**
     * Used when the owner was already online by the time positioning finished, so there's
     * no live login event to react to and the extra tick or two rightClickBlock costs (it
     * routes rotation+click through InputManager, applied on the next tick) doesn't matter.
     * Unlike {@link #fireClick()}, this verifies the interact actually landed before
     * declaring success.
     */
    private void beginVerifiedClickAttempt(PearlBotConfig.PendingPull pull) {
        awaitingClickConfirmation = true;
        clickAttemptStartMs = System.currentTimeMillis();
        BARITONE.rightClickBlock(pull.blockX, pull.blockY, pull.blockZ).addExecutedListener(req -> {
            if (activePull == null || !pull.ownerUuid.equals(activePull.ownerUuid)) return;
            if (!awaitingClickConfirmation) return;
            awaitingClickConfirmation = false;

            if (req.getNow()) {
                completePullSuccess(pull);
            } else {
                warn("Interact with trapdoor for {} could not be confirmed; reporting failure", labelOf(pull));
                abortActivePull(PLUGIN_MESSAGES.pullFailed);
            }
        });
    }

    private void submitTrapdoorFacingRotation(PearlBotConfig.PendingPull pull) {
        var rotation = RotationHelper.rotationTo(pull.blockX + 0.5, pull.blockY, pull.blockZ + 0.5);
        INPUTS.submit(InputRequest.builder()
            .owner(this)
            .yaw(rotation.getX())
            .pitch(rotation.getY())
            .priority(FACE_TRAPDOOR_PRIORITY)
            .build());
    }

    private void completePullSuccess(PearlBotConfig.PendingPull pull) {
        int tx = pull.blockX;
        int ty = pull.blockY;
        int tz = pull.blockZ;
        String label = labelOf(pull);

        if (PLUGIN_CONFIG.reopenTrapdoors) {
            reopenTx = tx;
            reopenTy = ty;
            reopenTz = tz;
            reopenStep = 1;
            reopenAtMs = System.currentTimeMillis() + PLUGIN_CONFIG.reopenTrapdoorsDelayMs;
        }
        dropReturnPearl(tx, ty, tz);
        PLUGIN_CONFIG.pendingPulls.removeIf(p -> pull.ownerUuid.equals(p.ownerUuid));
        clearActivePullState();
        recordPull(pull, true);

        var pullEmbed = Embed.builder()
            .title("Pearl Pulled")
            .addField("Owner", label)
            .addField("Position", "||" + tx + " " + ty + " " + tz + "||");
        if (pull.source != null) pullEmbed.addField("Triggered by", pull.source);
        pullNotification(pullEmbed.successColor(), false);

        // Don't rely on the pulled chamber having been removed from the map yet - the
        // despawn packet that triggers removal races with this code. Exclude it by
        // position explicitly instead of assuming it's (not) still present.
        int remaining = (int) PLUGIN_CONFIG.chambers.values().stream()
            .filter(c -> pull.ownerUuid.equals(c.ownerUuid))
            .filter(c -> c.x != tx || c.y != ty || c.z != tz)
            .count();
        if (pull.ownerName != null) {
            String tail = remaining == 1 ? "1 pearl" : remaining + " pearls";
            sendWhisper(pull.ownerName, PLUGIN_MESSAGES.format(PLUGIN_MESSAGES.pulled, "remaining", tail));
        }

        if (PLUGIN_CONFIG.idleGoal.enabled) {
            idleReturnAtMs = System.currentTimeMillis() + IDLE_RETURN_DELAY_MS;
        }
    }

    private static String normalizeSource(String source) {
        if (source == null) return "manual";
        return switch (source) {
            case "in-game whisper" -> "whisper";
            case "public chat" -> "chat";
            case "max chambers exceeded" -> "auto";
            default -> "discord";
        };
    }

    private void recordPull(PearlBotConfig.PendingPull pull, boolean success) {
        String source = normalizeSource(pull.source);
        if (pull.ownerUuid != null) {
            var ps = PLUGIN_CONFIG.playerStats.computeIfAbsent(
                pull.ownerUuid, k -> new PearlBotConfig.PlayerPullStats(pull.ownerName));
            if (pull.ownerName != null) ps.playerName = pull.ownerName;
            if (success) {
                ps.successful++;
                if (ps.bySource == null) ps.bySource = new java.util.concurrent.ConcurrentHashMap<>();
                ps.bySource.merge(source, 1L, Long::sum);
            } else {
                ps.aborted++;
            }
        }
        if (success) {
            PLUGIN_CONFIG.statsBySource.merge(source, 1L, Long::sum);
        }
        if (PLUGIN_CONFIG.historyEnabled) {
            PLUGIN_CONFIG.pullHistory.add(new PearlBotConfig.PullRecord(
                pull.ownerName, pull.ownerUuid, pull.source,
                pull.queuedAtMs, System.currentTimeMillis(), success));
            int excess = PLUGIN_CONFIG.pullHistory.size() - PLUGIN_CONFIG.historyMax;
            if (excess > 0)
                PLUGIN_CONFIG.pullHistory.subList(0, excess).clear();
        }
    }

    private void dropReturnPearl(int tx, int ty, int tz) {
        if (!PLUGIN_CONFIG.pearlDrop) return;
        int slot = InventoryUtil.searchPlayerInventory(
            stack -> stack != null && stack.getId() == ItemRegistry.ENDER_PEARL.id()
        );
        if (slot == -1) {
            debug("Return pearl enabled but no ender pearls in bot inventory");
            return;
        }
        var rotation = RotationHelper.rotationTo(tx + 0.5, ty, tz + 0.5);
        INPUTS.submit(InputRequest.builder()
            .owner(this)
            .yaw(rotation.getX())
            .pitch(rotation.getY())
            .priority(3000)
            .build());
        INVENTORY.submit(InventoryActionRequest.builder()
            .owner(this)
            .actions(new DropItem(slot, DropItemAction.DROP_FROM_SELECTED))
            .priority(3000)
            .build());
    }

    private void tickReopenTrapdoor() {
        if (reopenStep == 0 || System.currentTimeMillis() < reopenAtMs) return;
        if (reopenStep == 1) {
            sendUseItemOn(reopenTx, reopenTy, reopenTz);
            reopenStep = 2;
            reopenAtMs = System.currentTimeMillis() + PLUGIN_CONFIG.reopenTrapdoorsDelayMs;
        } else {
            openUpperTrapdoorIfClosed(reopenTx, reopenTy, reopenTz);
            reopenStep = 0;
        }
    }

    private void openUpperTrapdoorIfClosed(int tx, int ty, int tz) {
        var chunkCache = CACHE.getChunkCache();
        if (chunkCache == null) return;
        var section = chunkCache.getChunkSection(tx, ty + 1, tz);
        if (section == null) return;
        int stateId = section.getBlock(tx & 15, (ty + 1) & 15, tz & 15);
        if (stateId == 0) return;
        var block = BLOCK_DATA.getBlockDataFromBlockStateId(stateId);
        if (block == null || !block.name().endsWith("_trapdoor")) return;
        var state = new BlockState(block, stateId, tx, ty + 1, tz);
        if (!state.hasProperty(BlockStateProperties.HALF) || !state.hasProperty(BlockStateProperties.OPEN)) return;
        if (Half.BOTTOM.equals(state.getProperty(BlockStateProperties.HALF))
                && Boolean.FALSE.equals(state.getProperty(BlockStateProperties.OPEN))) {
            sendUseItemOn(tx, ty + 1, tz);
        }
    }

    private void sendUseItemOn(int x, int y, int z) {
        var packet = new ServerboundUseItemOnPacket(
            x, y, z,
            Direction.DOWN,
            Hand.MAIN_HAND,
            0.5f, 0.5f, 0.5f,
            false, false,
            0);
        sendClientPacketAsync(packet);
    }

}
