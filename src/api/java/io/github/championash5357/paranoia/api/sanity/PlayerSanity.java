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

package io.github.championash5357.paranoia.api.sanity;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.apache.http.ParseException;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.nbt.StringNBT;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;

//TODO: Document
public class PlayerSanity implements ISanity {

	@Nullable
	private final PlayerEntity player;
	private boolean firstInteraction;
	private int minSanity, maxSanity; //Should be effectively final
	private int sanity, tempMinSanity, tempMaxSanity;
	private final Map<Integer, Set<ResourceLocation>> unloadedCallbacks = new HashMap<>();
	private final Map<Integer, Set<SanityCallback>> loadedCallbacks = new HashMap<>();
	private final List<BiConsumer<ServerPlayerEntity, Integer>> deferredCallbacks = new ArrayList<>();

	public PlayerSanity() {
		this(null);
	}

	public PlayerSanity(@Nullable PlayerEntity player) {
		this(player, 0, 100, 100, 10, 100);
	}

	public PlayerSanity(@Nullable PlayerEntity player, int minSanity, int maxSanity) {
		this(player, minSanity, maxSanity, maxSanity, minSanity + 10, maxSanity);
	}

	public PlayerSanity(@Nullable PlayerEntity player, int minSanity, int maxSanity, int sanity) {
		this(player, minSanity, maxSanity, sanity, minSanity + 10, maxSanity);
	}

	public PlayerSanity(@Nullable PlayerEntity player, int minSanity, int maxSanity, int sanity, int tempMinSanity, int tempMaxSanity) {
		this.player = player;
		this.minSanity = minSanity;
		this.maxSanity = maxSanity;
		this.tempMinSanity = tempMinSanity;
		this.tempMaxSanity = tempMaxSanity;
		this.sanity = sanity;
	}

	@Override
	public int getSanity() {
		return this.sanity;
	}
	
	@Override
	public int getMaxSanity() {
		return this.tempMaxSanity;
	}

	@Override
	public void setSanity(int sanity) {
		int originalSanity = this.sanity;
		this.sanity = MathHelper.clamp(sanity, this.minSanity, this.tempMaxSanity);
		updateSanityInformation(originalSanity, this.sanity);
	}

	@Override
	public void changeSanity(int amount) {
		this.setSanity(this.sanity + amount);
	}

	@Override
	public void setMaxSanity(int maxSanity) {
		this.tempMaxSanity = MathHelper.clamp(maxSanity, this.tempMinSanity, this.maxSanity);
		this.setSanity(this.sanity);
	}

	@Override
	public void changeMaxSanity(int amount) {
		this.setMaxSanity(this.tempMaxSanity + amount);
	}

	@Override
	public void setMinSanity(int minSanity) {
		this.tempMinSanity = MathHelper.clamp(minSanity, this.minSanity, this.tempMaxSanity);
		this.setSanity(this.sanity);
	}

	@Override
	public void changeMinSanity(int amount) {
		this.setMinSanity(this.minSanity + amount);
	}
	
	@Override
	public void tick() {
		//TODO: Implement ticks
	}

	private void updateSanityInformation(int originalSanity, int newSanity) {
		if(!this.firstInteraction) this.setupInitialMaps();
		if(originalSanity == newSanity || this.player == null || this.player.world.isRemote) return;
		if(originalSanity > newSanity) {
			this.loadedCallbacks.entrySet().stream().flatMap(entry -> entry.getValue().stream()).forEach(callback -> callback.getHandler().update((ServerPlayerEntity) this.player, newSanity));
			for(int i = originalSanity - 1; i >= newSanity; --i) {
				@Nullable Set<ResourceLocation> unloaded = this.unloadedCallbacks.get(i);
				if(unloaded != null) {
					unloaded.forEach(location -> {
						SanityCallback callback = SanityCallbacks.createCallback(location);
						callback.getHandler().start((ServerPlayerEntity) this.player, newSanity);
						this.loadedCallbacks.computeIfAbsent(callback.getStopSanity(), a -> new HashSet<>()).add(callback);
					});
					this.unloadedCallbacks.remove(i);
				}
			}
		} else {
			for(int i = originalSanity + 1; i <= newSanity; ++i) {
				@Nullable Set<SanityCallback> loaded = this.loadedCallbacks.get(i);
				if(loaded != null) {
					loaded.forEach(callback -> {
						callback.getHandler().stop((ServerPlayerEntity) this.player, newSanity);
						this.unloadedCallbacks.computeIfAbsent(callback.getStartSanity(), a -> new HashSet<>()).add(callback.getId());
					});
					this.loadedCallbacks.remove(i);
				}
			}
			this.loadedCallbacks.entrySet().stream().flatMap(entry -> entry.getValue().stream()).forEach(callback -> callback.getHandler().update((ServerPlayerEntity) this.player, newSanity));
		}
	}

	private void setupInitialMaps() {
		if(this.player == null || this.player.world.isRemote) return;
		this.unloadedCallbacks.clear();
		this.loadedCallbacks.clear();
		Map<ResourceLocation, Function<ResourceLocation, SanityCallback>> registryMap = SanityCallbacks.getValidationMap();
		registryMap.forEach((id, callbackSupplier) -> {
			SanityCallback callback = callbackSupplier.apply(id);
			if(this.sanity <= callback.getStartSanity()) {
				this.loadedCallbacks.computeIfAbsent(callback.getStopSanity(), a -> new HashSet<>()).add(callback);
			} else {
				this.unloadedCallbacks.computeIfAbsent(callback.getStartSanity(), a -> new HashSet<>()).add(callback.getId());
			}
		});
		this.firstInteraction = true;
	}
	
	@Override
	public void executeLoginCallbacks(ServerPlayerEntity player) {
		this.deferredCallbacks.forEach(consumer -> consumer.accept(player, this.sanity));
		this.deferredCallbacks.clear();
	}

	@Override
	public CompoundNBT serializeNBT() {
		CompoundNBT nbt = new CompoundNBT();
		nbt.putInt("minSanity", this.minSanity);
		nbt.putInt("maxSanity", this.maxSanity);
		nbt.putInt("sanity", this.sanity);
		nbt.putInt("tempMinSanity", this.tempMinSanity);
		nbt.putInt("tempMaxSanity", this.tempMaxSanity);
		nbt.putBoolean("firstInteraction", this.firstInteraction);
		CompoundNBT unloadedCallbacks = new CompoundNBT();
		this.unloadedCallbacks.forEach((startSanity, list) -> {
			ListNBT locations = new ListNBT();
			list.forEach(location -> locations.add(StringNBT.valueOf(location.toString())));
			unloadedCallbacks.put(String.valueOf(startSanity), locations);
		});
		nbt.put("unloadedCallbacks", unloadedCallbacks);
		CompoundNBT loadedCallbacks = new CompoundNBT();
		this.loadedCallbacks.forEach((stopSanity, list) -> {
			ListNBT locations = new ListNBT();
			list.forEach(callback -> {
				if(callback.getHandler().hasData()) {
					CompoundNBT callbackData = new CompoundNBT();
					callbackData.putString("id", callback.getId().toString());
					callbackData.put("data", callback.getHandler().serializeNBT());
					locations.add(callbackData);
				} else {
					locations.add(StringNBT.valueOf(callback.getId().toString()));
				}
			});
			loadedCallbacks.put(String.valueOf(stopSanity), locations);
		});
		nbt.put("loadedCallbacks", loadedCallbacks);
		return nbt;
	}

	@Override
	public void deserializeNBT(CompoundNBT nbt) {
		this.minSanity = nbt.getInt("minSanity");
		this.maxSanity = nbt.getInt("maxSanity");
		this.sanity = nbt.getInt("sanity");
		this.tempMinSanity = nbt.getInt("tempMinSanity");
		this.tempMaxSanity = nbt.getInt("tempMaxSanity");
		this.firstInteraction = nbt.getBoolean("firstInteraction");
		this.unloadedCallbacks.clear();
		this.loadedCallbacks.clear();
		Map<ResourceLocation, Function<ResourceLocation, SanityCallback>> registryMap = SanityCallbacks.getValidationMap();
		CompoundNBT unloadedCallbacks = nbt.getCompound("unloadedCallbacks");
		unloadedCallbacks.keySet().forEach(startSanity -> {
			Set<ResourceLocation> locations = new HashSet<>();
			ListNBT list = unloadedCallbacks.getList(startSanity, 9);
			list.forEach(inbt -> {
				if (inbt instanceof StringNBT) {
					ResourceLocation location = new ResourceLocation(((StringNBT) inbt).getString());
					locations.add(location);
					registryMap.remove(location);
				} else {
					throw new ParseException("The specified INBT is not formatted as a StringNBT.");
				}
			});
			this.unloadedCallbacks.put(Integer.valueOf(startSanity), locations);
		});
		CompoundNBT loadedCallbacks = nbt.getCompound("loadedCallbacks");
		loadedCallbacks.keySet().forEach(stopSanity -> {
			Set<SanityCallback> callbacks = new HashSet<>();
			ListNBT list = unloadedCallbacks.getList(stopSanity, 9);
			list.forEach(inbt -> {
				if (inbt instanceof StringNBT) {
					ResourceLocation location = new ResourceLocation(((StringNBT) inbt).getString());
					callbacks.add(SanityCallbacks.createCallback(location));
					registryMap.remove(location);
				} else if (inbt instanceof CompoundNBT) {
					SanityCallback callback = SanityCallbacks.createCallback(new ResourceLocation(((CompoundNBT) inbt).getString("id")));
					callback.getHandler().deserializeNBT(((CompoundNBT) inbt).getCompound("data"));
					if (callback.getHandler().restartOnReload()) deferredCallbacks.add((player, sanity) -> callback.getHandler().start(player, sanity));
					callbacks.add(callback);
					registryMap.remove(callback.getId());
				} else {
					throw new ParseException("The specified INBT is not formatted as a StringNBT or CompoundNBT.");
				}
			});
			this.loadedCallbacks.put(Integer.valueOf(stopSanity), callbacks);
		});
		registryMap.forEach((id, callbackSupplier) -> {
			SanityCallback callback = callbackSupplier.apply(id);
			if(this.sanity <= callback.getStartSanity()) {
				deferredCallbacks.add((player, sanity) -> callback.getHandler().start(player, sanity));
				this.loadedCallbacks.computeIfAbsent(callback.getStopSanity(), a -> new HashSet<>()).add(callback);
			} else {
				this.unloadedCallbacks.computeIfAbsent(callback.getStartSanity(), a -> new HashSet<>()).add(callback.getId());
			}
		});
	}
}
