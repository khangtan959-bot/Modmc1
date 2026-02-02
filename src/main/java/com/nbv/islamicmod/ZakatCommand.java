package com.nbv.islamicmod;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public class ZakatCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("zakat")
            .executes(ZakatCommand::execute));
    }

    private static int execute(CommandContext<CommandSourceStack> context) {
        try {
            Player player = context.getSource().getPlayerOrException();
            
            // Duyệt qua túi đồ
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (!stack.isEmpty()) {
                    int amount = stack.getCount();
                    if (amount >= 2) {
                        int tax = amount / 2; // Lấy 50%
                        stack.shrink(tax); // Xóa khỏi túi
                        // Tùy chọn: Có thể spawn item ra đất nếu muốn "vứt" thật, 
                        // nhưng "shrink" là xóa hẳn (từ thiện biến mất)
                    }
                }
            }
            
            player.sendSystemMessage(Component.literal("Bạn đã thực hiện Zakat (Từ thiện) 50% tài sản!").withStyle(net.minecraft.ChatFormatting.GOLD));
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }
}