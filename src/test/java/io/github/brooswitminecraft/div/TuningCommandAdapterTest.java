package io.github.brooswitminecraft.div;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Ticket MINECRAFT-546 review (comment 54209/54213/54221): the actual command/adapter path,
 * exercised through REAL execution of {@code TuningCommand}'s own production code - never
 * reimplemented here - made possible by this project's build enabling the neoForge moddev
 * plugin's official unit-test support (see {@code build.gradle}'s own {@code unitTest} comment),
 * which puts the real Minecraft/NeoForge/Brigadier classes on this test source set's classpath
 * for the first time.
 *
 * <p>Covers exactly what John's own comment 54209/54221 describes as sufficient and acceptable:
 * a real {@code CommandDispatcher<CommandSourceStack>} and the real {@code TuningCommand.
 * register} (registration shape, including the actual permission REQUIREMENT PREDICATE
 * evaluated against real {@code CommandSourceStack} instances at different permission levels -
 * not merely checked for non-null); and {@code TuningCommand.respond} - the real command
 * DECISION logic {@code run} delegates to after extracting the selector argument - invoked
 * directly with a real, minimally-constructed {@code CommandSourceStack} (a {@code null} level/
 * server is never dereferenced by {@code hasPermission}/{@code sendSuccess}/{@code sendFailure}
 * with {@code allowLogging=false} - confirmed against the real method bytecode) wrapping a
 * capturing {@code CommandSource} fake, so assertions are against the ACTUAL text {@code
 * CommandSourceStack.sendSuccess}/{@code sendFailure} send, through the real production method,
 * not a hand-rolled stand-in for it. {@code TuningCommand.LookupResult} values are supplied
 * directly, standing in for what a live {@link TuningCommand.VehicleLookup} would have produced -
 * the "fake resolver" collaborator John's comment explicitly allows, since constructing a real
 * {@code EntityVehicleF_Physics}/Sable body remains infeasible in any unit test (see {@code
 * PhysicsBridgeStepperTest}'s own doc).
 *
 * <p>NOT covered, and not achievable from any unit test regardless: {@code TuningCommand.run}'s
 * own {@code EntityArgument.getEntity}/UUID-to-live-vehicle extraction, which needs a live,
 * running {@code ServerLevel} with a real spawned vehicle in it - the same pre-existing
 * constraint. That remaining glue is a few directly-inspectable lines; every DECISION it leads
 * to is covered here.
 */
class TuningCommandAdapterTest {
 @AfterEach void resetOverrides() {
  Overrides.setForTest(Map.of(), Map.of(), Map.of());
 }

 @Test void registersDivTuningWithTargetArgument() {
  var dispatcher = new CommandDispatcher<CommandSourceStack>();
  TuningCommand.register(dispatcher);
  CommandNode<CommandSourceStack> div = dispatcher.getRoot().getChild("div");
  assertNotNull(div, "the command tree must register a 'div' literal");
  CommandNode<CommandSourceStack> tuning = div.getChild("tuning");
  assertNotNull(tuning, "'div' must have a 'tuning' child literal");
  assertEquals(1, tuning.getChildren().size(), "'tuning' must have exactly one child: the target argument");
  var targetNode = tuning.getChildren().iterator().next();
  assertInstanceOf(ArgumentCommandNode.class, targetNode, "the 'tuning' child must be an argument node, not another literal");
  assertEquals("target", ((ArgumentCommandNode<?, ?>) targetNode).getName());
 }

 @Test void realPermissionRequirementAllowsLevel2AndDeniesLevel1() {
  var dispatcher = new CommandDispatcher<CommandSourceStack>();
  TuningCommand.register(dispatcher);
  var requirement = dispatcher.getRoot().getChild("div").getRequirement();
  assertTrue(requirement.test(sourceWithPermission(2)), "permission level 2 (op) must be allowed");
  assertTrue(requirement.test(sourceWithPermission(4)), "a higher permission level must still be allowed");
  assertFalse(requirement.test(sourceWithPermission(1)), "permission level 1 must be denied - this is an operator-only diagnostic");
  assertFalse(requirement.test(sourceWithPermission(0)), "permission level 0 (no permissions) must be denied");
 }

 @Test void respondReportsNotEligibleForANonVehicleTarget() {
  var source = sourceWithPermission(2);
  var capture = (CapturingCommandSource) source.source;
  int result = TuningCommand.respond(source, "Cow", TuningCommand.LookupResult.NOT_FOUND);
  assertEquals(0, result, "a non-eligible target must report failure (0), not success");
  assertEquals(List.of("[DIV] Cow is not a DIV-eligible vehicle entity."), capture.lines);
 }

 @Test void respondReportsNoLoadedDefinitionForAVehicleWithNoDefinition() {
  var source = sourceWithPermission(2);
  var capture = (CapturingCommandSource) source.source;
  int result = TuningCommand.respond(source, "ignored", TuningCommand.LookupResult.NO_DEFINITION);
  assertEquals(0, result);
  assertEquals(List.of("[DIV] that vehicle has no loaded definition."), capture.lines);
 }

 @Test void respondRendersTheRealResponseForAResolvedVehicleThroughRealSendSuccess() {
  var override = new Overrides.Tuning();
  override.driveForce = 4321.0;
  Overrides.setForTest(Map.of("divtest:probe", override));
  var source = sourceWithPermission(2);
  var capture = (CapturingCommandSource) source.source;
  var resolved = new TuningCommand.ResolvedVehicle("divtest:probe", null, List.of());
  int result = TuningCommand.respond(source, "ignored", TuningCommand.LookupResult.of(resolved));
  assertEquals(1, result, "a resolved vehicle must report success (1)");
  assertTrue(capture.lines.stream().anyMatch(l -> l.contains("not wired to any source in this build")),
    "the real TuningProvenance.explainResponse output must have been sent through the real sendSuccess");
  assertTrue(capture.lines.stream().anyMatch(l -> l.contains("vehicle divtest:probe")));
  assertTrue(capture.lines.stream().anyMatch(l -> l.contains("driveForce=4321.0") && l.contains("operator override")));
 }

 @Test void respondIncludesInstalledPartExplanationsFromTheResolvedVehicle() {
  var partOverride = new Overrides.Tuning();
  partOverride.friction = 2.5;
  Overrides.setForTest(Map.of(), Map.of(), Map.of("divtest:tire", partOverride));
  var source = sourceWithPermission(2);
  var capture = (CapturingCommandSource) source.source;
  var resolved = new TuningCommand.ResolvedVehicle("divtest:probe", null, List.of("divtest:tire"));
  TuningCommand.respond(source, "ignored", TuningCommand.LookupResult.of(resolved));
  assertTrue(capture.lines.stream().anyMatch(l -> l.startsWith("[DIV] part divtest:tire")));
  assertTrue(capture.lines.stream().anyMatch(l -> l.contains("friction=2.5")));
 }

 private static CommandSourceStack sourceWithPermission(int level) {
  return new CommandSourceStack(new CapturingCommandSource(), Vec3.ZERO, Vec2.ZERO, null, level,
    "test", Component.literal("test"), null, null);
 }

 /** A minimal real {@code CommandSource} capturing every {@code sendSystemMessage} call's plain
  * text - called by both the real {@code sendSuccess} and {@code sendFailure} (confirmed against
  * their bytecode) - so a test can assert on the ACTUAL content without a hand-rolled sink. */
 private static final class CapturingCommandSource implements CommandSource {
  final List<String> lines = new ArrayList<>();
  @Override public void sendSystemMessage(Component component) { lines.add(component.getString()); }
  @Override public boolean acceptsSuccess() { return true; }
  @Override public boolean acceptsFailure() { return true; }
  @Override public boolean shouldInformAdmins() { return false; }
 }
}
