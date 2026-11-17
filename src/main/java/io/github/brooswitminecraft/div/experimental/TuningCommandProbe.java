package io.github.brooswitminecraft.div.experimental;
import com.mojang.logging.LogUtils;
import io.github.brooswitminecraft.div.Overrides;
import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.items.instances.ItemVehicle;
import minecrafttransportsimulator.jsondefs.JSONCollisionBox;
import minecrafttransportsimulator.jsondefs.JSONCollisionGroup;
import minecrafttransportsimulator.jsondefs.JSONVehicle;
import minecrafttransportsimulator.mcinterface.AWrapperWorld;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
/**
 * Ticket MINECRAFT-546 review (John, comment 54231): now that {@code TuningCommand.
 * productionLookup} resolves through the real wrapper/entity relationship ({@code
 * TuningCommand.wrappedMtsEntity} - see its own doc for the fixed production defect this
 * replaces), this probe proves that fix live: spawn a real vehicle, select it via the real
 * Minecraft-side entity's own UUID (never {@code vehicle.uniqueUUID} - the exact mismatch the
 * fixed bug was about), dispatch `/div tuning &lt;uuid&gt;` through the REAL registered
 * dispatcher, and assert the real response actually names this vehicle and its configured
 * field - the thing a plain unit test cannot do (constructing a real {@code
 * EntityVehicleF_Physics}/Sable body remains infeasible there - see {@code
 * PhysicsBridgeStepperTest}'s own doc).
 *
 * <p>Opt-in via {@code -Ddiv.experimentalTuningCommandProbe=true} - OFF by default, same
 * convention as every other probe in this package. Removes its own spawned vehicle once done.
 *
 * <p>MINECRAFT-548 hardening: an earlier version of this probe picked the FIRST entity returned
 * by {@code level.getAllEntities()} - correct only by the accident of running in an otherwise-
 * empty bounded test chunk, and the exact "unrelated level entity" risk this probe exists to
 * prove the production fix is immune to. {@link #findOwnWrapper} instead selects by actual
 * identity - reference-equal to this probe's own spawned {@code vehicle} - via the same
 * reflective wrapper/entity crossing {@code TuningCommand.wrappedMtsEntity} uses (duplicated
 * narrowly here since that production accessor is package-private to {@code
 * io.github.brooswitminecraft.div}, not this experimental sub-package - ticket scope explicitly
 * disallows widening its visibility). Every resource this probe might own - the force-loaded
 * chunk, the {@code divtest} pack's {@code packSpeedFactors}/{@code packVehicleScales} entries,
 * the spawned vehicle, and the operator config file - is now tracked in {@link
 * TuningCommandProbeOwnership} and only ever restored/removed if THIS run actually created it;
 * a pre-existing forced chunk or pre-existing pack entries are left exactly as found. Every exit
 * path (success, any AWAIT_CHUNK/SETTLE timeout, any EXECUTE failure, or an unexpected exception
 * from any phase) now funnels through {@link Phase#CLEANUP} before {@link Phase#DONE}, instead of
 * the old code's single {@code finally} inside {@code execute()} alone - which a timeout or an
 * exception thrown before {@code execute()} ever ran bypassed entirely, leaking the forced chunk
 * and the spawned vehicle on those paths.
 */
public final class TuningCommandProbe {
 private TuningCommandProbe() {}
 private static final boolean ENABLED = Boolean.getBoolean("div.experimentalTuningCommandProbe");
 private static final Logger LOGGER = LogUtils.getLogger();
 private static final String PACK_ID = "divtest";
 private static final String SYSTEM_NAME = "tuningcommandprobe";
 private enum Phase { AWAIT_CHUNK, SPAWN, SETTLE, EXECUTE, CLEANUP, DONE }
 private static Phase phase = Phase.AWAIT_CHUNK;
 private static int ticksInPhase = 0;
 private static EntityVehicleF_Physics vehicle;
 private static net.minecraft.world.entity.Entity ownWrapperEntity;
 private static final TuningCommandProbeOwnership ownership = new TuningCommandProbeOwnership();
 // Live-run finding (this ticket's own earlier attempt): force-loading the chunk alone is not
 // synchronously complete - a chunk can still report not-yet-loaded for a tick or more after
 // the force-load request. Polling getChunkNow (same real readiness check
 // FlightRotorRuntimeFixtureProbe's own spawn-gating already uses) instead of a fixed tick
 // count is the actual fix.
 private static final int MAX_AWAIT_CHUNK_TICKS = 100;
 // Settle after spawnEntity before selecting the vehicle by selector/UUID - live-run finding:
 // a FIXED tick count here is itself flaky (observed live: occasionally still empty after 5
 // ticks depending on real-time server tick pacing right after startup) - polling for this
 // probe's own wrapping entity to actually appear (by identity, not merely "any entity") is the
 // real fix. Bounded the same way.
 private static final int MAX_SETTLE_TICKS = 100;

 public static void maybeRun(ServerLevel level) {
  if (!ENABLED || phase == Phase.DONE) return;
  try {
   switch (phase) {
    case AWAIT_CHUNK -> awaitChunk(level);
    case SPAWN -> { spawn(level); phase = Phase.SETTLE; ticksInPhase = 0; }
    case SETTLE -> awaitOwnWrapperVisible(level);
    case EXECUTE -> execute(level);
    case CLEANUP -> cleanup(level);
    case DONE -> {}
   }
  } catch (RuntimeException ex) {
   LOGGER.error("[DIV tuningCommandProbe] COMPLETE - FAILED: unexpected exception in phase "+phase, ex);
   phase = Phase.CLEANUP;
  }
 }

 /** The bounded reflective crossing {@code TuningCommand.wrappedMtsEntity} also uses - returns
  * the live MTS entity a {@code BuilderEntityExisting} wraps, or {@code null} for anything else
  * (including a reflective-read failure) - never throws. Package-private visibility in {@code
  * TuningCommand} keeps that production accessor out of reach from this sub-package (ticket
  * scope: "avoid edits to TuningCommand production behavior"), so this is this probe's own
  * narrowly-scoped copy, not a widened accessor on the production class. */
 private static Object wrappedMtsEntity(net.minecraft.world.entity.Entity target) {
  if (!(target instanceof mcinterface1211.BuilderEntityExisting builder)) return null;
  try {
   var field = mcinterface1211.BuilderEntityExisting.class.getDeclaredField("entity");
   field.setAccessible(true);
   return field.get(builder);
  } catch (ReflectiveOperationException ex) {
   return null;
  }
 }

 /** This probe's own spawned {@code vehicle}'s wrapping Minecraft entity, found by actual
  * identity (reference-equal {@link #wrappedMtsEntity} result) among every entity currently
  * tracked by {@code level} - never the first entity found, so an unrelated entity sharing the
  * level (another probe/player/mob) can never be mistaken for this probe's own vehicle. See
  * {@link TuningCommandProbeOwnership#selectOwnWrapper} for the pure selection logic this
  * delegates to. */
 private static net.minecraft.world.entity.Entity findOwnWrapper(ServerLevel level) {
  return TuningCommandProbeOwnership.selectOwnWrapper(level.getAllEntities(), TuningCommandProbe::wrappedMtsEntity, vehicle);
 }

 private static void awaitOwnWrapperVisible(ServerLevel level) {
  var own = findOwnWrapper(level);
  ticksInPhase++;
  var outcome = TuningCommandProbeOwnership.pollBoundedWait(own != null, ticksInPhase, MAX_SETTLE_TICKS);
  switch (outcome) {
   case READY -> { ownWrapperEntity = own; phase = Phase.EXECUTE; ticksInPhase = 0; }
   case TIMED_OUT -> {
    LOGGER.error("[DIV tuningCommandProbe] COMPLETE - FAILED: this probe's own just-spawned vehicle's wrapping Minecraft entity (selected by actual vehicle identity, not merely 'any entity') never appeared within "+MAX_SETTLE_TICKS+" ticks");
    phase = Phase.CLEANUP;
   }
   case CONTINUE -> {}
  }
 }

 private static void awaitChunk(ServerLevel level) {
  if (ticksInPhase == 0) {
   boolean alreadyForced = level.getForcedChunks().contains(ChunkPos.asLong(0, 0));
   if (!alreadyForced) {
    level.setChunkForced(0, 0, true);
    ownership.chunkForcedByUs = true;
   }
  }
  boolean ready = level.getChunkSource().getChunkNow(0, 0) != null;
  if (ready) { phase = Phase.SPAWN; ticksInPhase = 0; return; }
  ticksInPhase++;
  if (ticksInPhase >= MAX_AWAIT_CHUNK_TICKS) {
   LOGGER.error("[DIV tuningCommandProbe] COMPLETE - FAILED: chunk (0,0) never became ready after force-loading, within "+MAX_AWAIT_CHUNK_TICKS+" ticks");
   phase = Phase.CLEANUP;
  }
 }

 private static void spawn(ServerLevel level) {
  // Required - AEntityVehicleC_Colliding's constructor reads this pack's own
  // packSpeedFactors/packVehicleScales entries unconditionally (confirmed live: an absent
  // entry NPEs there) - same "putIfAbsent before first spawn of a never-before-seen pack ID"
  // convention FlightRotorRuntimeFixtureProbe's own spawn path already establishes. Tracked as
  // owned-by-us ONLY when actually absent beforehand, so cleanup never removes an entry some
  // other probe/pack/operator config already relied on.
  var packSpeedFactors = minecrafttransportsimulator.systems.ConfigSystem.settings.general.packSpeedFactors.value;
  var packVehicleScales = minecrafttransportsimulator.systems.ConfigSystem.settings.general.packVehicleScales.value;
  ownership.packSpeedFactorAddedByUs = !packSpeedFactors.containsKey(PACK_ID);
  ownership.packVehicleScaleAddedByUs = !packVehicleScales.containsKey(PACK_ID);
  packSpeedFactors.putIfAbsent(PACK_ID, 1.0);
  packVehicleScales.putIfAbsent(PACK_ID, 1.0);
  AWrapperWorld world = mcinterface1211.WrapperWorld.getWrapperFor(level);
  vehicle = buildAndSpawn(world);
 }

 private static void execute(ServerLevel level) {
  // Live-run finding (this ticket's own history, comment 54227): a freshly spawned vehicle's
  // own AEntityA_Base#uniqueUUID is independently random-generated from the wrapping Minecraft
  // entity's own UUID - selecting by vehicle.uniqueUUID would always fail to find anything.
  // This probe selects by the REAL wrapping Minecraft entity's own UUID instead - resolved by
  // actual identity in SETTLE (see findOwnWrapper), never merely "whatever is in this level".
  net.minecraft.world.entity.Entity minecraftEntity = ownWrapperEntity;
  LOGGER.info("[DIV tuningCommandProbe] vehicle.uniqueUUID="+vehicle.uniqueUUID+" wrappingMinecraftEntity.getUUID()="+minecraftEntity.getUUID()
    +" class="+minecraftEntity.getClass().getName()+" sameUUID="+vehicle.uniqueUUID.equals(minecraftEntity.getUUID()));
  String key = PACK_ID+":"+SYSTEM_NAME;
  Path configPath = FMLPaths.CONFIGDIR.get().resolve("dynamic-immersive-vehicles.json");
  // Review finding carried into this hardening: a failed snapshot read must never be treated as
  // "the file didn't exist" - that would delete (or fail to restore) a real pre-existing file
  // this probe itself never backed up. Abort to CLEANUP WITHOUT writing anything in that case;
  // ownership.configWasWritten stays false, so cleanup correctly leaves the file untouched.
  try {
   ownership.configExistedBefore = Files.exists(configPath);
   ownership.originalConfigContent = ownership.configExistedBefore ? Files.readString(configPath) : null;
  } catch (IOException ex) {
   LOGGER.error("[DIV tuningCommandProbe] COMPLETE - FAILED: could not snapshot the existing operator config file before writing - preserving it untouched rather than risk losing it: "+configPath, ex);
   phase = Phase.CLEANUP;
   return;
  }
  try {
   // Seed a real explicit operator override through the SAME public file-based reload path
   // every other probe in this package uses (see GroundSurfaceHotReloadProbe's own doc) - not
   // a test-only seam, which Overrides.setForTest is (package-private to io.github.
   // brooswitminecraft.div, deliberately inaccessible from this experimental sub-package).
   Files.writeString(configPath, "{\"templates\":{},\"vehicles\":{\""+key+"\":{\"driveForce\":4321.0}},\"parts\":{}}");
   ownership.configWasWritten = true;
   var reloadResult = Overrides.reload();
   if (!reloadResult.success()) {
    LOGGER.error("[DIV tuningCommandProbe] COMPLETE - FAILED: seeding the operator override via Overrides.reload() itself failed: "+reloadResult.message());
    return;
   }
   var capture = new CapturingCommandSource();
   CommandSourceStack source = level.getServer().createCommandSourceStack().withSource(capture);
   String command = "div tuning "+minecraftEntity.getUUID();
   int result;
   try {
    result = level.getServer().getCommands().getDispatcher().execute(command, source);
   } catch (com.mojang.brigadier.exceptions.CommandSyntaxException ex) {
    LOGGER.error("[DIV tuningCommandProbe] COMPLETE - FAILED: command '"+command+"' failed to parse/execute", ex);
    return;
   }
   LOGGER.info("[DIV tuningCommandProbe] executed '"+command+"' - dispatcher result="+result+" linesCaptured="+capture.lines.size());
   boolean sawUnwiredNotice = capture.lines.stream().anyMatch(l -> l.contains("not wired to any source in this build"));
   boolean sawVehicleHeader = capture.lines.stream().anyMatch(l -> l.contains("vehicle "+key));
   boolean sawOverriddenField = capture.lines.stream().anyMatch(l -> l.contains("driveForce=4321.0") && l.contains("operator override"));
   boolean allPassed = result == 1 && sawUnwiredNotice && sawVehicleHeader && sawOverriddenField;
   if (allPassed) {
    LOGGER.info("[DIV tuningCommandProbe] COMPLETE - PASSED: real dispatch resolved the real selector (by the real Minecraft-side UUID) to the real spawned vehicle via the fixed wrapper/entity accessor, and rendered the real operator-override value.");
   } else {
    LOGGER.error("[DIV tuningCommandProbe] COMPLETE - FAILED: result="+result+" sawUnwiredNotice="+sawUnwiredNotice
      +" sawVehicleHeader="+sawVehicleHeader+" sawOverriddenField="+sawOverriddenField+" capturedLines="+capture.lines);
   }
  } catch (IOException ex) {
   LOGGER.error("[DIV tuningCommandProbe] COMPLETE - FAILED: could not read/write the operator config file", ex);
  } finally {
   // MINECRAFT-548 hardening: this used to be the ONLY cleanup path, reached only from inside
   // execute() - a timeout in AWAIT_CHUNK/SETTLE, or an exception thrown before execute() ever
   // ran, bypassed it entirely. Every exit now funnels through the shared CLEANUP phase instead
   // (see cleanup(), and maybeRun's own RuntimeException handler), so this finally block's own
   // job is now just "always reach CLEANUP from here", not "do the cleanup itself".
   phase = Phase.CLEANUP;
  }
 }

 /** Reached from every terminal path (success, any AWAIT_CHUNK/SETTLE timeout, any EXECUTE
  * failure, or an unexpected exception from any phase) - restores/removes exactly the resources
  * {@link #ownership} recorded THIS run as actually owning, and nothing else. Idempotent:
  * {@link TuningCommandProbeOwnership#markCleanedUp} makes every subsequent {@code should*}
  * query false, so even a second call (should one ever happen) would act as a safe no-op. */
 private static void cleanup(ServerLevel level) {
  if (ownership.isCleanedUp()) { phase = Phase.DONE; return; }
  boolean configRestoredCleanly = true;
  if (ownership.shouldRestoreConfig()) {
   Path configPath = FMLPaths.CONFIGDIR.get().resolve("dynamic-immersive-vehicles.json");
   try {
    if (ownership.configExistedBefore) Files.writeString(configPath, ownership.originalConfigContent);
    else Files.deleteIfExists(configPath);
    Overrides.reload();
    String restoredContent = ownership.configExistedBefore ? Files.readString(configPath) : null;
    boolean restoredCleanly = ownership.configExistedBefore
      ? ownership.originalConfigContent.equals(restoredContent)
      : !Files.exists(configPath);
    if (restoredCleanly) {
     LOGGER.info("[DIV tuningCommandProbe] config restoration CONFIRMED byte-identical to the pre-probe original ("+(ownership.configExistedBefore?"restored":"deleted, as it did not exist before")+").");
    } else {
     LOGGER.error("[DIV tuningCommandProbe] config restoration MISMATCH - the file does not read back as byte-identical to the pre-probe original. Manual check needed: "+configPath);
     configRestoredCleanly = false;
    }
   } catch (IOException ex) {
    LOGGER.error("[DIV tuningCommandProbe] failed to restore the original operator config file - manual check needed: "+configPath, ex);
    configRestoredCleanly = false;
   }
  }
  if (ownership.shouldRemoveVehicle() && vehicle != null) {
   vehicle.remove();
  }
  // Review finding carried into this hardening: an earlier version unconditionally cleared the
  // force-load here, even when the chunk had already been force-loaded by someone else before
  // this probe ran - that silently dropped another owner's own ticket. Only unforced when THIS
  // probe is the one that actually requested it.
  if (ownership.shouldUnforceChunk()) {
   level.setChunkForced(0, 0, false);
  }
  // Review finding carried into this hardening: inserted pack config entries were never restored
  // at all - left behind indefinitely regardless of outcome. Removed now, but only the entries
  // THIS run actually inserted (putIfAbsent found absent beforehand).
  if (ownership.shouldRemovePackSpeedFactor()) {
   minecrafttransportsimulator.systems.ConfigSystem.settings.general.packSpeedFactors.value.remove(PACK_ID);
  }
  if (ownership.shouldRemovePackVehicleScale()) {
   minecrafttransportsimulator.systems.ConfigSystem.settings.general.packVehicleScales.value.remove(PACK_ID);
  }
  ownership.markCleanedUp();
  phase = Phase.DONE;
  LOGGER.info("[DIV tuningCommandProbe] CLEANUP "+(configRestoredCleanly?"complete - ALL_DONE":"FAILED for config restore, see above - manual check needed")+".");
 }

 private static EntityVehicleF_Physics buildAndSpawn(AWrapperWorld world) {
  var definition = new JSONVehicle();
  definition.packID = PACK_ID; definition.systemName = SYSTEM_NAME;
  definition.motorized = new JSONVehicle.VehicleMotorized();
  definition.motorized.litVariable = ""; definition.motorized.panel = "";
  // Required - AEntityVehicleC_Colliding's constructor reads this native mass field
  // unconditionally (confirmed live: an unset value NPEs there). Value is otherwise irrelevant
  // here - this probe never touches physics - matching FlightRotorRuntimeFixtureProbe's own
  // minimal-definition convention.
  definition.motorized.emptyMass = 900;
  var sub = new minecrafttransportsimulator.jsondefs.JSONSubDefinition();
  sub.subName = ""; sub.name = SYSTEM_NAME;
  definition.definitions = List.of(sub);
  definition.rendering = new minecrafttransportsimulator.jsondefs.JSONRendering();
  definition.rendering.modelType = minecrafttransportsimulator.jsondefs.JSONRendering.ModelType.NONE;
  definition.general = new minecrafttransportsimulator.jsondefs.AJSONItem.General();
  definition.general.health = 100;
  var mainBox = new JSONCollisionBox();
  mainBox.pos = new Point3D(0, 0, 0);
  mainBox.width = 1; mainBox.height = 1;
  var group = new JSONCollisionGroup();
  group.collisionTypes = Set.of(JSONCollisionGroup.CollisionType.VEHICLE);
  group.collisions = List.of(mainBox);
  definition.collisionGroups = List.of(group);
  definition.parts = new ArrayList<>();
  var item = new ItemVehicle(definition, definition.definitions.get(0), PACK_ID);
  var vehicle = new EntityVehicleF_Physics(world, null, item, null);
  vehicle.position.set(8.5, 150, 8.5);
  world.spawnEntity(vehicle);
  // Retain ownership the moment the entity actually exists in the world (review convention,
  // same reasoning FlightRotorRuntimeFixtureProbe's own spawn path documents: a failure between
  // here and an earlier "mark owned" point would otherwise leak a spawned-but-untracked entity).
  ownership.vehicleSpawned = true;
  LOGGER.info("[DIV tuningCommandProbe] spawned "+PACK_ID+":"+SYSTEM_NAME+" uuid="+vehicle.uniqueUUID);
  return vehicle;
 }

 /** A minimal real {@code CommandSource} that captures every {@code sendSystemMessage} call's
  * plain text instead of writing to the console - so this probe can assert on the ACTUAL
  * content {@code CommandSourceStack.sendSuccess}/{@code sendFailure} send, through the real
  * production code path, not a hand-rolled fake sink standing in for it. */
 private static final class CapturingCommandSource implements CommandSource {
  final List<String> lines = new ArrayList<>();
  @Override public void sendSystemMessage(Component component) { lines.add(component.getString()); }
  @Override public boolean acceptsSuccess() { return true; }
  @Override public boolean acceptsFailure() { return true; }
  @Override public boolean shouldInformAdmins() { return false; }
 }
}
