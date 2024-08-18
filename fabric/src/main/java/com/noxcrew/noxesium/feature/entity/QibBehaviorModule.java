package com.noxcrew.noxesium.feature.entity;

import com.noxcrew.noxesium.NoxesiumModule;
import com.noxcrew.noxesium.api.qib.QibEffect;
import com.noxcrew.noxesium.feature.rule.ServerRules;
import com.noxcrew.noxesium.network.serverbound.ServerboundQibTriggeredPacket;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Applies qib behaviors whenever players clip interaction entities.
 */
public class QibBehaviorModule implements NoxesiumModule {

    private final Map<String, AtomicInteger> collidingWithTypes = new HashMap<>();
    private final Map<Entity, AtomicInteger> collidingWithEntities = new HashMap<>();
    private final Set<Entity> triggeredJump = new HashSet<>();
    private final List<Pair<AtomicInteger, Triple<LocalPlayer, Entity, QibEffect>>> pending = new ArrayList<>();

    @Override
    public void onStartup() {
        ClientTickEvents.END_WORLD_TICK.register((world) -> {
            // If there are no qib behaviors set, do nothing!
            if (ServerRules.QIB_BEHAVIORS.getValue().isEmpty()) return;

            // Check if the player is colliding with any interaction entities
            tickEffects();
            checkForCollisions();
        });
    }

    /**
     * Sends the server that the player triggered the given type of behavior.
     */
    private void sendPacket(String behavior, ServerboundQibTriggeredPacket.Type type, int entityId) {
        new ServerboundQibTriggeredPacket(behavior, type, entityId).send();
    }

    /**
     * Triggers when a player jumps.
     */
    public void onPlayerJump(LocalPlayer player) {
        // Do not allow triggering a jump while in an auto spin attack!
        if (player.isAutoSpinAttack()) return;

        // Do not allow jumping while in a vehicle.
        if (player.getVehicle() != null) return;

        for (var entity : collidingWithEntities.keySet()) {
            // Don't trigger jumping twice for the same entity!
            if (triggeredJump.contains(entity)) continue;

            // Check the behavior of the entity
            if (!entity.noxesium$hasExtraData(ExtraEntityData.QIB_BEHAVIOR)) continue;
            var behavior = entity.noxesium$getExtraData(ExtraEntityData.QIB_BEHAVIOR);
            var knownBehaviors = ServerRules.QIB_BEHAVIORS.getValue();
            if (!knownBehaviors.containsKey(behavior)) continue;

            // Try to trigger the jump behavior
            var definition = knownBehaviors.get(behavior);
            if (definition.onJump() != null) {
                sendPacket(behavior, ServerboundQibTriggeredPacket.Type.JUMP, entity.getId());
                executeBehavior(player, entity, definition.onJump());
                triggeredJump.add(entity);
            }
        }
    }

    /**
     * Ticks down scheduled effects and runs them.
     */
    private void tickEffects() {
        // Increment all timers
        collidingWithTypes.values().forEach(AtomicInteger::incrementAndGet);
        collidingWithEntities.values().forEach(AtomicInteger::incrementAndGet);

        var iterator = pending.iterator();
        while (iterator.hasNext()) {
            var pair = iterator.next();
            if (pair.getKey().decrementAndGet() <= 0) {
                var value = pair.getValue();
                iterator.remove();
                executeBehavior(value.getLeft(), value.getMiddle(), value.getRight());
            }
        }
    }

    /**
     * Checks for collisions with interaction entities.
     */
    private void checkForCollisions() {
        /*
         *  Ideally we would use vanilla's getEntities method as it uses the local chunks directly to only check against nearby
         *  entities. However, vanilla's implementation only iterates over chunks that the player collides with and then checks
         *  the hitboxes of entities in there, it does not add entities to all chunks they clip. This means that any interaction
         *  entity that clips multiple chunks does not get recognised outside its source chunk.
         *
         *  To solve this we absolutely over-engineer this problem and use a spatial tree structure to find all interaction entities.
         */
        // var entities = player.level().getEntities(player, player.getBoundingBox(), (it) -> it.getType() == EntityType.INTERACTION);
        var player = Minecraft.getInstance().player;
        var entities = SpatialInteractionEntityTree.findEntities(player.getBoundingBox());

        // Determine all current collisions
        var collidingTypes = new ArrayList<String>();
        for (var entity : entities) {
            // Stop colliding with this entity
            if (!entity.noxesium$hasExtraData(ExtraEntityData.QIB_BEHAVIOR)) continue;

            // Determine this entity's behavior
            var behavior = entity.noxesium$getExtraData(ExtraEntityData.QIB_BEHAVIOR);
            var knownBehaviors = ServerRules.QIB_BEHAVIORS.getValue();
            if (!knownBehaviors.containsKey(behavior)) continue;
            var definition = knownBehaviors.get(behavior);

            // Try to trigger the entry
            if (definition.triggerEnterLeaveOnSwitch() || !collidingWithTypes.containsKey(behavior)) {
                if (definition.onEnter() != null) {
                    sendPacket(behavior, ServerboundQibTriggeredPacket.Type.ENTER, entity.getId());
                    executeBehavior(player, entity, definition.onEnter());
                }
            }

            // Always trigger the while inside logic
            if (definition.whileInside() != null) {
                sendPacket(behavior, ServerboundQibTriggeredPacket.Type.INSIDE, entity.getId());
                executeBehavior(player, entity, definition.whileInside());
            }
            collidingTypes.add(behavior);
            collidingWithTypes.putIfAbsent(behavior, new AtomicInteger());
            collidingWithEntities.putIfAbsent(entity, new AtomicInteger());
        }

        var iterator = collidingWithEntities.keySet().iterator();
        while (iterator.hasNext()) {
            // If you're still colliding with this entity we ignore it
            var collision = iterator.next();
            if (entities.contains(collision)) continue;

            // Remove them from the iterator
            iterator.remove();

            // Remove from triggered jump when you leave it
            triggeredJump.remove(collision);

            // Stop colliding with this entity
            if (!collision.noxesium$hasExtraData(ExtraEntityData.QIB_BEHAVIOR)) continue;

            // Determine this entity's behavior
            var behavior = collision.noxesium$getExtraData(ExtraEntityData.QIB_BEHAVIOR);
            var knownBehaviors = ServerRules.QIB_BEHAVIORS.getValue();
            if (!knownBehaviors.containsKey(behavior)) continue;
            var definition = knownBehaviors.get(behavior);

            // Execute the behavior if we always do or if you've left this type
            if (definition.triggerEnterLeaveOnSwitch() || !collidingTypes.contains(behavior)) {
                if (definition.onLeave() != null) {
                    sendPacket(behavior, ServerboundQibTriggeredPacket.Type.LEAVE, collision.getId());
                    executeBehavior(player, collision, definition.onLeave());
                }
            }
        }

        // Only keep the types you are still colliding with
        collidingWithTypes.keySet().retainAll(collidingTypes);
    }

    /**
     * Executes the given behavior.
     */
    private void executeBehavior(LocalPlayer player, Entity entity, QibEffect effect) {
        switch (effect) {
            case QibEffect.Multiple multiple -> {
                for (var nested : multiple.effects()) {
                    executeBehavior(player, entity, nested);
                }
            }
            case QibEffect.Stay stay -> {
                var timeSpent = stay.global() ? collidingWithTypes.getOrDefault(entity.noxesium$getExtraData(ExtraEntityData.QIB_BEHAVIOR), new AtomicInteger()).get() :
                    collidingWithEntities.getOrDefault(entity, new AtomicInteger()).get();

                if (timeSpent >= stay.ticks()) {
                    executeBehavior(player, entity, stay.effect());
                }
            }
            case QibEffect.Wait wait -> {
                pending.add(Pair.of(new AtomicInteger(wait.ticks()), Triple.of(player, entity, wait.effect())));
            }
            case QibEffect.Conditional conditional -> {
                boolean result = switch (conditional.condition()) {
                    case IS_GLIDING -> player.isFallFlying();
                    case IS_RIPTIDING -> player.isAutoSpinAttack();
                    case IS_IN_AIR -> !player.onGround();
                    case IS_ON_GROUND -> player.onGround();
                    case IS_IN_WATER -> player.isInWater();
                    case IS_IN_WATER_OR_RAIN -> player.isInWaterOrRain();
                };

                // Trigger the effect if it matches
                if (result == conditional.value()) {
                    executeBehavior(player, entity, conditional.effect());
                }
            }
            case QibEffect.PlaySound playSound -> {
                player.level().playLocalSound(
                    player,
                    SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(playSound.namespace(), playSound.path())),
                    SoundSource.PLAYERS,
                    playSound.volume(),
                    playSound.pitch()
                );
            }
            case QibEffect.GivePotionEffect giveEffect -> {
                var type = BuiltInRegistries.MOB_EFFECT.getHolder(ResourceLocation.fromNamespaceAndPath(giveEffect.namespace(), giveEffect.path())).orElse(null);
                player.noxesium$addClientsidePotionEffect(
                    new MobEffectInstance(
                        type,
                        giveEffect.duration(),
                        giveEffect.amplifier(),
                        giveEffect.ambient(),
                        giveEffect.visible(),
                        giveEffect.showIcon()
                    )
                );
            }
            case QibEffect.RemovePotionEffect removeEffect -> {
                player.noxesium$removeClientsidePotionEffect(BuiltInRegistries.MOB_EFFECT.getHolder(ResourceLocation.fromNamespaceAndPath(removeEffect.namespace(), removeEffect.path())).orElse(null));
            }
            case QibEffect.Move move -> {
                player.move(MoverType.SELF, new Vec3(move.x(), move.y(), move.z()));
            }
            case QibEffect.AddVelocity addVelocity -> {
                player.push(addVelocity.x(), addVelocity.y(), addVelocity.z());
            }
            case QibEffect.SetVelocity setVelocity -> {
                player.setDeltaMovement(setVelocity.x(), setVelocity.y(), setVelocity.z());
            }
            case QibEffect.SetVelocityYawPitch setVelocityYawPitch -> {
                var yawRad = Math.toRadians(setVelocityYawPitch.yaw() + (setVelocityYawPitch.yawRelative() ? player.yRotO : 0));
                var pitchRad = Math.toRadians(setVelocityYawPitch.pitch() + (setVelocityYawPitch.pitchRelative() ? player.xRotO : 0));

                var x = -Math.cos(pitchRad) * Math.sin(yawRad);
                var y = -Math.sin(pitchRad);
                var z = Math.cos(pitchRad) * Math.cos(yawRad);
                player.setDeltaMovement(
                    Math.clamp(x * setVelocityYawPitch.strength(), -setVelocityYawPitch.limit(), setVelocityYawPitch.limit()),
                    Math.clamp(y * setVelocityYawPitch.strength(), -setVelocityYawPitch.limit(), setVelocityYawPitch.limit()),
                    Math.clamp(z * setVelocityYawPitch.strength(), -setVelocityYawPitch.limit(), setVelocityYawPitch.limit())
                );
            }
            default -> throw new IllegalStateException("Unexpected value: " + effect);
        }
    }
}
