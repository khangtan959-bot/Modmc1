package com.nbv.islamicmod;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.*;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.*;

public class ModEvents {

    private static final Map<UUID, Boolean> hasShahada = new HashMap<>();
    private static final Map<UUID, Long> lastHalalPhrase = new HashMap<>();
    private static final Map<UUID, Integer> wuduCount = new HashMap<>();
    private static final Map<UUID, Boolean> isPraying = new HashMap<>();
    private static final Map<UUID, Integer> prayerProgress = new HashMap<>();
    
    private static final Map<UUID, UUID> combatTarget = new HashMap<>(); 
    private static final Set<UUID> hostileMobs = new HashSet<>(); 

    private static final Map<UUID, Integer> inventorySnapshot = new HashMap<>(); 
    private static final Map<UUID, Boolean> isLookingAtLootChest = new HashMap<>();

    private static final int[] PRAYER_TIMES = {0, 6000, 9000, 12000, 18000};
    private boolean prayerSessionActive = false;
    private int prayerSessionTimer = 0;

    private void punishPlayer(Player player, String reason) {
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.sendSystemMessage(Component.literal("HARAM! " + reason).withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
            serverPlayer.setGameMode(GameType.SURVIVAL);
            
            ServerLevel world = serverPlayer.serverLevel();
            BlockPos pos = serverPlayer.blockPosition();
            LightningBolt lightning = EntityType.LIGHTNING_BOLT.create(world);
            if (lightning != null) {
                lightning.moveTo(pos.getX(), pos.getY(), pos.getZ());
                world.addFreshEntity(lightning);
            }
            serverPlayer.setHealth(0);
            serverPlayer.kill(); 
        }
    }

    @SubscribeEvent
    public void onConsumeItem(LivingEntityUseItemEvent.Finish event) {
        if (event.getEntity() instanceof Player player) {
            ItemStack item = event.getItem();
            if (item.getItem() == Items.POTION || item.getItem() == Items.SPLASH_POTION || item.getItem() == Items.LINGERING_POTION) {
                punishPlayer(player, "Không được sử dụng chất kích thích (Potion)!");
            }
        }
    }

    @SubscribeEvent
    public void onVillagerHurt(LivingDeathEvent event) {
        if (event.getEntity() instanceof Villager && event.getSource().getEntity() instanceof Player player) {
            punishPlayer(player, "Bạn đã sát hại một người dân vô tội!");
        }
    }

    @SubscribeEvent
    public void onInteractVillager(PlayerInteractEvent.EntityInteract event) {
        if (event.getTarget() instanceof Villager) {
            ItemStack item = event.getItemStack();
            if (item.getItem() == Items.OAK_BOAT || item.getItem() == Items.MINECART || item.getItem().toString().contains("boat")) {
                event.setCanceled(true);
                punishPlayer(event.getEntity(), "Không được bắt nhốt dân làng!");
            }
        }
    }

    @SubscribeEvent
    public void onMobAttackPlayer(LivingDamageEvent event) {
        if (event.getEntity() instanceof Player player && event.getSource().getEntity() instanceof Monster monster) {
            hostileMobs.add(monster.getUUID());
            combatTarget.put(player.getUUID(), monster.getUUID());
            player.sendSystemMessage(Component.literal("CHIẾN TRANH BẮT ĐẦU! Không được chạy trốn!").withStyle(ChatFormatting.DARK_RED));
        }
    }

    @SubscribeEvent
    public void onMobDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof Monster monster) {
            hostileMobs.remove(monster.getUUID());
            combatTarget.entrySet().removeIf(entry -> entry.getValue().equals(monster.getUUID()));
        }
    }

    @SubscribeEvent
    public void onPlayerMoveCheck(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide) return;
        
        Player player = event.player;
        UUID targetMobId = combatTarget.get(player.getUUID());

        if (targetMobId != null && player.level() instanceof ServerLevel serverLevel) {
            Entity mob = serverLevel.getEntity(targetMobId);
            if (mob instanceof LivingEntity livingMob && livingMob.isAlive()) {
                if (player.distanceTo(livingMob) > 12) {
                    punishPlayer(player, "Bạn đã bỏ chạy khỏi trận chiến!");
                    combatTarget.remove(player.getUUID());
                }
            } else {
                combatTarget.remove(player.getUUID());
            }
        }
    }

    @SubscribeEvent
    public void onOpenContainer(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide) return;
        BlockEntity be = event.getLevel().getBlockEntity(event.getPos());
        
        if (be instanceof RandomizableContainerBlockEntity chest) {
            Player p = event.getEntity();
            isLookingAtLootChest.put(p.getUUID(), true);
            int totalItems = p.getInventory().items.stream().mapToInt(ItemStack::getCount).sum();
            inventorySnapshot.put(p.getUUID(), totalItems);
            p.sendSystemMessage(Component.literal("CẢNH BÁO: Tài sản công cộng. KHÔNG ĐƯỢC LẤY!").withStyle(ChatFormatting.YELLOW));
        }
    }

    @SubscribeEvent
    public void onCloseContainer(PlayerContainerEvent.Close event) {
        Player p = event.getEntity();
        if (isLookingAtLootChest.getOrDefault(p.getUUID(), false)) {
            int oldTotal = inventorySnapshot.getOrDefault(p.getUUID(), 0);
            int newTotal = p.getInventory().items.stream().mapToInt(ItemStack::getCount).sum();
            if (newTotal > oldTotal) {
                punishPlayer(p, "Bạn đã ăn cắp đồ!");
            }
            isLookingAtLootChest.remove(p.getUUID());
            inventorySnapshot.remove(p.getUUID());
        }
    }

    @SubscribeEvent
    public void onChat(ServerChatEvent event) {
        String msg = event.getMessage().getString().toLowerCase();
        ServerPlayer player = event.getPlayer();

        if (!hasShahada.getOrDefault(player.getUUID(), false)) {
            if (msg.contains("không có chúa trời nào ngoài allah") && msg.contains("muhammed là sứ giả")) {
                hasShahada.put(player.getUUID(), true);
                player.sendSystemMessage(Component.literal("Bạn đã tuyên thệ Shahada!").withStyle(ChatFormatting.GREEN));
            }
        }

        if (msg.contains("bismillah") && msg.contains("allahu akbar")) {
            lastHalalPhrase.put(player.getUUID(), System.currentTimeMillis());
            player.sendSystemMessage(Component.literal("Halal kích hoạt! (10s)").withStyle(ChatFormatting.GOLD));
        }
    }

    @SubscribeEvent
    public void onAttackAnimal(LivingDamageEvent event) {
        if (!(event.getSource().getEntity() instanceof Player player)) return;
        LivingEntity target = event.getEntity();

        if (target.getType() == EntityType.PIG) {
            event.setCanceled(true);
            punishPlayer(player, "Không được đụng vào heo!");
            return;
        }

        if (target instanceof Animal animal) {
            if (animal.isBaby()) {
                event.setCanceled(true);
                punishPlayer(player, "Không được giết con non!");
                return;
            }

            long lastTime = lastHalalPhrase.getOrDefault(player.getUUID(), 0L);
            if (System.currentTimeMillis() - lastTime > 10000) {
                event.setCanceled(true);
                player.sendSystemMessage(Component.literal("Thiếu lời thề Halal!").withStyle(ChatFormatting.RED));
                return;
            }

            if (event.getAmount() < target.getHealth()) {
                event.setCanceled(true);
                punishPlayer(player, "Không giết được trong 1 hit!");
            }
        }
    }

    @SubscribeEvent
    public void onPlayerAttackMonsterInit(LivingAttackEvent event) {
        if (event.getSource().getEntity() instanceof Player player && event.getEntity() instanceof Monster monster) {
            if (!hostileMobs.contains(monster.getUUID())) {
                event.setCanceled(true);
                player.sendSystemMessage(Component.literal("Không được tấn công trước!").withStyle(ChatFormatting.RED));
            }
        }
    }

    @SubscribeEvent
    public void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.START || event.level.isClientSide) return;
        long time = event.level.getDayTime() % 24000;
        for (int pTime : PRAYER_TIMES) {
            if (Math.abs(time - pTime) < 10 && !prayerSessionActive) {
                startPrayerSession(event.level);
            }
        }
        if (prayerSessionActive) {
            prayerSessionTimer++;
            if (prayerSessionTimer > 1200) endPrayerSession(event.level);
        }
    }

    private void startPrayerSession(Level level) {
        prayerSessionActive = true;
        prayerSessionTimer = 0;
        level.players().forEach(p -> {
            wuduCount.put(p.getUUID(), 0);
            isPraying.put(p.getUUID(), false);
            prayerProgress.put(p.getUUID(), 0);
            p.sendSystemMessage(Component.literal("=== GIỜ CẦU NGUYỆN ===").withStyle(ChatFormatting.GOLD));
        });
    }

    private void endPrayerSession(Level level) {
        prayerSessionActive = false;
        level.players().forEach(p -> {
            if (!isPraying.getOrDefault(p.getUUID(), false)) punishPlayer(p, "Bỏ lỡ giờ cầu nguyện!");
        });
    }

    @SubscribeEvent
    public void onInteractWater(PlayerInteractEvent.RightClickBlock event) {
        if (!prayerSessionActive) return;
        if (event.getLevel().getBlockState(event.getPos()).getFluidState().isSource()) {
             Player p = event.getEntity();
             int count = wuduCount.getOrDefault(p.getUUID(), 0) + 1;
             wuduCount.put(p.getUUID(), count);
             p.displayClientMessage(Component.literal("Rửa mình: " + count + "/3"), true);
             if (count == 3) {
                 p.sendSystemMessage(Component.literal("Wudu hoàn tất! Hãy quay hướng Bắc và ngồi xuống.").withStyle(ChatFormatting.AQUA));
             }
        }
    }

    @SubscribeEvent
    public void onPlayerPrayTick(TickEvent.PlayerTickEvent event) {
        if (!prayerSessionActive || event.phase != TickEvent.Phase.START) return;
        Player p = event.player;
        if (wuduCount.getOrDefault(p.getUUID(), 0) < 3) return;

        float yaw = p.getYRot(); 
        while (yaw <= -180) yaw += 360;
        while (yaw > 180) yaw -= 360;
        boolean facingNorth = (yaw >= 135 || yaw <= -135);

        if (p.isCrouching() && facingNorth) {
            int progress = prayerProgress.getOrDefault(p.getUUID(), 0) + 1;
            prayerProgress.put(p.getUUID(), progress);
            
            if (progress % 20 == 0) {
                p.displayClientMessage(Component.literal("Đang cầu nguyện... " + (progress / 20) + "/10s"), true);
            }

            if (progress >= 200) { 
                isPraying.put(p.getUUID(), true);
                if (progress == 200) {
                    p.sendSystemMessage(Component.literal("Cầu nguyện thành công!").withStyle(ChatFormatting.GREEN));
                }
            }
        }
    }
}