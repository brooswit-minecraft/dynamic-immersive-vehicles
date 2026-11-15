package io.github.brooswitminecraft.div;
import dev.ryanhcode.sable.api.physics.object.box.BoxPhysicsObject;
import net.minecraft.server.level.ServerLevel;
import org.joml.Vector3dc;

/**
 * The minimal pose/anchor surface {@code PhysicsBridge.createHitch} needs from EITHER side of a
 * hitch joint: the Sable level/box a {@code GenericConstraintConfiguration} connects, and the
 * body-local origin ({@code center}) {@link HitchJoint#anchorLocalOffset} composes a connection's
 * vehicle-root-local {@code pos} against - exactly the three fields {@code createHitch} already
 * read directly off {@code PhysicsBridge.Body} before this ticket. MINECRAFT-545: a float-only
 * hull's own {@link BuoyantBody} already holds the identical three fields (see its own
 * constructor) - it was simply never exposed to {@code PhysicsBridge}'s joint code, because
 * nothing needed a towed/towing boat before. Implemented by both {@code PhysicsBridge.Body}
 * (ground/flight/rotor) and {@link BuoyantBody} (float-only); {@code level()}'s return type here
 * covariantly narrows {@link VehicleBody#level()}'s {@code Object}, so a single method on each
 * implementer satisfies both interfaces. Deliberately separate from {@link VehicleBody} itself -
 * a future body category need not support hitching just to be a {@code VehicleBody} - and from
 * the live entity/tick surface {@code VehicleBody} exposes: this is pure anchor geometry, read
 * identically for either side of a joint regardless of which domain produced it.
 */
interface HitchAnchor {
 ServerLevel level();
 BoxPhysicsObject box();
 Vector3dc center();
}
