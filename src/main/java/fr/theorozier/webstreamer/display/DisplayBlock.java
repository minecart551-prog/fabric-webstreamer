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
import net.minecraft.block.ShapeContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// import com.mojang.serialization.MapCodec; // Old

public class DisplayBlock extends BlockWithEntity {

    // public static final MapCodec<DisplayBlock> CODEC = DisplayBlock.createCodec(DisplayBlock::new); // Old


    public static final DirectionProperty PROP_FACING = Properties.HORIZONTAL_FACING;
    // public static final EnumProperty<BlockFace> PROP_ATTACHMENT = EnumProperty.of("attachment", BlockFace.class, BlockFace.WALL, BlockFace.FLOOR, BlockFace.CEILING); // Old
    public static final EnumProperty<Direction> PROP_ATTACHMENT = EnumProperty.of("attachment", Direction.class, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN);

    protected static final VoxelShape SHAPE_NORTH = VoxelShapes.cuboid(0, 0, 0.9, 1, 1, 1);
    protected static final VoxelShape SHAPE_SOUTH = VoxelShapes.cuboid(0, 0, 0, 1, 1, 0.1);
    protected static final VoxelShape SHAPE_WEST = VoxelShapes.cuboid(0.9, 0, 0, 1, 1, 1);
    protected static final VoxelShape SHAPE_EAST = VoxelShapes.cuboid(0, 0, 0, 0.1, 1, 1);
    protected static final VoxelShape SHAPE_FLOOR = VoxelShapes.cuboid(0, 0, 0, 1, 0.1, 1);
    protected static final VoxelShape SHAPE_CEILING = VoxelShapes.cuboid(0, 0.9, 0, 1, 1.0, 1);

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
        // Use MODEL so block state model is rendered (TV frame/stand) and block entity overlay draws video on top.
        return BlockRenderType.MODEL;
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
        // Web display hitbox stays fixed in position and size regardless of GUI offsets/scale.
        // TV and BigTV blocks still use their own dynamic outline logic in their subclasses.
        return switch (state.get(PROP_ATTACHMENT)) {
            case NORTH, SOUTH, EAST, WEST -> switch (state.get(PROP_FACING)) {
                case NORTH -> SHAPE_NORTH;
                case SOUTH -> SHAPE_SOUTH;
                case EAST -> SHAPE_EAST;
                case WEST -> SHAPE_WEST;
                default -> VoxelShapes.fullCube();
            };
            case UP -> SHAPE_FLOOR;
            case DOWN -> SHAPE_CEILING;
        };
    }

    protected VoxelShape createDynamicOutlineShape(BlockState state, DisplayBlockEntity displayEntity) {
        float width = displayEntity.getWidth();
        float height = displayEntity.getHeight();
        double offsetX = displayEntity.getOffsetX();
        double offsetY = displayEntity.getOffsetY();
        double offsetZ = displayEntity.getOffsetZ();

        float halfW = width / 2.0f;
        float halfH = height / 2.0f;
        double thickness = 0.1;

        Direction attachment = state.get(PROP_ATTACHMENT);
        Direction facing = state.get(PROP_FACING);

        double minX, maxX, minY, maxY, minZ, maxZ;
        if (attachment == Direction.UP || attachment == Direction.DOWN) {
            // Keep TV as vertical surface even when attached to floor/ceiling.
            // Floor/ceiling attachments are rendered outside the anchor block, so depth uses 1.5/-0.5 base coordinates.
            double verticalOffset = (attachment == Direction.UP ? offsetY : -offsetY);
            minY = 0.5 - halfH + verticalOffset;
            maxY = 0.5 + halfH + verticalOffset;

            switch (facing) {
                case NORTH -> {
                    minX = 0.5 - halfW + offsetX;
                    maxX = 0.5 + halfW + offsetX;
                    if (attachment == Direction.UP) {
                        minZ = 1.5 - offsetZ - thickness / 2.0;
                        maxZ = 1.5 - offsetZ + thickness / 2.0;
                    } else {
                        minZ = 1.5 + offsetZ - thickness / 2.0;
                        maxZ = 1.5 + offsetZ + thickness / 2.0;
                    }
                }
                case SOUTH -> {
                    minX = 0.5 - halfW - offsetX;
                    maxX = 0.5 + halfW - offsetX;
                    if (attachment == Direction.UP) {
                        minZ = -0.5 + offsetZ - thickness / 2.0;
                        maxZ = -0.5 + offsetZ + thickness / 2.0;
                    } else {
                        minZ = -0.5 - offsetZ - thickness / 2.0;
                        maxZ = -0.5 - offsetZ + thickness / 2.0;
                    }
                }
                case EAST -> {
                    minZ = 0.5 - halfW + offsetX;
                    maxZ = 0.5 + halfW + offsetX;
                    if (attachment == Direction.UP) {
                        minX = -0.5 + offsetZ - thickness / 2.0;
                        maxX = -0.5 + offsetZ + thickness / 2.0;
                    } else {
                        minX = -0.5 - offsetZ - thickness / 2.0;
                        maxX = -0.5 - offsetZ + thickness / 2.0;
                    }
                }
                case WEST -> {
                    minZ = 0.5 - halfW - offsetX;
                    maxZ = 0.5 + halfW - offsetX;
                    if (attachment == Direction.UP) {
                        minX = 1.5 - offsetZ - thickness / 2.0;
                        maxX = 1.5 - offsetZ + thickness / 2.0;
                    } else {
                        minX = 1.5 + offsetZ - thickness / 2.0;
                        maxX = 1.5 + offsetZ + thickness / 2.0;
                    }
                }
                default -> {
                    minX = 0.5 - halfW;
                    maxX = 0.5 + halfW;
                    minZ = 0.0;
                    maxZ = 0.0 + thickness;
                }
            }
        } else {
            // Wall-mounted shape
            minY = 0.5 - halfH + offsetY;
            maxY = 0.5 + halfH + offsetY;
            switch (facing) {
                case NORTH -> {
                    minX = 0.5 - halfW + offsetX;
                    maxX = 0.5 + halfW + offsetX;
                    minZ = 1.5 - offsetZ - thickness / 2.0;
                    maxZ = 1.5 - offsetZ + thickness / 2.0;
                }
                case SOUTH -> {
                    minX = 0.5 - halfW - offsetX;
                    maxX = 0.5 + halfW - offsetX;
                    minZ = -0.5 + offsetZ - thickness / 2.0;
                    maxZ = -0.5 + offsetZ + thickness / 2.0;
                }
                case EAST -> {
                    minX = -0.5 + offsetZ - thickness / 2.0;
                    maxX = -0.5 + offsetZ + thickness / 2.0;
                    minZ = 0.5 - halfW + offsetX;
                    maxZ = 0.5 + halfW + offsetX;
                }
                case WEST -> {
                    minX = 1.5 - offsetZ - thickness / 2.0;
                    maxX = 1.5 - offsetZ + thickness / 2.0;
                    minZ = 0.5 - halfW - offsetX;
                    maxZ = 0.5 + halfW - offsetX;
                }
                default -> {
                    minX = 0.5 - halfW;
                    maxX = 0.5 + halfW;
                    minZ = 0.0;
                    maxZ = 0.0 + thickness;
                }
            }
        }

        return VoxelShapes.cuboid(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return getOutlineShape(state, world, pos, context);
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof DisplayBlockEntity dbe) {
            // If locked to creative and player is not creative, deny access
            if (dbe.requiresOp() && !player.isCreative()) {
                return ActionResult.PASS;
            }
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
