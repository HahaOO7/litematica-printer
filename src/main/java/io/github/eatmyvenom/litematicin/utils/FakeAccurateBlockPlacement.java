package io.github.eatmyvenom.litematicin.utils;

//see https://github.com/senseiwells/EssentialClient/blob/1.19.x/src/main/java/me/senseiwells/essentialclient/feature/BetterAccurateBlockPlacement.java

import fi.dy.masa.litematica.util.InventoryUtils;
import io.github.eatmyvenom.litematicin.LitematicaMixinMod;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.*;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.WallMountLocation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;

public class FakeAccurateBlockPlacement{

	// We implement FIFO Queue structure with responsible ticks.
	// By config, we define 'wait tick' between block placements
	public static Direction fakeDirection = null;
	public static int requestedTicks = -3;
	public static float fakeYaw = 0;
	public static float fakePitch = 0;
	private static BlockState stateGrindStone = null;
	private static float previousFakeYaw = 0;
	private static float previousFakePitch = 0;
	private static int tickElapsed = 0;
	private static Item currentHandling = Items.AIR;
	private static boolean betweenStartAndEnd = false;
	private static final Queue<PosWithBlock> waitingQueue = new ArrayDeque<>(1);
	private static final HashSet<Block> warningSet = new HashSet<>();
	// Cancel when handling
	public static boolean isHandling(){
		return requestedTicks >= 0;
	}
	// should be called every client tick.
	static {
		ClientTickEvents.END_CLIENT_TICK.register(FakeAccurateBlockPlacement::endtick);
		ClientTickEvents.START_CLIENT_TICK.register(FakeAccurateBlockPlacement::starttick);
	}
	public static void starttick(MinecraftClient minecraftClient){
		betweenStartAndEnd = false;
	}
	public static void endtick(MinecraftClient minecraftClient){
		betweenStartAndEnd = true;
		ClientPlayNetworkHandler clientPlayNetworkHandler = minecraftClient.getNetworkHandler();
		ClientPlayerEntity playerEntity = minecraftClient.player;
		tickElapsed = 0;
		if (playerEntity == null || clientPlayNetworkHandler == null){
			requestedTicks = -3;
			fakeDirection = null;
			return;
		}
		//previousFakeYaw = playerEntity.getYaw();
		//previousFakePitch = playerEntity.getPitch();
		if (requestedTicks >= 0){
			if (fakeYaw != previousFakeYaw || fakePitch != previousFakePitch){
				sendLookPacket(clientPlayNetworkHandler, playerEntity);
				previousFakePitch = fakePitch;
				previousFakeYaw = fakeYaw;
			}
			//we send this at last tick
			if (requestedTicks == 0){
				PosWithBlock obj = waitingQueue.poll();
				if (obj != null) {
					placeBlock(obj.pos, obj.blockState);
					//System.out.print(obj);
				}
			}
		}
		requestedTicks = requestedTicks -1;
		if (requestedTicks <= -1){
			currentHandling = Items.AIR;
			stateGrindStone = null;
		}
		if (requestedTicks <= -3){
			requestedTicks = -3;
			fakeDirection = Direction.getEntityFacingOrder(playerEntity)[0];
			previousFakePitch = playerEntity.getPitch();
			previousFakeYaw = playerEntity.getYaw();
		}
	}

	public static void sendLookPacket(ClientPlayNetworkHandler networkHandler, ClientPlayerEntity playerEntity){
		networkHandler.sendPacket(
			new PlayerMoveC2SPacket.LookAndOnGround(
				fakeYaw,
				fakePitch,
				playerEntity.isOnGround()
			)
		);
		//System.out.print(fakeYaw);
		//System.out.print(fakePitch);
	}
	/*
	Pure request function by yaw pitch direction
	 */
	public static boolean request(float yaw, float pitch, Direction direction, int duration, boolean force){
		if (isHandling()){
			if (!force) {
				return false;
			}
		}
		fakeDirection = direction;
		fakeYaw = yaw;
		fakePitch = pitch;
		requestedTicks = duration;
		// we might need it instantly
		final MinecraftClient minecraftClient = MinecraftClient.getInstance();
		final ClientPlayNetworkHandler networkHandler = minecraftClient.getNetworkHandler();
		final ClientPlayerEntity playerEntity = minecraftClient.player;
		if (networkHandler != null && playerEntity != null) {
			sendLookPacket(networkHandler, playerEntity);
			return true;
		}
		else {
			return false;
		}
	}

	private static boolean canPlaceWallMounted(BlockState blockState){
		if (blockState.getBlock() instanceof TorchBlock){
			if (blockState.getBlock() instanceof WallTorchBlock || blockState.getBlock() instanceof WallRedstoneTorchBlock){
				return fakeDirection == blockState.get(WallTorchBlock.FACING).getOpposite();
			}
			return fakeDirection == Direction.DOWN;
		}
		if (blockState.getBlock() instanceof WallMountedBlock){
			//so we have 2 properties, looking at down / up as first direction, horizontals as second direction.
			WallMountLocation location = blockState.get(WallMountedBlock.FACE);
			if (location == WallMountLocation.WALL){
				return true;
			}
			Direction facingSecond = blockState.get(WallMountedBlock.FACING);
			return fakeDirection == facingSecond;
		}
		else {
			return true;
		}
	}
	private static boolean requestGrindStone(BlockState state, BlockPos blockPos){
		Direction facing = state.get(WallMountedBlock.FACING);
		WallMountLocation location = state.get(WallMountedBlock.FACE);
		float fy = 0;
		float fp = 0;
		Direction lookRefdir;
		if (location == WallMountLocation.CEILING){
			//primary should be UP
			//secondary should be same as facing
			fp = -90;
			lookRefdir = facing;
		}
		else if (location == WallMountLocation.FLOOR){
			fp = 90;
			lookRefdir = facing;
		}
		else {
			fp = 0;
			lookRefdir = facing.getOpposite();
		}
		if (lookRefdir  == Direction.EAST) {
			fy = -87;
		}
		else if (lookRefdir  == Direction.WEST) {
			fy = 87;
		}
		else if (lookRefdir  == Direction.NORTH) {
			fy = 177;
		}
		else if (lookRefdir  == Direction.SOUTH) {
			fy = 3;
		}
		if (isHandling()){
			if (requestedTicks == 0 && stateGrindStone != null && canPlace(state)){
				//instant place
				pickFirst(state);
				placeBlock(blockPos, state);
				return true;
			}
			return false;
		}
		stateGrindStone = state;
		pickFirst(state);
		waitingQueue.add(new PosWithBlock(blockPos, state));
		request(fy, fp, lookRefdir,LitematicaMixinMod.FAKE_ROTATION_TICKS.getIntegerValue(), false );
		return true;
	}
	/***
	 *
	 * @param blockState : Block object(terracotta, etc...)
	 * @param blockPos : Block Position
	 * @return boolean : if its registered and just can place it.
	 * example : boolean canContinue = FakeAccurateBlockPlacement.request(SchematicState, BlockPos)
	 */
	public static boolean request(BlockState blockState, BlockPos blockPos){
		// instant
		if (!betweenStartAndEnd || !canPlace(blockState)){
			return false;
		}
		if (blockState.isOf(Blocks.GRINDSTONE)){
			return requestGrindStone(blockState, blockPos);
		}
		if (blockState.isOf(Blocks.HOPPER)){
			pickFirst(blockState);
			placeBlock(blockPos,blockState);
			return true;
		}
		if (!blockState.contains(Properties.FACING) && !blockState.contains(Properties.HORIZONTAL_FACING) && !(blockState.getBlock() instanceof AbstractRailBlock) && !(blockState.getBlock() instanceof TorchBlock)){
			pickFirst(blockState);
			placeBlock(blockPos,blockState);
			return true; //without facing properties
		}
		FacingData facingData = FacingData.getFacingData(blockState);
		if (facingData == null && !(blockState.getBlock() instanceof AbstractRailBlock) && !(blockState.getBlock() instanceof TorchBlock)){
			if (!warningSet.contains(blockState.getBlock())){
				warningSet.add(blockState.getBlock());
				System.out.printf("WARN : Block %s is not found\n", blockState.getBlock().toString());
			}
			pickFirst(blockState);
			placeBlock(blockPos, blockState);
			return true;
		}
		Direction facing = fi.dy.masa.malilib.util.BlockUtils.getFirstPropertyFacingValue(blockState); //facing of block itself
		if (facing == null && blockState.getBlock() instanceof AbstractRailBlock){
			facing = Printer.convertRailShapetoFace(blockState);
		}
		else if (blockState.getBlock() instanceof TorchBlock){
			if (blockState.getBlock() instanceof WallTorchBlock || blockState.getBlock() instanceof WallRedstoneTorchBlock){
				facing = blockState.get(WallTorchBlock.FACING).getOpposite();
			}
			else {
				facing = Direction.DOWN;
			}
		}
		if (facing == null){
			//System.out.println(blockState);
			pickFirst(blockState);
			placeBlock(blockPos, blockState);
			return true;
		}
		//assume player is looking at north
		boolean reversed = facingData != null && facingData.isReversed;
		int order = facingData == null ? 0 : facingData.type;
		Direction direction1 = facing;
		float fy = 0, fp = 0;
		if (order == 0 || order == 1){
			direction1 = reversed ? facing.getOpposite() : facing;
		}
		else if (order == 2){
			facing = blockState.get(WallMountedBlock.FACING);
			direction1 = blockState.contains(WallMountedBlock.FACE) && blockState.get(WallMountedBlock.FACE) == WallMountLocation.WALL ? facing.getOpposite() : facing;
			if (blockState.get(WallMountedBlock.FACE) == WallMountLocation.CEILING){
				fp = -90;
			}
			else if (blockState.get(WallMountedBlock.FACE) == WallMountLocation.FLOOR){
				fp = 90;
			}
			else {
				fp = 0;
			}
		}
		else if (order == 3){
			direction1 = facing.rotateYCounterclockwise();
		}
		if (order != 2 && (direction1 == null || fakeDirection == direction1) && canPlaceWallMounted(blockState)){
			pickFirst(blockState);
			placeBlock(blockPos,blockState);
			return true;
		}
		Direction lookRefdir = direction1;
		if (lookRefdir  == Direction.UP) {
			fp = -90;
		}
		else if (lookRefdir  == Direction.DOWN) {
			fp = 90;
		}
		else if (lookRefdir  == Direction.EAST) {
			fy = -87;
		}
		else if (lookRefdir  == Direction.WEST) {
			fy = 87;
		}
		else if (lookRefdir  == Direction.NORTH) {
			fy = 177;
		}
		else if (lookRefdir  == Direction.SOUTH) {
			fy = 3;
		}
		else {
			fy = 0;
			fp = 0;
		}
		if (LitematicaMixinMod.FAKE_ROTATION_TICKS.getIntegerValue() == 0){
			//instant place
			if (lookRefdir != fakeDirection) {
				if (tickElapsed > LitematicaMixinMod.FAKE_ROTATION_LIMIT.getIntegerValue()){
					return false;
				}
				tickElapsed += 1;
				request(fy, fp, lookRefdir, LitematicaMixinMod.FAKE_ROTATION_TICKS.getIntegerValue(), true);
				placeBlock(blockPos, blockState);
			}
			else {
				pickFirst(blockState);
				placeBlock(blockPos, blockState);
			}
			return true;
		}
		else {
			//delay
			if (isHandling() && (lookRefdir != fakeDirection || !canPlaceWallMounted(blockState))){
				return false;
			}
			request(fy, fp, lookRefdir, LitematicaMixinMod.FAKE_ROTATION_TICKS.getIntegerValue(), false);
			pickFirst(blockState);
			waitingQueue.add(new PosWithBlock(blockPos, blockState));
		}
		return false;
	}
	public static boolean canPlace(BlockState state){
		if (state.isOf(Blocks.GRINDSTONE)){
			if (stateGrindStone != null)
				return stateGrindStone.get(GrindstoneBlock.FACE) == state.get(GrindstoneBlock.FACE) && stateGrindStone.get(GrindstoneBlock.FACING) == state.get(GrindstoneBlock.FACING);
		}
		return betweenStartAndEnd && state.getBlock().asItem() == currentHandling || currentHandling == Items.AIR;
	}
	private static boolean placeBlock(BlockPos pos, BlockState blockState){
		final MinecraftClient minecraftClient = MinecraftClient.getInstance();
		final ClientPlayerEntity player = minecraftClient.player;
		final ClientPlayerInteractionManager interactionManager = minecraftClient.interactionManager;
		if (!minecraftClient.world.getBlockState(pos).getMaterial().isReplaceable()){
			return true;
		}
		Direction sideOrig = Direction.NORTH;
		Vec3d hitPos = new Vec3d(pos.getX(), pos.getY(), pos.getZ());
		Direction side = Printer.applyPlacementFacing(blockState, sideOrig, minecraftClient.world.getBlockState(pos));
		Vec3d appliedHitVec = Printer.applyHitVec(pos, blockState, side);
		//Trapdoor actually occasionally refers to player and UP DOWN wtf
		if (blockState.getBlock() instanceof TrapdoorBlock){
			side = blockState.get(TrapdoorBlock.HALF) == BlockHalf.BOTTOM ? Direction.UP : Direction.DOWN;
			appliedHitVec = Vec3d.of(pos);
		}
		else if (blockState.getBlock() instanceof GrindstoneBlock){
			appliedHitVec = Vec3d.ofCenter(pos);
			if (blockState.get(GrindstoneBlock.FACE) == WallMountLocation.CEILING ){
				side = Direction.DOWN;
			}
			else if (blockState.get(GrindstoneBlock.FACE) == WallMountLocation.FLOOR){
				side = Direction.UP;
			}
		}
		else if (blockState.getBlock() instanceof TorchBlock){
			appliedHitVec = Vec3d.ofCenter(pos); //follows player looking
		}
		BlockHitResult blockHitResult = new BlockHitResult(appliedHitVec, side, pos, true);
		pickFirst(blockState);
		//System.out.print("Interacted via fake application\n");
		if (blockState.getBlock().asItem() == currentHandling) {
			interactionManager.interactBlock(player, Hand.MAIN_HAND, blockHitResult);
			Printer.cacheEasyPlacePosition(pos, false);
		}

		return true;
	}
	private static void pickFirst(BlockState blockState){
		final MinecraftClient minecraftClient = MinecraftClient.getInstance();
		currentHandling = blockState.getBlock().asItem();
		//InventoryUtils.setPickedItemToHand(currentHandling.getDefaultStack(), minecraftClient);
		fi.dy.masa.malilib.util.InventoryUtils.swapItemToMainHand(currentHandling.getDefaultStack(), minecraftClient);
	}
	// we just record pos + block and put in queue.
	private record PosWithBlock(BlockPos pos,BlockState blockState){
	}

}