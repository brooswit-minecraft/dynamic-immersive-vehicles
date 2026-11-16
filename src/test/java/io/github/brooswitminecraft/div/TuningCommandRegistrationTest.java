package io.github.brooswitminecraft.div;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Ticket MINECRAFT-546 review (comment 54202): the actual command/adapter REGISTRATION path,
 * exercised against the real {@code TuningCommand} class - made possible by enabling this
 * project's build's official NeoForge moddev "unit test" support ({@code neoForge { unitTest {
 * enable() } }}, see build.gradle's own comment), which wires the real Minecraft/NeoForge/MTS/
 * Brigadier classes onto this test source set's classpath for the first time. Before that
 * change, merely referencing {@code TuningCommand} from any test failed with {@code
 * NoClassDefFoundError} on class load (see {@code TuningCommandTest}'s own doc for the full
 * investigation) - this test is the direct proof that gap is now closed for registration.
 *
 * <p>Still NOT covered here (and not achievable from ANY unit test in this codebase, unit-test-
 * support enabled or not): full command EXECUTION against a real spawned {@code
 * EntityVehicleF_Physics}/Sable body, or a real {@code CommandSourceStack} instance (both need a
 * live, running dedicated server/world - see {@code PhysicsBridgeStepperTest}'s own doc for this
 * pre-existing, unrelated constraint). Registration needs no such instance at all - {@code
 * CommandDispatcher.register} only ever builds a tree, it never executes anything - so this is
 * exactly the boundary of what IS testable: does {@code TuningCommand.register} actually wire up
 * the right literals/argument/permission gate, independent of ever running the command.
 */
class TuningCommandRegistrationTest {
 @Test void registersDivTuningWithTargetArgumentAndAPermissionGate() {
  var dispatcher = new CommandDispatcher<CommandSourceStack>();
  TuningCommand.register(dispatcher);
  CommandNode<CommandSourceStack> div = dispatcher.getRoot().getChild("div");
  assertNotNull(div, "the command tree must register a 'div' literal");
  assertNotNull(div.getRequirement(), "the 'div' literal must carry a permission requirement - never executable unconditionally");
  CommandNode<CommandSourceStack> tuning = div.getChild("tuning");
  assertNotNull(tuning, "'div' must have a 'tuning' child literal");
  assertEquals(1, tuning.getChildren().size(), "'tuning' must have exactly one child: the target argument");
  var targetNode = tuning.getChildren().iterator().next();
  assertInstanceOf(ArgumentCommandNode.class, targetNode, "the 'tuning' child must be an argument node, not another literal");
  assertEquals("target", ((ArgumentCommandNode<?, ?>) targetNode).getName());
 }
}
