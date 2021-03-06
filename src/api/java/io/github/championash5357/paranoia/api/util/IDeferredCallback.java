/*
 * Paranoia
 * Copyright (C) 2020 ChampionAsh5357
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as 
 * published by the Free Software Foundation version 3.0 of the License.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.championash5357.paranoia.api.util;

import io.github.championash5357.paranoia.api.sanity.ISanity;
import net.minecraft.entity.player.ServerPlayerEntity;

/**
 * Used to defer running restart callbacks until after
 * the player has logged in. For internal use.
 */
@FunctionalInterface
public interface IDeferredCallback {

	/**
	 * Runs the associated callback.
	 * 
	 * @param player The server player
	 * @param inst The sanity instance
	 * @param sanity The current sanity level
	 * @param prevSanity The last updated sanity level
	 */
	void run(ServerPlayerEntity player, ISanity inst, int sanity, int prevSanity);
}
