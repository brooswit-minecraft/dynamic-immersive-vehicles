package io.github.brooswitminecraft.div.mixin;
import minecrafttransportsimulator.entities.instances.PartEngine;
import minecrafttransportsimulator.entities.instances.PartPropeller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.List;
/** Exposes IV's own private linkedPropellers - the exact propellers this engine actually
 * drives, not reachable through any public API - so BuoyantForces can allocate underwater
 * thrust per engine-to-propeller linkage instead of one vehicle-wide signal applied to every
 * installed propeller regardless of whether this engine drives it at all. */
@Mixin(value=PartEngine.class, remap=false)
public interface PartEngineAccessor { @Accessor("linkedPropellers") List<PartPropeller> div$linkedPropellers(); }
