package dev.watchcraft.watchcraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

public final class KeyMappings {

    public static final String CATEGORY = "key.categories.watchcraft";

    public static final KeyMapping LINK = new KeyMapping(
            "key.watchcraft.link",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            CATEGORY);

    public static final KeyMapping THROW = new KeyMapping(
            "key.watchcraft.throw",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            CATEGORY);

    public static final KeyMapping RECALL = new KeyMapping(
            "key.watchcraft.recall",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_R,
            CATEGORY);

    /** One press = one barrell roll. Only meaningful while piloting. */
    public static final KeyMapping ROLL = new KeyMapping(
            "key.watchcraft.roll",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_C,
            CATEGORY);

    /** Blows the warhead where the drone is standing. Only meaningful while piloting. */
    public static final KeyMapping DETONATE = new KeyMapping(
            "key.watchcraft.detonate",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_X,
            CATEGORY);

    private KeyMappings() {
    }
}
