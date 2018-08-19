package forestry.core.climate;

import java.util.function.BiFunction;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import forestry.api.climate.ClimateType;
import forestry.api.climate.IClimateHousing;
import forestry.api.climate.IClimateManipulator;
import forestry.api.climate.IClimateState;
import forestry.api.climate.IClimateTransformer;
import forestry.api.climate.TransformerProperties;

public class FakeClimateTransformer implements IClimateTransformer {
	public static final FakeClimateTransformer INSTANCE = new FakeClimateTransformer();

	private FakeClimateTransformer() {
	}

	@Override
	public void setCircular(boolean circular) {
	}

	@Override
	public boolean isCircular() {
		return false;
	}

	@Override
	public int getRange() {
		return 0;
	}

	@Override
	public void setRange(int range) {
	}

	@Override
	public void setTarget(IClimateState target) {

	}

	@Override
	public void setCurrent(IClimateState state) {

	}

	@Override
	public int getArea() {
		return 0;
	}

	@Override
	public IClimateState getTarget() {
		return ClimateStateHelper.INSTANCE.absent();
	}

	@Override
	public IClimateState getCurrent() {
		return ClimateStateHelper.INSTANCE.absent();
	}

	@Override
	public IClimateState getDefault() {
		return ClimateStateHelper.INSTANCE.absent();
	}

	@Override
	public void addTransformer() {
	}

	@Override
	public void removeTransformer() {
	}

	@Override
	public IClimateHousing getHousing() {
		return null;
	}

	@Override
	public TransformerProperties createProperties() {
		return null;
	}

	@Override
	public IClimateManipulator createManipulator(ClimateType type, BiFunction<ClimateType, TransformerProperties, Float> changeSupplier) {
		return null;
	}

	@Override
	public BlockPos getCoordinates() {
		return BlockPos.ORIGIN;
	}

	@Override
	public World getWorldObj() {
		return null;
	}
}
