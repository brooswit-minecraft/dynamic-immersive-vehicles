package io.github.brooswitminecraft.div;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
/**
 * Ticket MINECRAFT-546: explains, per {@code Overrides.Tuning} field, which precedence level
 * actually won for a given vehicle or installed-part key - the "effective tuning values and
 * provenance" the ticket asks for. Deliberately reuses the actual shared resolver rather than
 * reimplementing any part of its precedence: {@link Overrides#effective}/{@link
 * Overrides#effectivePartOverride} for the real {@code extends}-chain merge, {@link
 * Overrides#defaultTuning} for the real documented fallback, and {@link
 * PhysicsBridge#applyLiveSafeFields}/{@link PhysicsBridge#reportFieldsNeedingRespawn} for the
 * real live-reload-vs-respawn-required classification - this class only enumerates {@code
 * Tuning}'s own public fields (via reflection, so it never drifts out of sync with a sibling
 * sprint ticket adding a new field) and reports, per field, which of those already-computed
 * values is non-null first.
 *
 * <p>{@code packMetadata}/{@code ivAdapted}/{@code physicallyDerived} are NOT wired to any real
 * source anywhere in this codebase today - every production call site ({@code
 * PhysicsBridge.tuning}, every {@code experimental.*Probe}) passes {@code null} for all three to
 * {@link Overrides#resolved}/{@link Overrides#resolvedPart} (see that method's own doc). This
 * class therefore never fabricates a PACK_METADATA/IV_ADAPTED/PHYSICALLY_DERIVED source for any
 * field - only {@link Source#OPERATOR_OVERRIDE} and {@link Source#FALLBACK} (or {@link
 * Source#UNSET}, for the handful of fields with no flat fallback) can actually occur, and the
 * other three enum values exist only so a future caller that DOES wire one of those layers in can
 * report it accurately without another change here.
 */
final class TuningProvenance {
 private TuningProvenance() {}
 /** Where a field's resolved value actually came from. Only {@code OPERATOR_OVERRIDE},
  * {@code FALLBACK} and {@code UNSET} are reachable today - see the class doc. */
 enum Source { OPERATOR_OVERRIDE, PACK_METADATA, IV_ADAPTED, PHYSICALLY_DERIVED, FALLBACK, UNSET }
 /**
  * One field's resolved value and where it came from. {@code templateName} is non-null only when
  * {@code source} is {@code OPERATOR_OVERRIDE} AND this entry's own raw config did not set the
  * field directly - naming the nearest ancestor in the {@code extends} chain that actually set
  * it. {@code liveSafe}/{@code respawnRequired} are this field's real classification from {@link
  * PhysicsBridge} (see {@link #ensureClassified}) - both false means this field has no wired
  * runtime consumer classification at all yet (neither hot-reloadable nor known to need a
  * respawn), which is itself useful, truthful information for an operator, not an omission.
  */
 record FieldOrigin(String field, Object value, Source source, String templateName, boolean liveSafe, boolean respawnRequired) {}
 /**
  * The full explanation for one vehicle or part key. {@code found} is false when no config entry
  * exists at all for this key (every field then resolves purely from fallback/unset, same as a
  * real unconfigured vehicle). {@code extendsChain} names every template this entry's raw
  * (un-merged) config actually walks through, outermost-first, or empty if it sets no {@code
  * extends} at all.
  */
 record Explanation(String key, boolean isPart, boolean found, List<String> extendsChain, List<FieldOrigin> fields) {}
 private static final String EXTENDS_FIELD = "extendsTemplate";
 // Computed once, lazily, by delegating to PhysicsBridge's own real classification methods
 // (never re-stated here by hand) - see ensureClassified's own doc.
 private static Set<String> liveSafeFields;
 private static Set<String> respawnRequiredFields;
 private static Set<String> partSupportedFields;
 /**
  * Derives {@link #liveSafeFields}/{@link #respawnRequiredFields} by actually calling {@link
  * PhysicsBridge#applyLiveSafeFields}/{@link PhysicsBridge#reportFieldsNeedingRespawn} against a
  * pair of sentinel {@code Tuning} objects that differ on EVERY field (every {@code Double} set
  * to a distinct value, every {@code Boolean} set to opposite values) - whichever field names
  * those real methods actually report are the true, current classification, with no duplicated/
  * hand-maintained field list on this side to drift out of sync as a sibling ticket adds a field.
  * Two independent sentinel pairs are used (rather than reusing one pair across both calls) so
  * this can never depend on call order between the two real methods - {@code
  * applyLiveSafeFields}'s own doc says it mutates {@code target} in place, which would otherwise
  * leave the live-safe fields no longer different by the time {@code
  * reportFieldsNeedingRespawn} ran against the same objects.
  */
 private static synchronized void ensureClassified() {
  if (liveSafeFields != null) return;
  var applied = new ArrayList<String>();
  PhysicsBridge.applyLiveSafeFields(sentinel(1.0, true), sentinel(2.0, false), applied);
  liveSafeFields = Set.copyOf(applied);
  var pending = new TreeSet<String>();
  PhysicsBridge.reportFieldsNeedingRespawn(sentinel(1.0, true), sentinel(2.0, false), pending);
  respawnRequiredFields = Set.copyOf(pending);
  var partSupported = new TreeSet<String>();
  for (Field f : Overrides.Tuning.class.getFields()) {
   if (f.getName().equals(EXTENDS_FIELD)) continue;
   Overrides.Tuning probe = sentinel(0.0, false); // every field null except the one under test
   clearAllFields(probe);
   setField(f, probe, f.getType() == Boolean.class ? Boolean.TRUE : 1.0);
   try {
    probe.validateSupportedForPart();
    partSupported.add(f.getName());
   } catch (IllegalArgumentException ex) {
    // this field has no part-level consumer (Overrides.Tuning#validateSupportedForPart's own
    // doc) - not part of the part-supported set.
   }
  }
  partSupportedFields = Set.copyOf(partSupported);
 }
 private static Overrides.Tuning sentinel(double doubleValue, boolean boolValue) {
  Overrides.Tuning t = new Overrides.Tuning();
  for (Field f : Overrides.Tuning.class.getFields()) {
   if (f.getName().equals(EXTENDS_FIELD)) continue;
   setField(f, t, f.getType() == Boolean.class ? boolValue : doubleValue);
  }
  return t;
 }
 private static void clearAllFields(Overrides.Tuning t) {
  for (Field f : Overrides.Tuning.class.getFields()) {
   if (f.getName().equals(EXTENDS_FIELD)) continue;
   setField(f, t, null);
  }
 }
 private static void setField(Field f, Overrides.Tuning target, Object value) {
  try { f.set(target, value); } catch (IllegalAccessException ex) { throw new IllegalStateException(ex); }
 }
 private static Object readField(Field f, Overrides.Tuning target) {
  if (target == null) return null;
  try { return f.get(target); } catch (IllegalAccessException ex) { throw new IllegalStateException(ex); }
 }
 private static Field fieldByName(String name) {
  try { return Overrides.Tuning.class.getField(name); } catch (NoSuchFieldException ex) { throw new IllegalStateException(ex); }
 }
 static boolean isLiveSafe(String field) { ensureClassified(); return liveSafeFields.contains(field); }
 static boolean isRespawnRequired(String field) { ensureClassified(); return respawnRequiredFields.contains(field); }
 /** Whether {@code field} has an actual part-level consumer at all, derived from {@link
  * Overrides.Tuning#validateSupportedForPart} itself (see its own doc) rather than a second,
  * hand-maintained list of the same three fields. */
 static boolean isPartSupported(String field) { ensureClassified(); return partSupportedFields.contains(field); }
 /** The vehicle-level explanation for {@code key} (a vehicle's own {@code packID:systemName} -
  * the same key shape {@code PhysicsBridge.tuning} already uses). Every {@code Tuning} field but
  * the structural {@code extends} is included. */
 static Explanation explainVehicle(String key) { return explain(key, false); }
 /** The part-level explanation for {@code partKey} ({@code partPackID:partSystemName} - the same
  * key shape {@link Overrides#resolvedPart} already uses). Only fields with an actual part-level
  * consumer ({@link #isPartSupported}) are included - every other field has no part-level
  * meaning at all (an entry that set one would already have failed to load, per {@link
  * Overrides.Tuning#validateSupportedForPart}), so including it here would be exactly the
  * "unrelated config contents" the ticket's acceptance explicitly excludes. */
 static Explanation explainPart(String partKey) { return explain(partKey, true); }
 private static Explanation explain(String key, boolean isPart) {
  Overrides.Tuning raw = isPart ? Overrides.getPart(key) : Overrides.get(key);
  boolean found = raw != null;
  // The actual shared resolver, never re-implemented: effective()/effectivePartOverride() walk
  // the real extends chain; defaultTuning() is the real documented fallback singleton (copied,
  // per its own doc, so nothing here can mutate the shared FALLBACK).
  Overrides.Tuning override = isPart ? Overrides.effectivePartOverride(key) : Overrides.effective(Overrides.get(key));
  Overrides.Tuning fallback = Overrides.defaultTuning();
  List<String> chain = extendsChain(raw);
  List<FieldOrigin> fields = new ArrayList<>();
  for (Field f : Overrides.Tuning.class.getFields()) {
   String name = f.getName();
   if (name.equals(EXTENDS_FIELD)) continue;
   if (isPart && !isPartSupported(name)) continue;
   Object overrideVal = readField(f, override);
   Source source; Object value; String templateName = null;
   if (overrideVal != null) {
    value = overrideVal;
    source = Source.OPERATOR_OVERRIDE;
    boolean setDirectly = readField(f, raw) != null;
    templateName = setDirectly ? null : nearestTemplateSetting(raw, f);
   } else {
    Object fallbackVal = readField(f, fallback);
    if (fallbackVal != null) { value = fallbackVal; source = Source.FALLBACK; }
    else { value = null; source = Source.UNSET; }
   }
   fields.add(new FieldOrigin(name, value, source, templateName, isLiveSafe(name), isRespawnRequired(name)));
  }
  return new Explanation(key, isPart, found, chain, List.copyOf(fields));
 }
 /** Walks the SAME {@code extends} links {@link Overrides#effective} resolves, purely to name
  * which template (if any) actually supplied {@code field} - a detail {@code effective}'s own
  * merged return value cannot express. A cycle is already impossible to reach here in practice
  * ({@code Overrides.readAndValidate} rejects one at load time, before this entry could ever be
  * active) - the visited-set below is defensive only, so a latent bug elsewhere degrades this
  * diagnostic to "stop early" instead of hanging. */
 private static String nearestTemplateSetting(Overrides.Tuning entry, Field field) {
  if (entry == null || entry.extendsTemplate == null) return null;
  Set<String> visited = new HashSet<>();
  String templateKey = entry.extendsTemplate;
  while (templateKey != null && visited.add(templateKey)) {
   Overrides.Tuning template = Overrides.getTemplate(templateKey);
   if (template == null) return null; // unknown target - Overrides.effective would already have rejected this at load time
   if (readField(field, template) != null) return templateKey;
   templateKey = template.extendsTemplate;
  }
  return null;
 }
 private static List<String> extendsChain(Overrides.Tuning raw) {
  if (raw == null || raw.extendsTemplate == null) return List.of();
  List<String> chain = new ArrayList<>();
  Set<String> visited = new HashSet<>();
  String templateKey = raw.extendsTemplate;
  while (templateKey != null && visited.add(templateKey)) {
   chain.add(templateKey);
   Overrides.Tuning template = Overrides.getTemplate(templateKey);
   templateKey = template != null ? template.extendsTemplate : null;
  }
  return List.copyOf(chain);
 }
 /**
  * Plain-text rendering of one {@link Explanation} - no Minecraft/Brigadier dependency, so this
  * is directly unit-testable; {@code TuningCommand} sends each returned line as its own chat
  * component. {@code label} is a short caller-supplied header (e.g. {@code "vehicle "+key} or
  * {@code "part "+key}) - this method does not itself decide vehicle-vs-part phrasing.
  */
 /**
  * Ticket MINECRAFT-546's explicit "report pack/native/derived layers as unwired unless actual
  * code proves otherwise": printed once per command invocation (not once per field - see {@link
  * #render}), not computed from any live state, because it is a true statement about THIS
  * CODEBASE's current call sites (see this class's own doc), not about any one vehicle's config.
  */
 static final String UNWIRED_LAYERS_NOTICE =
   "[DIV] pack DIV metadata, IV-adapted native values, and physically-derived values are not wired to any source in this build - only an explicit operator override or the documented fallback can ever win below.";
 static List<String> render(String label, Explanation explanation) {
  List<String> lines = new ArrayList<>();
  if (!explanation.found()) {
   lines.add("[DIV] "+label+": no operator-override entry - every field below is pack/native/derived-unwired fallback or unset.");
  } else if (!explanation.extendsChain().isEmpty()) {
   lines.add("[DIV] "+label+": extends "+String.join(" -> ", explanation.extendsChain()));
  } else {
   lines.add("[DIV] "+label+":");
  }
  for (FieldOrigin fo : explanation.fields()) {
   StringBuilder line = new StringBuilder("  ").append(fo.field()).append("=").append(fo.value());
   line.append(" [").append(describeSource(fo)).append("]");
   if (fo.liveSafe()) line.append(" (live-reloadable)");
   else if (fo.respawnRequired()) line.append(" (respawn required to apply a change)");
   lines.add(line.toString());
  }
  return lines;
 }
 /**
  * {@code TuningCommand}'s own vehicle-not-found failure message - kept here (not in {@code
  * TuningCommand}) purely so it stays unit-testable: this project's test source set has no
  * Minecraft/NeoForge/MTS classes on its classpath at all, and merely referencing {@code
  * TuningCommand} from a test fails to even load the class (confirmed: {@code
  * NoClassDefFoundError}) - see {@code TuningCommand}'s own doc.
  */
 static String notEligibleMessage(String targetDisplayName) {
  return "[DIV] "+targetDisplayName+" is not a DIV-eligible vehicle entity.";
 }
 /** {@code TuningCommand}'s own no-loaded-definition failure message - see {@link
  * #notEligibleMessage} for why this lives here instead. */
 static final String NO_DEFINITION_MESSAGE = "[DIV] that vehicle has no loaded definition.";
 /**
  * The full command-response assembly {@code TuningCommand.run} delegates to, extracted as a
  * pure function of plain data (never an {@code EntityVehicleF_Physics}/{@code APart}/{@code
  * CommandSourceStack}, none of which this project's test source set can even load - see {@code
  * TuningCommand}'s own doc) so it is directly unit-testable: every line this method needs is
  * read out of the live vehicle by {@code TuningCommand.run} first and passed in as plain data.
  * Dedupes {@code installedPartKeysInEncounterOrder} by part key (packID:systemName) - several
  * installed parts (e.g. four identical wheels) commonly share one definition/override entry, so
  * explaining the same key repeatedly would be noise, not new information - and skips any part
  * with no override entry at all (nothing to explain for it).
  */
 static List<String> explainResponse(String vehicleKey, boolean active, List<String> installedPartKeysInEncounterOrder) {
  List<String> lines = new ArrayList<>();
  lines.add(UNWIRED_LAYERS_NOTICE);
  lines.add("[DIV] vehicle "+vehicleKey+" - "+(active ? "active (spawned/physics-engaged)" : "not currently active"));
  lines.addAll(render("vehicle "+vehicleKey, explainVehicle(vehicleKey)));
  var explainedPartKeys = new LinkedHashSet<String>();
  for (String partKey : installedPartKeysInEncounterOrder) {
   if (!explainedPartKeys.add(partKey)) continue;
   if (Overrides.getPart(partKey) == null) continue;
   lines.addAll(render("part "+partKey, explainPart(partKey)));
  }
  return lines;
 }
 private static String describeSource(FieldOrigin fo) {
  return switch (fo.source()) {
   case OPERATOR_OVERRIDE -> fo.templateName() != null ? "operator override, via template '"+fo.templateName()+"'" : "operator override, direct";
   case FALLBACK -> "fallback (no operator override, pack metadata, or native/physically-derived value)";
   case UNSET -> "unset (no fallback defined for this field - feature off/not configured)";
   case PACK_METADATA -> "pack DIV metadata";
   case IV_ADAPTED -> "IV-adapted native value";
   case PHYSICALLY_DERIVED -> "physically-derived value";
  };
 }
}
