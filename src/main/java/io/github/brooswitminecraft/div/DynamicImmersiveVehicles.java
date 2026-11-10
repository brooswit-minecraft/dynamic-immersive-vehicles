package io.github.brooswitminecraft.div;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
@Mod("dynamicimmersivevehicles")
public final class DynamicImmersiveVehicles {
 public DynamicImmersiveVehicles() {
  GroundContactDiagnostics.logStartupMarker();
  Overrides.load();
  NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> PhysicsBridge.cleanup());
  NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> PhysicsBridge.unload(event.getLevel()));
 }
}
