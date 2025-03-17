package net.IneiTsuki.forgiving_altar.item;

import net.IneiTsuki.forgiving_altar.Forgiving_altar;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public class ModItems {
    public static final Item FORGIVENESS_STONE = Registry.register(
            Registries.ITEM,
            Identifier.of(Forgiving_altar.MOD_ID, "forgiveness_stone"),
            new Item(new Item.Settings())
    );

    public static final Item SOUL_SHARD = Registry.register(
            Registries.ITEM,
            Identifier.of(Forgiving_altar.MOD_ID, "soul_shard"),
            new Item(new Item.Settings())
    );

    public static void registerModItems() {
        Forgiving_altar.LOGGER.info("Registering Mod Items for " + Forgiving_altar.MOD_ID);

        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL)
                .register(entries -> entries.add(FORGIVENESS_STONE));
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL)
                .register(entries -> entries.add(SOUL_SHARD));
    }
}
