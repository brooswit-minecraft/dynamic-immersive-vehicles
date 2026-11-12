package io.github.brooswitminecraft.div.mixin;
import io.github.brooswitminecraft.div.EngineSoundDiagnostics;
import minecrafttransportsimulator.entities.components.AEntityB_Existing;
import minecrafttransportsimulator.sound.SoundInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/**
 * Review 53482's own ask: the real final per-tick computed gain/pitch, not the construction
 * defaults {@link InterfaceSoundMixin} alone could see. Injected at the TAIL of {@code
 * updateSounds(float)} itself - by then its own per-tick loop (volumeSwitchbox.runSound/
 * pitchSwitchbox.runSound, in the real pinned source) has already applied the real computed
 * values to every sound currently in {@code this.sounds} for this entity, including one that
 * started this exact same tick (it is synchronously added to {@code entity.sounds} from inside
 * {@code playQuickSound}, called earlier in this same method, before this loop runs - see
 * {@link EngineSoundDiagnostics}'s class doc for the full ordering). Reads only {@code
 * this.sounds}' own already-computed fields - no {@code AL10} call, no mutation.
 */
@Mixin(targets="minecrafttransportsimulator.entities.components.AEntityD_Definable", remap=false)
public abstract class DefinableSoundMixin {
 @Inject(method="updateSounds", at=@At("RETURN"))
 private void div$logFinalGainPitch(float partialTicks, CallbackInfo ci) {
  AEntityB_Existing entity = (AEntityB_Existing)(Object)this;
  for (SoundInstance sound : entity.sounds) {
   EngineSoundDiagnostics.logFinalGainPitch(sound.soundPlayingName, sound.volume, sound.pitch);
  }
 }
}
