package com.tendrun.WolfSleepRespawn;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.DyeColor;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerWakeUpEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.*;

/**
 * Handler eventów dla moda WolfSleepRespawn.
 * Zajmuje się:
 * - Śledzeniem śmierci oswojonych wilków
 * - Odradzaniem ich po spaniu właściciela
 */
@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class WolfSleepRespawnEvents
{
    private static final Logger LOGGER = LogUtils.getLogger();

    // NBT key for per-player persistent storage
    private static final String NBT_KEY = "WolfSleepRespawn:lost_wolves";

    // Helper: read lost wolves from player's persistent NBT
    private static List<LostWolfEntry> readLostWolvesFromPlayer(ServerPlayer player) {
        CompoundTag root = player.getPersistentData();
        if (!root.contains(NBT_KEY)) return Collections.emptyList();
        ListTag list = root.getList(NBT_KEY, 10); // 10 == CompoundTag
        List<LostWolfEntry> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            float health = t.getFloat("health");
            boolean sitting = t.getBoolean("sitting");
            int collar = t.getInt("collar");
            String name = t.contains("name") ? t.getString("name") : null;
            out.add(new LostWolfEntry(health, sitting, collar, name));
        }
        return out;
    }

    // Helper: append a lost wolf entry to player's persistent NBT
    private static void addLostWolfToPlayer(ServerPlayer player, LostWolfEntry entry) {
        CompoundTag root = player.getPersistentData();
        ListTag list = root.contains(NBT_KEY) ? root.getList(NBT_KEY, 10) : new ListTag();
        CompoundTag t = new CompoundTag();
        t.putFloat("health", entry.health());
        t.putBoolean("sitting", entry.sitting());
        t.putInt("collar", entry.collarColor());
        if (entry.customName() != null) t.putString("name", entry.customName());
        list.add(t);
        root.put(NBT_KEY, list);
    }

    // Helper: remove lost wolves data from player NBT
    private static void removeLostWolvesFromPlayer(ServerPlayer player) {
        CompoundTag root = player.getPersistentData();
        if (root.contains(NBT_KEY)) {
            root.remove(NBT_KEY);
        }
    }

    /**
     * Event: Gracz się budzi po spaniu
     * Tu będziemy odradzać wilki właściciela.
     */
    @SubscribeEvent
    public static void onPlayerWakeUp(PlayerWakeUpEvent event)
    {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        // Upewnij się, że działamy tylko po stronie serwera
        Level level = player.level(); // use accessor method
        if (level.isClientSide()) return;

        UUID playerUUID = player.getUUID();

        // Single source of truth: persistent NBT (prevents duplicates)
        List<LostWolfEntry> merged = readLostWolvesFromPlayer(player);

        // Debug: wyślij do gracza stan jego listy
        if (merged.isEmpty()) {
            LOGGER.info("Player {} woke up. No lost wolves to respawn.", player.getName().getString());
        } else {
            for (int i = 0; i < merged.size(); i++) {
                LostWolfEntry entry = merged.get(i);
                String name = entry.customName() == null ? "<no name>" : entry.customName();
                String line = String.format("[%d] health=%.1f sitting=%s collarColor=%d name=%s",
                        i + 1,
                        entry.health(),
                        entry.sitting(),
                        entry.collarColor(),
                        name);
            }

            LOGGER.info("Player {} woke up. Respawning {} wolves.", player.getName().getString(), merged.size());

            // Logic: respawn each stored wolf near the player
            for (int i = 0; i < merged.size(); i++)
            {
                LostWolfEntry entry = merged.get(i);

                // Create wolf entity
                Wolf wolf = (Wolf) EntityType.WOLF.create(level);
                if (wolf == null) {
                    LOGGER.warn("Failed to create Wolf entity for respawn.");
                    continue;
                }

                // Set basic properties
                wolf.setOwnerUUID(playerUUID);
                wolf.setTame(true);

                // Position it slightly offset so they don't stack exactly on player
                double x = player.getX() + (i % 2 == 0 ? 0.5 : -0.5);
                double y = player.getY();
                double z = player.getZ() + ((i / 2.0) * 0.5); // use floating division
                wolf.setPos(x, y, z);

                // Health
                try {
                    wolf.setHealth(entry.health());
                } catch (Exception ex) {
                    // setHealth may throw or clamp; log and continue
                    LOGGER.warn("Unable to set wolf health to {}: {}", entry.health(), ex.getMessage());
                }

                // Sitting
                wolf.setOrderedToSit(entry.sitting());

                // Collar color
                try {
                    DyeColor color = DyeColor.byId(entry.collarColor());
                    wolf.setCollarColor(color);
                } catch (Exception ex) {
                    LOGGER.warn("Invalid collar color id {}: {}", entry.collarColor(), ex.getMessage());
                }

                // Custom name
                if (entry.customName() != null) {
                    wolf.setCustomName(Component.literal(entry.customName()));
                }

                // Add to world
                boolean added = level.addFreshEntity(wolf);
                if (added) {
                    LOGGER.info("Respawned wolf for player {} with health {}", player.getName().getString(), entry.health());
                } else {
                    LOGGER.warn("Failed to add respawned wolf entity to world for player {}", player.getName().getString());
                }
            }

            // Po wypisaniu i respawnie usuwamy wpisy
                removeLostWolvesFromPlayer(player);
         }

        // Debug: globalnie używamy teraz tylko NBT per-gracz (bez RAM cache)
    }

    /**
     * Event: Oswojony wilk umiera
     * Zapisujemy informacje o utracie, aby go później odrodzić.
     */
    @SubscribeEvent
    public static void onWolfDeath(LivingDeathEvent event)
    {
        if (!(event.getEntity() instanceof Wolf wolf)) return;
        if (!wolf.isTame()) return;

        var owner = wolf.getOwner();
        if (!(owner instanceof ServerPlayer player)) return;

        LostWolfEntry entry = LostWolfEntry.fromWolf(wolf);

         // Persist entry to player's NBT so it survives restarts
         addLostWolfToPlayer(player, entry);

         LOGGER.info("Wolf died! Owner: {}, Health was: {}", player.getName().getString(), entry.health());
    }

    /**
     * Struktura danych: informacja o utranym wilku.
     * Na razie przechowujemy w RAM; później przenosimy na capability gracza.
     */
    public record LostWolfEntry(
            float health,
            boolean sitting,
            int collarColor,
            String customName
    )
    {
        /**
         * Tworzy wpis na podstawie stanu żywego wilka.
         */
        static LostWolfEntry fromWolf(Wolf wolf)
        {
            return new LostWolfEntry(
                    wolf.getMaxHealth(),
                    wolf.isOrderedToSit(),
                    wolf.getCollarColor().getId(),
                    wolf.getCustomName() != null ? wolf.getCustomName().getString() : null
            );
        }
    }
}
