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
 * the actual explanation and response-assembly logic, which this class only feeds vehicle data
 * into and sends). Requires permission level 2 (the conventional operator/admin level) - this
 * mod had no prior command to match against, but a sparse config's resolved values are the same
 * information an operator config file itself already exposes, not a player-facing feature, and
 * level 2 is vanilla's own standard "trusted admin" bar for exactly that kind of diagnostic.
 *
 * <p>{@code target} is a vanilla entity selector ({@code EntityArgument.entity()}), resolving to
 * whatever real {@code net.minecraft.world.entity.Entity} Minecraft itself tracks - never the
 * MTS {@code EntityVehicleF_Physics} directly (IV's own {@code BuilderEntityExisting} wraps that
 * as a {@code protected}, non-public field). {@link #productionLookup} crosses that boundary via
 * the selected Minecraft entity's own UUID, looked up in the live MTS world through {@code
 * mcinterface1211.WrapperWorld.getWrapperFor(level)}'s inherited {@code EntityManager.getEntity}.
 *
 * <p>Review (comment 54209/54213/54221): the actual DECISION logic this class makes - "is this a
 * DIV-eligible vehicle, and if so, what should the response say" - is exercised through real
 * command execution in {@code TuningCommandAdapterTest} via the {@link #run(CommandContext,
 * VehicleLookup)} seam below, with a FAKE {@link VehicleLookup} standing in for the live-world
 * entity/UUID bridge (constructing a real {@code EntityVehicleF_Physics}/Sable body remains
 * infeasible in any unit test - see {@code PhysicsBridgeStepperTest}'s own doc) - never by
 * reimplementing this class's logic in the test. Only {@link #productionLookup} itself (the
 * actual live-world crossing, a few directly-inspectable lines) and command registration/
 * dispatch glue remain outside what a plain unit test can exercise.
 */
final class TuningCommand {
 private TuningCommand() {}
 static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
  dispatcher.register(Commands.literal("div")
    .requires(source -> source.hasPermission(2))
    .then(Commands.literal("tuning")
      .then(Commands.argument("target", EntityArgument.entity())
        .executes(ctx -> run(ctx, TuningCommand::productionLookup)))));
 }
 /** The result of resolving a selected Minecraft {@code Entity} to a DIV vehicle - {@code
  * resolved} is non-null exactly when the entity IS one. {@code eligibleButNoDefinition}
  * distinguishes "found an MTS vehicle entity, but it has no loaded JSON definition" (reports
  * {@link TuningProvenance#NO_DEFINITION_MESSAGE}) from "no MTS vehicle entity here at all"
  * (reports {@link TuningProvenance#notEligibleMessage}) - both leave {@code resolved} null. */
 record LookupResult(ResolvedVehicle resolved, boolean eligibleButNoDefinition) {
  static final LookupResult NOT_FOUND = new LookupResult(null, false);
  static final LookupResult NO_DEFINITION = new LookupResult(null, true);
  static LookupResult of(ResolvedVehicle resolved) { return new LookupResult(resolved, false); }
 }
 /** Everything {@link TuningProvenance#explainResponse} needs about a resolved vehicle, as plain
  * data - never an {@code EntityVehicleF_Physics} itself, so a test's {@link VehicleLookup} can
  * supply one without constructing any MTS/Sable object at all. */
 record ResolvedVehicle(String key, Overrides.Tuning liveTuning, List<String> installedPartKeys) {}
 /** The live-world-crossing seam {@link #run(CommandContext,VehicleLookup)} delegates to -
  * {@link #productionLookup} is the real implementation; a test supplies a fake one instead,
  * per review comment 54209/54221's explicit "bounded testable adapter seam with fake resolver
  * ... collaborators" allowance. */
 interface VehicleLookup {
  LookupResult lookup(Entity target);
 }
 /**
  * The thin glue {@link #register} actually wires in: extract the selector argument and its
  * display name (needs a live world/{@code EntityArgument} selector search - not reachable from
  * a plain unit test, see {@link #productionLookup}'s own doc), then delegate every actual
  * DECISION to {@link #respond} - which takes neither an {@code Entity} nor anything else
  * requiring a live world, so a test can call it directly, for real, with a fake {@link
  * LookupResult} standing in for what a live {@link VehicleLookup} would have produced.
  */
 static int run(CommandContext<CommandSourceStack> ctx, VehicleLookup lookup) throws CommandSyntaxException {
  Entity target = EntityArgument.getEntity(ctx, "target");
  return respond(ctx.getSource(), target.getName().getString(), lookup.lookup(target));
 }
 /**
  * The actual command DECISION logic - exercised through REAL execution (this exact method,
  * called verbatim from {@link #run}, itself registered verbatim in {@link #register}) in
  * {@code TuningCommandAdapterTest}, never reimplemented there. Needs no {@code Entity}/live
  * world at all: {@code targetDisplayName} and {@code result} are exactly what {@link #run}
  * would have extracted from one, supplied directly - per review comment 54209/54221's explicit
  * "bounded testable adapter seam with fake resolver ... collaborators" allowance. {@code source}
  * can be a real, minimally-constructed {@code CommandSourceStack} (a {@code null} level/server
  * is never dereferenced by {@code hasPermission}/{@code sendSuccess}/{@code sendFailure} with
  * {@code allowLogging=false}, confirmed against the real method bytecode) wrapping a capturing
  * {@code CommandSource} fake, so a test can assert on the ACTUAL rendered output text, not a
  * hand-rolled substitute for it.
  */
 static int respond(CommandSourceStack source, String targetDisplayName, LookupResult result) {
  if (result.resolved() == null) {
   String message = result.eligibleButNoDefinition()
     ? TuningProvenance.NO_DEFINITION_MESSAGE
     : TuningProvenance.notEligibleMessage(targetDisplayName);
   source.sendFailure(Component.literal(message));
   return 0;
  }
  var resolved = result.resolved();
  for (String line : TuningProvenance.explainResponse(resolved.key(), resolved.liveTuning(), resolved.installedPartKeys())) send(source, line);
  return 1;
 }
 /**
  * The real live-world crossing {@link #run} cannot avoid: the selected Minecraft {@code
  * Entity}'s own UUID, looked up in the live MTS world - documented (ticket comment,
  * {@code AEntityB_Existing}'s own save/load contract) to mirror the wrapping Minecraft entity's
  * UUID once persisted through at least one real save/load, the same identity {@code
  * PhysicsBridge}'s own debug receipts already key vehicles by ({@code v.uniqueUUID}). Not
  * reachable from a plain unit test (needs a live {@code ServerLevel}) - see {@link
  * #VehicleLookup}'s own doc for how the rest of this command's logic is tested without it.
  */
 private static LookupResult productionLookup(Entity target) {
  AWrapperWorld world = mcinterface1211.WrapperWorld.getWrapperFor(target.level());
  EntityVehicleF_Physics vehicle = world.getEntity(target.getUUID());
  if (vehicle == null) return LookupResult.NOT_FOUND;
  var definition = vehicle.definition;
  if (definition == null) return LookupResult.NO_DEFINITION;
  String key = definition.packID+":"+definition.systemName;
  Overrides.Tuning liveTuning = PhysicsBridge.activeTuning(vehicle);
  List<String> installedPartKeys = new ArrayList<>();
  for (APart part : vehicle.allParts) {
   if (part.definition == null) continue;
   installedPartKeys.add(part.definition.packID+":"+part.definition.systemName);
  }
  return LookupResult.of(new ResolvedVehicle(key, liveTuning, installedPartKeys));
 }
 private static void send(CommandSourceStack source, String line) {
  source.sendSuccess(() -> Component.literal(line), false);
 }
}
