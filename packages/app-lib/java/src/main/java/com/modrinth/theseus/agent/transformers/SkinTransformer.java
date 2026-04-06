package com.modrinth.theseus.agent.transformers;

import com.modrinth.theseus.agent.SkinInjector;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * Transforms {@code com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService}
 * to inject our custom skin texture into the GameProfile when
 * {@code fillProfileProperties} is called.
 *
 * <p>This works because authlib classes are NOT obfuscated — they ship as a separate
 * library bundled with all Minecraft versions since 1.7.2.
 *
 * <p>The transformation appends bytecode at the end of {@code fillProfileProperties}
 * that calls {@link SkinInjector} to add the "textures" property to the returned
 * GameProfile, but only if the profile's UUID matches the local player.
 */
public final class SkinTransformer extends ClassNodeTransformer {
    @Override
    protected boolean transform(ClassNode classNode) {
        for (MethodNode method : classNode.methods) {
            if (!"fillProfileProperties".equals(method.name)) {
                continue;
            }

            // fillProfileProperties(GameProfile profile, boolean requireSecure) -> GameProfile
            // We need to inject code before every ARETURN that adds our skin property
            // to the returned GameProfile

            InsnList inject = buildInjection(classNode);
            if (inject == null) {
                return false;
            }

            // Find all ARETURN instructions and inject before them
            AbstractInsnNode[] insns = method.instructions.toArray();
            for (AbstractInsnNode insn : insns) {
                if (insn.getOpcode() == Opcodes.ARETURN) {
                    method.instructions.insertBefore(insn, cloneInsnList(inject));
                }
            }

            System.out.println("[Crackrinth] Transformed fillProfileProperties in " + classNode.name);
            return true;
        }

        // fillProfileProperties not found - may be a different authlib version
        System.err.println("[Crackrinth] fillProfileProperties not found in " + classNode.name);
        return false;
    }

    /**
     * Builds the injection bytecode. Before ARETURN (which has the GameProfile on stack),
     * we duplicate the profile reference and call our static injection method.
     *
     * <pre>
     * // Stack before: [GameProfile]
     * DUP
     * INVOKESTATIC SkinInjector.injectSkinIntoProfile(GameProfile)V
     * // Stack after: [GameProfile]  (unchanged, ready for ARETURN)
     * </pre>
     */
    private static InsnList buildInjection(ClassNode classNode) {
        InsnList list = new InsnList();
        // Duplicate the GameProfile on top of stack (it's about to be returned)
        list.add(new InsnNode(Opcodes.DUP));
        // Call our static injection method
        list.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "com/modrinth/theseus/agent/SkinInjector",
                "injectSkinIntoProfile",
                "(Ljava/lang/Object;)V",
                false));
        return list;
    }

    private static InsnList cloneInsnList(InsnList original) {
        InsnList clone = new InsnList();
        for (AbstractInsnNode insn : original) {
            clone.add(insn.clone(null));
        }
        return clone;
    }
}
