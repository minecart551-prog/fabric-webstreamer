package fr.theorozier.webstreamer.display;

import net.minecraft.block.*;
import net.minecraft.block.entity.BlockEntity;
// import net.minecraft.block.enums.BlockFace; // Not available in 1.20.1
import net.minecraft.util.math.Direction; // A replacement for BlockFace
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
// import net.minecraft.util.math.Direction; // Unused?
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// import com.mojang.serialization.MapCodec; // Old

public class DisplayBlock extends BlockWithEntity {

    // public static final MapCodec<DisplayBlock> CODEC = DisplayBlock.createCodec(DisplayBlock::new); // Old


    public static final DirectionProperty PROP_FACING = Properties.HORIZONTAL_FACING;
    // public static final EnumProperty<BlockFace> PROP_ATTACHMENT = EnumProperty.of("attachment", BlockFace.class, BlockFace.WALL, BlockFace.FLOOR, BlockFace.CEILING); // Old
    public static final EnumProperty<Direction> PROP_ATTACHMENT = EnumProperty.of("attachment", Direction.class, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN);

    private static final VoxelShape SHAPE_NORTH = VoxelShapes.cuboid(0, 0, 0.9, 1, 1, 1);
    private static final VoxelShape SHAPE_SOUTH = VoxelShapes.cuboid(0, 0, 0, 1, 1, 0.1);
    private static final VoxelShape SHAPE_WEST = VoxelShapes.cuboid(0.9, 0, 0, 1, 1, 1);
    private static final VoxelShape SHAPE_EAST = VoxelShapes.cuboid(0, 0, 0, 0.1, 1, 1);
    private static final VoxelShape SHAPE_FLOOR = VoxelShapes.cuboid(0, 0, 0, 1, 0.1, 1);
    private static final VoxelShape SHAPE_CEILING = VoxelShapes.cuboid(0, 0.9, 0, 1, 1.0, 1);

    public DisplayBlock(Settings settings) {
        super(settings);
    }

    public DisplayBlock() {
        this(Settings.create()
                .sounds(BlockSoundGroup.GLASS)
                .strength(-1.0f, 3600000.0f)
                .requiresTool()
                .dropsNothing()
                .nonOpaque());
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(PROP_FACING);
        builder.add(PROP_ATTACHMENT);
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new DisplayBlockEntity(pos, state);
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.ENTITYBLOCK_ANIMATED;
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {

        PlayerEntity playerEntity = ctx.getPlayer();
        if (playerEntity != null && !canPlace(playerEntity)) {
            return null;
        }

        Direction dir = ctx.getSide();
        // BlockFace face = BlockFace.WALL; // Old
        Direction face = dir;

        /*
        if (dir == Direction.DOWN) {
            face = BlockFace.CEILING;
        } else if (dir == Direction.UP) {
            face = BlockFace.FLOOR;
        }
        */

        if (face != Direction.NORTH && face != Direction.SOUTH && face != Direction.EAST && face != Direction.WEST) {
            dir = ctx.getHorizontalPlayerFacing().getOpposite();
        }

        return this.getDefaultState()
                .with(PROP_FACING, dir)
                .with(PROP_ATTACHMENT, face);

    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return switch (state.get(PROP_ATTACHMENT)) {
            case NORTH, SOUTH, EAST, WEST -> switch (state.get(PROP_FACING)) {
                case NORTH -> SHAPE_NORTH;
                case SOUTH -> SHAPE_SOUTH;
                case EAST -> SHAPE_EAST;
                case WEST -> SHAPE_WEST;
                default -> null;
            };
            case DOWN -> SHAPE_FLOOR;
            case UP -> SHAPE_CEILING;
        };
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        BlockEntity be = world.getBlockEntity(pos);
        if (canUse(player) && be instanceof DisplayBlockEntity dbe) {
            if (player instanceof DisplayBlockInteract interact) {
                interact.openDisplayBlockScreen(dbe);
                return ActionResult.success(world.isClient);
            } else {
                return ActionResult.CONSUME;
            }
        } else {
            return ActionResult.PASS;
        }
    }

    public static boolean canPlace(@NotNull PlayerEntity player) {
        return player.hasPermissionLevel(2);
    }

    public static boolean canUse(@NotNull PlayerEntity player) {
        return player.hasPermissionLevel(2);
    }

    // @Override // Old
    // protected MapCodec<? extends BlockWithEntity> getCodec() {
    //     return CODEC;
    // }

}
