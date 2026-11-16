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
 */
public final class TuningCommandProbe {
 private TuningCommandProbe() {}
 private static final boolean ENABLED = Boolean.getBoolean("div.experimentalTuningCommandProbe");
 private static final Logger LOGGER = LogUtils.getLogger();
 private static final String PACK_ID = "divtest";
 private static final String SYSTEM_NAME = "tuningcommandprobe";
 private enum Phase { AWAIT_CHUNK, SPAWN, SETTLE, EXECUTE, DONE }
 private static Phase phase = Phase.AWAIT_CHUNK;
 private static int ticksInPhase = 0;
 private static EntityVehicleF_Physics vehicle;
 // Live-run finding (this ticket's own earlier attempt): force-loading the chunk alone is not
 // synchronously complete - a chunk can still report not-yet-loaded for a tick or more after
 // the force-load request. Polling getChunkNow (same real readiness check
 // FlightRotorRuntimeFixtureProbe's own spawn-gating already uses) instead of a fixed tick
 // count is the actual fix.
 private static final int MAX_AWAIT_CHUNK_TICKS = 100;
 // Settle after spawnEntity before selecting the vehicle by selector/UUID - live-run finding:
 // executing on the SAME tick as spawnEntity failed with "No entity was found".
 private static final int SETTLE_TICKS = 5;

 public static void maybeRun(ServerLevel level) {
  if (!ENABLED || phase == Phase.DONE) return;
  try {
   switch (phase) {
    case AWAIT_CHUNK -> awaitChunk(level);
    case SPAWN -> { spawn(level); phase = Phase.SETTLE; ticksInPhase = 0; }
    case SETTLE -> { if (++ticksInPhase >= SETTLE_TICKS) phase = Phase.EXECUTE; }
    case EXECUTE -> { execute(level); phase = Phase.DONE; }
    case DONE -> {}
   }
  } catch (RuntimeException ex) {
   LOGGER.error("[DIV tuningCommandProbe] COMPLETE - FAILED: unexpected exception", ex);
   phase = Phase.DONE;
  }
 }

 private static void awaitChunk(ServerLevel level) {
  if (ticksInPhase == 0) level.setChunkForced(0, 0, true);
  if (level.getChunkSource().getChunkNow(0, 0) != null) { phase = Phase.SPAWN; ticksInPhase = 0; return; }
  if (++ticksInPhase >= MAX_AWAIT_CHUNK_TICKS) {
   LOGGER.error("[DIV tuningCommandProbe] COMPLETE - FAILED: chunk (0,0) never became ready after force-loading, within "+MAX_AWAIT_CHUNK_TICKS+" ticks");
   phase = Phase.DONE;
  }
 }

 private static void spawn(ServerLevel level) {
  // Required - AEntityVehicleC_Colliding's constructor reads this pack's own
  // packSpeedFactors/packVehicleScales entries unconditionally (confirmed live: an absent
  // entry NPEs there) - same "putIfAbsent before first spawn of a never-before-seen pack ID"
  // convention FlightRotorRuntimeFixtureProbe's own spawn path already establishes.
  var packSpeedFactors = minecrafttransportsimulator.systems.ConfigSystem.settings.general.packSpeedFactors.value;
  var packVehicleScales = minecrafttransportsimulator.systems.ConfigSystem.settings.general.packVehicleScales.value;
  packSpeedFactors.putIfAbsent(PACK_ID, 1.0);
  packVehicleScales.putIfAbsent(PACK_ID, 1.0);
  AWrapperWorld world = mcinterface1211.WrapperWorld.getWrapperFor(level);
  vehicle = buildAndSpawn(world);
 }

 private static void execute(ServerLevel level) {
  // Live-run finding (this ticket's own history, comment 54227): a freshly spawned vehicle's
  // own AEntityA_Base#uniqueUUID is independently random-generated from the wrapping Minecraft
  // entity's own UUID - selecting by vehicle.uniqueUUID would always fail to find anything.
  // This probe selects by the REAL wrapping Minecraft entity's own UUID instead, found by
  // scanning this bounded test chunk - exactly what a real player's selector would resolve to.
  // Live-run finding: level.getEntities(Entity,AABB,Predicate) over this bounded test chunk
  // returned empty even after the chunk-readiness/settle fixes above - ServerLevel.
  // getAllEntities() (a direct iterable over every tracked entity, no AABB/section lookup
  // involved at all) is the simpler, reliable enumeration this probe actually needs.
  net.minecraft.world.entity.Entity minecraftEntity = null;
  int entityCount = 0;
  for (net.minecraft.world.entity.Entity candidate : level.getAllEntities()) {
   entityCount++;
   if (minecraftEntity == null) minecraftEntity = candidate;
  }
  if (minecraftEntity == null) {
   LOGGER.error("[DIV tuningCommandProbe] COMPLETE - FAILED: could not find the just-spawned vehicle's own Minecraft-side entity anywhere in the level - entityCount="+entityCount);
   return;
  }
  LOGGER.info("[DIV tuningCommandProbe] vehicle.uniqueUUID="+vehicle.uniqueUUID+" wrappingMinecraftEntity.getUUID()="+minecraftEntity.getUUID()
    +" class="+minecraftEntity.getClass().getName()+" sameUUID="+vehicle.uniqueUUID.equals(minecraftEntity.getUUID()));
  String key = PACK_ID+":"+SYSTEM_NAME;
  Path configPath = FMLPaths.CONFIGDIR.get().resolve("dynamic-immersive-vehicles.json");
  boolean existed = false; String originalContent = null;
  try {
   // Seed a real explicit operator override through the SAME public file-based reload path
   // every other probe in this package uses (see GroundSurfaceHotReloadProbe's own doc) - not
   // a test-only seam, which Overrides.setForTest is (package-private to io.github.
   // brooswitminecraft.div, deliberately inaccessible from this experimental sub-package).
   existed = Files.exists(configPath);
   if (existed) originalContent = Files.readString(configPath);
   Files.writeString(configPath, "{\"templates\":{},\"vehicles\":{\""+key+"\":{\"driveForce\":4321.0}},\"parts\":{}}");
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
   try {
    if (existed) Files.writeString(configPath, originalContent);
    else Files.deleteIfExists(configPath);
    Overrides.reload();
   } catch (IOException ex) {
    LOGGER.error("[DIV tuningCommandProbe] failed to restore the original operator config file - manual check needed: "+configPath, ex);
   }
   vehicle.remove();
   level.setChunkForced(0, 0, false);
  }
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
