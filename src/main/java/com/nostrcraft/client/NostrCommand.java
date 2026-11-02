package com.nostrcraft.client;

import com.nostrcraft.nostr.NostrClient;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;

/** Client-side /nostr command (never sent to the server, so your key stays private). */
public class NostrCommand extends CommandBase {
    @Override
    public String getName() { return "nostr"; }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/nostr <login <nsec|hex> | generate | logout | whoami | export | post <text> | relay <list|add|remove> [url]>";
    }

    @Override
    public int getRequiredPermissionLevel() { return 0; }

    @Override
    public boolean checkPermission(MinecraftServer server, ICommandSender sender) { return true; }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) throw new WrongUsageException(getUsage(sender));
        NostrClient nc = NostrClient.get();
        nc.ensureStarted();
        try {
            switch (args[0].toLowerCase()) {
                case "login":
                    if (args.length < 2) throw new WrongUsageException("/nostr login <nsec1...|hex>");
                    nc.login(args[1]);
                    say(sender, "\u00a7aLogged in as " + nc.getNpub());
                    break;
                case "generate":
                    nc.generateKey();
                    say(sender, "\u00a7aNew identity: " + nc.getNpub());
                    say(sender, "\u00a7eBack up your secret key with /nostr export");
                    break;
                case "logout":
                    nc.logout();
                    say(sender, "\u00a77Logged out");
                    break;
                case "whoami":
                    say(sender, nc.isLoggedIn() ? "\u00a7b" + nc.getNpub() : "\u00a77Not logged in");
                    break;
                case "export":
                    if (!nc.isLoggedIn()) throw new CommandException("Not logged in");
                    say(sender, "\u00a7cSECRET KEY - never share it:");
                    say(sender, "\u00a7f" + nc.getNsec());
                    break;
                case "post":
                    if (args.length < 2) throw new WrongUsageException("/nostr post <text>");
                    nc.publish(buildString(args, 1));
                    say(sender, "\u00a7aPublishing... " + nc.getStatus());
                    break;
                case "relay":
                    relay(sender, nc, args);
                    break;
                default:
                    throw new WrongUsageException(getUsage(sender));
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new CommandException(e.getMessage());
        }
    }

    private void relay(ICommandSender sender, NostrClient nc, String[] args) throws CommandException {
        String sub = args.length > 1 ? args[1].toLowerCase() : "list";
        if (sub.equals("list")) {
            for (String r : nc.getRelays()) say(sender, (nc.isRelayOpen(r) ? "\u00a7a\u25cf " : "\u00a7c\u25cb ") + "\u00a7f" + r);
        } else if (sub.equals("add") && args.length > 2) {
            nc.addRelay(args[2]);
            say(sender, "\u00a7aAdded " + args[2]);
        } else if (sub.equals("remove") && args.length > 2) {
            nc.removeRelay(args[2]);
            say(sender, "\u00a77Removed " + args[2]);
        } else {
            throw new WrongUsageException("/nostr relay <list|add <url>|remove <url>>");
        }
    }

    private static void say(ICommandSender sender, String msg) {
        sender.sendMessage(new TextComponentString(msg));
    }
}
