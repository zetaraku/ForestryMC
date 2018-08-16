/*******************************************************************************
 * Copyright (c) 2011-2014 SirSengir.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser Public License v3
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl-3.0.txt
 *
 * Various Contributors including, but not limited to:
 * SirSengir (original work), CovertJaguar, Player, Binnie, MysteriousAges
 ******************************************************************************/
package forestry.climatology.tiles;

import javax.annotation.Nullable;
import java.io.IOException;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.biome.Biome;

import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;

import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import forestry.api.circuits.ChipsetManager;
import forestry.api.circuits.CircuitSocketType;
import forestry.api.circuits.ICircuitBoard;
import forestry.api.circuits.ICircuitSocketType;
import forestry.api.climate.ClimateType;
import forestry.api.climate.IClimateHousing;
import forestry.api.climate.IClimateLogic;
import forestry.api.climate.IClimateManipulator;
import forestry.api.climate.IClimateState;
import forestry.api.climate.LogicInfo;
import forestry.api.core.EnumHumidity;
import forestry.api.core.EnumTemperature;
import forestry.api.core.IErrorLogic;
import forestry.api.recipes.IHygroregulatorRecipe;
import forestry.climatology.gui.ContainerHabitatformer;
import forestry.climatology.gui.GuiHabitatformer;
import forestry.climatology.inventory.InventoryHabitatformer;
import forestry.core.circuits.ISocketable;
import forestry.core.climate.ClimateLogic;
import forestry.core.config.Config;
import forestry.core.config.Constants;
import forestry.core.errors.EnumErrorCode;
import forestry.core.fluids.FilteredTank;
import forestry.core.fluids.FluidHelper;
import forestry.core.fluids.ITankManager;
import forestry.core.fluids.TankManager;
import forestry.core.inventory.InventoryAdapter;
import forestry.core.network.PacketBufferForestry;
import forestry.core.recipes.HygroregulatorManager;
import forestry.core.tiles.IClimatised;
import forestry.core.tiles.ILiquidTankTile;
import forestry.core.tiles.TilePowered;
import forestry.energy.EnergyManager;

public class TileHabitatformer extends TilePowered implements IClimateHousing, IClimatised, ISocketable, ILiquidTankTile {
	private static final String LOGIC_NBT_KEY = "Logic";

	//A inventory that contains the circuits of this tile.
	private final InventoryAdapter sockets = new InventoryAdapter(1, "sockets");

	//The logic that handles the climate  changes.
	private final ClimateLogic logic;

	private final FilteredTank resourceTank;
	private final TankManager tankManager;

	public TileHabitatformer() {
		super(1200, 8000);
		this.logic = new ClimateLogic(this);
		setInternalInventory(new InventoryHabitatformer(this));
		resourceTank = new FilteredTank(Constants.PROCESSOR_TANK_CAPACITY).setFilters(HygroregulatorManager.getRecipeFluids());
		tankManager = new TankManager(this, resourceTank);
		setTicksPerWorkCycle(10);
		setEnergyPerWorkCycle(0);
	}

	@Override
	public ITankManager getTankManager() {
		return tankManager;
	}

	@Override
	public void onRemoval() {
		logic.onRemoval();
	}

	@Override
	protected void updateServerSide() {
		super.updateServerSide();
		logic.update();
		if (updateOnInterval(20)) {
			// Check if we have suitable items waiting in the item slot
			FluidHelper.drainContainers(tankManager, this, 0);
		}
	}

	@Override
	public boolean hasWork() {
		return true;
	}

	@Nullable
	private FluidStack cachedStack = null;

	@Override
	protected boolean workCycle() {
		IErrorLogic errorLogic = getErrorLogic();
		IClimateState currentState = logic.getCurrent();
		IClimateState changedState = logic.getTarget().subtract(currentState);
		IClimateState difference = getClimateDifference();
		cachedStack = null;
		if (difference.getHumidity() != 0.0F) {
			updateHumidity(errorLogic, changedState);
		}
		if (difference.getTemperature() != 0.0F) {
			updateTemperature(errorLogic, changedState);
		}
		return true;
	}

	private void updateHumidity(IErrorLogic errorLogic, IClimateState changedState) {
		IClimateManipulator manipulator = logic.createManipulator(ClimateType.HUMIDITY, this::getClimateChange);
		if (manipulator.canAdd()) {
			errorLogic.setCondition(false, EnumErrorCode.WRONG_RESOURCE);
			int currentCost = getFluidCost(changedState);
			if (resourceTank.drain(currentCost, false) != null) {
				IClimateState simulatedState = /*changedState.add(ClimateType.HUMIDITY, climateChange)*/
					changedState.toImmutable().add(manipulator.addChange(true));
				int fluidCost = getFluidCost(simulatedState);
				if (resourceTank.drain(fluidCost, false) != null) {
					cachedStack = resourceTank.drain(fluidCost, true);
					manipulator.addChange(false);
				} else {
					cachedStack = resourceTank.drain(currentCost, true);
				}
				errorLogic.setCondition(false, EnumErrorCode.NO_RESOURCE_LIQUID);
			} else {

				manipulator.removeChange(false);
				errorLogic.setCondition(true, EnumErrorCode.NO_RESOURCE_LIQUID);
			}
		} else {
			if (resourceTank.isEmpty()) {
				errorLogic.setCondition(true, EnumErrorCode.NO_RESOURCE_LIQUID);
			} else {
				errorLogic.setCondition(true, EnumErrorCode.WRONG_RESOURCE);
				errorLogic.setCondition(false, EnumErrorCode.NO_RESOURCE_LIQUID);
			}
		}
		manipulator.finish();
	}

	private void updateTemperature(IErrorLogic errorLogic, IClimateState changedState) {
		IClimateManipulator manipulator = logic.createManipulator(ClimateType.TEMPERATURE, this::getClimateChange);
		manipulator.setAllowBackwards();
		EnergyManager energyManager = getEnergyManager();
		int currentCost = getEnergyCost(changedState);
		if (energyManager.extractEnergy(currentCost, true) > 0) {
			IClimateState simulatedState = manipulator.addChange(true);
			int energyCost = getEnergyCost(simulatedState);
			if (energyManager.extractEnergy(energyCost, true) > 0) {
				energyManager.extractEnergy(energyCost, false);
				manipulator.addChange(false);
			} else {
				energyManager.extractEnergy(currentCost, false);
			}
			errorLogic.setCondition(false, EnumErrorCode.NO_POWER);
		} else {
			manipulator.removeChange(false);
			errorLogic.setCondition(true, EnumErrorCode.NO_POWER);
		}
		manipulator.finish();
	}

	private int getFluidCost(IClimateState state) {
		FluidStack fluid = resourceTank.getFluid();
		if (fluid == null) {
			return 0;
		}
		IHygroregulatorRecipe recipe = HygroregulatorManager.findMatchingRecipe(fluid);
		if (recipe == null) {
			return 0;
		}
		return Math.round((1.0F + MathHelper.abs(state.getHumidity())) * logic.getResourceModifier() * getCostModifier() * recipe.getResource().amount);
	}

	private int getEnergyCost(IClimateState state) {
		return Math.round((1.0F + MathHelper.abs(state.getTemperature())) * logic.getResourceModifier() * getCostModifier());
	}

	private float getCostModifier() {
		return 1.0F + ((logic.getArea() / 36F) * Config.habitatformerAreaCostModifier);
	}

	private float getSpeedModifier() {
		return 1.0F + ((logic.getArea() / 36F) * Config.habitatformerAreaSpeedModifier);
	}

	private float getClimateChange(ClimateType type, LogicInfo info) {
		if (type == ClimateType.HUMIDITY) {
			FluidStack fluid = resourceTank.getFluid();
			if (fluid != null) {
				IHygroregulatorRecipe recipe = HygroregulatorManager.findMatchingRecipe(fluid);
				if (recipe != null) {
					return recipe.getHumidChange() * logic.getChangeModifier() / getSpeedModifier();
				}
			}
		}
		float fluidChange = 0.0F;
		if (cachedStack != null) {
			IHygroregulatorRecipe recipe = HygroregulatorManager.findMatchingRecipe(cachedStack);
			if (recipe != null) {
				fluidChange = recipe.getTempChange();
			}
		}
		return (0.05F + fluidChange) * logic.getChangeModifier() * 0.5F / getSpeedModifier();
	}

	private IClimateState getClimateDifference() {
		IClimateState defaultState = logic.getDefault();
		IClimateState targetedState = logic.getTarget();
		return targetedState.subtract(defaultState);
	}

	@Override
	public void markNetworkUpdate() {
		setNeedsNetworkUpdate();
	}

	@Override
	@SideOnly(Side.CLIENT)
	public GuiContainer getGui(EntityPlayer player, int data) {
		return new GuiHabitatformer(player, this);
	}

	@Override
	public Container getContainer(EntityPlayer player, int data) {
		return new ContainerHabitatformer(player.inventory, this);
	}

	@Override
	public EnumTemperature getTemperature() {
		return EnumTemperature.getFromValue(getExactTemperature());
	}

	@Override
	public EnumHumidity getHumidity() {
		return EnumHumidity.getFromValue(getExactHumidity());
	}

	@Override
	public Biome getBiome() {
		return world.getBiome(getPos());
	}

	@Override
	public float getExactTemperature() {
		return logic.getCurrent().getTemperature();
	}

	@Override
	public float getExactHumidity() {
		return logic.getCurrent().getHumidity();
	}

	/* Methods - Implement IGreenhouseHousing */
	@Override
	public IClimateLogic getLogic() {
		return logic;
	}

	/* Methods - Implement IStreamableGui */
	@Override
	public void writeGuiData(PacketBufferForestry data) {
		super.writeGuiData(data);
		logic.writeData(data);
		sockets.writeData(data);
	}

	@Override
	public void readGuiData(PacketBufferForestry data) throws IOException {
		super.readGuiData(data);
		logic.readData(data);
		sockets.readData(data);
	}

	/* Methods - Implement ISocketable */
	@Override
	public int getSocketCount() {
		return sockets.getSizeInventory();
	}

	@Override
	public ItemStack getSocket(int slot) {
		return sockets.getStackInSlot(slot);
	}

	@Override
	public void setSocket(int slot, ItemStack stack) {

		if (!stack.isEmpty() && !ChipsetManager.circuitRegistry.isChipset(stack)) {
			return;
		}

		// Dispose correctly of old chipsets
		if (!sockets.getStackInSlot(slot).isEmpty()) {
			if (ChipsetManager.circuitRegistry.isChipset(sockets.getStackInSlot(slot))) {
				ICircuitBoard chipset = ChipsetManager.circuitRegistry.getCircuitBoard(sockets.getStackInSlot(slot));
				if (chipset != null) {
					chipset.onRemoval(this);
				}
			}
		}

		sockets.setInventorySlotContents(slot, stack);
		if (stack.isEmpty()) {
			return;
		}

		ICircuitBoard chipset = ChipsetManager.circuitRegistry.getCircuitBoard(stack);
		if (chipset != null) {
			chipset.onInsertion(this);
		}
	}

	@Override
	public ICircuitSocketType getSocketType() {
		return CircuitSocketType.HABITAT_FORMER;
	}

	/* Methods - SAVING & LOADING */
	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound data) {
		super.writeToNBT(data);

		tankManager.writeToNBT(data);

		data.setTag(LOGIC_NBT_KEY, logic.writeToNBT(new NBTTagCompound()));

		sockets.writeToNBT(data);

		return data;
	}

	@Override
	public void readFromNBT(NBTTagCompound data) {
		super.readFromNBT(data);

		tankManager.readFromNBT(data);

		if (data.hasKey(LOGIC_NBT_KEY)) {
			NBTTagCompound nbtTag = data.getCompoundTag(LOGIC_NBT_KEY);
			logic.readFromNBT(nbtTag);
		}

		sockets.readFromNBT(data);

		ItemStack chip = sockets.getStackInSlot(0);
		if (!chip.isEmpty()) {
			ICircuitBoard chipset = ChipsetManager.circuitRegistry.getCircuitBoard(chip);
			if (chipset != null) {
				chipset.onLoad(this);
			}
		}
	}

	/* Network */
	@Override
	public void writeData(PacketBufferForestry data) {
		super.writeData(data);
		tankManager.writeData(data);
		logic.writeData(data);
	}

	@Override
	@SideOnly(Side.CLIENT)
	public void readData(PacketBufferForestry data) throws IOException {
		super.readData(data);
		tankManager.readData(data);
		logic.readData(data);
	}

	@Nullable
	@Override
	public <T> T getCapability(Capability<T> capability, @Nullable EnumFacing facing) {
		if (capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY) {
			return CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY.cast(tankManager);
		}
		return super.getCapability(capability, facing);
	}

	@Override
	public boolean hasCapability(Capability<?> capability, @Nullable EnumFacing facing) {
		return capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY || super.hasCapability(capability, facing);
	}

	public void changeClimateConfig(float changeChange, float rangeChange, float energyChange) {
		logic.changeClimateConfig(changeChange, rangeChange, energyChange);
	}
}
