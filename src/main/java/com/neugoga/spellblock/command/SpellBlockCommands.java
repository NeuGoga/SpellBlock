package com.neugoga.spellblock.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.neugoga.spellblock.catalog.OracleCard;
import com.neugoga.spellblock.catalog.OracleIndex;
import com.neugoga.spellblock.catalog.OracleService;
import com.neugoga.spellblock.datapack.SpellBlockDataPacks;
import com.neugoga.spellblock.network.BinderServer;
import com.neugoga.spellblock.network.TradeServer;
import com.neugoga.spellblock.library.CardKey;
import com.neugoga.spellblock.library.Finish;
import com.neugoga.spellblock.library.LibraryWorldData;
import com.neugoga.spellblock.library.PlayerLibrary;
import com.neugoga.spellblock.library.DeckBoxData;
import com.neugoga.spellblock.library.DeckEntry;
import com.neugoga.spellblock.item.DeckBoxItem;
import com.neugoga.spellblock.item.SpellBlockItems;
import com.neugoga.spellblock.item.SpellBlockComponents;

import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class SpellBlockCommands {
	private static final int MAX_COUNT = 1000;

	private SpellBlockCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			dispatcher.register(Commands.literal("spellblock")
				.executes(SpellBlockCommands::rootStatus)
				.then(Commands.literal("status")
					.requires(source -> source.hasPermission(2))
					.executes(SpellBlockCommands::status))
				.then(Commands.literal("reindex")
					.requires(source -> source.hasPermission(2))
					.executes(SpellBlockCommands::reindex))
				.then(Commands.literal("grant")
					.requires(source -> source.hasPermission(2))
					.then(Commands.argument("card", StringArgumentType.greedyString())
						.executes(SpellBlockCommands::grant)))
				.then(importNode().requires(source -> source.hasPermission(2)))
				.then(tradeNode())));
	}

	private static int rootStatus(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		if (!source.hasPermission(2)) {
			source.sendSuccess(() -> Component.literal("Usage: /spellblock trade <player> | /spellblock trade cancel"), false);
			return 1;
		}
		return status(context);
	}

	private static int status(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		String message = indexLine();
		if (source.getEntity() instanceof ServerPlayer player) {
			message = message + " " + binderLine(source, player);
		}
		String text = message;
		source.sendSuccess(() -> Component.literal(text), false);
		return 1;
	}

	private static int reindex(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		if (OracleService.ensure(true)) {
			source.sendSuccess(() -> Component.literal("Rebuilding the card index in the background. Check /spellblock status."), true);
			return 1;
		}
		source.sendFailure(Component.literal("The card index is already building."));
		return 0;
	}

	private static int grant(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		if (!SpellBlockDataPacks.rules().grantEnabled()) {
			source.sendFailure(Component.literal("Granting cards is turned off on this server."));
			return 0;
		}
		if (!(source.getEntity() instanceof ServerPlayer player)) {
			source.sendFailure(Component.literal("Only a player has a binder."));
			return 0;
		}
		OracleIndex index = OracleService.index();
		if (index == null) {
			source.sendFailure(Component.literal("The card index is not ready (" + OracleService.detail() + ")."));
			return 0;
		}
		String raw = StringArgumentType.getString(context, "card").trim();
		if (raw.isEmpty()) {
			source.sendFailure(Component.literal("Name a card."));
			return 0;
		}
		Parsed parsed = parse(index, raw);
		if (parsed.card() == null) {
			String missing = parsed.name() == null ? raw : parsed.name();
			source.sendFailure(Component.literal("No card named \"" + missing + "\". Use the full name, for example \"Lightning Bolt\"."));
			return 0;
		}
		if (parsed.count() == 0 || Math.abs(parsed.count()) > MAX_COUNT) {
			source.sendFailure(Component.literal("Count must be from -" + MAX_COUNT + " to " + MAX_COUNT + ", and not zero."));
			return 0;
		}
		if (parsed.count() > 0 && !BinderServer.canAddCard(parsed.card())) {
			source.sendFailure(Component.literal("\"" + parsed.card().name() + "\" is disabled by server rules."));
			return 0;
		}

		LibraryWorldData data = LibraryWorldData.get(source.getServer());
		PlayerLibrary library = data.getOrCreate(player.getUUID());
		boolean repaired = BinderServer.repairLibrary(library, index);
		CardKey key = new CardKey(parsed.card().oracleId(), parsed.card().printingId(), Finish.NONFOIL);
		int before = library.count(key);
		library.add(key, parsed.count());
		int after = library.count(key);
		int delta = after - before;
		if (delta != 0 || repaired) {
			data.setDirty();
		}
		String text = grantMessage(parsed.card().name(), delta, after);
		source.sendSuccess(() -> Component.literal(text), delta != 0);
		return 1;
	}

	private static String indexLine() {
		OracleIndex index = OracleService.index();
		if (index != null && OracleService.state() == OracleService.State.READY) {
			return "Index: " + OracleService.detail() + ".";
		}
		if (index != null) {
			return "Index: " + index.size() + " cards. Refresh: " + OracleService.detail() + ".";
		}
		if (OracleService.state() == OracleService.State.FAILED) {
			return "Index failed: " + OracleService.detail() + ". Run /spellblock reindex.";
		}
		return "Index is still building (" + OracleService.detail() + ").";
	}

	private static String binderLine(CommandSourceStack source, ServerPlayer player) {
		PlayerLibrary library = LibraryWorldData.get(source.getServer()).get(player.getUUID());
		if (library == null || library.copyCount() == 0) {
			return "Binder: empty.";
		}
		return "Binder: " + library.uniqueCount() + " unique, " + library.copyCount() + " copies.";
	}

	private static String grantMessage(String name, int delta, int after) {
		if (delta > 0) {
			return "Added " + delta + " " + name + ". Binder now holds " + after + ".";
		}
		if (delta < 0) {
			return "Removed " + (-delta) + " " + name + ". Binder now holds " + after + ".";
		}
		return "No change. Binder holds " + after + " " + name + ".";
	}

	/**
	 * A leading number is a count only when the whole string is not itself a card.
	 * "1996 World Champion" stays a name. "4 1996 World Champion" is four copies.
	 */
	private static Parsed parse(OracleIndex index, String raw) {
		OracleCard whole = index.find(raw);
		if (whole != null) {
			return new Parsed(1, whole, null);
		}
		int i = 0;
		int sign = 1;
		if (raw.charAt(0) == '+' || raw.charAt(0) == '-') {
			sign = raw.charAt(0) == '-' ? -1 : 1;
			i++;
		}
		int digits = i;
		while (i < raw.length() && Character.isDigit(raw.charAt(i))) {
			i++;
		}
		if (i == digits || i >= raw.length() || raw.charAt(i) != ' ') {
			return new Parsed(0, null, raw);
		}
		int count;
		try {
			count = Math.multiplyExact(sign, Integer.parseInt(raw.substring(digits, i)));
		} catch (ArithmeticException | NumberFormatException e) {
			return new Parsed(0, null, raw);
		}
		String name = raw.substring(i + 1).trim();
		if (name.isEmpty()) {
			return new Parsed(count, null, raw);
		}
		return new Parsed(count, index.find(name), name);
	}

	private record Parsed(int count, OracleCard card, String name) {
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> importNode() {
		return Commands.literal("import")
			.then(Commands.literal("moxfield")
				.then(Commands.argument("code", StringArgumentType.greedyString())
					.executes(ctx -> importDeck(ctx, "moxfield"))))
			.then(Commands.literal("archidekt")
				.then(Commands.argument("code", StringArgumentType.greedyString())
					.executes(ctx -> importDeck(ctx, "archidekt"))));
	}

	private static int importDeck(CommandContext<CommandSourceStack> context, String source) {
		CommandSourceStack stack = context.getSource();
		if (!SpellBlockDataPacks.rules().importEnabled()) {
			stack.sendFailure(Component.literal("Deck import is turned off on this server."));
			return 0;
		}
		if (!(stack.getEntity() instanceof ServerPlayer player)) {
			stack.sendFailure(Component.literal("Only a player can receive a deck box."));
			return 0;
		}
		OracleIndex index = OracleService.index();
		if (index == null) {
			stack.sendFailure(Component.literal("The card index is not ready (" + OracleService.detail() + ")."));
			return 0;
		}
		String raw = StringArgumentType.getString(context, "code").trim();
		String code = DeckImporter.extractCode(source, raw);
		if (code.isEmpty()) {
			stack.sendFailure(Component.literal("Pass a deck code or full link, e.g. /spellblock import moxfield AbCdEfG"));
			return 0;
		}
		if (player.getInventory().getFreeSlot() == -1) {
			stack.sendFailure(Component.literal("Your inventory is full. Free a slot for the deck box."));
			return 0;
		}
		stack.sendSuccess(() -> Component.literal("Fetching " + source + " deck " + code + "..."), false);
		MinecraftServer server = stack.getServer();
		CompletableFuture.supplyAsync(() -> {
			try {
				return DeckImporter.fetch(source, code, index);
			} catch (Exception e) {
				return e;
			}
		}).whenComplete((result, err) -> server.execute(() -> {
			if (err != null) {
				stack.sendFailure(Component.literal("Import failed: " + err.getMessage()));
				return;
			}
			if (result instanceof Exception e) {
				stack.sendFailure(Component.literal("Import failed: " + e.getMessage()));
				return;
			}
			if (!(result instanceof DeckImporter.ImportedDeck deck)) {
				stack.sendFailure(Component.literal("Import failed."));
				return;
			}
			giveImportedDeck(stack, player, deck);
		}));
		return 1;
	}

	private static void giveImportedDeck(CommandSourceStack source, ServerPlayer player, DeckImporter.ImportedDeck deck) {
		if (player.hasDisconnected()) {
			return;
		}
		if (player.getInventory().getFreeSlot() == -1) {
			source.sendFailure(Component.literal("Your inventory is full. Free a slot for the deck box."));
			return;
		}
		List<DeckEntry> commanders = filterAllowedEntries(deck.commanders());
		List<DeckEntry> main = filterAllowedEntries(deck.main());
		List<DeckEntry> side = filterAllowedEntries(deck.side());
		int skippedDisabled = countLines(deck.commanders()) + countLines(deck.main()) + countLines(deck.side())
			- (commanders.size() + main.size() + side.size());
		int total = 0;
		for (DeckEntry e : commanders) {
			total += e.count();
		}
		for (DeckEntry e : main) {
			total += e.count();
		}
		for (DeckEntry e : side) {
			total += e.count();
		}
		if (total == 0) {
			if (skippedDisabled > 0) {
				source.sendFailure(Component.literal("All cards in this deck are disabled by server rules."));
				return;
			}
			String sample = deck.missing().isEmpty() ? "(none listed)" : String.join(", ", deck.missing().stream().limit(5).toList());
			source.sendFailure(Component.literal("No cards resolved against the local index. Missing examples: " + sample));
			return;
		}
		if (total > DeckBoxData.MAX_CARDS) {
			source.sendFailure(Component.literal("Deck has " + total + " cards; a box holds at most " + DeckBoxData.MAX_CARDS + "."));
			return;
		}
		DeckBoxData data = new DeckBoxData(
			deck.name(),
			deck.format().id(),
			false,
			"",
			commanders,
			main,
			side
		);
		DeckBoxItem boxItem = SpellBlockItems.box("black");
		if (boxItem == null) {
			boxItem = SpellBlockItems.box(DeckBoxItem.COLORS[0]);
		}
		ItemStack item = new ItemStack(boxItem);
		item.set(SpellBlockComponents.DECK, data);
		if (!player.getInventory().add(item)) {
			source.sendFailure(Component.literal("Could not add the deck box to your inventory."));
			return;
		}
		StringBuilder msg = new StringBuilder();
		msg.append("Imported '").append(deck.name()).append("' (").append(deck.format().label()).append(") — ").append(total).append(" cards");
		if (!deck.commanders().isEmpty()) {
			msg.append(", ").append(commanders.size()).append(" commander line(s)");
		}
		if (!deck.side().isEmpty()) {
			msg.append(", ").append(side.size()).append(" sideboard line(s)");
		}
		if (skippedDisabled > 0) {
			msg.append(". Skipped ").append(skippedDisabled).append(" disabled line(s)");
		}
		if (!deck.missing().isEmpty()) {
			msg.append(". Missing ").append(deck.missing().size()).append(": ");
			msg.append(String.join(", ", deck.missing().stream().limit(8).toList()));
			if (deck.missing().size() > 8) {
				msg.append("...");
			}
		} else {
			msg.append(".");
		}
		String text = msg.toString();
		source.sendSuccess(() -> Component.literal(text), true);
	}

	private static List<DeckEntry> filterAllowedEntries(List<DeckImporter.Line> lines) {
		if (lines == null || lines.isEmpty()) {
			return List.of();
		}
		List<DeckImporter.Line> allowed = lines.stream()
			.filter(line -> BinderServer.canAddCard(line.oracleId()))
			.toList();
		return DeckImporter.toEntries(allowed);
	}

	private static int countLines(List<DeckImporter.Line> lines) {
		return lines == null ? 0 : lines.size();
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> tradeNode() {
		return Commands.literal("trade")
			.then(Commands.literal("cancel").executes(SpellBlockCommands::tradeCancel))
			.then(Commands.argument("player", EntityArgument.player()).executes(SpellBlockCommands::tradeAsk));
	}

	private static int tradeAsk(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		if (!(source.getEntity() instanceof ServerPlayer player)) {
			source.sendFailure(Component.literal("Only a player can trade cards."));
			return 0;
		}
		ServerPlayer target = EntityArgument.getPlayer(context, "player");
		TradeServer.CommandResult result = TradeServer.requestOrAccept(player, target);
		if (!result.ok()) {
			source.sendFailure(Component.literal(result.message()));
			return 0;
		}
		source.sendSuccess(() -> Component.literal(result.message()), false);
		return 1;
	}

	private static int tradeCancel(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		if (!(source.getEntity() instanceof ServerPlayer player)) {
			source.sendFailure(Component.literal("Only a player can cancel a trade."));
			return 0;
		}
		TradeServer.CommandResult result = TradeServer.cancel(player);
		if (!result.ok()) {
			source.sendFailure(Component.literal(result.message()));
			return 0;
		}
		source.sendSuccess(() -> Component.literal(result.message()), false);
		return 1;
	}
}
