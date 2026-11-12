package io.github.brooswitminecraft.div.mixin;
import io.github.brooswitminecraft.div.EngineSoundDiagnostics;
import minecrafttransportsimulator.sound.SoundInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/**
 * Ticket MINECRAFT-503 comment 53475's own ask: a targeted, read-only diagnostic for whether an
 * engine sound ever reaches IV's own native sound-playback entry point at all. Non-cancelling,
 * never alters {@code sound} - see {@link EngineSoundDiagnostics}'s own class doc for why this
 * single injection point is sufficient to answer that question without instrumenting every
 * earlier client-side gate separately.
 */
@Mixin(targets="mcinterface1211.InterfaceSound", remap=false)
public abstract class InterfaceSoundMixin {
 @Inject(method="playQuickSound", at=@At("HEAD"))
 private void div$logQuickSound(SoundInstance sound, CallbackInfo ci) {
  EngineSoundDiagnostics.logQuickSound(sound.soundPlayingName, sound.volume, sound.pitch,
    sound.soundDef != null && sound.soundDef.looping, sound.radio != null);
 }
}
