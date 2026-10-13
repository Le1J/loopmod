package com.example.loopmod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class LoopModClient implements ClientModInitializer {

    // Une "frame" = l'état des touches + la rotation à un tick donné
    private record Frame(boolean fwd, boolean back, boolean left, boolean right,
                         boolean jump, boolean sneak, boolean sprint,
                         boolean attack, boolean use, float yaw, float pitch) {}

    // ---------- Boucle de mouvements ----------
    private static final List<Frame> frames = new ArrayList<>();
    private static boolean recording = false;
    private static boolean looping = false;
    private static int index = 0;
    private static int activeTicks = 0;
    private static int blocksBroken = 0;

    // ---------- Auto-pêche ----------
    private static final Identifier BOBBER_SPLASH = Identifier.ofVanilla("entity.fishing_bobber.splash");
    private static final Random random = new Random();
    private static boolean autoFish = false;
    private static volatile boolean biteFlag = false;
    private static int fishTicks = 0;
    private static int casts = 0;
    private static int catches = 0;
    private static int cooldown = 0;
    private static int hookTicks = 0;
    private static int reelDelay = -1;

    private static KeyBinding recordKey;
    private static KeyBinding loopKey;
    private static KeyBinding fishKey;

    @Override
    public void onInitializeClient() {
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("loopmod", "keys"));

        recordKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.loopmod.record", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, category));
        loopKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.loopmod.loop", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, category));
        fishKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.loopmod.fish", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, category));

        // Compte les blocs cassés uniquement quand la boucle est active
        ClientPlayerBlockBreakEvents.AFTER.register((world, player, pos, state) -> {
            if (looping) blocksBroken++;
        });

        // Détection de la touche : on écoute le son "splash" du flotteur près de NOTRE bouchon
        ClientLifecycleEvents.CLIENT_STARTED.register(mc ->
                mc.getSoundManager().registerListener((sound, soundSet, range) -> {
                    if (!autoFish || mc.player == null) return;
                    FishingBobberEntity hook = mc.player.fishHook;
                    if (hook == null) return;
                    if (!BOBBER_SPLASH.equals(sound.getId())) return;
                    double dx = sound.getX() - hook.getX();
                    double dy = sound.getY() - hook.getY();
                    double dz = sound.getZ() - hook.getZ();
                    if (dx * dx + dy * dy + dz * dz < 4.0) biteFlag = true;
                }));

        ClientTickEvents.START_CLIENT_TICK.register(this::onTickStart);
        ClientTickEvents.END_CLIENT_TICK.register(this::onTickEnd);

        HudRenderCallback.EVENT.register((ctx, tickCounter) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            int y = 5;
            if (recording) {
                ctx.drawTextWithShadow(mc.textRenderer,
                        "REC - " + frames.size() + " ticks", 5, y, 0xFFFF5555);
                y += 12;
            }
            if (looping) {
                ctx.drawTextWithShadow(mc.textRenderer,
                        "LOOP actif - " + formatTime(activeTicks), 5, y, 0xFF55FF55);
                ctx.drawTextWithShadow(mc.textRenderer,
                        "Blocs cassés : " + blocksBroken, 5, y + 12, 0xFFFFFFFF);
                y += 24;
            }
            if (autoFish) {
                ctx.drawTextWithShadow(mc.textRenderer,
                        "PÊCHE auto - " + formatTime(fishTicks), 5, y, 0xFF55FFFF);
                ctx.drawTextWithShadow(mc.textRenderer,
                        "Pêches réussies : " + catches + " (lancers : " + casts + ")", 5, y + 12, 0xFFFFFFFF);
            }
        });
    }

    private static String formatTime(int ticks) {
        int s = ticks / 20;
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    // ================= TICK DÉBUT =================
    private void onTickStart(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) {
            if (looping) stopLoop(mc);
            if (autoFish) stopFish(mc);
            recording = false;
            return;
        }
        GameOptions o = mc.options;

        if (recording) {
            frames.add(new Frame(
                    o.forwardKey.isPressed(), o.backKey.isPressed(),
                    o.leftKey.isPressed(), o.rightKey.isPressed(),
                    o.jumpKey.isPressed(), o.sneakKey.isPressed(), o.sprintKey.isPressed(),
                    o.attackKey.isPressed(), o.useKey.isPressed(),
                    mc.player.getYaw(), mc.player.getPitch()));
        }

        if (looping && !frames.isEmpty()) {
            Frame f = frames.get(index);
            o.forwardKey.setPressed(f.fwd());
            o.backKey.setPressed(f.back());
            o.leftKey.setPressed(f.left());
            o.rightKey.setPressed(f.right());
            o.jumpKey.setPressed(f.jump());
            o.sneakKey.setPressed(f.sneak());
            o.sprintKey.setPressed(f.sprint());
            o.attackKey.setPressed(f.attack());
            o.useKey.setPressed(f.use());
            mc.player.setYaw(f.yaw());
            mc.player.setPitch(f.pitch());

            index = (index + 1) % frames.size(); // retour au début = boucle
            activeTicks++;
        }

        if (autoFish) tickAutoFish(mc);
    }

    // ================= AUTO-PÊCHE =================
    private static Hand rodHand(ClientPlayerEntity p) {
        if (p.getMainHandStack().isOf(Items.FISHING_ROD)) return Hand.MAIN_HAND;
        if (p.getOffHandStack().isOf(Items.FISHING_ROD)) return Hand.OFF_HAND;
        return null;
    }

    private static void tickAutoFish(MinecraftClient mc) {
        fishTicks++;
        if (mc.currentScreen != null || mc.interactionManager == null) return;

        Hand hand = rodHand(mc.player);
        if (hand == null) return; // pas de canne à pêche en main : on attend

        if (cooldown > 0) {
            cooldown--;
            return;
        }

        FishingBobberEntity hook = mc.player.fishHook;

        // Pas de ligne à l'eau : on lance
        if (hook == null) {
            biteFlag = false;
            reelDelay = -1;
            hookTicks = 0;
            mc.interactionManager.interactItem(mc.player, hand);
            casts++;
            cooldown = 20; // laisse le temps au flotteur d'apparaître côté client
            return;
        }

        hookTicks++;

        // Une touche a été détectée : on remonte après un petit délai
        if (reelDelay > 0) {
            reelDelay--;
            if (reelDelay == 0) {
                mc.interactionManager.interactItem(mc.player, hand);
                catches++;
                reelDelay = -1;
                biteFlag = false;
                cooldown = 20;
            }
            return;
        }

        if (biteFlag && hookTicks > 10) {
            reelDelay = 3 + random.nextInt(5);
            biteFlag = false;
            return;
        }

        // Sécurité : ligne à l'eau depuis plus d'une minute sans touche -> on relance
        if (hookTicks > 1200) {
            mc.interactionManager.interactItem(mc.player, hand);
            cooldown = 20;
        }
    }

    private static void stopFish(MinecraftClient mc) {
        autoFish = false;
        biteFlag = false;
        reelDelay = -1;
        msg(mc, "Pêche auto arrêtée - " + formatTime(fishTicks) + ", " + catches + " pêches réussies");
    }

    // ================= TICK FIN (touches) =================
    private void onTickEnd(MinecraftClient mc) {
        if (mc.player == null) return;

        while (recordKey.wasPressed()) {
            if (looping) stopLoop(mc);
            recording = !recording;
            if (recording) {
                frames.clear();
                msg(mc, "Enregistrement démarré");
            } else {
                msg(mc, "Enregistrement terminé (" + frames.size() + " ticks)");
            }
        }

        while (loopKey.wasPressed()) {
            if (looping) {
                stopLoop(mc);
            } else {
                recording = false;
                if (frames.isEmpty()) {
                    msg(mc, "Rien d'enregistré : appuie d'abord sur R");
                } else {
                    index = 0;
                    activeTicks = 0;
                    blocksBroken = 0;
                    looping = true;
                    msg(mc, "Boucle activée");
                }
            }
        }

        while (fishKey.wasPressed()) {
            if (autoFish) {
                stopFish(mc);
            } else {
                fishTicks = 0;
                casts = 0;
                catches = 0;
                cooldown = 0;
                hookTicks = 0;
                reelDelay = -1;
                biteFlag = false;
                autoFish = true;
                msg(mc, "Pêche auto activée (tiens une canne à pêche)");
            }
        }
    }

    private static void stopLoop(MinecraftClient mc) {
        looping = false;
        // Remet les touches selon l'état réel du clavier
        KeyBinding.updatePressedStates();
        msg(mc, "Boucle arrêtée - " + (activeTicks / 20) + "s, " + blocksBroken + " blocs cassés");
    }

    private static void msg(MinecraftClient mc, String text) {
        if (mc.player != null) mc.player.sendMessage(Text.literal(text), true);
    }
}
