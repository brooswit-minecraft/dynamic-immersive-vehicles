package io.github.brooswitminecraft.div.mixin;
import minecrafttransportsimulator.entities.instances.PartPropeller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
/** Exposes IV's own private currentRPM - not reachable through any public API - so
 * BuoyantForces can confirm a linked, active propeller is actually turning before giving it
 * underwater thrust, rather than inferring "powered" purely from isActiveVar (which only rules
 * out destroyed/removed). */
@Mixin(value=PartPropeller.class, remap=false)
public interface PartPropellerAccessor { @Accessor("currentRPM") double div$currentRPM(); }
