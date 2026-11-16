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
import java.util.ArrayList;
import java.util.List;
/**
 * Ticket MINECRAFT-546: {@code /div tuning <target>} - a read-only, permission-checked operator
 * diagnostic printing the targeted vehicle's (and its installed parts') effective resolved
 * {@code Overrides.Tuning}, with each field's winning precedence level, so an operator no longer
 * has to read the operator config file and {@code Overrides}' own merge code by eye to answer
 * "why is this value what it is". Never mutates any world/entity/settings state - purely reads
 * already-loaded {@code Overrides}/{@code PhysicsBridge} state (see {@link TuningProvenance} for
 * the actual explanation and response-assembly logic, which this class only feeds live vehicle
 * data into and sends). Requires permission level 2 (the conventional operator/admin level) -
 * this mod had no prior command to match against, but a sparse config's resolved values are the
 * same information an operator config file itself already exposes, not a player-facing feature,
 * and level 2 is vanilla's own standard "trusted admin" bar for exactly that kind of diagnostic.
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
 *
 * <p>This class's own logic (registration, entity/UUID resolution, sending through {@code
 * CommandSourceStack}) is NOT covered by a plain unit test - this project's test source set has
 * no Minecraft/NeoForge/Brigadier/MTS classes on its compile-or-runtime classpath at all (only
 * {@code main} does, via the neoForge moddev plugin), and merely referencing this class from a
 * test fails to even load it (confirmed: {@code NoClassDefFoundError}), the same pre-existing
 * constraint {@code PhysicsBridgeStepperTest}'s own doc already states for a live {@code
 * EntityVehicleF_Physics}/Sable body. Every actual DECISION this class makes (the vehicle-not-
 * found/no-definition messages, and the full per-field response assembly including part dedup
 * and unconfigured-part filtering) is therefore extracted into {@link TuningProvenance} - a class
 * with no Minecraft/MTS import at all - and covered by {@code TuningCommandTest} there; what
 * remains here is the thin, directly-inspectable glue reading plain fields off a live vehicle
 * and forwarding to that logic.
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
   source.sendFailure(Component.literal(TuningProvenance.notEligibleMessage(target.getName().getString())));
   return 0;
  }
  var definition = vehicle.definition;
  if (definition == null) {
   source.sendFailure(Component.literal(TuningProvenance.NO_DEFINITION_MESSAGE));
   return 0;
  }
  String key = definition.packID+":"+definition.systemName;
  boolean active = PhysicsBridge.activeTuning(vehicle) != null;
  List<String> installedPartKeys = new ArrayList<>();
  for (APart part : vehicle.allParts) {
   if (part.definition == null) continue;
   installedPartKeys.add(part.definition.packID+":"+part.definition.systemName);
  }
  for (String line : TuningProvenance.explainResponse(key, active, installedPartKeys)) send(source, line);
  return 1;
 }
 private static void send(CommandSourceStack source, String line) {
  source.sendSuccess(() -> Component.literal(line), false);
 }
}
