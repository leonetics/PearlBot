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

public class PearlBotMessages {

    public String noPearlFound = "No pearl found for you.";

    public String pulled = "Pulled. You have {remaining} left.";

    public String maxPearlsExceeded = "You have {count} pearl(s) but the max is {max}! Automatically pulling your oldest pearl.";

    public String pearlCount = "You have {count} pearl(s) stasised.";

    public String pullTimedOut = "Positioning timed out after {timeout}s.";

    public String chamberEmpty = "Your stasis chamber is empty - no pearl to pull.";

    public String ownerTimedOut = "Expired - you did not log on within {timeout}s.";

    public String pullFailed = "Failed to pull your pearl - please try again.";

    public String authUsage = "Usage: !auth <code> - get a code by typing !auth in Discord first.";
    public String authInvalidCode = "Invalid or expired code.";

    public String authLinked = "Linked to Discord {discordUsername}.";

    public String discordNoAccountsLinked = "No MC accounts linked. Type `!auth` to link one.";

    public String discordMultipleAccounts = "Accounts linked: {accounts}. Please type `{trigger} <username>` to pull a specific account.";

    public String discordAccountNotFound = "No linked account named `{name}`. Accounts linked: {accounts}.";

    public String discordAuthCode = "Whisper me `!auth {code}` in-game from each MC account you want to link. Expires in {ttl} minutes.";

    public String discordAuthAristoisHint = "Can't whisper in-game? Join `auth.aristois.net`, then run `!auth aristois <code>` here.";

    public String discordAuthLinked = "Linked MC account `{mcUsername}`.";

    public String discordAuthAristoisLinked = "Linked MC account `{mcUsername}` via Aristois.";

    public String discordAuthAristoisFailed = "Aristois link failed: {reason}";

    public String discordAuthAristoisDisabled = "Aristois linking is disabled.";

    public String discordQueued = "Queued: {names}. ";

    public String discordAlreadyPending = "Already pending: {names}. ";

    public String discordNoChamber = "No chamber: {names}.";

    public String discordNothingToPull = "Nothing to pull.";

    public String format(String template, Object... keyValues) {
        String result = template;
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            result = result.replace("{" + keyValues[i] + "}", String.valueOf(keyValues[i + 1]));
        }
        return result;
    }
}