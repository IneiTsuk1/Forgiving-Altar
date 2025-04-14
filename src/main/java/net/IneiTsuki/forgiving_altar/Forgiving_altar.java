package net.IneiTsuki.forgiving_altar;

import net.IneiTsuki.forgiving_altar.block.ModBlocks;
import net.IneiTsuki.forgiving_altar.item.ForgivenessStoneItem;
import net.IneiTsuki.forgiving_altar.item.ModItems;
import net.IneiTsuki.forgiving_altar.util.ModLootTableModifiers;
import net.IneiTsuki.forgivingmod.command.RegisterCommands;
import net.IneiTsuki.forgivingmod.config.DeathTracker;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.BannedPlayerList;
import net.minecraft.server.command.ServerCommandSource;
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
    public void onInitialize() {
        registerItems();
        registerEvents();
    }

    //registers custom items/blocks and loot tables
    private void registerItems() {
        ModItems.registerModItems();
        ModBlocks.registerModBlocks();
        ModLootTableModifiers.modifyLootTables();
    }

    //registers the hit/message/ and teleport events
    private void registerEvents() {
        /// Block interaction callback
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
                        return ActionResult.FAIL;
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
            assert server != null;
            ServerCommandSource source = server.getCommandSource();

            if (waitingForSelection.getOrDefault(playerId, false)) {
                waitingForSelection.remove(playerId); // Exit selection mode
                String playerName = message.getSignedContent().trim();

                // Check if the player name is empty
                if (playerName.isEmpty()) {
                    sender.sendMessage(Text.literal("§c[Altar] Player name cannot be empty!"), false);
                    return false; // Prevent the message from being sent to the chat
                }

                BlockPos altarPos = sender.getBlockPos();

                // Attempt to unban with ForgivingMod first, otherwise fallback to normally unban players
                if (unbanForgivingMod(playerName, source, server, sender, altarPos)) {
                    System.out.println("Unbanning with ForgivingMod installed!!");
                    sender.sendMessage(Text.literal("§a[Altar] " + playerName + " has been unbanned!"), false);

                    // Play a sound effect
                    sender.getWorld().playSound(null, sender.getX(), sender.getY(), sender.getZ(),
                            SoundEvents.BLOCK_NOTE_BLOCK_PLING, SoundCategory.PLAYERS, 1.0F, 1.0F);

                    // Spawn visual effects at the altar
                    ServerWorld world = sender.getServerWorld();
                    if (world != null) {
                        spawnEnhancedParticles(world, altarPos);
                    }

                    // Consume the forgiveness stone
                    if (sender.getMainHandStack().getItem() instanceof ForgivenessStoneItem) {
                        sender.getMainHandStack().decrement(1);
                    }
                } else {
                    sender.sendMessage(Text.literal("§c[Altar] No banned player found with the name '" + playerName + "'."), false);
                }

                return false; // Prevent the message from being sent to the chat
            }
            return true; // Allow the message to be sent if not in selection mode
        });

        // Handle player teleport after they join the server (if they were pending teleport)
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            UUID playerUUID = player.getGameProfile().getId();
            if (pendingTeleport.containsKey(playerUUID)) {
                BlockPos teleportPos = pendingTeleport.remove(playerUUID);

                if (teleportPos != null) {
                    ServerWorld world = server.getWorld(World.OVERWORLD);
                    if (world != null) {
                        player.teleport(world, teleportPos.getX() + 0.5, teleportPos.getY() + 1, teleportPos.getZ() + 0.5, 0, 0);
                        player.sendMessage(Text.literal("§6[Altar] §fYou have been forgiven and returned!"), false);
                    }
                }
            }
        });
    }

    // Checks if the altar is correctly built
    private boolean isValidAltar(World world, BlockPos pos) {
        BlockPos baseStart = pos.down(); // Base is directly below the given position

        // Define altar pattern positions
        Set<BlockPos> seaLanternPositions = Set.of(
                baseStart.add(0, 0, -1), baseStart.add(1, 0, 0),
                baseStart.add(0, 0, 1), baseStart.add(-1, 0, 0)
        );

        Set<BlockPos> stairPositions = Set.of(
                baseStart.add(0, 0, -3), baseStart.add(-1, 0, -3), baseStart.add(1, 0, -3),  // West
                baseStart.add(3, 0, 0), baseStart.add(3, 0, 1), baseStart.add(3, 0, -1),  // East
                baseStart.add(0, 0, 3), baseStart.add(-1, 0, 3), baseStart.add(1, 0, 3),  // South
                baseStart.add(-3, 0, 0), baseStart.add(-3, 0, -1), baseStart.add(-3, 0, 1) // North
        );

        Set<BlockPos> airPositions = Set.of(
                baseStart.add(-3, 0, 2), baseStart.add(-3, 0, -2), // West
                baseStart.add(3, 0, 2), baseStart.add(3, 0, -2),  // East
                baseStart.add(2, 0, -3), baseStart.add(-2, 0, -3), // South
                baseStart.add(2, 0, 3), baseStart.add(-2, 0, 3)  // North
        );

        // Define pillar positions (3 blocks tall, 1 block at height 0, 1 block at height 1, and 1 block at height 2)
        Set<BlockPos> pillarPositions = new HashSet<>();
        // Add 3 blocks tall pillars in the 4 directions (North, South, East, West)
        for (int i = -3; i <= 3; i += 6) {
            pillarPositions.add(baseStart.add(i, 0, 3)); // Base (North & South)
            pillarPositions.add(baseStart.add(i, 1, 3)); // 1 Block Above (North & South)
            pillarPositions.add(baseStart.add(i, 2, 3)); // 2 Blocks Above (North & South)

            pillarPositions.add(baseStart.add(i, 0, -3)); // Base (North & South)
            pillarPositions.add(baseStart.add(i, 1, -3)); // 1 Block Above (North & South)
            pillarPositions.add(baseStart.add(i, 2, -3)); // 2 Blocks Above (North & South)
        }

        for (int i = -3; i <= 3; i += 6) {
            pillarPositions.add(baseStart.add(3, 0, i)); // Base (East & West)
            pillarPositions.add(baseStart.add(3, 1, i)); // 1 Block Above (East & West)
            pillarPositions.add(baseStart.add(3, 2, i)); // 2 Blocks Above (East & West)

            pillarPositions.add(baseStart.add(-3, 0, i)); // Base (East & West)
            pillarPositions.add(baseStart.add(-3, 1, i)); // 1 Block Above (East & West)
            pillarPositions.add(baseStart.add(-3, 2, i)); // 2 Blocks Above (East & West)
        }

        // Check each position in the 7x7x3 altar area
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                for (int y = 0; y <= 2; y++) {  // Check height 0, 1, 2
                    BlockPos currentPos = baseStart.add(x, y, z);
                    Block currentBlock = world.getBlockState(currentPos).getBlock();

                    if (seaLanternPositions.contains(currentPos)) {
                        if (currentBlock != Blocks.SEA_LANTERN) return false;
                    } else if (stairPositions.contains(currentPos)) {
                        if (currentBlock != Blocks.BLACKSTONE_STAIRS) return false;
                    } else if (airPositions.contains(currentPos)) {
                        if (!world.isAir(currentPos)) return false;
                    } else if (pillarPositions.contains(currentPos)) {
                        // Ensure the pillar is made of Polished Blackstone at height 0, 1, and 2
                        if (currentBlock != Blocks.POLISHED_BLACKSTONE) return false;
                    } else {
                        // Only check for BLACKSTONE at y = 0 (base level)
                        if (y == 0 && currentBlock != Blocks.BLACKSTONE) {
                            return false;
                        }
                    }
                }
            }
        }

        // Debugging: Print the center block to make sure it’s correct
        BlockPos centerPos = pos.up();  // The center block is 1 block above the base
        Block centerBlock = world.getBlockState(centerPos).getBlock();
        System.out.println("Center Block: " + centerBlock); // Print the block at the center position
        if (centerBlock != ModBlocks.ALTAR_BLOCK) {
            System.out.println("Invalid altar: Center block is not correct.");
            return false; // The center block should be the correct ALTAR_BLOCK
        }

        return true; // The altar structure is correct
    }

    // Unbans a player by their name
    @SuppressWarnings("unused") boolean unbanPlayer(String playerName, MinecraftServer server, ServerPlayerEntity sender, BlockPos altarPos) {
        return handleUnbanAndTeleport(playerName, server, sender, altarPos);
    }

    @SuppressWarnings("unused") boolean unbanForgivingMod(String playerName, ServerCommandSource source, MinecraftServer server, ServerPlayerEntity sender, BlockPos altarPos) {
        if (FabricLoader.getInstance().isModLoaded("forgivingmod")) {
            System.out.println("ForgivingMod is loaded!");

            // First, check if the player is on the ban list
            BannedPlayerList banList = server.getPlayerManager().getUserBanList();

            System.out.println("Looking up player profile for: " + playerName);
            Optional<GameProfile> optionalProfile = Objects.requireNonNull(server.getUserCache()).findByName(playerName);

            if (optionalProfile.isEmpty()) {
                System.out.println("User cache is empty or player not found in cache: " + playerName);
                sender.sendMessage(Text.literal("§c[Altar] No player found with that name in the server."), false);
                return false;
            }

            GameProfile profile = optionalProfile.get();
            //check if the profile is in the banned list
            if (!banList.contains(profile)) {
                sender.sendMessage(Text.literal("§c[Altar] Player '" + playerName + "' is not banned."), false);
                return false;  // Player is not on the banned list
            }

            // Now that we know the player is banned, proceed with the unban logic
            ServerPlayerEntity unbannedPlayer = server.getPlayerManager().getPlayer(profile.getId());

            // If the player is online, unban and teleport them
            if (unbannedPlayer != null) {

                //Handles the unbanning of the player
                handleUnbanAndTeleport(playerName, server, sender, altarPos);

                //Handles the resetting of the health and death count for the player is the ForgivingMod is installed
                System.out.println("Resetting Health for " + playerName + ".");
                EntityAttributeInstance healthAttribute = unbannedPlayer.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
                if (healthAttribute != null) {
                    healthAttribute.setBaseValue(20.0);
                    unbannedPlayer.setHealth(unbannedPlayer.getMaxHealth());
                }

                System.out.println("Resetting Deaths for " + playerName + ".");
                DeathTracker.loadDeathData();
            } else {
                // If the player is offline, queue teleportation
                pendingTeleport.put(profile.getId(), altarPos);
            }
        } else {
            System.out.println("Using built in unbanning system!");
            return unbanPlayer(playerName, server, sender, altarPos);
        }
        return false;  // If the "forgiving" mod isn't loaded, return false
    }

    private boolean handleUnbanAndTeleport(String playerName, MinecraftServer server, ServerPlayerEntity sender, BlockPos altarPos) {
        BannedPlayerList banList = server.getPlayerManager().getUserBanList();
        Optional<GameProfile> optionalProfile = Objects.requireNonNull(server.getUserCache()).findByName(playerName);

        if (optionalProfile.isPresent()) {
            GameProfile profile = optionalProfile.get();
            banList.remove(profile);  // Unban the player
            ServerPlayerEntity unbannedPlayer = server.getPlayerManager().getPlayer(profile.getId());

            // If the player is online, teleport them
            if (unbannedPlayer != null) {
                ServerWorld world = server.getWorld(World.OVERWORLD);
                if (world != null) {
                    unbannedPlayer.teleport(world, altarPos.getX() + 0.5, altarPos.getY() + 1, altarPos.getZ() + 0.5, 0, 0);
                    unbannedPlayer.sendMessage(Text.literal("§6[Altar] §fYou have been forgiven and returned!"), false);
                } else {
                    LOGGER.warn("[Forgiving Altar] Attempted to unban '{}', but no matching player found.", playerName);
                }
            } else {
                // Queue the teleport if the player isn't online
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
        return false;  // Player not found
    }

    @SuppressWarnings("unused") void handleResettingData(String playerName, MinecraftServer server, ServerPlayerEntity sender, BlockPos altarPos) {

        // First, check if the player is on the ban list
        BannedPlayerList banList = server.getPlayerManager().getUserBanList();

        System.out.println("Looking up player profile for: " + playerName);
        Optional<GameProfile> optionalProfile = Objects.requireNonNull(server.getUserCache()).findByName(playerName);

        if (optionalProfile.isEmpty()) {
            sender.sendMessage(Text.literal("§c[Altar] No player found with that name in the server."), false);
            return;
        }

        GameProfile profile = optionalProfile.get();
        //check if thr profile is in the banned list
        if (!banList.contains(profile)) {
            sender.sendMessage(Text.literal("§c[Altar] Player '" + playerName + "' is not banned."), false);
            return;  // Player is not on the banned list
        }

        // Now that we know the player is banned, proceed with the unban logic
        ServerPlayerEntity unbannedPlayer = server.getPlayerManager().getPlayer(profile.getId());

        if (unbannedPlayer != null) {
            System.out.println("Resetting Health for " + playerName + ".");
            EntityAttributeInstance healthAttribute = unbannedPlayer.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
            if (healthAttribute != null) {
                healthAttribute.setBaseValue(20.0);
                unbannedPlayer.setHealth(unbannedPlayer.getMaxHealth());
            }

            System.out.println("Resetting Deaths for " + playerName + ".");
            DeathTracker.loadDeathData();
        }
    }

    // Spawn a glowing aura around the altar
    private void spawnGlowingAura(ServerWorld world, BlockPos pos) {
        // Use soul fire or other magical particles for a more mystical effect
        world.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5, 100, 1, 1, 1, 0.05);
    }

    // Spawn enhanced particle effects for the unban process
    private void spawnEnhancedParticles(ServerWorld world, BlockPos altarPos) {
        // A combination of particles to represent the powerful energy being released
        world.spawnParticles(ParticleTypes.END_ROD, altarPos.getX() + 0.5, altarPos.getY() + 1, altarPos.getZ() + 0.5, 200, 1, 1, 1, 0.15);
        world.spawnParticles(ParticleTypes.EXPLOSION, altarPos.getX() + 0.5, altarPos.getY() + 1, altarPos.getZ() + 0.5, 50, 1, 1, 1, 0.2);
        world.spawnParticles(ParticleTypes.ENCHANT, altarPos.getX() + 0.5, altarPos.getY() + 1, altarPos.getZ() + 0.5, 100, 0.5, 1, 0.5, 0.1);
    }
}
