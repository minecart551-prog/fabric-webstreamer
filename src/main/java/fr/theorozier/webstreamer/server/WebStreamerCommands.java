package fr.theorozier.webstreamer.server;

import com.mojang.brigadier.CommandDispatcher;
import fr.theorozier.webstreamer.WebStreamerMod;
import fr.theorozier.webstreamer.display.DisplayNetworking;
import fr.theorozier.webstreamer.util.WebStreamerConfig;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

public class WebStreamerCommands {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, boolean dedicated) {
        dispatcher.register(CommandManager.literal("webstreamer")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.literal("config")
                        .then(CommandManager.literal("reload")
                                .executes(context -> {
                                    ServerCommandSource source = context.getSource();
                                    WebStreamerConfig.load(WebStreamerMod.getConfigDir());
                                    int count = ServerSourceRegistry.reload();
                                    if (count < 0) {
                                        source.sendFeedback(() -> Text.literal("\u00a7cFailed to reload sources.txt"), false);
                                        return 0;
                                    }
                                    DisplayNetworking.broadcastSourcesToAll(source.getServer());
                                    source.sendFeedback(() -> Text.literal("\u00a7aReloaded " + count + " source(s) from sources.txt"), false);
                                    return count;
                                })
                        )
                )
        );
    }

}
