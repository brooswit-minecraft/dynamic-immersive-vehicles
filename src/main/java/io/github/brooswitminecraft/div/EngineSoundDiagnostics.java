package io.github.brooswitminecraft.div;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

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
 * <p>Logs every {@code SoundInstance} actually handed to IV's own native sound-playback entry
 * point ({@code mcinterface1211.InterfaceSound.playQuickSound}), via {@code InterfaceSoundMixin}.
 * This is the single point downstream of ALL of updateSounds' own gating (activeAnimations,
 * interior/exterior viewpoint selection, distance/conical checks) and upstream of actual OpenAL
 * playback - so whether an engine sound ever reaches this log line at all directly answers
 * "did every earlier client-side gate pass" without needing to instrument each gate separately.
 */
public final class EngineSoundDiagnostics {
 static final boolean ENABLED = Boolean.getBoolean("div.debugEngineSound");
 private static final Logger LOGGER = LogUtils.getLogger();
 private EngineSoundDiagnostics() {}
 /** Called from {@code InterfaceSoundMixin} (a different package - the mixin package - hence
  * public) at the head of {@code playQuickSound}, before any native OpenAL call - read-only,
  * never alters the sound in any way. */
 public static void logQuickSound(String soundPlayingName, float volume, float pitch, boolean looping, boolean isRadio) {
  if (ENABLED) LOGGER.info("[DIV debugEngineSound] playQuickSound soundPlayingName=\"" + soundPlayingName
    + "\" volume=" + volume + " pitch=" + pitch + " looping=" + looping + " isRadio=" + isRadio);
 }
}
