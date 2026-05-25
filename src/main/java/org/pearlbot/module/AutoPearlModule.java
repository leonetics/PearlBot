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
package org.pearlbot.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.Proxy;
import com.zenith.discord.Embed;
import com.zenith.event.chat.WhisperChatEvent;
import com.zenith.event.client.ClientBotTick;
import com.zenith.feature.pathfinder.goals.GoalNear;
import com.zenith.feature.whitelist.PlayerListsManager;
import com.zenith.mc.block.BlockPos;
import com.zenith.module.api.Module;
import com.zenith.util.ChatUtil;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.EventListener;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;
import org.pearlbot.PearlBotConfig;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.CONFIG;
import static com.zenith.Globals.DISCORD;
import static org.pearlbot.PearlBotPlugin.PLUGIN_CONFIG;
import static org.pearlbot.PearlBotPlugin.PLUGIN_MESSAGES;

public class AutoPearlModule extends Module {
    private static final long PULL_RETRY_INTERVAL_MS = 1_000L;
    private static final long IDLE_RETURN_DELAY_MS = 1500L;
    private static final String DISCORD_AUTH_CMD = "!auth";
    private static final String INGAME_AUTH_CMD = "!auth";
    private static final String INGAME_LIST_CMD = "list";

    private long lastAttemptMs = 0L;
    private PearlBotConfig.PendingPull activePull = null;
    private long activePullStartMs = 0L;
    private boolean readyAtTrapdoor = false;
    private long readyAtMs = 0L;
    private long idleReturnAtMs = 0L;

    private final Map<String, PendingAuth> pendingAuthCodes = new HashMap<>();
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
            of(WhisperChatEvent.class, this::onWhisper)
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
            if (PLUGIN_CONFIG.whitelist.enabled && !PLUGIN_CONFIG.whitelist.players.containsKey(uuid)) return;
            long count = PLUGIN_CONFIG.chambers.values().stream()
                .filter(c -> uuid.equals(c.ownerUuid))
                .count();
            int max = PLUGIN_CONFIG.maxChambersPerPlayer;
            String countStr = max > 0 ? count + "/" + max : String.valueOf(count);
            sendWhisper(name, PLUGIN_MESSAGES.format(PLUGIN_MESSAGES.pearlCount, "count", countStr));
            return;
        }

        if (!firstWord.equals(triggerWordIngame())) return;

        if (PLUGIN_CONFIG.whitelist.enabled && !PLUGIN_CONFIG.whitelist.players.containsKey(uuid)) {
            return;
        }

        PearlBotConfig.StasisChamber chamber = findChamberFor(uuid);
        if (chamber == null) {
            sendWhisper(name, PLUGIN_MESSAGES.noPearlFound);
            return;
        }

        enqueuePull(uuid, name, chamber);
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
            String code = newAuthCode(discordUserId, discordUsername);
            long ttl = (long) PLUGIN_CONFIG.discordTrigger.authCodeTtlMinutes;
            channel.sendMessage("<@" + discordUserId + "> " + PLUGIN_MESSAGES.format(PLUGIN_MESSAGES.discordAuthCode, "code", code, "ttl", ttl)).queue();
            return;
        }

        if (!firstWord.equals(triggerWordDiscord())) return;

        String targetName = content.split("\\s+", 2).length > 1
            ? content.split("\\s+", 2)[1].trim().toLowerCase()
            : null;

        List<PearlBotConfig.LinkedAccount> linked = PLUGIN_CONFIG.linkedAccounts.entrySet().stream()
            .filter(e -> discordUserId.equals(e.getValue().discordUserId))
            .map(Map.Entry::getValue)
            .toList();
        if (linked.isEmpty()) {
            channel.sendMessage("<@" + discordUserId + "> " + PLUGIN_MESSAGES.discordNoAccountsLinked).queue();
            return;
        }

        if (linked.size() > 1 && targetName == null) {
            String names = linked.stream()
                .map(a -> a.mcUsername != null ? a.mcUsername : "?")
                .collect(java.util.stream.Collectors.joining(", "));
            channel.sendMessage("<@" + discordUserId + "> " + PLUGIN_MESSAGES.format(PLUGIN_MESSAGES.discordMultipleAccounts, "accounts", names, "trigger", triggerWordDiscord())).queue();
            return;
        }

        List<String> queued = new java.util.ArrayList<>();
        List<String> noChamber = new java.util.ArrayList<>();
        List<String> alreadyPending = new java.util.ArrayList<>();
        List<String> notFound = new java.util.ArrayList<>();
        for (var account : linked) {
            if (targetName != null && !targetName.equalsIgnoreCase(account.mcUsername)) continue;
            UUID mcUuid = lookupMcUuid(account);
            if (mcUuid == null) continue;
            PearlBotConfig.StasisChamber chamber = findChamberFor(mcUuid);
            if (chamber == null) {
                noChamber.add(account.mcUsername);
                continue;
            }
            if (enqueuePull(mcUuid, account.mcUsername, chamber)) {
                queued.add(account.mcUsername);
            } else {
                alreadyPending.add(account.mcUsername);
            }
        }

        if (targetName != null && queued.isEmpty() && noChamber.isEmpty() && alreadyPending.isEmpty()) {
            String names = linked.stream()
                .map(a -> a.mcUsername != null ? a.mcUsername : "?")
                .collect(java.util.stream.Collectors.joining(", "));
            channel.sendMessage("<@" + discordUserId + "> " + PLUGIN_MESSAGES.format(PLUGIN_MESSAGES.discordAccountNotFound, "name", targetName, "accounts", names)).queue();
            return;
        }

        StringBuilder reply = new StringBuilder("<@").append(discordUserId).append("> ");
        if (!queued.isEmpty()) reply.append("Queued: ").append(String.join(", ", queued)).append(". ");
        if (!alreadyPending.isEmpty()) reply.append("Already pending: ").append(String.join(", ", alreadyPending)).append(". ");
        if (!noChamber.isEmpty()) reply.append("No chamber: ").append(String.join(", ", noChamber)).append(".");
        if (queued.isEmpty() && alreadyPending.isEmpty() && noChamber.isEmpty()) {
            reply.append("Nothing to pull.");
        }
        channel.sendMessage(reply.toString()).queue();
    }

    private UUID lookupMcUuid(PearlBotConfig.LinkedAccount account) {
        return PLUGIN_CONFIG.linkedAccounts.entrySet().stream()
            .filter(e -> e.getValue() == account)
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(null);
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

    private String triggerWordIngame() {
        String w = PLUGIN_CONFIG.triggerWord;
        return (w == null || w.isBlank()) ? "warp" : w.toLowerCase();
    }

    private String triggerWordDiscord() {
        return "!" + triggerWordIngame();
    }

    private void sendWhisper(String name, String message) {
        if (name == null || name.isBlank()) return;
        String suffix = String.format("%08x", ThreadLocalRandom.current().nextInt());
        sendClientPacketAsync(ChatUtil.getWhisperChatPacket(name, message + " - " + suffix));
    }

    public void checkAndEnforceMaxChambers(UUID ownerUuid) {
        int max = PLUGIN_CONFIG.maxChambersPerPlayer;
        if (max <= 0) return;
        long count = PLUGIN_CONFIG.chambers.values().stream()
            .filter(c -> ownerUuid.equals(c.ownerUuid))
            .count();
        if (count <= max) return;
        String name = resolvePlayerName(ownerUuid);
        info("Player {} has {} chamber(s), exceeding max of {}; auto-pulling oldest",
            name != null ? name : ownerUuid, count, max);
        // chambers is a LinkedHashMap, so findChamberFor returns the oldest registered chamber.
        PearlBotConfig.StasisChamber chamber = findChamberFor(ownerUuid);
        if (chamber == null) return;
        if (name != null) {
            sendWhisper(name, PLUGIN_MESSAGES.format(PLUGIN_MESSAGES.maxPearlsExceeded, "count", count, "max", max));
        }
        enqueuePull(ownerUuid, name, chamber);
    }

    private String resolvePlayerName(UUID uuid) {
        if (uuid == null) return null;
        var linked = PLUGIN_CONFIG.linkedAccounts.get(uuid);
        if (linked != null && linked.mcUsername != null) return linked.mcUsername;
        var whitelisted = PLUGIN_CONFIG.whitelist.players.get(uuid);
        if (whitelisted != null && whitelisted.username != null) return whitelisted.username;
        return PlayerListsManager.getProfileFromUUID(uuid)
            .map(p -> p.name())
            .orElse(null);
    }

    private int remainingPearlsFor(UUID ownerUuid) {
        if (ownerUuid == null) return 0;
        return (int) PLUGIN_CONFIG.chambers.values().stream()
            .filter(c -> ownerUuid.equals(c.ownerUuid))
            .count();
    }

    public boolean enqueuePull(UUID ownerUuid, String requesterName, PearlBotConfig.StasisChamber chamber) {
        boolean already = PLUGIN_CONFIG.pendingPulls.stream()
            .anyMatch(p -> ownerUuid.equals(p.ownerUuid));
        if (already) return false;
        PLUGIN_CONFIG.pendingPulls.add(new PearlBotConfig.PendingPull(
            ownerUuid, requesterName, chamber.x, chamber.y, chamber.z, System.currentTimeMillis()));
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
            } else if (isOwnerOnline(activePull.ownerUuid)) {
                fireClick();
            } else {
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

        PearlBotConfig.PendingPull next = PLUGIN_CONFIG.pendingPulls.get(0);
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
        discordAndIngameNotification(Embed.builder()
            .title("Pearl Pull Cancelled")
            .addField("Owner", labelOf(pull))
            .description(whisperReason)
            .errorColor());

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

    private void executePull(PearlBotConfig.PendingPull pull) {
        int tx = pull.blockX;
        int ty = pull.blockY;
        int tz = pull.blockZ;
        String label = labelOf(pull);
        boolean ownerOnlineAtStart = isOwnerOnline(pull.ownerUuid);

        info("Positioning for {} at {} {} {} (owner currently {})",
            label, tx, ty, tz, ownerOnlineAtStart ? "online" : "OFFLINE");
        discordAndIngameNotification(Embed.builder()
            .title("Positioning for Pull")
            .addField("Owner", label)
            .addField("Owner Online", ownerOnlineAtStart ? "yes" : "no")
            .primaryColor());

        var pf = CONFIG.client.extra.pathfinder;
        boolean prevAllowBreak = pf.allowBreak;
        boolean prevAllowPlace = pf.allowPlace;
        pf.allowBreak = false;
        pf.allowPlace = false;

        activePull = pull;
        activePullStartMs = System.currentTimeMillis();
        readyAtTrapdoor = false;

        BARITONE.pathTo(new GoalNear(new BlockPos(tx, ty, tz), 9)).addExecutedListener(req -> {
            pf.allowBreak = prevAllowBreak;
            pf.allowPlace = prevAllowPlace;

            if (activePull == null || !pull.ownerUuid.equals(activePull.ownerUuid)) {
                debug("Path completed for {} but active pull changed; ignoring", label);
                return;
            }
            readyAtTrapdoor = true;
            readyAtMs = System.currentTimeMillis();
            info("Ready at trapdoor for {} - {}",
                label, isOwnerOnline(pull.ownerUuid) ? "owner online, clicking" : "waiting for owner online");
        });
    }

    private void fireClick() {
        PearlBotConfig.PendingPull pull = activePull;
        if (pull == null) return;
        int tx = pull.blockX;
        int ty = pull.blockY;
        int tz = pull.blockZ;
        String label = labelOf(pull);

        sendUseItemOn(tx, ty, tz);
        PLUGIN_CONFIG.pendingPulls.removeIf(p -> pull.ownerUuid.equals(p.ownerUuid));
        clearActivePullState();

        discordAndIngameNotification(Embed.builder()
            .title("Pearl Pulled")
            .addField("Owner", label)
            .successColor());

        int remaining = Math.max(0, remainingPearlsFor(pull.ownerUuid) - 1);
        if (pull.ownerName != null) {
            String tail = remaining == 1 ? "1 pearl" : remaining + " pearls";
            sendWhisper(pull.ownerName, PLUGIN_MESSAGES.format(PLUGIN_MESSAGES.pulled, "remaining", tail));
        }

        if (PLUGIN_CONFIG.idleGoal.enabled) {
            idleReturnAtMs = System.currentTimeMillis() + IDLE_RETURN_DELAY_MS;
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

    public List<PearlBotConfig.PendingPull> pending() {
        return PLUGIN_CONFIG.pendingPulls;
    }

    public Map<UUID, PearlBotConfig.StasisChamber> chambers() {
        return PLUGIN_CONFIG.chambers;
    }
}
