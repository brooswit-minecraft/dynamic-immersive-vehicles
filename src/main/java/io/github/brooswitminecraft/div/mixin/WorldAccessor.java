package io.github.brooswitminecraft.div.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import net.minecraft.world.level.Level;
@Mixin(targets="mcinterface1211.WrapperWorld", remap=false)
public interface WorldAccessor { @Accessor("world") Level div$level(); }
