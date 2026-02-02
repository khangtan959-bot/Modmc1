package com.nbv.islamicmod;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
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
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.*;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.*;

public class ModEvents {

    // --- CÁC BIẾN LƯU TRẠNG THÁI ---
    private static final Map<UUID, Boolean> hasShahada = new HashMap<>();
    private static final Map<UUID, Long> lastHalalPhrase = new HashMap<>();
    private static final Map<UUID, Integer> wuduCount = new HashMap<>();
    private static final Map<UUID, Boolean> isPraying = new HashMap<>();
    private static final Map<UUID, Integer> prayerProgress = new HashMap<>();
    
    // Biến cho Luật Chiến Tranh
    private static final Map<UUID, UUID> combatTarget = new HashMap<>(); // Player -> Mob đang đánh
    private static final Set<UUID> hostileMobs = new HashSet<>(); // Mob được phép đánh trả

    // Biến cho Luật Không Ăn Cắp
    private static final Map<UUID, Integer> inventorySnapshot = new HashMap<>(); // Lưu số lượng item trước khi mở rương
    private static final Map<UUID, Boolean> isLookingAtLootChest = new HashMap<>();

    // Thời gian cầu nguyện
    private static final int[] PRAYER_TIMES = {0, 6000, 9000, 12000, 18000};
    private boolean prayerSessionActive = false;
    private int prayerSessionTimer = 0;

    // --- HÀM TRỪNG PHẠT (CHẾT 100%) ---
    private void punishPlayer(Player player, String reason) {
        if (player instanceof ServerPlayer serverPlayer) {
            // Thông báo lý do
            serverPlayer.sendSystemMessage(Component.literal("HARAM! " + reason).withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
            
            // Chuyển về Survival để đảm bảo chết được
            serverPlayer.setGameMode(GameType.SURVIVAL);
            
            // Triệu hồi sét
            ServerLevel world = serverPlayer.serverLevel();
            BlockPos pos = serverPlayer.blockPosition();
            LightningBolt lightning = EntityType.LIGHTNING_BOLT.create(world);
            if (lightning != null) {
                lightning.moveTo(pos.getX(), pos.getY(), pos.getZ());
                world.addFreshEntity(lightning);
            }

            // Giết ngay lập tức
            serverPlayer.setHealth(0);
            serverPlayer.kill(); 
        }
    }

    // --- 1. LUẬT CẤM THUỐC (NO DRUGS/POTIONS) ---
    @SubscribeEvent
    public void onConsumeItem(LivingEntityUseItemEvent.Finish event) {
        if (event.getEntity() instanceof Player player) {
            ItemStack item = event.getItem();
            // Nếu uống Potion (trừ nước thường nếu game coi là potion, nhưng logic này bắt hết các loại thuốc có effect)
            if (item.getItem() == Items.POTION || item.getItem() == Items.SPLASH_POTION || item.getItem() == Items.LINGERING_POTION) {
                punishPlayer(player, "Không được sử dụng chất kích thích (Potion)!");
            }
        }
    }

    // --- 2. LUẬT DÂN LÀNG (NO VILLAGER ABUSE) ---
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
            // Cấm dùng Thuyền hoặc Xe mỏ lên dân làng (Hành vi bắt cóc)
            if (item.getItem() == Items.OAK_BOAT || item.getItem() == Items.MINECART || item.getItem().toString().contains("boat")) {
                event.setCanceled(true);
                punishPlayer(event.getEntity(), "Không được bắt nhốt/bắt cóc dân làng!");
            }
        }
    }

    // --- 3. LUẬT CHIẾN TRANH (NO RETREAT) ---
    @SubscribeEvent
    public void onMobAttackPlayer(LivingDamageEvent event) {
        if (event.getEntity() instanceof Player player && event.getSource().getEntity() instanceof Monster monster) {
            // Kích hoạt trạng thái chiến đấu
            hostileMobs.add(monster.getUUID());
            combatTarget.put(player.getUUID(), monster.getUUID());
            player.sendSystemMessage(Component.literal("CHIẾN TRANH BẮT ĐẦU! Không được chạy trốn!").withStyle(ChatFormatting.DARK_RED));
        }
    }

    @SubscribeEvent
    public void onMobDeath(LivingDeathEvent event) {
        // Nếu quái chết, giải phóng người chơi khỏi trạng thái chiến đấu
        if (event.getEntity() instanceof Monster monster) {
            hostileMobs.remove(monster.getUUID());
            // Tìm người chơi đang đánh con này để xóa trạng thái
            combatTarget.entrySet().removeIf(entry -> entry.getValue().equals(monster.getUUID()));
        }
    }

    @SubscribeEvent
    public void onPlayerMoveCheck(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide) return;
        
        Player player = event.player;
        UUID targetMobId = combatTarget.get(player.getUUID());

        if (targetMobId != null) {
            if (player.level() instanceof ServerLevel serverLevel) {
                Entity mob = serverLevel.getEntity(targetMobId);
                
                // Nếu quái còn sống
                if (mob instanceof LivingEntity livingMob && livingMob.isAlive()) {
                    // Kiểm tra khoảng cách (12 block)
                    double distance = player.distanceTo(livingMob);
                    if (distance > 12) {
                        punishPlayer(player, "Kẻ hèn nhát! Bạn đã bỏ chạy khỏi trận chiến!");
                        combatTarget.remove(player.getUUID()); // Xóa để không spam
                    }
                } else {
                    // Quái đã biến mất hoặc chết mà không kích hoạt event Death
                    combatTarget.remove(player.getUUID());
                }
            }
        }
    }

    // --- 4. LUẬT KHÔNG ĂN CẮP (NO STEALING) ---
    @SubscribeEvent
    public void onOpenContainer(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide) return;
        
        if (event.getLevel().getBlockEntity(event.getPos()) instanceof RandomizableContainerBlockEntity chest) {
            // Nếu rương có LootTable (nghĩa là rương tự nhiên chưa ai đụng vào)
            if (chest.lootTable != null) {
                Player p = event.getEntity();
                isLookingAtLootChest.put(p.getUUID(), true);
                
                // Đếm tổng số item hiện có trong người
                int totalItems = p.getInventory().items.stream().mapToInt(ItemStack::getCount).sum();
                inventorySnapshot.put(p.getUUID(), totalItems);
                
                p.sendSystemMessage(Component.literal("CẢNH BÁO: Đây là tài sản của người khác. Chỉ được xem, KHÔNG ĐƯỢC LẤY!").withStyle(ChatFormatting.YELLOW));
            }
        }
    }

    @SubscribeEvent
    public void onCloseContainer(PlayerContainerEvent.Close event) {
        Player p = event.getEntity();
        if (isLookingAtLootChest.getOrDefault(p.getUUID(), false)) {
            // Kiểm tra xem số lượng item có tăng lên không
            int oldTotal = inventorySnapshot.getOrDefault(p.getUUID(), 0);
            int newTotal = p.getInventory().items.stream().mapToInt(ItemStack::getCount).sum();

            if (newTotal > oldTotal) {
                punishPlayer(p, "Bạn đã ăn cắp đồ! Luật là chặt tay (nhưng ở đây là Sét đánh)!");
            }
            
            // Reset trạng thái
            isLookingAtLootChest.remove(p.getUUID());
            inventorySnapshot.remove(p.getUUID());
        }
    }

    // --- CÁC LUẬT CŨ (SHAHADA, HALAL, CẦU NGUYỆN) ---
    // (Đã tích hợp hàm punishPlayer vào các logic cũ bên dưới)

    @SubscribeEvent
    public void onChat(ServerChatEvent event) {
        String msg = event.getMessage().getString().toLowerCase();
        ServerPlayer player = event.getPlayer();

        if (!hasShahada.getOrDefault(player.getUUID(), false)) {
            if (msg.contains("không có chúa trời nào ngoài allah") && msg.contains("muhammed là sứ giả")) {
                hasShahada.put(player.getUUID(), true);
                player.sendSystemMessage(Component.literal("Bạn đã tuyên thệ Shahada! Game bắt đầu.").withStyle(ChatFormatting.GREEN));
                player.level().playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1f, 1f);
            } else {
                player.sendSystemMessage(Component.literal("Bạn phải gõ Shahada để di chuyển!").withStyle(ChatFormatting.RED));
            }
        }

        if (msg.contains("bismillah") && msg.contains("allahu akbar")) {
            lastHalalPhrase.put(player.getUUID(), System.currentTimeMillis());
            player.sendSystemMessage(Component.literal("Đã đọc thần chú! Bạn có 10 giây để giết động vật.").withStyle(ChatFormatting.GOLD));
        }
    }

    @SubscribeEvent
    public void onAttackAnimal(LivingDamageEvent event) {
        if (!(event.getSource().getEntity() instanceof Player player)) return;
        LivingEntity target = event.getEntity();

        // Cấm thịt heo
        if (target.getType() == EntityType.PIG) {
            event.setCanceled(true);
            punishPlayer(player, "Không được đụng vào heo!");
            return;
        }

        if (target instanceof Animal animal) {
            if (animal.isBaby()) {
                event.setCanceled(true);
                punishPlayer(player, "Không được sát sinh con non!");
                return;
            }

            long lastTime = lastHalalPhrase.getOrDefault(player.getUUID(), 0L);
            if (System.currentTimeMillis() - lastTime > 10000) {
                event.setCanceled(true);
                player.sendSystemMessage(Component.literal("Phải gõ 'Bismillah Allahu Akbar' trước khi giết!").withStyle(ChatFormatting.RED));
                return;
            }

            if (event.getAmount() < target.getHealth()) {
                event.setCanceled(true);
                punishPlayer(player, "Không giết được trong 1 hit (Luật Helen)!");
            }
        }
    }

    @SubscribeEvent
    public void onPlayerAttackMonsterInit(LivingAttackEvent event) {
        if (event.getSource().getEntity() instanceof Player player && event.getEntity() instanceof Monster monster) {
            if (!hostileMobs.contains(monster.getUUID())) {
                event.setCanceled(true);
                player.sendSystemMessage(Component.literal("Luật Công Lý: Không được tấn công trước!").withStyle(ChatFormatting.RED));
            }
        }
    }

    // --- LOGIC CẦU NGUYỆN (GIỮ NGUYÊN) ---
    @SubscribeEvent
    public void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.START || event.level.isClientSide) return;
        if (event.level.dimension() == Level.END) return; 

        long time = event.level.getDayTime() % 24000;
        for (int pTime : PRAYER_TIMES) {
            if (Math.abs(time - pTime) < 10 && !prayerSessionActive) {
                startPrayerSession(event.level);
            }
        }

        if (prayerSessionActive) {
            prayerSessionTimer++;
            if (prayerSessionTimer > 1200) { 
                endPrayerSession(event.level);
            }
        }
    }

    private void startPrayerSession(Level level) {
        prayerSessionActive = true;
        prayerSessionTimer = 0;
        level.players().forEach(p -> {
            wuduCount.put(p.getUUID(), 0);
            isPraying.put(p.getUUID(), false);
            prayerProgress.put(p.getUUID(), 0);
            p.sendSystemMessage(Component.literal("=== ĐẾN GIỜ CẦU NGUYỆN ===").withStyle(ChatFormatting.GOLD));
            p.sendSystemMessage(Component.literal("1. Rửa mình (Click nước 3 lần)").withStyle(ChatFormatting.YELLOW));
            p.sendSystemMessage(Component.literal("2. Quay hướng BẮC (North) và Ngồi (Shift) 10s").withStyle(ChatFormatting.YELLOW));
        });
    }

    private void endPrayerSession(Level level) {
        prayerSessionActive = false;
        level.players().forEach(p -> {
            if (!isPraying.getOrDefault(p.getUUID(), false)) {
                punishPlayer(p, "Bạn đã bỏ lỡ giờ cầu nguyện!");
            } else {
                p.sendSystemMessage(Component.literal("Buổi cầu nguyện kết thúc an toàn.").withStyle(ChatFormatting.GREEN));
            }
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
             p.level().playSound(null, p.blockPosition(), SoundEvents.BOAT_PADDLE_WATER, SoundSource.PLAYERS, 1f, 1f);
             
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
                    p.level().playSound(null, p.blockPosition(), SoundEvents.NOTE_BLOCK_PLING, SoundSource.PLAYERS, 1f, 2f);
                }
            }
        } else {
            if (!isPraying.getOrDefault(p.getUUID(), false)) {
                prayerProgress.put(p.getUUID(), 0);
            }
        }
    }
}