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
 *  <li>v3 logged per-ENTITY (by {@code uniqueUUID}, so two identical ATVs are distinguishable)
 *  and only on an actual transition in that entity's own classification/activation/early-return
 *  state - not every tick - bounding output to real state changes rather than call frequency;
 *  tagged client vs. server side; covered the early ineligible/broken-definition/no-container
 *  returns explicitly; and the startup marker was called directly from the mod's actual
 *  constructor ({@code DynamicImmersiveVehicles}), independent of any vehicle ever reaching
 *  {@code Body} at all - but two gaps remained (comment 52897): {@code instrumentedConstruct}
 *  still logged every construction attempt unconditionally (a RETRYABLE construction failure -
 *  anything but {@code InvalidChassisGeometry} - is retried every tick by {@code
 *  activateOrClassify} with no cache, so a sustained failure would still log forever); and the
 *  transition state was keyed by entity UUID alone, not UUID+side, so in an integrated/
 *  singleplayer environment where the same entity's client and server logical sides both call
 *  {@code active()}, their two independently-alternating decisions (a client side always reports
 *  {@code CLIENT_ACTIVE}; the server computes the real one) would look like a continuous
 *  client/server/client/server transition and log forever too.</li>
 *  <li>v4 (this version) keys every transition - construction included - by entity UUID AND
 *  side together, so each side's own history is tracked independently and neither alternates
 *  the other into permanent "transitioning." Construction now goes through the same bounded
 *  transition helper as classification/early-return, so a sustained retryable construction
 *  failure logs once, not every tick.</li>
 * </ul>
 */
final class GroundContactDiagnostics {
 static final boolean ENABLED = Boolean.getBoolean("div.debugGroundContact");
 private static final Logger LOGGER = LogUtils.getLogger();
 private GroundContactDiagnostics() {}
 /** Called once from the mod's own startup, independent of any vehicle ever activating - proves
  * logging readiness even when every vehicle falls back before reaching any later diagnostic
  * call at all. {@code debugGroundContactTicks} and {@code effectiveFrictionSolverIterations}
  * are the already-resolved (validated/defaulted, see {@code PhysicsBridge.resolveDebugGroundContactTicks}
  * and {@code PhysicsBridge.resolveExperimentalFrictionSolverIterations}) values Body will use
  * this run - surfaced here so a configured-but-invalid/out-of-range value is visible at startup
  * rather than only inferable from counting log lines later (ticket comments 53098, 53127). */
 static void logStartupMarker(int debugGroundContactTicks, int effectiveFrictionSolverIterations) {
  if (ENABLED) LOGGER.info("[DIV debugGroundContact] diagnostics ENABLED (-Ddiv.debugGroundContact=true) - mod startup reached, groundContactTicks="+debugGroundContactTicks
    +" effectiveFrictionSolverIterations="+effectiveFrictionSolverIterations);
 }
 // Keyed by entity UUID AND side together (a plain string key, "<uuid>:client"/"<uuid>:server"),
 // not UUID alone: in an integrated/singleplayer environment the same entity's client and server
 // logical sides both call active() independently, and conflating their two separately-
 // alternating histories into one entry would make every call look like a transition forever
 // (comment 52897). A line is only emitted when THIS side's own state for this entity actually
 // changes (including the first observation), not on every call - active() runs every tick for
 // every vehicle, so logging unconditionally would be unbounded spam (comment 52889).
 private static final Map<String, String> lastLogged = new ConcurrentHashMap<>();
 private static String sideKey(UUID entityId, boolean isClient) { return entityId + ":" + (isClient ? "client" : "server"); }
 private static void logOnTransition(UUID entityId, boolean isClient, String state, String line) {
  if (!ENABLED) return;
  if (!state.equals(lastLogged.put(sideKey(entityId, isClient), state))) LOGGER.info(line, entityId, isClient ? "client" : "server");
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
  * definition key, before the exception or result reaches activateOrClassify unchanged - but
  * only on a transition in THIS entity+side's own last construction outcome, since a retryable
  * (non-InvalidChassisGeometry) failure is retried every tick with no cache and would otherwise
  * log forever. Returns the original supplier unchanged when the diagnostic is disabled - zero
  * overhead/behavior change in the default/released configuration. */
 static <T> Supplier<T> instrumentedConstruct(Supplier<T> construct, UUID entityId, boolean isClient, String key) {
  if (!ENABLED) return construct;
  return () -> {
   try {
    T body = construct.get();
    String className = body.getClass().getSimpleName();
    logOnTransition(entityId, isClient, "construct:ok:"+className,
      "[DIV debugGroundContact] constructed entity={} side={} key="+key+" class="+className);
    return body;
   } catch (RuntimeException ex) {
    logOnTransition(entityId, isClient, "construct:fail:"+ex.getClass().getName()+":"+ex.getMessage(),
      "[DIV debugGroundContact] constructionFailed entity={} side={} key="+key+" exception="+ex.getClass().getName()+" message="+ex.getMessage());
    throw ex;
   }
  };
 }
}
