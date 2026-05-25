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

public class PearlBotMessages {

    // --- In-game whispers ---

    public String noPearlFound = "No pearl found for you.";

    // {remaining} = e.g. "2 pearls" or "1 pearl"
    public String pulled = "Pulled. You have {remaining} left.";

    // {count} = current pearl count, {max} = configured max
    public String maxPearlsExceeded = "You have {count} pearl(s) but the max is {max}! Automatically pulling your oldest pearl.";

    // {count} = e.g. "2" or "2/3" when a max is set
    public String pearlCount = "You have {count} pearl(s) stasised.";

    // {timeout} = seconds configured for pull timeout
    public String pullTimedOut = "Positioning timed out after {timeout}s.";

    // {timeout} = seconds configured for owner-online timeout
    public String ownerTimedOut = "Expired - you did not log on within {timeout}s.";

    public String authUsage = "Usage: !auth <code> - get a code by typing !auth in Discord first.";
    public String authInvalidCode = "Invalid or expired code.";

    // {discordUsername} = the Discord username that was linked
    public String authLinked = "Linked to Discord {discordUsername}.";

    // --- Discord messages (the @mention is prepended automatically) ---

    public String discordNoAccountsLinked = "No MC accounts linked. Type `!auth` to link one.";

    // {accounts} = comma-separated account names, {trigger} = e.g. "!warp"
    public String discordMultipleAccounts = "Accounts linked: {accounts}. Please type `{trigger} <username>` to pull a specific account.";

    // {name} = the username that wasn't found, {accounts} = comma-separated linked account names
    public String discordAccountNotFound = "No linked account named `{name}`. Accounts linked: {accounts}.";

    // {code} = auth code, {ttl} = minutes until expiry
    public String discordAuthCode = "Whisper me `!auth {code}` in-game from each MC account you want to link. Expires in {ttl} minutes.";

    // {mcUsername} = the MC account that was linked
    public String discordAuthLinked = "Linked MC account `{mcUsername}`.";

    // Replaces {key} placeholders — pass alternating key, value pairs
    public String format(String template, Object... keyValues) {
        String result = template;
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            result = result.replace("{" + keyValues[i] + "}", String.valueOf(keyValues[i + 1]));
        }
        return result;
    }
}