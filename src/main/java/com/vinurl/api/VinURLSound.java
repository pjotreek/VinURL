package com.vinurl.api;

import com.vinurl.net.ClientEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.vinurl.VinURL.CUSTOM_RECORD;
import static com.vinurl.util.Constants.*;

@SuppressWarnings("unused")
public class VinURLSound {
	private static final double JUKEBOX_RANGE = 64;
	private static final double INFINITE_RANGE = Double.POSITIVE_INFINITY;

	private record ActiveJukebox(ServerLevel level, String url, boolean loop, Set<UUID> listeningPlayers) {}
	private static final Map<BlockPos, ActiveJukebox> activeJukeboxes = new HashMap<>();

	public static void playAt(ServerLevel level, ItemStack stack, BlockPos pos) {
		if (!stack.is(CUSTOM_RECORD)) {return;}

		CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
		String url = tag.get(URL_KEY);
		boolean loop = tag.get(LOOP_KEY);

		if (url.isEmpty()) {return;}

		List<ServerPlayer> players = playersInRange(level, pos, JUKEBOX_RANGE);
		Set<UUID> playerUuids = new HashSet<>();

		for (ServerPlayer player : players) {
			NETWORK_CHANNEL.serverHandle(player).send(new ClientEvent.PlaySoundRecord(pos, url, loop));
			playerUuids.add(player.getUUID());
		}

		activeJukeboxes.put(pos, new ActiveJukebox(level, url, loop, playerUuids));
	}

	public static void playFor(ServerLevel level, ItemStack stack, UUID uuid) {
		send(stack, () -> playerByUuid(level, uuid), (tag) ->
			new ClientEvent.PlaySoundRecord(null, tag.get(URL_KEY), tag.get(LOOP_KEY))
		);
	}

	public static void stopAt(ServerLevel level, ItemStack stack, BlockPos pos, boolean cancelable) {
		if (!stack.is(CUSTOM_RECORD)) {return;}

		CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
		String url = tag.get(URL_KEY);

		ActiveJukebox active = activeJukeboxes.remove(pos);
		if (active != null) {
			for (UUID uuid : active.listeningPlayers()) {
				ServerPlayer player = level.getServer().getPlayerList().getPlayer(uuid);
				if (player != null) {
					NETWORK_CHANNEL.serverHandle(player).send(new ClientEvent.StopSoundRecord(pos, url, cancelable));
				}
			}
		} else {
			send(stack, () -> playersInRange(level, pos, INFINITE_RANGE), (t) ->
				new ClientEvent.StopSoundRecord(pos, t.get(URL_KEY), cancelable)
			);
		}
	}

	public static void stopFor(ServerLevel level, ItemStack stack, UUID uuid, boolean cancelable) {
		send(stack, () -> playerByUuid(level, uuid), (tag) ->
			new ClientEvent.StopSoundRecord(null, tag.get(URL_KEY), cancelable)
		);
	}

	public static void tickJukebox(ServerLevel level, BlockPos pos) {
		ActiveJukebox active = activeJukeboxes.get(pos);
		if (active == null || active.level() != level) {return;}

		List<ServerPlayer> currentPlayers = playersInRange(level, pos, JUKEBOX_RANGE);
		Set<UUID> currentUuids = new HashSet<>();
		for (ServerPlayer player : currentPlayers) {
			currentUuids.add(player.getUUID());
		}

		// Send play to new players entering range
		for (ServerPlayer player : currentPlayers) {
			if (!active.listeningPlayers().contains(player.getUUID())) {
				NETWORK_CHANNEL.serverHandle(player).send(
					new ClientEvent.PlaySoundRecord(pos, active.url(), active.loop())
				);
				active.listeningPlayers().add(player.getUUID());
			}
		}

		// Send stop to players who left range
		Iterator<UUID> it = active.listeningPlayers().iterator();
		while (it.hasNext()) {
			UUID uuid = it.next();
			if (!currentUuids.contains(uuid)) {
				ServerPlayer player = level.getServer().getPlayerList().getPlayer(uuid);
				if (player != null) {
					NETWORK_CHANNEL.serverHandle(player).send(
						new ClientEvent.StopSoundRecord(pos, active.url(), false)
					);
				}
				it.remove();
			}
		}
	}

	public static void clearAll() {
		activeJukeboxes.clear();
	}

	private static void send(ItemStack stack, Supplier<List<ServerPlayer>> players, Function<CompoundTag, Record> factory) {
		if (!stack.is(CUSTOM_RECORD)) {return;}

		CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
		for (ServerPlayer player : players.get()) {
			NETWORK_CHANNEL.serverHandle(player).send(factory.apply(tag));
		}
	}

	private static List<ServerPlayer> playersInRange(ServerLevel level, BlockPos pos, double range) {
		return level.getPlayers((player) -> player.position().distanceTo(pos.getCenter()) <= range);
	}

	private static List<ServerPlayer> playerByUuid(ServerLevel level, UUID uuid) {
		return level.getPlayers((player) -> player.getUUID().equals(uuid));
	}
}
