package net.IneiTsuki.forgiving_altar;

import net.IneiTsuki.forgiving_altar.item.ForgivenessStoneItem;
import net.IneiTsuki.forgiving_altar.item.ModItems;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.BannedPlayerList;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import com.mojang.authlib.GameProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class Forgiving_altar implements ModInitializer {

    public static final String MOD_ID = "forgiving_altar";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final Map<UUID, Boolean> waitingForSelection = new HashMap<>();
    private static final Map<UUID, BlockPos> pendingTeleport = new HashMap<>();

    @Override
    @SuppressWarnings("unused")public void onInitialize() {
        ModItems.registerModItems();

        // Block interaction callback
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            ItemStack heldItem = player.getMainHandStack();
            if (heldItem.getItem() == ModItems.FORGIVENESS_STONE) {
                BlockPos pos = hitResult.getBlockPos();

                // Check if the block is part of a valid altar
                if (isValidAltar(world, pos)) {
                    if (!waitingForSelection.containsKey(player.getGameProfile().getId())) {
                        player.sendMessage(Text.literal("§6[Altar] §fYou have begun the Forgiveness Ritual! Type the name of the player to unban."), false);
                        waitingForSelection.put(player.getGameProfile().getId(), true);

                        if (world instanceof ServerWorld serverWorld) {
                            spawnGlowingAura(serverWorld, pos);
                        } else {
                            // Handle client-side or unexpected cases
                            System.out.println("The world is not a ServerWorld.");
                        }
                    }
                    return ActionResult.SUCCESS;
                } else {
                    player.sendMessage(Text.literal("§c[Altar] §fThis is not a valid altar."), false);
                    return ActionResult.FAIL;
                }
            }
            return ActionResult.PASS;
        });

        // Modify the chat message to prevent it from being sent to other players
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, parameters) -> {
            UUID playerId = sender.getGameProfile().getId();
            MinecraftServer server = sender.getServer();

            if (waitingForSelection.getOrDefault(playerId, false)) {
                waitingForSelection.remove(playerId); // Exit selection mode
                String playerName = message.getSignedContent().trim();
                BlockPos altarPos = sender.getBlockPos();

                assert server != null;
                if (unbanPlayer(playerName, server, sender, altarPos)) {
                    sender.sendMessage(Text.literal("§a[Altar] " + playerName + " has been unbanned!"), false);

                    // Play the *ding* sound effect
                    sender.getWorld().playSound(null, sender.getX(), sender.getY(), sender.getZ(),
                            SoundEvents.BLOCK_NOTE_BLOCK_PLING, SoundCategory.PLAYERS, 1.0F, 1.0F);

                    // Spawn lightning at the altar
                    ServerWorld world = sender.getServerWorld();
                    if (world != null) {
                        // Enhanced particle effect with bigger range and longer duration
                        spawnEnhancedParticles(world, altarPos);
                    }

                    // Consume the forgiveness stone
                    if (sender.getMainHandStack().getItem() instanceof ForgivenessStoneItem) {
                        sender.getMainHandStack().decrement(1);
                    }
                } else {
                    sender.sendMessage(Text.literal("§c[Altar] No banned player found with the name '" + playerName + "'."), false);
                }
                return false; // Prevent the message from being sent to the server chat
            }
            return true; // Allow the message to be sent to other players if not in selection mode
        });

        // Handle player teleport after they join the server (if they were pending teleport)
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

    // Checks if the altar is correctly built with gold blocks
    private boolean isValidAltar(World world, BlockPos pos) {
        BlockPos baseStart = pos.down();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (world.getBlockState(baseStart.add(x, 0, z)).getBlock() != Blocks.GOLD_BLOCK) {
                    return false;
                }
            }
        }
        return true;
    }

    // Unbans a player by their name
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
                        pendingTeleport.put(profile.getId(), altarPos); // Queue teleport if player isn't online
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

    // Spawn a glowing aura around the altar
    private void spawnGlowingAura(ServerWorld world, BlockPos pos) {
        world.spawnParticles(ParticleTypes.GLOW, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5, 100, 1, 1, 1, 0.05);
    }

    // Spawn enhanced particle effects for the unban process
    private void spawnEnhancedParticles(ServerWorld world, BlockPos altarPos) {
        world.spawnParticles(ParticleTypes.ENCHANT, altarPos.getX() + 0.5, altarPos.getY() + 1, altarPos.getZ() + 0.5, 200, 1, 1, 1, 0.15);
        world.spawnParticles(ParticleTypes.SMOKE, altarPos.getX() + 0.5, altarPos.getY() + 1, altarPos.getZ() + 0.5, 100, 1, 1, 1, 0.05);
    }
}
