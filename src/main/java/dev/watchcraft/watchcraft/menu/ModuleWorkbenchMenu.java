package dev.watchcraft.watchcraft.menu;

import dev.watchcraft.watchcraft.item.DroneModuleItem;
import dev.watchcraft.watchcraft.item.DroneModules;
import dev.watchcraft.watchcraft.registry.ModItems;
import dev.watchcraft.watchcraft.registry.ModMenus;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerListener;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Three slots: the drone, the module going into it, and the finished drone to take out.
 *
 * <p>The two inputs and the output are plain containers owned by this menu rather than a block
 * entity's inventory, which is exactly how the vanilla crafting table works. The bench is a jig,
 * not a chest: you clamp a drone in, seat one board, and take the drone back out. Nothing is meant
 * to be left in it, so nothing needs to persist - and anything that <em>is</em> left in it drops
 * when the screen closes, the way a crafting grid does.
 *
 * <p>The result slot is derived, never placed into. {@link #refreshResult()} rebuilds it from the
 * two inputs on every change, so the output cannot drift out of step with what is in the jig, and
 * a module the drone cannot accept simply produces no output instead of a wrong one.
 */
public class ModuleWorkbenchMenu extends AbstractContainerMenu {

    public static final int SLOT_DRONE = 0;
    public static final int SLOT_MODULE = 1;
    public static final int SLOT_RESULT = 2;
    /** Number of slots the bench itself owns; the player inventory starts right after. */
    public static final int BENCH_SLOTS = 3;

    /** Indices inside the two slot jig. Named separately from the slot ids so the two cannot drift. */
    private static final int JIG_DRONE = 0;
    private static final int JIG_MODULE = 1;

    private static final int PLAYER_ROWS_Y = 84;
    private static final int HOTBAR_Y = 142;
    private static final int BENCH_Y = 36;

    // Typed as the concrete class, not the Container interface: addListener lives on SimpleContainer,
    // and the listener is the whole reason this menu works.
    private final SimpleContainer input = new SimpleContainer(2);
    private final ResultContainer result = new ResultContainer();

    /**
     * Bridges the jig's change notices into {@link #slotsChanged}.
     *
     * <p>This is not optional bookkeeping. {@code AbstractContainerMenu#addSlot} only files the slot
     * away - it never subscribes to the container behind it - so a bare {@link SimpleContainer}
     * changes with nobody listening and {@code slotsChanged} is never called. Vanilla gets this for
     * free because {@code TransientCraftingContainer} takes the menu as a listener in its own
     * constructor; a plain {@code SimpleContainer} has no such constructor, so the wiring has to be
     * done by hand. Without it the result slot stays empty forever.
     */
    private final ContainerListener jigListener = this::slotsChanged;

    private final Player player;

    public ModuleWorkbenchMenu(int containerId, Inventory playerInventory) {
        super(ModMenus.MODULE_WORKBENCH.get(), containerId);

        this.player = playerInventory.player;
        this.input.addListener(this.jigListener);

        this.addSlot(new Slot(this.input, JIG_DRONE, 44, BENCH_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.is(ModItems.RECON_DRONE.get());
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }
        });
        this.addSlot(new Slot(this.input, JIG_MODULE, 80, BENCH_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.getItem() instanceof DroneModuleItem;
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }
        });
        this.addSlot(new Slot(this.result, 0, 116, BENCH_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }

            @Override
            public boolean mayPickup(Player player) {
                return !this.getItem().isEmpty();
            }

            @Override
            public void onTake(Player player, ItemStack stack) {
                // One drone and one board per finished drone. This runs after the result has
                // already left the slot, so removing the inputs here cannot clobber it; each
                // removal fires slotsChanged, which rebuilds the output from what is left.
                ModuleWorkbenchMenu.this.input.removeItem(JIG_DRONE, 1);
                ModuleWorkbenchMenu.this.input.removeItem(JIG_MODULE, 1);
                super.onTake(player, stack);
            }
        });

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9,
                        8 + col * 18, PLAYER_ROWS_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, HOTBAR_Y));
        }
    }

    @Override
    public void slotsChanged(Container container) {
        // Derive first, then broadcast. super.slotsChanged is what ships the slot diffs to the
        // client, so the result has to be sitting in its slot before it runs - otherwise the client
        // learns about it a tick later, from the menu's own per-tick broadcastChanges.
        //
        // Server side only, the way the crafting table does it: the client's copy of the result
        // arrives by packet, and letting both sides derive it invites the local guess to fight the
        // authoritative value during the round trip.
        if (container == this.input && this.player != null && !this.player.level().isClientSide) {
            this.refreshResult();
        }
        super.slotsChanged(container);
    }

    /** Rebuilds the output from the jig. Never called with the result container as the source. */
    private void refreshResult() {
        ItemStack drone = this.input.getItem(JIG_DRONE);
        ItemStack module = this.input.getItem(JIG_MODULE);

        if (!drone.is(ModItems.RECON_DRONE.get())
                || !(module.getItem() instanceof DroneModuleItem board)
                || !board.canInstallOn(DroneModules.of(drone))) {
            if (!this.result.getItem(0).isEmpty()) {
                this.result.setItem(0, ItemStack.EMPTY);
            }
            return;
        }

        // copyWithCount(1) rather than copy(): the stack in the slot is a single drone, but going
        // through the explicit count keeps the output at one no matter what the input claims.
        ItemStack fitted = drone.copyWithCount(1);
        DroneModules.install(fitted, board.flag());
        if (!ItemStack.matches(fitted, this.result.getItem(0))) {
            this.result.setItem(0, fitted);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();

        if (index == SLOT_RESULT) {
            if (!this.moveItemStackTo(stack, BENCH_SLOTS, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
            slot.onQuickCraft(stack, original);
        } else if (index < BENCH_SLOTS) {
            if (!this.moveItemStackTo(stack, BENCH_SLOTS, this.slots.size(), false)) {
                return ItemStack.EMPTY;
            }
        } else if (!this.moveIntoBench(stack)) {
            // Not something the bench takes: fall back to the usual inventory <-> hotbar shuffle.
            int mainStart = BENCH_SLOTS;
            int hotbarStart = this.slots.size() - 9;
            if (index < hotbarStart) {
                if (!this.moveItemStackTo(stack, hotbarStart, this.slots.size(), false)) {
                    return ItemStack.EMPTY;
                }
            } else if (!this.moveItemStackTo(stack, mainStart, hotbarStart, false)) {
                return ItemStack.EMPTY;
            }
        }

        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (stack.getCount() == original.getCount()) {
            return ItemStack.EMPTY;
        }
        slot.onTake(player, stack);
        return original;
    }

    /** Sends a shift-clicked stack to whichever half of the jig will take it. */
    private boolean moveIntoBench(ItemStack stack) {
        if (stack.is(ModItems.RECON_DRONE.get())) {
            return this.moveItemStackTo(stack, SLOT_DRONE, SLOT_DRONE + 1, false);
        }
        if (stack.getItem() instanceof DroneModuleItem) {
            return this.moveItemStackTo(stack, SLOT_MODULE, SLOT_MODULE + 1, false);
        }
        return false;
    }

    /**
     * The bench has no block entity to invalidate against, so the screen stays open until the
     * player closes it - the same contract the vanilla crafting table offers.
     */
    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        // Leftovers go back to the player rather than vanishing with the screen.
        clearContainer(player, this.input);
    }
}
