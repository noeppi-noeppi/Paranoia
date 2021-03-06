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
import java.util.function.Function;

import javax.annotation.Nullable;

import io.github.championash5357.paranoia.api.callback.ICallback.Phase;
import io.github.championash5357.paranoia.api.callback.SanityCallback;
import io.github.championash5357.paranoia.api.callback.SanityCallbacks;
import io.github.championash5357.paranoia.api.util.*;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.nbt.*;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.common.util.Constants;

/**
 * A basic implementation of {@link ISanity}.
 * Used for the sanity capability on players.
 */
public class PlayerSanity implements ISanity {

	@Nullable
	private final PlayerEntity player;
	private boolean firstInteraction;
	private int minSanity, maxSanity; // Should be effectively final
	private int prevSanity, sanity, tempMinSanity, tempMaxSanity;
	private int time, recoveryTime, attackTime; // Only used to keep track of tick information
	private int threshold, recoveryThreshold, attackThreshold; // Thresholds on when to execute tick
	private final Map<Integer, Set<ResourceLocation>> unloadedCallbacks = new HashMap<>();
	private final Map<Integer, Set<SanityCallback>> loadedCallbacks = new HashMap<>();
	private final List<IDeferredCallback> deferredCallbacks = new ArrayList<>();
	private final Map<String, ITickable> temporaryTickables = new HashMap<>();

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
		this.prevSanity = sanity;
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
	public void addTemporaryTickable(ResourceLocation location, ITickable tickable) {
		this.temporaryTickables.put(location.toString(), tickable);
	}

	@Override
	public boolean removeTemporaryTickable(ResourceLocation location) {
		return this.temporaryTickables.remove(location.toString()) != null;
	}

	@Override
	public void setSanity(int sanity, boolean overrideChecks) {
		if(!overrideChecks) if(player == null || player.world.isRemote || !((ServerPlayerEntity) player).interactionManager.survivalOrAdventure()) return;
		this.prevSanity = this.sanity;
		this.sanity = MathHelper.clamp(sanity, this.minSanity, this.tempMaxSanity);
		this.updateSanityInformation(this.prevSanity, this.sanity);
	}

	@Override
	public void changeSanity(int amount, boolean overrideChecks) {
		this.setSanity(this.sanity + amount, overrideChecks);
	}

	@Override
	public void setMaxSanity(int maxSanity, boolean overrideChecks) {
		if(!overrideChecks) if(player == null || player.world.isRemote || !((ServerPlayerEntity) player).interactionManager.survivalOrAdventure()) return;
		this.tempMaxSanity = MathHelper.clamp(maxSanity, this.tempMinSanity, this.maxSanity);
		this.setSanity(this.sanity, overrideChecks);
	}

	@Override
	public void changeMaxSanity(int amount, boolean overrideChecks) {
		this.setMaxSanity(this.tempMaxSanity + amount, overrideChecks);
	}

	@Override
	public void setMinSanity(int minSanity, boolean overrideChecks) {
		if(!overrideChecks) if(player == null || player.world.isRemote || !((ServerPlayerEntity) player).interactionManager.survivalOrAdventure()) return;
		this.tempMinSanity = MathHelper.clamp(minSanity, this.minSanity, this.tempMaxSanity);
		this.setSanity(this.sanity, overrideChecks);
	}

	@Override
	public void changeMinSanity(int amount, boolean overrideChecks) {
		this.setMinSanity(this.minSanity + amount, overrideChecks);
	}

	@Override
	public void tick() {
		if(this.player.world.isRemote) return;
		ServerPlayerEntity player = (ServerPlayerEntity) this.player;
		double multiplier = SanityCallbacks.handleMultipliers(player, this).apply(false);
		int lightLevel = player.world.isThundering() ? player.world.getNeighborAwareLightSubtracted(player.getPosition(), 10) : player.world.getLight(player.getPosition());
		if(this.maxSanity != this.tempMaxSanity) {
			int recoveryThreshold = SanityCallbacks.getSanityManager().getMaxSanityRecoveryTime(lightLevel);
			this.recoveryThreshold = (int) (recoveryThreshold != -1 ? Math.max(this.recoveryThreshold, recoveryThreshold * multiplier) : -1);
		}
		int threshold = SanityCallbacks.getSanityManager().getSanityLevelTime(lightLevel, 20 - (int) MathHelper.clamp(player.getHealth(), 1, 20)); //TODO: Make more expansive later
		threshold *= threshold < 0 ? SanityCallbacks.handleMultipliers(player, this).apply(true) : multiplier;
		this.threshold = threshold > 0 ? Math.max(this.threshold, threshold) : -1 * Math.min(this.threshold == -1 ? Integer.MAX_VALUE : Math.abs(this.threshold), Math.abs(threshold));

		if(!this.temporaryTickables.isEmpty()) this.temporaryTickables.values().forEach(tickable -> tickable.tick(player, this));
		if(this.attackThreshold != -1) this.attackTime++;
		if(this.recoveryThreshold != -1 && this.maxSanity != this.tempMaxSanity) this.recoveryTime++;
		else this.recoveryTime = 0;
		this.time++;

		if(this.recoveryThreshold != -1 && this.recoveryTime >= this.recoveryThreshold) {
			this.changeMaxSanity(1);
			this.recoveryTime = 0;
			this.recoveryThreshold = -1;
		}
		if(this.time >= Math.abs(this.threshold)) {
			this.changeSanity(this.threshold > 0 ? 1 : -1);
			this.time = 0;
			this.threshold = -1;
		}
		if(this.attackThreshold != -1 && this.attackTime >= this.attackThreshold) {
			player.attackEntityFrom(DamageSources.PARANOIA, 1.0f);
			this.attackTime = 0;
			this.attackThreshold = - 1;
			setAttackThreshold();
		}
	}
	
	private void setAttackThreshold() {
		int attackThreshold = SanityCallbacks.getSanityManager().getAttackTime(this.sanity);
		this.attackThreshold = attackThreshold != -1 ? Math.min(this.attackThreshold == -1 ? Integer.MAX_VALUE : this.attackThreshold, SanityCallbacks.getSanityManager().getAttackTime(this.sanity)) : -1;
		if(this.attackThreshold == -1) this.attackTime = 0;
	}

	private void updateSanityInformation(int originalSanity, int newSanity) {
		if(!this.firstInteraction) this.setupInitialMaps();
		if(originalSanity == newSanity) return;
		if(originalSanity > newSanity) {
			this.loadedCallbacks.entrySet().stream().flatMap(entry -> entry.getValue().stream()).forEach(callback -> callback.getHandler().call((ServerPlayerEntity) this.player, this, newSanity, originalSanity, Phase.UPDATE));
			for(int i = originalSanity - 1; i >= newSanity; --i) {
				@Nullable Set<ResourceLocation> unloaded = this.unloadedCallbacks.get(i);
				if(unloaded != null) {
					unloaded.forEach(location -> {
						SanityCallback callback = SanityCallbacks.createCallback(location);
						callback.getHandler().call((ServerPlayerEntity) this.player, this, newSanity, originalSanity, Phase.START);
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
						callback.getHandler().call((ServerPlayerEntity) this.player, this, newSanity, originalSanity, Phase.STOP);
						this.unloadedCallbacks.computeIfAbsent(callback.getStartSanity(), a -> new HashSet<>()).add(callback.getId());
					});
					this.loadedCallbacks.remove(i);
				}
			}
			this.loadedCallbacks.entrySet().stream().flatMap(entry -> entry.getValue().stream()).forEach(callback -> callback.getHandler().call((ServerPlayerEntity) this.player, this, newSanity, originalSanity, Phase.UPDATE));
		}
		setAttackThreshold();
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
		this.deferredCallbacks.forEach(callback -> callback.run(player, this, this.sanity, this.prevSanity));
		this.deferredCallbacks.clear();
	}

	@Override
	public CompoundNBT serializeNBT() {
		CompoundNBT nbt = new CompoundNBT();
		nbt.putInt("minSanity", this.minSanity);
		nbt.putInt("maxSanity", this.maxSanity);
		nbt.putInt("sanity", this.sanity);
		nbt.putInt("prevSanity", this.prevSanity);
		nbt.putInt("tempMinSanity", this.tempMinSanity);
		nbt.putInt("tempMaxSanity", this.tempMaxSanity);
		nbt.putBoolean("firstInteraction", this.firstInteraction);
		nbt.putInt("time", this.time);
		nbt.putInt("recoveryTime", this.recoveryTime);
		nbt.putInt("attackTime", this.attackTime);
		nbt.putInt("threshold", this.threshold);
		nbt.putInt("recoveryThreshold", this.recoveryThreshold);
		nbt.putInt("attackThreshold", this.attackThreshold);
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
				CompoundNBT callbackData = new CompoundNBT();
				callbackData.putString("id", callback.getId().toString());
				if(callback.getHandler().hasData()) callbackData.put("data", callback.getHandler().serializeNBT());
				locations.add(callbackData);
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
		this.prevSanity = nbt.getInt("prevSanity");
		this.tempMinSanity = nbt.getInt("tempMinSanity");
		this.tempMaxSanity = nbt.getInt("tempMaxSanity");
		this.firstInteraction = nbt.getBoolean("firstInteraction");
		this.time = nbt.getInt("time");
		this.recoveryTime = nbt.getInt("recoveryTime");
		this.attackTime = nbt.getInt("attackTime");
		this.threshold = nbt.getInt("threshold");
		this.recoveryThreshold = nbt.getInt("recoveryThreshold");
		this.attackThreshold = nbt.getInt("attackThreshold");
		this.unloadedCallbacks.clear();
		this.loadedCallbacks.clear();
		Map<ResourceLocation, Function<ResourceLocation, SanityCallback>> registryMap = SanityCallbacks.getValidationMap();
		CompoundNBT unloadedCallbacks = nbt.getCompound("unloadedCallbacks");
		unloadedCallbacks.keySet().forEach(startSanity -> {
			Set<ResourceLocation> locations = new HashSet<>();
			ListNBT list = unloadedCallbacks.getList(startSanity, Constants.NBT.TAG_STRING);
			list.forEach(inbt -> {
				if (inbt instanceof StringNBT) {
					ResourceLocation location = new ResourceLocation(((StringNBT) inbt).getString());
					locations.add(location);
					registryMap.remove(location);
				} else {
					throw new RuntimeException("The specified INBT is not formatted as a StringNBT.");
				}
			});
			this.unloadedCallbacks.put(Integer.valueOf(startSanity), locations);
		});
		CompoundNBT loadedCallbacks = nbt.getCompound("loadedCallbacks");
		loadedCallbacks.keySet().forEach(stopSanity -> {
			Set<SanityCallback> callbacks = new HashSet<>();
			ListNBT list = loadedCallbacks.getList(stopSanity, Constants.NBT.TAG_COMPOUND);
			list.forEach(inbt -> {
				if (inbt instanceof CompoundNBT) {
					SanityCallback callback = SanityCallbacks.createCallback(new ResourceLocation(((CompoundNBT) inbt).getString("id")));
					if(((CompoundNBT) inbt).contains("data")) callback.getHandler().deserializeNBT(((CompoundNBT) inbt).getCompound("data"));
					if (callback.getHandler().restartOnReload()) deferredCallbacks.add((player, inst, sanity, prevSanity) -> callback.getHandler().call(player, inst, sanity, prevSanity, Phase.START));
					callbacks.add(callback);
					registryMap.remove(callback.getId());
				} else {
					throw new RuntimeException("The specified INBT is not formatted as a StringNBT or CompoundNBT.");
				}
			});
			this.loadedCallbacks.put(Integer.valueOf(stopSanity), callbacks);
		});
		registryMap.forEach((id, callbackSupplier) -> {
			SanityCallback callback = callbackSupplier.apply(id);
			if(this.sanity <= callback.getStartSanity()) {
				deferredCallbacks.add((player, inst, sanity, prevSanity) -> callback.getHandler().call(player, inst, sanity, prevSanity, Phase.START));
				this.loadedCallbacks.computeIfAbsent(callback.getStopSanity(), a -> new HashSet<>()).add(callback);
			} else {
				this.unloadedCallbacks.computeIfAbsent(callback.getStartSanity(), a -> new HashSet<>()).add(callback.getId());
			}
		});
	}
}
