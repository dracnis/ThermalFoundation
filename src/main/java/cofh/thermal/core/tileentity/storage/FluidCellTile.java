package cofh.thermal.core.tileentity.storage;

import cofh.core.network.packet.client.TileStatePacket;
import cofh.core.util.helpers.FluidHelper;
import cofh.lib.fluid.FluidHandlerRestrictionWrapper;
import cofh.lib.fluid.FluidStorageAdjustable;
import cofh.lib.fluid.FluidStorageCoFH;
import cofh.lib.util.Utils;
import cofh.lib.util.helpers.AugmentDataHelper;
import cofh.lib.util.helpers.BlockHelper;
import cofh.thermal.core.inventory.container.storage.FluidCellContainer;
import cofh.thermal.lib.tileentity.CellTileBase;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.container.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.ITickableTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Direction;
import net.minecraftforge.client.model.data.IModelData;
import net.minecraftforge.client.model.data.ModelDataMap;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Predicate;

import static cofh.core.client.renderer.model.ModelUtils.*;
import static cofh.lib.util.StorageGroup.ACCESSIBLE;
import static cofh.lib.util.constants.Constants.BUCKET_VOLUME;
import static cofh.lib.util.constants.Constants.TANK_MEDIUM;
import static cofh.thermal.core.init.TCoreReferences.FLUID_CELL_TILE;
import static cofh.thermal.lib.common.ThermalAugmentRules.FLUID_STORAGE_VALIDATOR;
import static cofh.thermal.lib.common.ThermalConfig.storageAugments;
import static net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE;

public class FluidCellTile extends CellTileBase implements ITickableTileEntity {

    public static final int BASE_CAPACITY = TANK_MEDIUM * 4;

    protected FluidStorageCoFH fluidStorage = new FluidStorageAdjustable(BASE_CAPACITY, fluid -> filter.valid(fluid))
            .setTransferLimits(() -> amountInput, () -> amountOutput);

    public FluidCellTile() {

        super(FLUID_CELL_TILE);

        amountInput = BUCKET_VOLUME;
        amountOutput = BUCKET_VOLUME;

        tankInv.addTank(fluidStorage, ACCESSIBLE);

        transferControl.initControl(false, true);

        addAugmentSlots(storageAugments);
        initHandlers();
    }

    //    @Override
    //    public void neighborChanged(Block blockIn, BlockPos fromPos) {
    //
    //        super.neighborChanged(blockIn, fromPos);
    //
    //        // TODO: Handle caching of neighbor caps.
    //    }

    @Override
    public void tick() {

        if (redstoneControl.getState()) {
            transferOut();
            transferIn();
        }
        if (Utils.timeCheck(level) || fluidStorage.getFluidStack().getFluid() != renderFluid.getFluid()) {
            updateTrackers(true);
        }
    }

    @Override
    public int getLightValue() {

        return FluidHelper.luminosity(renderFluid);
    }

    protected void transferIn() {

        if (!transferControl.getTransferIn()) {
            return;
        }
        if (amountInput <= 0 || fluidStorage.isFull()) {
            return;
        }
        for (int i = inputTracker; i < 6 && fluidStorage.getSpace() > 0; ++i) {
            if (reconfigControl.getSideConfig(i).isInput()) {
                attemptTransferIn(Direction.from3DDataValue(i));
            }
        }
        for (int i = 0; i < inputTracker && fluidStorage.getSpace() > 0; ++i) {
            if (reconfigControl.getSideConfig(i).isInput()) {
                attemptTransferIn(Direction.from3DDataValue(i));
            }
        }
        ++inputTracker;
        inputTracker %= 6;
    }

    protected void transferOut() {

        if (!transferControl.getTransferOut()) {
            return;
        }
        if (amountOutput <= 0 || fluidStorage.isEmpty()) {
            return;
        }
        for (int i = outputTracker; i < 6 && fluidStorage.getAmount() > 0; ++i) {
            if (reconfigControl.getSideConfig(i).isOutput()) {
                attemptTransferOut(Direction.from3DDataValue(i));
            }
        }
        for (int i = 0; i < outputTracker && fluidStorage.getAmount() > 0; ++i) {
            if (reconfigControl.getSideConfig(i).isOutput()) {
                attemptTransferOut(Direction.from3DDataValue(i));
            }
        }
        ++outputTracker;
        outputTracker %= 6;
    }

    protected void attemptTransferIn(Direction side) {

        FluidHelper.extractFromAdjacent(this, fluidStorage, Math.min(amountInput, fluidStorage.getSpace()), side);
    }

    // This is used rather than the generic in FluidHelper as it can be optimized somewhat.
    protected void attemptTransferOut(Direction side) {

        TileEntity adjTile = BlockHelper.getAdjacentTileEntity(this, side);
        if (adjTile != null) {
            Direction opposite = side.getOpposite();
            int maxTransfer = Math.min(amountOutput, fluidStorage.getAmount());
            adjTile.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, opposite)
                    .ifPresent(e -> fluidStorage.modify(-e.fill(new FluidStack(fluidStorage.getFluidStack(), maxTransfer), EXECUTE)));
        }
    }

    @Override
    protected boolean keepFluids() {

        return true;
    }

    @Override
    public int getMaxInput() {

        return fluidStorage.getCapacity() / 4;
    }

    @Override
    public int getMaxOutput() {

        return fluidStorage.getCapacity() / 4;
    }

    @Nullable
    @Override
    public Container createMenu(int i, PlayerInventory inventory, PlayerEntity player) {

        return new FluidCellContainer(i, level, worldPosition, inventory, player);
    }

    @Nonnull
    @Override
    public IModelData getModelData() {

        return new ModelDataMap.Builder()
                .withInitial(SIDES, reconfigControl().getRawSideConfig())
                .withInitial(FACING, reconfigControl.getFacing())
                .withInitial(FLUID, renderFluid)
                .withInitial(LEVEL, levelTracker)
                .build();
    }

    @Override
    protected void updateTrackers(boolean send) {

        prevLight = getLightValue();
        renderFluid = fluidStorage.getFluidStack();

        int curScale = fluidStorage.getAmount() > 0 ? 1 + (int) (fluidStorage.getRatio() * 14) : 0;
        if (curScale != compareTracker) {
            compareTracker = curScale;
            if (send) {
                setChanged();
            }
        }
        if (fluidStorage.isCreative()) {
            curScale = fluidStorage.isEmpty() ? 10 : 9;
        } else {
            curScale = fluidStorage.getAmount() > 0 ? 1 + Math.min((int) (fluidStorage.getRatio() * 8), 7) : 0;
        }
        if (levelTracker != curScale) {
            levelTracker = curScale;
            if (send) {
                TileStatePacket.sendToClient(this);
            }
        }
    }

    // region AUGMENTS
    @Override
    protected Predicate<ItemStack> augValidator() {

        return item -> AugmentDataHelper.hasAugmentData(item) && FLUID_STORAGE_VALIDATOR.test(item, getAugmentsAsList());
    }
    // endregion

    // region CAPABILITIES
    protected LazyOptional<?> inputFluidCap = LazyOptional.empty();
    protected LazyOptional<?> outputFluidCap = LazyOptional.empty();

    @Override
    protected void updateHandlers() {

        // Optimization to prevent callback logic as contents may change rapidly.
        LazyOptional<?> prevFluidCap = fluidCap;
        LazyOptional<?> prevFluidInputCap = inputFluidCap;
        LazyOptional<?> prevFluidOutputCap = outputFluidCap;

        IFluidHandler inputHandler = new FluidHandlerRestrictionWrapper(fluidStorage, true, false);
        IFluidHandler outputHandler = new FluidHandlerRestrictionWrapper(fluidStorage, false, true);

        fluidCap = LazyOptional.of(() -> fluidStorage);
        inputFluidCap = LazyOptional.of(() -> inputHandler);
        outputFluidCap = LazyOptional.of(() -> outputHandler);

        prevFluidCap.invalidate();
        prevFluidInputCap.invalidate();
        prevFluidOutputCap.invalidate();
    }

    @Override
    protected <T> LazyOptional<T> getFluidHandlerCapability(@Nullable Direction side) {

        if (side == null) {
            return super.getFluidHandlerCapability(side);
        }
        switch (reconfigControl.getSideConfig(side)) {
            case SIDE_NONE:
                return LazyOptional.empty();
            case SIDE_INPUT:
                return inputFluidCap.cast();
            case SIDE_OUTPUT:
                return outputFluidCap.cast();
            default:
                return super.getFluidHandlerCapability(side);
        }
    }
    // endregion
}