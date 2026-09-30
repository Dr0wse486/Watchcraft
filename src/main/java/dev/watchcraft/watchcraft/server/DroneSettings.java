package dev.watchcraft.watchcraft.server;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * World scoped drone rules, kept in the overworld's data storage so they survive a restart.
 */
public class DroneSettings extends SavedData {

    private static final String FILE_ID = "watchcraft_settings";
    private static final String DOOR_INTERACTION = "DoorInteraction";

    private static final SavedData.Factory<DroneSettings> FACTORY =
            new SavedData.Factory<>(DroneSettings::new, DroneSettings::load);

    /** Whether a pilot may use the drone's own reach to work doors, trapdoors and fence gates. */
    private boolean doorInteraction = true;

    public static DroneSettings get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(FACTORY, FILE_ID);
    }

    private static DroneSettings load(CompoundTag tag, HolderLookup.Provider registries) {
        DroneSettings settings = new DroneSettings();
        // Defaults to on: worlds saved before the rule existed should keep the feature enabled.
        settings.doorInteraction = !tag.contains(DOOR_INTERACTION) || tag.getBoolean(DOOR_INTERACTION);
        return settings;
    }

    public boolean isDoorInteraction() {
        return this.doorInteraction;
    }

    public void setDoorInteraction(boolean value) {
        if (this.doorInteraction != value) {
            this.doorInteraction = value;
            this.setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putBoolean(DOOR_INTERACTION, this.doorInteraction);
        return tag;
    }
}
