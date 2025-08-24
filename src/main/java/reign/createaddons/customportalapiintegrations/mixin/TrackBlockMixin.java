package reign.createaddons.customportalapiintegrations.mixin;

import java.util.Random;

import com.google.common.base.Predicates;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.foundation.utility.BlockFace;
import com.simibubi.create.foundation.utility.Components;
import com.simibubi.create.foundation.utility.Iterate;

import com.simibubi.create.foundation.utility.Lang;
import com.simibubi.create.foundation.utility.Pair;

import net.kyrptonaught.customportalapi.CustomPortalApiRegistry;
import net.kyrptonaught.customportalapi.CustomPortalBlock;
import net.kyrptonaught.customportalapi.CustomPortalsMod;
import net.kyrptonaught.customportalapi.util.CustomPortalHelper;
import net.kyrptonaught.customportalapi.util.CustomTeleporter;
import net.kyrptonaught.customportalapi.util.PortalLink;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.core.registries.Registries;

import net.minecraft.world.level.block.entity.BlockEntity;

import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.portal.PortalForcer;
import net.minecraft.world.level.portal.PortalInfo;
import net.minecraft.world.phys.AABB;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.simibubi.create.content.trains.track.TrackPropagator;
import com.simibubi.create.content.trains.track.TrackShape;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockBehaviour.Properties;
import net.minecraft.world.level.block.state.properties.EnumProperty;

@Mixin(TrackBlock.class)
public abstract class TrackBlockMixin {

	@Shadow
	@Final
	public static final EnumProperty<TrackShape> SHAPE = EnumProperty.create("shape", TrackShape.class);

	@Shadow
	@Final
	public static final BooleanProperty HAS_BE = BooleanProperty.create("turn");

	protected void connectToOtherDimension(ServerLevel level, BlockPos pos, BlockState state) {
		TrackShape shape = state.getValue(TrackBlock.SHAPE);
		Axis portalTest = shape == TrackShape.XO ? Axis.X : shape == TrackShape.ZO ? Axis.Z : null;
		if (portalTest == null)
			return;

		boolean pop = false;
		String fail = null;
		BlockPos failPos = null;

		for(Direction d : Iterate.directionsInAxis(portalTest)) {
			BlockPos portalPos = pos.relative(d);
			BlockState portalState = level.getBlockState(portalPos);
			if (!(portalState.getBlock() instanceof CustomPortalBlock || portalState.getBlock() instanceof NetherPortalBlock))
				continue;

			pop = true;
			Pair<ServerLevel, BlockFace> otherSide = getOtherSide(level, new BlockFace(pos, d));
			if (otherSide == null) {
				fail = "missing";
				continue;
			}

			ServerLevel otherLevel = otherSide.getFirst();
			BlockFace otherTrack = otherSide.getSecond();
			BlockPos otherTrackPos = otherTrack.getPos();
			BlockState existing = otherLevel.getBlockState(otherTrackPos);
			if(!existing.Properties()
					.replaceable()) {
				fail = "blocked";
				failPos = otherTrackPos;
				continue;
			}

			level.setBlock(pos, state.setValue(SHAPE, TrackShape.asPortal(d))
					.setValue(HAS_BE, true), 3);
			BlockEntity be = level.getBlockEntity(pos);
			if (be instanceof TrackBlockEntity tbe)
				tbe.bind(otherLevel.dimension(), otherTrackPos);

			otherLevel.setBlock(otherTrackPos, state.setValue(SHAPE, TrackShape.asPortal(otherTrack.getFace()))
					.setValue(HAS_BE, true), 3);
			BlockEntity otherBE = otherLevel.getBlockEntity(otherTrackPos);
			if (otherBE instanceof TrackBlockEntity tbe)
				tbe.bind(level.dimension(), pos);

			pop = false;
		}

		if (!pop)
			return;

		level.destroyBlock(pos, true);

		if (fail == null)
			return;
		Player player = level.getNearestPlayer(pos.getX(), pos.getY(), pos.getZ(), 10, Predicates.alwaysTrue());
		if (player == null)
			return;
		player.displayClientMessage(Components.literal("<!> ").append(Lang.translateDirect("portal_track.failed"))
				.withStyle(ChatFormatting.GOLD), false);
		MutableComponent component =
				failPos != null ? Lang.translateDirect("portal_track." + fail, failPos.getX(), failPos.getY(), failPos.getZ())
						: Lang.translateDirect("portal_track." + fail);
		player.displayClientMessage(Components.literal(" - ").withStyle(ChatFormatting.GRAY)
				.append(component.withStyle(st -> st.withColor(0xFFD3B4))), false);
	}

	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void trackBlock$tick(BlockState state, ServerLevel level, BlockPos pos, Random p_60465_, CallbackInfo ci) {
		TrackPropagator.onRailAdded(level, pos, state);
		if (!state.getValue(SHAPE)
				.isPortal())
			connectToOtherDimension(level, pos, state);
		ci.cancel();
	}

	protected Pair<ServerLevel, BlockFace> getOtherSide(ServerLevel level, BlockFace inboundTrack) {
		BlockPos portalPos = inboundTrack.getConnectedPos();
		BlockState portalState = level.getBlockState(portalPos);
		if (!(portalState.getBlock() instanceof NetherPortalBlock || portalState.getBlock() instanceof CustomPortalBlock))
			return null;

		MinecraftServer minecraftserver = level.getServer();
		ServerLevel otherLevel = null;
		if(portalState.getBlock() instanceof NetherPortalBlock) {
			ResourceKey<Level> resourcekey = level.dimension() == Level.NETHER ? Level.OVERWORLD : Level.NETHER;
			otherLevel = minecraftserver.getLevel(resourcekey);
		} else {
			PortalLink link = CustomPortalApiRegistry.getPortalLinkFromBase(((CustomPortalBlock)portalState.getBlock()).getPortalBase(level, portalPos));
			ResourceKey<Level> resourcekey = level.dimension() == ResourceKey.create(Registries.DIMENSION,link.dimID) ? ResourceKey.create(Registries.DIMENSION,link.returnDimID) : ResourceKey.create(Registries.DIMENSION,link.dimID);;
			otherLevel = minecraftserver.getLevel(resourcekey);
		}
		if (otherLevel == null)
			return null;

		PortalForcer teleporter = otherLevel.getPortalForcer();
		SuperGlueEntity probe = new SuperGlueEntity(level, new AABB(portalPos));
		probe.setYRot(inboundTrack.getFace()
				.toYRot());
		PortalInfo portalinfo = null;
		if(portalState.getBlock() instanceof NetherPortalBlock) {
			portalinfo = probe.findDimensionEntryPoint(otherLevel);
		} else {
			PortalLink link = CustomPortalApiRegistry.getPortalLinkFromBase(((CustomPortalBlock)portalState.getBlock()).getPortalBase(level, portalPos));
			portalinfo = CustomTeleporter.customTPTarget(otherLevel, probe, portalPos, ((CustomPortalBlock)portalState.getBlock()).getPortalBase(level, portalPos), link.getFrameTester());
		}
		if (portalinfo == null)
			return null;

		BlockPos otherPortalPos = new BlockPos(portalinfo.pos);
		BlockState otherPortalState = otherLevel.getBlockState(otherPortalPos);
		if (!(otherPortalState.getBlock() instanceof NetherPortalBlock) && !(otherPortalState.getBlock() instanceof CustomPortalBlock))
			return null;

		Direction targetDirection = inboundTrack.getFace();
		if (targetDirection.getAxis() == CustomPortalHelper.getAxisFrom(otherPortalState))
			targetDirection = targetDirection.getClockWise();
		BlockPos otherPos = otherPortalPos.relative(targetDirection);
		return Pair.of(otherLevel, new BlockFace(otherPos, targetDirection.getOpposite()));
	}
}
