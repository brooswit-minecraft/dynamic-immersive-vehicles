package io.github.brooswitminecraft.div.mixin;
import io.github.brooswitminecraft.div.EngineSoundDiagnostics;
import minecrafttransportsimulator.sound.SoundInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/**
 * Review 53482's correction: logs at the TAIL of {@code playQuickSound}, not the HEAD - by then
 * the method has already either successfully allocated a native OpenAL source and added {@code
 * sound} to {@code sound.entity.sounds} (the real, observable success outcome), or silently
 * returned early without doing either (ALC not ready, OGG decode failure, or no free source) -
 * see {@link EngineSoundDiagnostics}'s own class doc for the full real call-order this reflects.
 * Deliberately never calls any {@code AL10} function itself (only reads {@code sound}'s own
 * already-set fields and plain list membership), so it cannot consume the sticky OpenAL error
 * flag the method's own subsequent calls still depend on reading.
 */
@Mixin(targets="mcinterface1211.InterfaceSound", remap=false)
public abstract class InterfaceSoundMixin {
 @Inject(method="playQuickSound", at=@At("RETURN"))
 private void div$logPlaybackOutcome(SoundInstance sound, CallbackInfo ci) {
  boolean addedToEntitySounds = sound.entity != null && sound.entity.sounds.contains(sound);
  EngineSoundDiagnostics.logPlaybackOutcome(sound.soundPlayingName, sound.volume, sound.pitch,
    sound.soundDef != null && sound.soundDef.looping, sound.radio != null, addedToEntitySounds, sound.sourceIndex);
 }
}
