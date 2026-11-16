package io.github.brooswitminecraft.div;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import minecrafttransportsimulator.entities.instances.APart;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.mcinterface.AWrapperWorld;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import java.util.LinkedHashSet;
/**
 * Ticket MINECRAFT-546: {@code /div tuning <target>} - a read-only, permission-checked operator
 * diagnostic printing the targeted vehicle's (and its installed parts') effective resolved
 * {@code Overrides.Tuning}, with each field's winning precedence level, so an operator no longer
 * has to read the operator config file and {@code Overrides}' own merge code by eye to answer
 * "why is this value what it is". Never mutates any world/entity/settings state - purely reads
 * already-loaded {@code Overrides}/{@code PhysicsBridge} state (see {@link TuningProvenance}
 * for the actual explanation logic, which this class only renders and sends). Requires
 * permission level 2 (the conventional operator/admin level) - this mod had no prior command to
 * match against, but a sparse config's resolved values are the same information an operator
 * config file itself already exposes, not a player-facing feature, and level 2 is vanilla's own
 * standard "trusted admin" bar for exactly that kind of diagnostic.
 *
 * <p>{@code target} is a vanilla entity selector ({@code EntityArgument.entity()}), resolving to
 * whatever real {@code net.minecraft.world.entity.Entity} Minecraft itself tracks - never the
 * MTS {@code EntityVehicleF_Physics} directly (IV's own {@code BuilderEntityExisting} wraps that
 * as a {@code protected}, non-public field - see {@code WheelAuxPayload}'s existing {@code
 * mcinterface1211.WrapperPlayer.getWrapperFor(player).getEntityRiding()} call for this
 * codebase's established precedent for crossing that boundary). This command instead looks the
 * selected entity's own UUID up in the live MTS world via {@code
 * mcinterface1211.WrapperWorld.getWrapperFor(level)}'s inherited {@code EntityManager.getEntity}
 * - documented (ticket comment, {@code AEntityB_Existing}'s own save/load contract) to mirror
 * the wrapping Minecraft entity's UUID exactly, the same identity {@code PhysicsBridge}'s own
 * debug receipts already key vehicles by ({@code v.uniqueUUID}).
 */
final class TuningCommand {
 private TuningCommand() {}
 static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
  dispatcher.register(Commands.literal("div")
    .requires(source -> source.hasPermission(2))
    .then(Commands.literal("tuning")
      .then(Commands.argument("target", EntityArgument.entity())
        .executes(TuningCommand::run))));
 }
 private static int run(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
  Entity target = EntityArgument.getEntity(ctx, "target");
  CommandSourceStack source = ctx.getSource();
  AWrapperWorld world = mcinterface1211.WrapperWorld.getWrapperFor(target.level());
  EntityVehicleF_Physics vehicle = world.getEntity(target.getUUID());
  if (vehicle == null) {
   source.sendFailure(Component.literal("[DIV] "+target.getName().getString()+" is not a DIV-eligible vehicle entity."));
   return 0;
  }
  var definition = vehicle.definition;
  if (definition == null) {
   source.sendFailure(Component.literal("[DIV] that vehicle has no loaded definition."));
   return 0;
  }
  String key = definition.packID+":"+definition.systemName;
  boolean active = PhysicsBridge.activeTuning(vehicle) != null;
  send(source, TuningProvenance.UNWIRED_LAYERS_NOTICE);
  send(source, "[DIV] vehicle "+key+" - "+(active ? "active (spawned/physics-engaged)" : "not currently active"));
  var vehicleExplanation = TuningProvenance.explainVehicle(key);
  for (String line : TuningProvenance.render("vehicle "+key, vehicleExplanation)) send(source, line);
  // Dedupe by part key (packID:systemName) - several installed parts (e.g. four identical
  // wheels) commonly share one definition/override entry; explaining the same key repeatedly
  // would be noise, not new information.
  var explainedPartKeys = new LinkedHashSet<String>();
  for (APart part : vehicle.allParts) {
   if (part.definition == null) continue;
   String partKey = part.definition.packID+":"+part.definition.systemName;
   if (!explainedPartKeys.add(partKey)) continue;
   if (Overrides.getPart(partKey) == null) continue; // no part-override entry at all - nothing to explain
   var partExplanation = TuningProvenance.explainPart(partKey);
   for (String line : TuningProvenance.render("part "+partKey, partExplanation)) send(source, line);
  }
  return 1;
 }
 private static void send(CommandSourceStack source, String line) {
  source.sendSuccess(() -> Component.literal(line), false);
 }
}
