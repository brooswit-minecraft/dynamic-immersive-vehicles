package io.github.brooswitminecraft.div;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Opt-in diagnostic for the still-open initial-placement/asymmetric-support rollover question
 * (ticket MINECRAFT-503, John's exact report: flat-ground placement sinks/tilts left/bangs then
 * slides sideways into a slope and tips; a hill placement rolls down without sinking, then flips
 * while driving). Off by default; enabled only via {@code -Ddiv.debugGroundContact=true}.
 *
 * <p>Deliberately its own top-level class, kept out of both the outer {@code PhysicsBridge} class
 * and {@code activateOrClassify}'s generic signature: those are loaded directly by pure unit
 * tests (with fake generic types, for {@code activateOrClassify}), and merely referencing a
 * Minecraft/SLF4J type from a method on the outer class would make the JVM's class verifier
 * require Log4j/SLF4J to be resolvable just to load {@code PhysicsBridge.class} at all, breaking
 * every one of those tests - confirmed the hard way earlier on this branch
 * ({@code gravityMagnitude}). This class's own methods take only plain JDK/project types in their
 * signatures (never a Minecraft or SLF4J type), so {@code PhysicsBridge.active()} can call them
 * freely without that risk - only loading THIS class (never touched by a pure unit test) actually
 * resolves the logger.
 *
 * <p>Revision history on this branch, each fixing a real gap a live manual test or review
 * actually found:
 * <ul>
 *  <li>v1 used {@code System.out.println} directly from {@code Body.stepGround} - produced ZERO
 *  records in the actual hosted/launcher's latest.log/debug.log despite the flag being confirmed
 *  set and the exact build confirmed installed (comment 52877): stdout capture cannot be assumed
 *  in that environment.</li>
 *  <li>v2 routed through {@code LogUtils}' SLF4J logger (the same mechanism every other mod's
 *  logging relies on to actually reach latest.log/debug.log), and added activation/classification/
 *  construction-failure visibility - but logged every {@code active()} call unconditionally
 *  (every tick, client AND server, for every vehicle): unlike the already-bounded wheel trace,
 *  that is unbounded spam, its one-time "startup marker" only fired once {@code Body.class} was
 *  first touched (never, for a vehicle that falls back before reaching that path at all - exactly
 *  the failure mode this diagnostic exists to catch), two identical vehicles of the same
 *  definition were not distinguishable from each other, and the earliest-return paths
 *  (ineligible/broken-definition/no sub-level container) were not logged at all (comment 52889).</li>
 *  <li>v3 (this version) logs per-ENTITY (by {@code uniqueUUID}, so two identical ATVs are
 *  distinguishable) and only on an actual transition in that entity's own classification/
 *  activation/early-return state - not every tick - bounding output to real state changes rather
 *  than call frequency; tags client vs. server side; covers the early ineligible/broken-
 *  definition/no-container returns explicitly; and the startup marker is called directly from
 *  the mod's actual constructor ({@code DynamicImmersiveVehicles}), independent of any vehicle
 *  ever reaching {@code Body} at all, so logging readiness is verifiable even when every vehicle
 *  falls back early.</li>
 * </ul>
 */
final class GroundContactDiagnostics {
 static final boolean ENABLED = Boolean.getBoolean("div.debugGroundContact");
 private static final Logger LOGGER = LogUtils.getLogger();
 private GroundContactDiagnostics() {}
 /** Called once from the mod's own startup, independent of any vehicle ever activating - proves
  * logging readiness even when every vehicle falls back before reaching any later diagnostic
  * call at all. */
 static void logStartupMarker() {
  if (ENABLED) LOGGER.info("[DIV debugGroundContact] diagnostics ENABLED (-Ddiv.debugGroundContact=true) - mod startup reached");
 }
 // Per-entity (java.util.UUID, not per-definition - two identical ATVs must be distinguishable)
 // last-logged state string. A line is only emitted when this entity's own state actually
 // changes (including the first observation, from no prior entry), not on every active() call -
 // active() runs every tick for every vehicle, client and server, so logging unconditionally
 // there would be unbounded spam (the exact v2 regression comment 52889 found).
 private static final Map<UUID, String> lastLogged = new ConcurrentHashMap<>();
 private static void logOnTransition(UUID entityId, boolean isClient, String state, String line) {
  if (!ENABLED) return;
  if (!state.equals(lastLogged.put(entityId, state))) LOGGER.info(line, entityId, isClient ? "client" : "server");
 }
 /** Logs one of active()'s own early-return reasons (no definition, ineligible for either
  * domain, or a cached-broken definition) - the paths a vehicle can fall back on before any
  * classification/activation decision is even computed, which were entirely invisible before. */
 static void logEarlyReturn(UUID entityId, boolean isClient, String reason) {
  logOnTransition(entityId, isClient, "early:"+reason, "[DIV debugGroundContact] earlyReturn entity={} side={} reason="+reason);
 }
 /** Logs this entity's classification/activation decision. */
 static void logClassification(UUID entityId, boolean isClient, String key, boolean ground, boolean floating, String decision) {
  logOnTransition(entityId, isClient, "classify:"+ground+":"+floating+":"+decision,
    "[DIV debugGroundContact] classify entity={} side={} key="+key+" ground="+ground+" floating="+floating+" decision="+decision);
 }
 /** Wraps a body-construction supplier so a swallowed InvalidChassisGeometry/RuntimeException
  * (activateOrClassify's own job to catch and classify - this does not change that outcome, only
  * observes it) and a successful construction are both logged with this entity's identity and
  * definition key, before the exception or result reaches activateOrClassify unchanged. Returns
  * the original supplier unchanged when the diagnostic is disabled - zero overhead/behavior
  * change in the default/released configuration. */
 static <T> Supplier<T> instrumentedConstruct(Supplier<T> construct, UUID entityId, String key) {
  if (!ENABLED) return construct;
  return () -> {
   try {
    T body = construct.get();
    LOGGER.info("[DIV debugGroundContact] constructed entity={} key="+key+" class={}", entityId, body.getClass().getSimpleName());
    return body;
   } catch (RuntimeException ex) {
    LOGGER.warn("[DIV debugGroundContact] constructionFailed entity={} key="+key+" exception={} message={}", entityId, ex.getClass().getName(), ex.getMessage());
    throw ex;
   }
  };
 }
}
