package io.github.brooswitminecraft.div;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opt-in, read-only client-side diagnostic (ticket MINECRAFT-503 comment 53475: "cannot hear the
 * engine... need actual client engine_powered gate, selected inside/outside loop, computed
 * gain/pitch and OpenAL playback/source outcome"). Off by default; enabled only via
 * {@code -Ddiv.debugEngineSound=true}.
 *
 * <p>Own top-level class, kept out of {@code PhysicsBridge} for the same reason
 * {@link GroundContactDiagnostics} is: merely referencing a Minecraft/SLF4J type from a method on
 * the outer class would make the JVM's class verifier require those to be resolvable just to load
 * {@code PhysicsBridge.class} at all, breaking its own pure unit tests.
 *
 * <p>Review 53482's correction to this class's first version: {@code
 * AEntityD_Definable.updateSounds}'s own real call order is {@code playQuickSound(new
 * SoundInstance(...))} FIRST (synchronously loading the OGG, allocating an OpenAL source, and -
 * only on success - adding the instance to {@code entity.sounds}, all inside {@code
 * playQuickSound} itself), THEN, back in the caller, a loop over {@code entity.sounds} applies the
 * REAL volume/pitch switchbox computation. A {@code HEAD}-of-{@code playQuickSound} hook (this
 * class's first version) therefore only ever sees the brand-new instance's constructor defaults
 * (volume=pitch=1), never the real computed gain/pitch, and says nothing about whether a native
 * OpenAL source was actually allocated. Two separate, correctly-timed hooks now report each half:
 * {@link #logPlaybackOutcome} (via {@code InterfaceSoundMixin}, injected at {@code playQuickSound}'s
 * own TAIL, after the real success/failure is known) for the request + native-allocation outcome,
 * and {@link #logFinalGainPitch} (via {@code DefinableSoundMixin}, injected at {@code
 * updateSounds}'s own TAIL, after its full per-tick switchbox update loop has run) for the real
 * final computed volume/pitch. Neither ever calls any {@code AL10} function itself - observing
 * only plain Java state (list membership, the instance's own already-computed fields) - so neither
 * can accidentally consume the sticky OpenAL error flag the original method's own subsequent
 * {@code alGetError()} calls depend on reading themselves.
 *
 * <p>Filtered to sound names containing "engine" (case-insensitive) per review 53482's own ask -
 * every other sound in the game (footsteps, ambient, UI, radio) is never logged by either hook,
 * regardless of the flag - and rate-limited per unique sound name (only every {@link
 * #LOG_EVERY_NTH_CALL}th call logs) to bound total volume while driving for an extended test.
 */
public final class EngineSoundDiagnostics {
 static final boolean ENABLED = Boolean.getBoolean("div.debugEngineSound");
 private static final int LOG_EVERY_NTH_CALL = 20;
 private static final Logger LOGGER = LogUtils.getLogger();
 private static final Map<String, Integer> callCounters = new ConcurrentHashMap<>();
 private EngineSoundDiagnostics() {}
 private static boolean isEngineSound(String soundPlayingName) {
  return soundPlayingName != null && soundPlayingName.toLowerCase(java.util.Locale.ROOT).contains("engine");
 }
 private static boolean shouldLogThisCall(String soundPlayingName) {
  int count = callCounters.merge(soundPlayingName, 1, Integer::sum);
  return count % LOG_EVERY_NTH_CALL == 1; // log the 1st, (1+N)th, (1+2N)th... call for this name
 }
 /** Called from {@code InterfaceSoundMixin} at the TAIL of {@code playQuickSound}, after the
  * real native-allocation outcome is known - never before, and never touches any {@code AL10}
  * function itself. {@code addedToEntitySounds}/{@code sourceIndex} together say whether a
  * native OpenAL source was actually allocated and bound (true success) or the sound was
  * silently dropped (ALC not ready, decode failure, or no free source) - a plain Java
  * observation of {@code entity.sounds}' own list membership and the instance's own already-set
  * field, not an AL call. */
 public static void logPlaybackOutcome(String soundPlayingName, float requestedVolume, float requestedPitch,
   boolean looping, boolean isRadio, boolean addedToEntitySounds, int sourceIndex) {
  if (ENABLED && isEngineSound(soundPlayingName) && shouldLogThisCall("outcome:" + soundPlayingName))
   LOGGER.info("[DIV debugEngineSound] playbackOutcome soundPlayingName=\"" + soundPlayingName
     + "\" requestedVolume=" + requestedVolume + " requestedPitch=" + requestedPitch + " looping=" + looping
     + " isRadio=" + isRadio + " addedToEntitySounds=" + addedToEntitySounds + " sourceIndex=" + sourceIndex);
 }
 /** Called from {@code DefinableSoundMixin} at the TAIL of {@code updateSounds(float)}, after
  * its own per-tick switchbox update loop has already run for every currently-playing sound
  * belonging to that entity - {@code finalVolume}/{@code finalPitch} are the real values a
  * listening player would actually hear, not the placeholder construction defaults.
  * {@code configuredSoundVolume} is IV's own separate in-game sound-volume config setting (NOT
  * Minecraft's master/category volume - IV deliberately bypasses that), multiplied into the
  * REAL final AL_GAIN one further step downstream, in {@code InterfaceSound.update()}'s own
  * per-tick loop, which neither this hook nor {@link #logPlaybackOutcome} otherwise observes -
  * {@code effectiveGain} is that real product, computed here (not mutated/sent anywhere) purely
  * so a low/zero in-mod volume setting shows up directly instead of looking identical to a
  * healthy finalVolume. */
 public static void logFinalGainPitch(String soundPlayingName, float finalVolume, float finalPitch, float configuredSoundVolume) {
  if (ENABLED && isEngineSound(soundPlayingName) && shouldLogThisCall("final:" + soundPlayingName))
   LOGGER.info("[DIV debugEngineSound] finalGainPitch soundPlayingName=\"" + soundPlayingName
     + "\" finalVolume=" + finalVolume + " finalPitch=" + finalPitch
     + " configuredSoundVolume=" + configuredSoundVolume + " effectiveGain=" + (finalVolume * configuredSoundVolume));
 }
}
