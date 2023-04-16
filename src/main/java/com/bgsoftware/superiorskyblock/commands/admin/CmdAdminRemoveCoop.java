package com.bgsoftware.superiorskyblock.commands.admin;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.commands.CommandTabCompletes;
import com.bgsoftware.superiorskyblock.commands.IAdminIslandCommand;
import com.bgsoftware.superiorskyblock.core.messages.Message;
import com.bgsoftware.superiorskyblock.island.IslandUtils;
import org.bukkit.command.CommandSender;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class CmdAdminRemoveCoop implements IAdminIslandCommand {

    @Override
    public List<String> getAliases() {
        return List.of("removecoop");
    }

    @Override
    public String getPermission() {
        return "superior.admin.removecoop";
    }

    @Override
    public String getUsage(Locale locale) {
        return "admin removecoop <" +
                Message.COMMAND_ARGUMENT_PLAYER_NAME.getMessage(locale) + "/" +
                Message.COMMAND_ARGUMENT_ISLAND_NAME.getMessage(locale) + "> <" +
                Message.COMMAND_ARGUMENT_PLAYER_NAME.getMessage(locale) + ">";
    }

    @Override
    public String getDescription(Locale locale) {
        return Message.COMMAND_DESCRIPTION_ADMIN_REMOVE_COOP.getMessage(locale);
    }

    @Override
    public int getMinArgs() {
        return 4;
    }

    @Override
    public int getMaxArgs() {
        return 5;
    }

    @Override
    public boolean canBeExecutedByConsole() {
        return true;
    }

    @Override
    public boolean supportMultipleIslands() {
        return false;
    }

    @Override
    public void execute(SuperiorSkyblockPlugin plugin, CommandSender sender, SuperiorPlayer superiorPlayer, Island island, String[] args) {
        SuperiorPlayer targetPlayer = plugin.getPlayers().getSuperiorPlayer(args[3]);

        if (targetPlayer == null)
            return;

        if (!targetPlayer.isOnline()) {
            Message.PLAYER_NOT_ONLINE.send(sender, args[3]);
            return;
        }

        if (!island.isCoop(targetPlayer)) {
            Message.PLAYER_NOT_COOP.send(sender);
            return;
        }

        if (!plugin.getEventsBus().callIslandCoopPlayerEvent(island, superiorPlayer, targetPlayer))
            return;

        island.removeCoop(targetPlayer);
        IslandUtils.sendMessage(island, Message.LEAVE_COOP_ANNOUNCEMENT, Collections.emptyList(), superiorPlayer.getName(), targetPlayer.getName());

        if (island.getName().isEmpty())
            Message.LEFT_ISLAND_COOP.send(targetPlayer, superiorPlayer.getName());
        else
            Message.LEFT_ISLAND_COOP_NAME.send(targetPlayer, island.getName());
    }

    @Override
    public List<String> adminTabComplete(SuperiorSkyblockPlugin plugin, CommandSender sender, Island island, String[] args) {
        return args.length == 4 ? CommandTabCompletes.getOnlinePlayers(plugin, args[2], false,
                superiorPlayer -> superiorPlayer.getIsland() == null) : Collections.emptyList();
    }
    
}
