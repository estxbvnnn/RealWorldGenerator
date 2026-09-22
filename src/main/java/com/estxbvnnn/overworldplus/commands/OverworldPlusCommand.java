package com.estxbvnnn.overworldplus.commands;

import com.estxbvnnn.overworldplus.Area;
import com.estxbvnnn.overworldplus.ChunkEnhanceListener;
import com.estxbvnnn.overworldplus.Pregenerator;
import com.estxbvnnn.overworldplus.SolidGroundFinder;
import com.estxbvnnn.overworldplus.structures.AbandonedHouseGenerator;
import com.estxbvnnn.overworldplus.structures.StructureSchematic;
import com.estxbvnnn.overworldplus.structures.StructureSchematicLibrary;
import com.estxbvnnn.overworldplus.structures.VineCleanup;
import com.estxbvnnn.overworldplus.trees.SchematicTreeLibrary;
import com.estxbvnnn.overworldplus.trees.TreeSpecies;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

public class OverworldPlusCommand implements CommandExecutor {

    private final JavaPlugin plugin;
    private final SchematicTreeLibrary treeLibrary;
    private final StructureSchematicLibrary structureLibrary;
    private final ChunkEnhanceListener listener;
    private final Pregenerator pregenerator;

    public OverworldPlusCommand(JavaPlugin plugin, SchematicTreeLibrary treeLibrary,
                                StructureSchematicLibrary structureLibrary, ChunkEnhanceListener listener,
                                Pregenerator pregenerator) {
        this.plugin = plugin;
        this.treeLibrary = treeLibrary;
        this.structureLibrary = structureLibrary;
        this.listener = listener;
        this.pregenerator = pregenerator;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length >= 1 ? args[0].toLowerCase() : "";
        switch (sub) {
            case "reload" -> {
                plugin.reloadConfig();
                sender.sendMessage(ChatColor.GREEN + "[OverworldPlus] Config reloaded.");
            }
            case "stats" -> {
                sender.sendMessage(ChatColor.GREEN + "[OverworldPlus] " + ChatColor.WHITE + listener.statsLine());
                sender.sendMessage(ChatColor.GREEN + "[OverworldPlus] " + ChatColor.WHITE + pregenerator.progressLine());
            }
            case "testtree" -> testTree(sender, args.length >= 2 ? args[1] : null);
            case "testhouse" -> testHouse(sender, args);
            case "cleanvines" -> cleanVines(sender, args.length >= 2 ? args[1] : null);
            case "pregen" -> pregen(sender, args);
            case "verify" -> verify(sender, args);
            default -> {
                sender.sendMessage(ChatColor.YELLOW + "Usage: /" + label
                        + " <reload|stats|pregen <radius> [world]|pregen stop|verify [radius] [world]|testtree [species]|testhouse [world x z]|cleanvines [radius]>");
                sender.sendMessage(ChatColor.GRAY + "Species: oak, birch, spruce, jungle, acacia, dark_oak, mangrove, cherry, pale_oak");
            }
        }
        return true;
    }

    private void pregen(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("stop")) {
            if (!pregenerator.isRunning()) {
                sender.sendMessage(ChatColor.YELLOW + "[OverworldPlus] No pre-generation running.");
                return;
            }
            pregenerator.stop();
            sender.sendMessage(ChatColor.GREEN + "[OverworldPlus] Pre-generation stopped.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "[OverworldPlus] " + pregenerator.progressLine());
            sender.sendMessage(ChatColor.GRAY + "Usage: /overworldplus pregen <radius-in-chunks> [world] | pregen stop");
            return;
        }

        int radius;
        try {
            radius = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Radius must be a number of chunks.");
            return;
        }
        radius = Math.max(1, Math.min(radius, 200));

        World world;
        if (args.length >= 3) {
            world = Bukkit.getWorld(args[2]);
        } else if (sender instanceof Player player) {
            world = player.getWorld();
        } else {
            world = Bukkit.getWorlds().get(0);
        }
        if (world == null) {
            sender.sendMessage(ChatColor.RED + "Unknown world.");
            return;
        }

        if (pregenerator.start(world, radius, false)) {
            sender.sendMessage(ChatColor.GREEN + "[OverworldPlus] Pre-generating '" + world.getName()
                    + "' out to radius " + radius + " chunks — progress is logged to console.");
        } else {
            sender.sendMessage(ChatColor.RED + "[OverworldPlus] A pre-generation is already running: " + pregenerator.progressLine());
        }
    }

    /** Audits every planned tree in loaded chunks around the player (or spawn, from console) — see TreeAudit. */
    private void verify(CommandSender sender, String[] args) {
        int radius = 8;
        if (args.length >= 2) {
            try {
                radius = Math.max(1, Math.min(Integer.parseInt(args[1]), 64));
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Radius must be a number of chunks.");
                return;
            }
        }
        World world;
        int cx, cz;
        if (sender instanceof Player player) {
            world = player.getWorld();
            cx = player.getLocation().getBlockX() >> 4;
            cz = player.getLocation().getBlockZ() >> 4;
        } else {
            world = args.length >= 3 ? Bukkit.getWorld(args[2]) : Bukkit.getWorlds().get(0);
            if (world == null) {
                sender.sendMessage(ChatColor.RED + "Unknown world.");
                return;
            }
            cx = world.getSpawnLocation().getBlockX() >> 4;
            cz = world.getSpawnLocation().getBlockZ() >> 4;
            if (args.length >= 5) {
                try {
                    cx = Integer.parseInt(args[3]) >> 4;
                    cz = Integer.parseInt(args[4]) >> 4;
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "x and z must be numbers.");
                    return;
                }
            }
        }
        com.estxbvnnn.overworldplus.trees.TreeAudit.Report report =
                com.estxbvnnn.overworldplus.trees.TreeAudit.run(world, cx, cz, radius, treeLibrary, plugin.getConfig());
        sender.sendMessage(ChatColor.GREEN + "[OverworldPlus] " + ChatColor.WHITE + report);
    }

    /** Pastes a random (or specified) species' tree at the player's feet immediately — no biome check. */
    private void testTree(CommandSender sender, String speciesArg) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this.");
            return;
        }

        TreeSpecies species;
        if (speciesArg == null) {
            TreeSpecies[] all = TreeSpecies.values();
            species = all[ThreadLocalRandom.current().nextInt(all.length)];
        } else {
            try {
                species = TreeSpecies.valueOf(speciesArg.toUpperCase());
            } catch (IllegalArgumentException e) {
                sender.sendMessage(ChatColor.RED + "Unknown species. Options: "
                        + Arrays.toString(TreeSpecies.values()).toLowerCase());
                return;
            }
        }

        if (treeLibrary.isEmpty(species)) {
            sender.sendMessage(ChatColor.RED + "[OverworldPlus] No " + species + " variants loaded — check the server log.");
            return;
        }

        World world = player.getWorld();
        int x = player.getLocation().getBlockX(), z = player.getLocation().getBlockZ();
        Area area = Area.of(world, List.of(), (cx, cz) -> true);
        int groundY = SolidGroundFinder.findY(area, x, z);
        int clearance = world.getMaxHeight() - groundY - 3;
        Optional<SchematicTreeLibrary.Pick> pick = treeLibrary.pickFor(species, clearance, ThreadLocalRandom.current().nextInt());

        if (pick.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "[OverworldPlus] No " + species + " variant fits under " + clearance + " blocks of clearance here.");
            return;
        }

        treeLibrary.paste(area, species, pick.get().tree(), x, groundY + 1, z);
        sender.sendMessage(ChatColor.GREEN + "[OverworldPlus] Pasted a " + species + " tree — height " + pick.get().tree().height()
                + ", " + pick.get().tree().blocks().size() + " blocks.");
    }

    /**
     * Pastes the abandoned-house schematic at the player's feet, or — from console —
     * at the given world/x/z on whatever solid ground is there. Ignores spacing/biome.
     */
    private void testHouse(CommandSender sender, String[] args) {
        Optional<StructureSchematic> schematic = structureLibrary.abandonedHouse();
        if (schematic.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "[OverworldPlus] No house schematic loaded — check the server log.");
            return;
        }

        World world;
        int x, z;
        if (args.length >= 4) {
            world = Bukkit.getWorld(args[1]);
            if (world == null) {
                sender.sendMessage(ChatColor.RED + "Unknown world: " + args[1]);
                return;
            }
            try {
                x = Integer.parseInt(args[2]);
                z = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "x and z must be numbers.");
                return;
            }
        } else if (sender instanceof Player player) {
            world = player.getWorld();
            x = player.getLocation().getBlockX();
            z = player.getLocation().getBlockZ();
        } else {
            sender.sendMessage(ChatColor.RED + "From console: /overworldplus testhouse <world> <x> <z>");
            return;
        }

        Area area = Area.of(world, List.of(), (cx, cz) -> true);
        int groundY = SolidGroundFinder.findY(area, x, z);
        AbandonedHouseGenerator.build(area, x, groundY, z, schematic.get());
        sender.sendMessage(ChatColor.GREEN + "[OverworldPlus] Pasted the abandoned house at "
                + x + ", " + groundY + ", " + z + " (" + schematic.get().blocks().size() + " blocks).");
    }

    /** Removes vines hanging from nothing — leftovers of earlier plugin versions. See VineCleanup. */
    private void cleanVines(CommandSender sender, String radiusArg) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this.");
            return;
        }

        int radius = 48;
        if (radiusArg != null) {
            try {
                radius = Integer.parseInt(radiusArg);
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Radius must be a number.");
                return;
            }
        }
        radius = Math.max(1, Math.min(radius, 200));

        int removed = VineCleanup.sweep(player.getWorld(), player.getLocation(), radius);
        sender.sendMessage(ChatColor.GREEN + "[OverworldPlus] Removed " + removed
                + " unsupported vine block(s) within " + radius + " blocks (loaded chunks only).");
    }
}
