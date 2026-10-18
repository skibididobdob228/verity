package dev.verity.registry;

import dev.verity.VerityMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Effects and music. Voice lines are not registered: the client plays them straight from sounds.json by id. */
public final class ModSounds {
    public static final DeferredRegister<SoundEvent> REGISTER = DeferredRegister.create(Registries.SOUND_EVENT, VerityMod.MODID);

    public static final DeferredHolder<SoundEvent, SoundEvent> KNOCK = reg("sfx.knock"), BOX_OPEN = reg("sfx.box_open"), BOUNCE = reg("sfx.bounce"),
            GLITCH = reg("sfx.glitch"), HEARTBEAT = reg("sfx.heartbeat"), DRONE = reg("sfx.drone"), SIREN = reg("sfx.siren"),
            SCREAM = reg("sfx.scream"), GROWL = reg("sfx.growl"), TRANSFORM = reg("sfx.transform"), STEP = reg("sfx.step"),
            JUMPSCARE = reg("sfx.jumpscare"), MACHINE = reg("sfx.machine"), OLD_TUNE = reg("music.old_tune");

    private static DeferredHolder<SoundEvent, SoundEvent> reg(String name) {
        return REGISTER.register(name, () -> SoundEvent.createVariableRangeEvent(VerityMod.id(name)));
    }

    private ModSounds() {}
}
