package com.shulkerrainmod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.ShulkerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import org.joml.Vector3f;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ShulkerRainMod implements ModInitializer {
	private static boolean globalActive = false;
	private static final Map<UUID, PlayerShulkerRainData> playerDataMap = new ConcurrentHashMap<>();
	private static final Random random = Random.create();

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(CommandManager.literal("start")
					.executes(context -> {
						ServerCommandSource source = context.getSource();
						PlayerEntity player = source.getPlayer();
						if (player == null) {
							source.sendError(Text.literal("有人才能玩，兄弟awa"));
							return 0;
						}

						if (!globalActive) {
							globalActive = true;
							for (ServerPlayerEntity onlinePlayer : source.getServer().getPlayerManager().getPlayerList()) {
								if (!playerDataMap.containsKey(onlinePlayer.getUuid())) {
									playerDataMap.put(onlinePlayer.getUuid(), new PlayerShulkerRainData(onlinePlayer));
								}
								PlayerShulkerRainData data = playerDataMap.get(onlinePlayer.getUuid());
								if (!data.isActive()) {
									data.start();
								}
							}
							source.sendFeedback(() -> Text.literal("游戏开始！记得给B站@浓硫酸酸不酸一键三连哦~~"), false);
						} else {
							source.sendFeedback(() -> Text.literal("游戏已经开始"), false);
						}
						return 1;
					}));

			dispatcher.register(CommandManager.literal("stop")
					.executes(context -> {
						ServerCommandSource source = context.getSource();
						if (globalActive) {
							globalActive = false;
							for (PlayerShulkerRainData data : playerDataMap.values()) {
								data.stop();
							}
							playerDataMap.clear();
							source.sendFeedback(() -> Text.literal("游戏停止"), false);
						} else {
							source.sendFeedback(() -> Text.literal("已经停止了！"), false);
						}
						return 1;
					}));
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (!globalActive) return;

			Iterator<Map.Entry<UUID, PlayerShulkerRainData>> iterator = playerDataMap.entrySet().iterator();
			while (iterator.hasNext()) {
				Map.Entry<UUID, PlayerShulkerRainData> entry = iterator.next();
				PlayerShulkerRainData data = entry.getValue();
				if (data.isActive()) {
					data.tick();
				} else {
					iterator.remove();
				}
			}

			for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
				if (!playerDataMap.containsKey(player.getUuid())) {
					playerDataMap.put(player.getUuid(), new PlayerShulkerRainData(player));
					playerDataMap.get(player.getUuid()).start();
				}
			}
		});

		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (!world.isClient() && player instanceof ServerPlayerEntity) {
				BlockPos pos = hitResult.getBlockPos();
				BlockState blockState = world.getBlockState(pos);

				if (blockState.getBlock() instanceof ShulkerBoxBlock) {
					StatusEffectInstance glowingEffect = new StatusEffectInstance(
							StatusEffects.GLOWING,
							60,
							0,
							false,
							true
					);
					player.addStatusEffect(glowingEffect);

					if (world instanceof ServerWorld serverWorld) {
						Vector3f particleColor = new Vector3f(0.0f, 1.0f, 0.2f);
						DustParticleEffect effect = new DustParticleEffect(particleColor, 1.0f);
						serverWorld.spawnParticles(
								(ServerPlayerEntity) player,
								effect,
								true,
								player.getX(),
								player.getY() + 1.0,
								player.getZ(),
								10,
								0.5, 0.5, 0.5,
								0.1
						);
					}
				}
			}
			return ActionResult.PASS;
		});
	}

	static class PlayerShulkerRainData {
		private final PlayerEntity player;
		private int ticksActive = 0;
		private boolean active = false;
		private final List<FallingShulker> fallingShulkers = new ArrayList<>();

		public PlayerShulkerRainData(PlayerEntity player) {
			this.player = player;
		}

		public void start() {
			active = true;
			ticksActive = 0;
			generateInitialShulkers();
		}

		public void stop() {
			active = false;
			for (FallingShulker shulker : fallingShulkers) {
				shulker.cleanup();
			}
			fallingShulkers.clear();
		}

		public boolean isActive() {
			return active;
		}

		public void tick() {
			if (!active || player == null || player.isRemoved()) {
				stop();
				return;
			}

			ticksActive++;

			if (ticksActive % 300 == 0) {
				generatePeriodicShulkers();
			}

			Iterator<FallingShulker> iterator = fallingShulkers.iterator();
			while (iterator.hasNext()) {
				FallingShulker shulker = iterator.next();
				if (!shulker.update()) {
					iterator.remove();
				}
			}

			if (ticksActive > 12000) {
				stop();
			}
		}

		private void generateInitialShulkers() {
			World world = player.getWorld();
			BlockPos playerPos = player.getBlockPos();

			for (int i = 0; i < 32; i++) {
				int offsetX = random.nextInt(512) - 256;
				int offsetZ = random.nextInt(512) - 256;
				int y = 100 + random.nextInt(101);

				BlockPos spawnPos = playerPos.add(offsetX, y, offsetZ);
				spawnFallingShulker(world, spawnPos);
			}
		}

		private void generatePeriodicShulkers() {
			World world = player.getWorld();
			BlockPos playerPos = player.getBlockPos();

			for (int i = 0; i < 16; i++) {
				int offsetX = random.nextInt(256) - 128;
				int offsetZ = random.nextInt(256) - 128;
				int y = 100 + random.nextInt(101);

				BlockPos spawnPos = playerPos.add(offsetX, y, offsetZ);
				spawnFallingShulker(world, spawnPos);
			}
		}

		private void spawnFallingShulker(World world, BlockPos spawnPos) {
			DyeColor color = DyeColor.values()[random.nextInt(DyeColor.values().length)];
			fallingShulkers.add(new FallingShulker(world, spawnPos, color));
		}
	}

	static class FallingShulker {
		private final World world;
		private final ShulkerEntity shulkerEntity;
		private final DyeColor color;
		private final Vector3f particleColor;
		private boolean isLanded = false;
		private int ticksFalling = 0;

		public FallingShulker(World world, BlockPos startPos, DyeColor color) {
			this.world = world;
			this.color = color;

			float[] rgb = color.getColorComponents();
			this.particleColor = new Vector3f(rgb[0], rgb[1], rgb[2]);

			this.shulkerEntity = new ShulkerEntity(EntityType.SHULKER, world);
			this.shulkerEntity.setPosition(startPos.getX() + 0.5, startPos.getY(), startPos.getZ() + 0.5);

			this.shulkerEntity.setAiDisabled(true);
			this.shulkerEntity.setInvulnerable(true);
			this.shulkerEntity.setVariant(Optional.of(color));
			this.shulkerEntity.setNoGravity(true);
			this.shulkerEntity.setCustomName(Text.literal("falling_shulker"));
			this.shulkerEntity.setCustomNameVisible(false);

			addGlowingEffect();
			world.spawnEntity(this.shulkerEntity);
		}

		private void addGlowingEffect() {
			StatusEffectInstance glowingEffect = new StatusEffectInstance(
					StatusEffects.GLOWING,
					1000000,
					0,
					false,
					true
			);
			this.shulkerEntity.addStatusEffect(glowingEffect);
		}

		public boolean update() {
			if (isLanded || !shulkerEntity.isAlive()) {
				return false;
			}

			ticksFalling++;

			if (ticksFalling % 5 == 0) {
				double newX = shulkerEntity.getX();
				double newY = shulkerEntity.getY() - 1.0;
				double newZ = shulkerEntity.getZ();
				shulkerEntity.teleport(newX, newY, newZ);
			}

			BlockPos currentPos = shulkerEntity.getBlockPos();
			BlockPos downPos = currentPos.down();

			if (world.getBlockState(downPos).isSolid()) {
				land();
				return false;
			}

			createParticleTrail();
			return true;
		}

		private void createParticleTrail() {
			if (world instanceof ServerWorld serverWorld) {
				DustParticleEffect effect = new DustParticleEffect(particleColor, 1.0f);
				serverWorld.spawnParticles(
						effect,
						shulkerEntity.getX(),
						shulkerEntity.getY(),
						shulkerEntity.getZ(),
						3,
						0.1, 0.1, 0.1,
						0.02
				);
			}
		}

		private void land() {
			BlockPos landPos = shulkerEntity.getBlockPos();
			shulkerEntity.remove(Entity.RemovalReason.DISCARDED);

			BlockState shulkerState = getShulkerBlockState(color);
			if (world.setBlockState(landPos, shulkerState)) {
				if (world.getBlockEntity(landPos) instanceof ShulkerBoxBlockEntity shulkerEntityBE) {
					fillShulkerBox(shulkerEntityBE);
				}
			}
			isLanded = true;
		}

		public void cleanup() {
			if (shulkerEntity.isAlive() && !shulkerEntity.isRemoved()) {
				shulkerEntity.remove(Entity.RemovalReason.DISCARDED);
			}
		}

		private BlockState getShulkerBlockState(DyeColor color) {
			return switch (color) {
				case WHITE -> Blocks.WHITE_SHULKER_BOX.getDefaultState();
				case ORANGE -> Blocks.ORANGE_SHULKER_BOX.getDefaultState();
				case MAGENTA -> Blocks.MAGENTA_SHULKER_BOX.getDefaultState();
				case LIGHT_BLUE -> Blocks.LIGHT_BLUE_SHULKER_BOX.getDefaultState();
				case YELLOW -> Blocks.YELLOW_SHULKER_BOX.getDefaultState();
				case LIME -> Blocks.LIME_SHULKER_BOX.getDefaultState();
				case PINK -> Blocks.PINK_SHULKER_BOX.getDefaultState();
				case GRAY -> Blocks.GRAY_SHULKER_BOX.getDefaultState();
				case LIGHT_GRAY -> Blocks.LIGHT_GRAY_SHULKER_BOX.getDefaultState();
				case CYAN -> Blocks.CYAN_SHULKER_BOX.getDefaultState();
				case PURPLE -> Blocks.PURPLE_SHULKER_BOX.getDefaultState();
				case BLUE -> Blocks.BLUE_SHULKER_BOX.getDefaultState();
				case BROWN -> Blocks.BROWN_SHULKER_BOX.getDefaultState();
				case GREEN -> Blocks.GREEN_SHULKER_BOX.getDefaultState();
				case RED -> Blocks.RED_SHULKER_BOX.getDefaultState();
				case BLACK -> Blocks.BLACK_SHULKER_BOX.getDefaultState();
			};
		}

		private void fillShulkerBox(ShulkerBoxBlockEntity shulker) {
			for (int i = 0; i < 27; i++) {
				if (random.nextFloat() < 0.7) {
					ItemStack contentStack = createRandomItemForColor(color);
					if (!contentStack.isEmpty()) {
						shulker.setStack(i, contentStack);
					}
				}
			}
		}

		private ItemStack createRandomItemForColor(DyeColor color) {
			List<Item> possibleItems = getPossibleItemsForColor(color);
			if (possibleItems.isEmpty()) {
				return ItemStack.EMPTY;
			}

			Item item = possibleItems.get(random.nextInt(possibleItems.size()));
			ItemStack stack = new ItemStack(item);

			if (item.getMaxCount() > 1) {
				stack.setCount(1 + random.nextInt(Math.min(8, item.getMaxCount())));
			}

			if (item instanceof ToolItem || item instanceof ArmorItem || item instanceof SwordItem) {
				addRandomEnchantments(stack);
			}

			return stack;
		}

		private void addRandomEnchantments(ItemStack stack) {
			int enchantCount = random.nextInt(3) + 1;
			Map<Enchantment, Integer> enchantments = new HashMap<>();

			List<Enchantment> allEnchantments = new ArrayList<>();
			for (Enchantment enchantment : Registries.ENCHANTMENT) {
				if (enchantment.isAcceptableItem(stack)) {
					allEnchantments.add(enchantment);
				}
			}

			if (allEnchantments.isEmpty()) return;

			Collections.shuffle(allEnchantments);

			for (int i = 0; i < Math.min(enchantCount, allEnchantments.size()); i++) {
				Enchantment enchant = allEnchantments.get(i);
				int maxLevel = enchant.getMaxLevel();
				int level = random.nextInt(maxLevel) + 1;
				enchantments.put(enchant, level);
			}

			EnchantmentHelper.set(enchantments, stack);
		}

		private List<Item> getPossibleItemsForColor(DyeColor color) {
			return switch (color) {
				case WHITE -> Arrays.asList(Items.PAPER, Items.BONE_MEAL, Items.NAME_TAG, Items.BIRCH_DOOR, Items.BIRCH_SAPLING, Items.BONE_BLOCK, Items.END_ROD, Items.WHITE_BED, Items.DIORITE, Items.POLISHED_DIORITE, Items.BIRCH_STAIRS, Items.BIRCH_SLAB, Items.QUARTZ_BLOCK, Items.QUARTZ_STAIRS, Items.QUARTZ_SLAB, Items.SNOWBALL, Items.POWDER_SNOW_BUCKET, Items.WHITE_STAINED_GLASS, Items.WHITE_STAINED_GLASS_PANE, Items.EGG, Items.DIORITE_STAIRS, Items.DIORITE_SLAB, Items.POLISHED_DIORITE_STAIRS, Items.POLISHED_DIORITE_SLAB);
				case ORANGE -> Arrays.asList(Items.POTATO, Items.BAKED_POTATO, Items.COOKIE, Items.LEATHER, Items.LAVA_BUCKET, Items.SPYGLASS, Items.CAMPFIRE, Items.CARROT, Items.TROPICAL_FISH, Items.RED_SANDSTONE_STAIRS, Items.RED_SANDSTONE, Items.PUMPKIN_PIE, Items.MAGMA_BLOCK, Items.ORANGE_DYE, Items.ORANGE_BED, Items.ORANGE_WOOL, Items.ORANGE_CARPET, Items.ORANGE_STAINED_GLASS, Items.ORANGE_STAINED_GLASS_PANE, Items.RED_SANDSTONE_STAIRS);
				case MAGENTA -> Arrays.asList(Items.MAGENTA_WOOL, Items.MAGENTA_CARPET, Items.CHORUS_FRUIT);
				case LIGHT_BLUE -> Arrays.asList(Items.LIGHT_BLUE_WOOL, Items.LIGHT_BLUE_CARPET, Items.ICE);
				case YELLOW -> Arrays.asList(Items.SCAFFOLDING, Items.GOLDEN_HELMET, Items.GOLDEN_CHESTPLATE, Items.BLAZE_ROD, Items.BLAZE_POWDER, Items.TUBE_CORAL, Items.GOLDEN_HELMET, Items.GOLDEN_AXE, Items.GOLDEN_CHESTPLATE, Items.GOLDEN_LEGGINGS, Items.GOLDEN_BOOTS, Items.LIGHT, Items.ENCHANTED_GOLDEN_APPLE, Items.GOLDEN_APPLE, Items.GOLDEN_CARROT, Items.GLOWSTONE, Items.GLOWSTONE_DUST, Items.YELLOW_CARPET, Items.GOLD_BLOCK, Items.GOLD_INGOT, Items.HONEY_BOTTLE, Items.HONEY_BLOCK, Items.GOLDEN_AXE, Items.YELLOW_STAINED_GLASS, Items.YELLOW_CONCRETE, Items.YELLOW_STAINED_GLASS_PANE, Items.SAND, Items.RAW_GOLD);
				case LIME -> Arrays.asList(Items.LIME_WOOL, Items.LIME_CARPET, Items.SLIME_BALL);
				case PINK -> Arrays.asList(Items.PINK_BANNER, Items.PINK_DYE, Items.PINK_CONCRETE, Items.PINK_WOOL, Items.PINK_STAINED_GLASS, Items.PINK_STAINED_GLASS_PANE, Items.PINK_CANDLE, Items.CHERRY_SAPLING, Items.CHERRY_PLANKS, Items.CHERRY_FENCE, Items.CHERRY_FENCE_GATE, Items.CHICKEN, Items.GLOW_ITEM_FRAME, Items.COD);
				case GRAY -> Arrays.asList(Items.ENDER_PEARL, Items.SOUL_CAMPFIRE, Items.DEEPSLATE_EMERALD_ORE, Items.DEEPSLATE_DIAMOND_ORE, Items.DEEPSLATE_IRON_ORE, Items.DEEPSLATE_COPPER_ORE, Items.MUSIC_DISC_13, Items.GRAY_WOOL, Items.DEEPSLATE_BRICKS, Items.GRAY_CARPET, Items.POLISHED_DEEPSLATE, Items.DEEPSLATE, Items.DEEPSLATE_BRICK_STAIRS, Items.POLISHED_DEEPSLATE_STAIRS, Items.DEEPSLATE_BRICK_SLAB, Items.POLISHED_DEEPSLATE_SLAB);
				case LIGHT_GRAY -> Arrays.asList(Items.DEEPSLATE_EMERALD_ORE, Items.DEEPSLATE_DIAMOND_ORE, Items.DEEPSLATE_IRON_ORE, Items.DEEPSLATE_COPPER_ORE, Items.MUSIC_DISC_13, Items.SCULK_SENSOR, Items.CHAIN, Items.POLISHED_BLACKSTONE_BRICKS, Items.CALIBRATED_SCULK_SENSOR, Items.SCULK, Items.SCULK_CATALYST, Items.LIGHT_GRAY_STAINED_GLASS, Items.LIGHT_GRAY_BED, Items.LIGHT_GRAY_WOOL, Items.LIGHT_GRAY_STAINED_GLASS_PANE, Items.ARROW);
				case CYAN -> Arrays.asList(Items.TWISTING_VINES, Items.MELON_SLICE, Items.TURTLE_EGG, Items.CYAN_STAINED_GLASS, Items.CYAN_STAINED_GLASS_PANE, Items.CYAN_WOOL, Items.CYAN_CARPET, Items.CARROT_ON_A_STICK, Items.WARPED_FUNGUS_ON_A_STICK, Items.WARPED_PLANKS, Items.WARPED_DOOR, Items.WARPED_SIGN, Items.DIAMOND, Items.DIAMOND_HELMET, Items.DIAMOND_PICKAXE, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS, Items.DIAMOND_SWORD, Items.ENCHANTING_TABLE, Items.DIAMOND_HOE, Items.DIAMOND_SHOVEL);
				case PURPLE -> Arrays.asList(Items.PURPLE_BANNER, Items.PURPLE_DYE, Items.PURPLE_CONCRETE, Items.PURPLE_WOOL, Items.PURPLE_STAINED_GLASS, Items.PURPLE_STAINED_GLASS_PANE, Items.PURPLE_CANDLE, Items.SHULKER_BOX, Items.SHULKER_SHELL, Items.AMETHYST_SHARD, Items.AMETHYST_CLUSTER, Items.CHORUS_FRUIT);
				case BLUE -> Arrays.asList(Items.BLUE_BANNER, Items.BLUE_DYE, Items.BLUE_CONCRETE, Items.BLUE_WOOL, Items.BLUE_STAINED_GLASS, Items.BLUE_STAINED_GLASS_PANE, Items.BLUE_CANDLE);
				case BROWN -> Arrays.asList(Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS, Items.POTATO, Items.BAKED_POTATO, Items.COOKIE, Items.LEATHER, Items.BROWN_WOOL, Items.BROWN_BED, Items.BROWN_CONCRETE, Items.BROWN_CARPET, Items.BROWN_STAINED_GLASS, Items.BROWN_STAINED_GLASS_PANE, Items.CHEST, Items.WOODEN_AXE, Items.WOODEN_PICKAXE, Items.WOODEN_SWORD, Items.WOODEN_SHOVEL, Items.WOODEN_HOE);
				case GREEN -> Arrays.asList(Items.EXPERIENCE_BOTTLE, Items.TURTLE_HELMET, Items.GREEN_CONCRETE, Items.GREEN_CONCRETE_POWDER, Items.GREEN_BED, Items.EMERALD, Items.SUGAR_CANE, Items.GRASS, Items.TALL_GRASS, Items.SLIME_BLOCK, Items.SLIME_BALL, Items.GREEN_STAINED_GLASS, Items.GREEN_STAINED_GLASS_PANE, Items.GREEN_BANNER, Items.GREEN_DYE, Items.GREEN_WOOL, Items.GREEN_CANDLE, Items.GREEN_SHULKER_BOX, Items.KELP, Items.SEAGRASS);
				case RED -> Arrays.asList(Items.SHIELD, Items.BOWL, Items.TNT_MINECART, Items.TNT, Items.FIREWORK_ROCKET, Items.RED_WOOL, Items.RED_CARPET, Items.RED_STAINED_GLASS_PANE, Items.RED_STAINED_GLASS);
				case BLACK -> Arrays.asList(Items.BLACK_CANDLE, Items.COAL, Items.CHARCOAL, Items.DARK_OAK_WOOD, Items.DARK_OAK_LOG, Items.BLACK_WOOL, Items.NETHERITE_INGOT, Items.DARK_OAK_PLANKS, Items.DARK_OAK_DOOR, Items.DARK_OAK_FENCE, Items.DARK_OAK_TRAPDOOR, Items.ENDER_CHEST, Items.BLACK_CARPET, Items.BLACK_STAINED_GLASS, Items.BLACK_WOOL, Items.BLACK_STAINED_GLASS_PANE, Items.NETHERITE_PICKAXE, Items.NETHERITE_SWORD, Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS, Items.NETHERITE_AXE, Items.NETHERITE_SHOVEL, Items.NETHERITE_HOE);
				default -> Collections.singletonList(Items.STONE);
			};
		}
	}
}