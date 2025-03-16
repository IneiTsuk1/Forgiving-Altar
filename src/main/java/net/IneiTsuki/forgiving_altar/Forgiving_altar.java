package net.IneiTsuki.forgiving_altar;

import net.IneiTsuki.forgiving_altar.item.ForgivenessStoneItem;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.network.message.FilterMask;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.BannedPlayerList;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import com.mojang.authlib.GameProfile;
import java.util.*;

public class Forgiving_altar implements ModInitializer {

    private static final Map<UUID, Boolean> waitingForSelection = new HashMap<>();
    private static final Map<UUID, BlockPos> pendingTeleport = new HashMap<>();

    @Override
    @SuppressWarnings("unused")
    public void onInitialize() {
        // Register the Forgiveness Stone item
        Item FORGIVENESS_STONE = new ForgivenessStoneItem(new Item.Settings());
        Registry.register(Registries.ITEM, Identifier.tryParse("forgiving_altar:forgiveness_stone"), FORGIVENESS_STONE);
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries -> entries.add(FORGIVENESS_STONE));

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (player.getMainHandStack().getItem() instanceof ForgivenessStoneItem) {
                BlockPos pos = hitResult.getBlockPos();
                if (!waitingForSelection.containsKey(player.getGameProfile().getId())) {
                    player.sendMessage(Text.literal("§6[Altar] §fYou have begun the Forgiveness Ritual! Type the name of the player to unban."), false);
                    waitingForSelection.put(player.getGameProfile().getId(), true);
                }
                return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        });

        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            UUID playerId = sender.getGameProfile().getId();
            MinecraftServer server = sender.getServer();

            if (waitingForSelection.getOrDefault(playerId, false)) {
                waitingForSelection.remove(playerId);
                String playerName = message.getSignedContent();
                BlockPos altarPos = sender.getBlockPos();



                assert server != null;
                if (unbanPlayer(playerName, server, sender, altarPos)) {
                    sender.sendMessage(Text.literal("§a[Altar] " + playerName + " has been unbanned!"), false);

                    // Play the *ding* sound effect
                    Identifier dingSound = Identifier.tryParse("minecraft:block.note_block.pling");
                    SoundEvent soundEvent = Registries.SOUND_EVENT.get(dingSound);
                    if (soundEvent != null) {
                        sender.getWorld().playSound(null, sender.getX(), sender.getY(), sender.getZ(), SoundEvents.BLOCK_NOTE_BLOCK_PLING, SoundCategory.PLAYERS, 1.0F, 1.0F);

                    }

                    // Spawn lightning
                    ServerWorld world = sender.getServerWorld();
                    if (world != null) {
                        // Spawn lightning
                        net.minecraft.entity.LightningEntity lightning = new net.minecraft.entity.LightningEntity(
                                net.minecraft.entity.EntityType.LIGHTNING_BOLT, world
                        );
                        lightning.refreshPositionAfterTeleport(altarPos.getX() + 0.5, altarPos.getY(), altarPos.getZ() + 0.5);
                        world.spawnEntity(lightning);

                        // Apply screen shake effect (small knockback)
                        double shakeRadius = 5.0; // Players within 5 blocks will be affected
                        double shakePower = 0.3;  // Small knockback effect

                        for (ServerPlayerEntity player : world.getPlayers()) {
                            if (player.squaredDistanceTo(altarPos.getX(), altarPos.getY(), altarPos.getZ()) <= shakeRadius * shakeRadius) {
                                double dx = player.getX() - altarPos.getX();
                                double dz = player.getZ() - altarPos.getZ();
                                double distance = Math.sqrt(dx * dx + dz * dz);

                                if (distance > 0) {
                                    dx /= distance;
                                    dz /= distance;
                                }

                                player.addVelocity(dx * shakePower, 0.1, dz * shakePower); // Apply knockback
                                player.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket(player));
                            }
                        }
                    }

                    // Consume the forgiveness stone
                    if (sender.getMainHandStack().getItem() instanceof ForgivenessStoneItem) {
                        sender.getMainHandStack().decrement(1);
                    }
                } else {
                    sender.sendMessage(Text.literal("§c[Altar] No banned player found with the name '" + playerName + "'."), false);
                }
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            UUID playerUUID = player.getGameProfile().getId();
            if (pendingTeleport.containsKey(playerUUID)) {
                BlockPos teleportPos = pendingTeleport.remove(playerUUID);
                ServerWorld world = server.getWorld(World.OVERWORLD);
                if (world != null) {
                    player.teleport(world, teleportPos.getX() + 0.5, teleportPos.getY() + 1, teleportPos.getZ() + 0.5, 0, 0);
                    player.sendMessage(Text.literal("§6[Altar] §fYou have been forgiven and returned!"), false);
                }
            }
        });
    }

    private boolean unbanPlayer(String playerName, MinecraftServer server, ServerPlayerEntity sender, BlockPos altarPos) {
        BannedPlayerList banList = server.getPlayerManager().getUserBanList();
        for (String bannedName : banList.getNames()) {
            if (bannedName.equalsIgnoreCase(playerName)) {
                Optional<GameProfile> optionalProfile = Objects.requireNonNull(server.getUserCache()).findByName(playerName);
                if (optionalProfile.isPresent()) {
                    GameProfile profile = optionalProfile.get();
                    banList.remove(profile);
                    ServerPlayerEntity unbannedPlayer = server.getPlayerManager().getPlayer(profile.getId());

                    if (unbannedPlayer != null) {
                        ServerWorld world = server.getWorld(World.OVERWORLD);
                        if (world != null) {
                            unbannedPlayer.teleport(world, altarPos.getX() + 0.5, altarPos.getY() + 1, altarPos.getZ() + 0.5, 0, 0);
                            unbannedPlayer.sendMessage(Text.literal("§6[Altar] §fYou have been forgiven and returned!"), false);
                        }
                    } else {
                        pendingTeleport.put(profile.getId(), altarPos);
                    }

                    // Add Ritual Effects
                    if (sender.getWorld() instanceof ServerWorld serverWorld) {
                        serverWorld.spawnParticles(ParticleTypes.ENCHANT, altarPos.getX() + 0.5, altarPos.getY() + 1, altarPos.getZ() + 0.5, 50, 0.5, 1, 0.5, 0.1);
                        serverWorld.spawnParticles(ParticleTypes.SMOKE, altarPos.getX() + 0.5, altarPos.getY() + 1, altarPos.getZ() + 0.5, 20, 0.5, 0.5, 0.5, 0.02);
                    }
                    sender.getWorld().playSound(null, sender.getX(), sender.getY(), sender.getZ(), SoundEvents.ENTITY_WITHER_AMBIENT, SoundCategory.AMBIENT, 1F, 1F);

                    // Random Lore Messages
                    String[] loreMessages = {
                            "§5[Altar] Ancient spirits whisper through the void...",
                            "§5[Altar] A long-forgotten soul stirs from the abyss...",
                            "§5[Altar] The altar glows as judgment is passed..."
                    };
                    sender.sendMessage(Text.literal(loreMessages[new Random().nextInt(loreMessages.length)]), false);
                    return true;
                }
            }
        }
        return false;
    }
}