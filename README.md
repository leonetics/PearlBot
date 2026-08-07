# PearlBot

A [ZenithProxy](https://github.com/rfresh2/ZenithProxy) plugin that pulls stasis chambers on demand from in-game whispers or Discord.

## Features

- Auto-detects stasis chambers with automatic owner resolution.
- Offline pulling: the bot walks to the trapdoor and waits, then fires the moment the owner logs on.
- Discord ⇄ Minecraft account linking via short `!auth` codes (one Discord user → many MC accounts).
- Whitelist for in-game whisper triggers.
- AFK-return position after each pull.
- Configurable per-player chamber cap — the bot warns the player and auto-pulls pearls to keep them at the cap.
- Players can whisper `list` to check how many pearls they have set.

## Install

1. Build with `./gradlew build` or grab the latest build from the Releases page.
2. Drop the jar into the `plugins/` folder next to your ZenithProxy launcher.
3. Restart ZenithProxy. Plugins require the `java` release channel.

## Setup

In Zenith's terminal or Discord command channel:

```
pearlbot on
pearlbot whitelist add <yourMcName>
pearlbot idle here
```

It is recommended to adjust Zenith's antiAFK settings to avoid accidental pearl pulling:

```
antiAFK walk off
antiAFK sneak off
antiAFK jump off
antiAFK swing on
```

## How to use

You can create stasis chambers by throwing your pearl down a bubble column. The bot will automatically register it.

**In-game**: whisper your PearlBot the trigger word (default `warp`). You can also whisper `list` to see how many pearls you currently have set.

**Offline/Discord**: in your configured Discord channel after authenticating, type the prefix and then the trigger word (default `!warp`). When you log back on, the PearlBot will instantly pull you. Useful for escaping logout traps and such.

To limit how many chambers each player can have at once, use `pearlbot maxchambers <count>` (set to `0` for no limit). If a player exceeds the cap, the bot will whisper them a warning and automatically queue a pull for their oldest pearl.

## Discord Authentication

For the Discord channel, the bot needs **Read Messages** + **Send Messages** in that channel, and **Message Content Intent** enabled in the Discord Developer Portal.

```
pearlbot discord on
pearlbot discord channel <channelId>
```

To trigger pulls from Discord, link your Discord account to your Minecraft account once:

1. In the configured Discord channel, type `!auth`.
2. The bot replies with a 6-character code, e.g. `A3F1C7`. Codes are single-use and expire in 5 minutes.
3. Log in to the Minecraft account you want to link and whisper the bot:
   ```
   /msg <BotName> !auth A3F1C7
   ```
4. The bot confirms in Discord:
   ```
   @you Linked MC account `yourMcName`.
   ```

One Discord user can link any number of Minecraft accounts — just request a new code for each.

### Linking without in-game chat (Aristois)

If you can't whisper the bot in-game (e.g. you're stuck in queue or can't reach its server), you can link through the third-party [Aristois](https://aristois.net) auth server instead:

1. Join the Minecraft server `auth.aristois.net` with the account you want to link. It gives you a token.
2. In the configured Discord channel, run:
   ```
   !auth aristois <token>
   ```
3. The bot verifies the token, resolves your account, and confirms:
   ```
   @you Linked MC account `yourMcName` via Aristois.
   ```

This path trusts `auth.aristois.net` to authenticate the account on your behalf. It's a secondary option — disable it with `aristoisLinkingEnabled = false` in the Discord trigger config if you'd rather only allow in-game verification.

After that, typing `!warp` in the channel queues a pull for every linked account that has a chamber.

## Credits & License

[GPL-3.0](LICENSE) — GNU General Public License v3.0.

- Pearl detection inspired by [ShaysBot](https://github.com/ShayBox/ShaysBot).
- Integration patterns referenced from [PearlPlus](https://github.com/duccss/pearlplus).
- Built for [ZenithProxy](https://github.com/rfresh2/ZenithProxy) by rfresh2.
