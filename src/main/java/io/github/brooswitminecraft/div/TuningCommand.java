package io.github.brooswitminecraft.div;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import minecrafttransportsimulator.entities.instances.APart;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
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
 * as a {@code protected}, non-public field). {@link #productionLookup}/{@link #wrappedMtsEntity}
 * cross that boundary via a bounded reflective read of that exact field - NOT UUID equality
 * (review finding, comment 54227: a freshly-spawned vehicle's own MTS-side UUID and its
 * wrapping Minecraft entity's own UUID are independently random-generated and never
 * synchronized by the spawn path, so a UUID-based lookup silently failed for every vehicle that
 * had not yet round-tripped through a real save/load - see {@link #productionLookup}'s own doc
 * for the full finding).
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
  * The real live-world crossing {@link #run} cannot avoid. Review finding (comment 54227): an
  * earlier version of this method looked the selected entity up by UUID in the live MTS world
  * ({@code AWrapperWorld.getEntity(target.getUUID())}) - WRONG for exactly the common "just
  * placed/spawned this vehicle" case, confirmed via decompiled {@code WrapperWorld.
  * spawnEntityInternal} bytecode: a freshly-spawned MTS vehicle's own {@code AEntityA_Base#
  * uniqueUUID} (randomUUID() when constructed with no NBT) is NEVER copied onto the wrapping
  * Minecraft entity (itself independently randomUUID()-assigned by vanilla's own {@code Entity}
  * constructor) - that UUID-equality lookup silently returned {@code NOT_FOUND} for every
  * selected vehicle that had not yet round-tripped through at least one real NBT save/load.
  *
  * <p>Fixed by resolving through the ACTUAL wrapper/entity relationship instead of UUID equality:
  * {@code mcinterface1211.BuilderEntityExisting} (confirmed, via decompiled {@code
  * spawnEntityInternal}, to unconditionally hold the live wrapped {@code AEntityB_Existing} in
  * its own {@code entity} field THE MOMENT it is constructed - before any save/load ever
  * happens) holds that reference in a {@code protected} field with no public accessor anywhere
  * in MTS's own API - {@link #wrappedMtsEntity} is the bounded reflective accessor this crossing
  * needs, reading that exact field by name. Never throws: an entity that is not actually a
  * {@code BuilderEntityExisting}, or a future MTS version renaming/removing the field, degrades
  * to the same {@code NOT_FOUND} an actually-ineligible entity already gets - never a crash.
  */
 private static LookupResult productionLookup(Entity target) {
  if (!(wrappedMtsEntity(target) instanceof EntityVehicleF_Physics vehicle)) return LookupResult.NOT_FOUND;
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
 /**
  * The bounded reflective accessor {@link #productionLookup} needs (review comment 54227's own
  * "adding a bounded production accessor if necessary") - {@code mcinterface1211.
  * BuilderEntityExisting#entity} (type {@code AEntityB_Existing}, confirmed via decompiled
  * bytecode) is the live wrapped MTS entity, set unconditionally at construction, independent of
  * any UUID relationship. Returns {@code null} - never throws - for any entity that is not
  * actually a {@code BuilderEntityExisting}, or if the reflective read fails for any reason (a
  * future MTS version renaming or removing the field): this crossing degrading to "not found"
  * is exactly as safe as an actually-ineligible entity already is, never a command crash.
  */
 // Package-private, not private - lets TuningCommandAdapterTest directly exercise the
 // reflective field lookup's own safety net (a non-BuilderEntityExisting entity, and the field's
 // continued existence/type under the current MTS jar) without needing a live world.
 static Object wrappedMtsEntity(Entity target) {
  if (!(target instanceof mcinterface1211.BuilderEntityExisting builder)) return null;
  try {
   var field = mcinterface1211.BuilderEntityExisting.class.getDeclaredField("entity");
   field.setAccessible(true);
   return field.get(builder);
  } catch (ReflectiveOperationException ex) {
   return null;
  }
 }
 private static void send(CommandSourceStack source, String line) {
  source.sendSuccess(() -> Component.literal(line), false);
 }
}
