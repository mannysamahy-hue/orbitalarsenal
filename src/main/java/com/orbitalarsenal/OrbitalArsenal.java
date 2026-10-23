package com.orbitalarsenal;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

/**
 * ORBITAL ARSENAL - Fabric mod for Minecraft 26.2 (mojmap, Java 25), one source file, no asset files.
 *
 * Items (all creative-tab + /give orbitalarsenal:<id>):
 *   tnt_minecart_cannon          - fires TNT minecarts as ballistic shells (sneak = 8-cart barrage)
 *   orbital_tnt_cannon           - carpet-bombs the aimed point with falling TNT
 *   orbital_stab_cannon          - a 15-block-wide shaft from the sky down to bedrock
 *   orbital_nuke_cannon          - 40-block-radius crater, shockwave, scorched earth, mushroom cloud
 *   orbital_wither_skull_cannon  - a storm of wither skulls, each blast radius 10
 *   orbital_mob_cannon           - drops 150-200 mobs of YOUR choice (sneak = cycle, /orbitalmob <name> = any mob)
 *   stasis_rod                   - freeze any entity in place (aim + use toggles; sneak = release all yours)
 *   stasis_teleporter_rod        - lock another player in stasis and teleport them (see usage in lore)
 *
 * Commands: /orbitalmob [name]   /stasislink <player>
 */
public class OrbitalArsenal implements ModInitializer {

    public static final String MOD_ID = "orbitalarsenal";
    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    // =====================================================================================
    //  TUNING - change these to taste
    // =====================================================================================
    /** "10x as destructive" multiplier applied to the orbital weapons' vanilla-equivalent radii. */
    static final int M = 10;

    static final double AIM_RANGE = 400.0;

    static final int NUKE_RADIUS = 4 * M;          // vanilla TNT ~4  -> 40
    static final int NUKE_SCORCH_RADIUS = 75;      // removes soft blocks (leaves, grass, dirt, glass...)
    static final int NUKE_DAMAGE_RADIUS = 120;
    static final int NUKE_DELAY = 100;             // ticks of warning

    static final int STAB_RADIUS = (3 * M) / 2;    // 15-block shaft radius
    static final int STAB_DELAY = 50;

    static final int SKULL_COUNT = 36;
    static final int SKULL_RADIUS = 1 * M;         // vanilla wither skull ~1 -> 10 per skull
    static final int SKULL_SPREAD = 40;
    static final int SKULL_DELAY = 40;

    static final int TNT_COUNT = 10;
    static final int TNT_RADIUS = 2 * M;           // 20 per TNT (vanilla ~4)
    static final int TNT_SPREAD = 28;
    static final int TNT_DELAY = 40;

    static final float CART_POWER = 14f;           // real TNT-type explosion per cart (vanilla minecart ~4)
    static final int CART_SALVO = 3;
    static final int CART_BARRAGE = 8;

    static final int MOB_MIN = 150;
    static final int MOB_MAX = 200;
    static final int MOB_SPREAD = 14;
    static final int MOB_HEIGHT = 45;
    static final int MOB_PER_TICK = 6;
    static final int MOB_DELAY = 30;

    static final int STASIS_MAX_TICKS = 20 * 60 * 10; // auto-release after 10 minutes

    /** Work budget per server tick for block removal (1 per scanned block, +12 per removed block). */
    static final int WORK_PER_TICK = 450_000;

    private static final int BLOCK_FLAGS = 2 | 16; // UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE (no neighbour-update lag)

    // =====================================================================================
    //  STATE
    // =====================================================================================
    enum Kind { TNT_MINECART, ORBITAL_TNT, ORBITAL_STAB, ORBITAL_NUKE, ORBITAL_WITHER, ORBITAL_MOB, STASIS, STASIS_TP }

    static final List<Item> ALL_ITEMS = new ArrayList<>();
    static final List<Job> JOBS = new ArrayList<>();
    static final List<Job> NEW_JOBS = new ArrayList<>();
    static int workBudget = WORK_PER_TICK;

    static final Map<UUID, Identifier> MOB_CHOICE = new HashMap<>();
    static final Map<UUID, Integer> MOB_CYCLE = new HashMap<>();
    static final Map<UUID, Stasis> STASIS = new HashMap<>();
    static final Map<UUID, UUID> LINKS = new HashMap<>(); // owner -> linked player

    static final String[] CURATED_MOBS = {
            "zombie", "skeleton", "creeper", "spider", "cave_spider", "enderman", "blaze", "ghast",
            "wither_skeleton", "piglin_brute", "ravager", "vindicator", "evoker", "witch", "phantom",
            "slime", "magma_cube", "iron_golem", "cow", "pig", "sheep", "chicken", "wolf", "cat",
            "parrot", "villager", "bee", "silverfish", "endermite", "guardian", "shulker", "hoglin",
            "zoglin", "warden", "breeze", "vex", "pillager", "drowned", "husk", "stray", "bogged",
            "camel", "horse", "llama", "panda", "polar_bear", "fox", "goat", "frog", "creaking"
    };

    // =====================================================================================
    //  ENTRYPOINT
    // =====================================================================================
    @Override
    public void onInitialize() {
        reg("tnt_minecart_cannon", Kind.TNT_MINECART, "minecraft:tnt_minecart", "TNT Minecart Cannon", ChatFormatting.RED,
                "Use: fire a salvo of TNT minecarts.", "Sneak + Use: 8-cart barrage.");
        reg("orbital_tnt_cannon", Kind.ORBITAL_TNT, "minecraft:tnt", "Orbital TNT Cannon", ChatFormatting.GOLD,
                "Use: call a TNT carpet-bombing run", "on the block you are aiming at.");
        reg("orbital_stab_cannon", Kind.ORBITAL_STAB, "minecraft:end_rod", "Orbital Stab Cannon", ChatFormatting.AQUA,
                "Use: a beam from orbit drills a", "15-block-wide shaft down to bedrock.");
        reg("orbital_nuke_cannon", Kind.ORBITAL_NUKE, "minecraft:nether_star", "Orbital Nuke Cannon", ChatFormatting.DARK_RED,
                "Use: tactical nuke on the aimed point.", "40-block crater + shockwave. 5s warning.");
        reg("orbital_wither_skull_cannon", Kind.ORBITAL_WITHER, "minecraft:wither_skeleton_skull", "Orbital Wither Skull Cannon",
                ChatFormatting.DARK_GRAY, "Use: rain 36 wither skulls from orbit,", "each with a 10-block blast + Wither.");
        reg("orbital_mob_cannon", Kind.ORBITAL_MOB, "minecraft:zombie_spawn_egg", "Orbital Mob Cannon", ChatFormatting.GREEN,
                "Use: drop 150-200 mobs on the aimed point.", "Sneak + Use: cycle mob.  /orbitalmob <name>: any mob.");
        reg("stasis_rod", Kind.STASIS, "minecraft:breeze_rod", "Stasis Rod", ChatFormatting.LIGHT_PURPLE,
                "Use on an entity: freeze it in place.", "Use again to release. Sneak + Use: release all yours.");
        reg("stasis_teleporter_rod", Kind.STASIS_TP, "minecraft:lightning_rod", "Stasis Teleporter Rod", ChatFormatting.BLUE,
                "Aim at a player + Use: lock them in stasis.", "Use on a block: teleport them there. Sneak + Use: bring them to you.",
                "Aim at the locked player again: release.");

        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(entries -> {
            for (Item item : ALL_ITEMS) {
                entries.accept(item);
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(OrbitalArsenal::serverTick);

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            releaseAll(server);
            JOBS.clear();
            NEW_JOBS.clear();
            LINKS.clear();
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            try {
                UUID id = handler.getPlayer().getUUID();
                release(server, id);
                LINKS.remove(id);
            } catch (Throwable t) {
                LOG.warn("disconnect cleanup failed", t);
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("orbitalmob")
                    .executes(ctx -> {
                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                        Identifier cur = MOB_CHOICE.getOrDefault(p.getUUID(), Identifier.parse("minecraft:zombie"));
                        msg(p, "Orbital Mob Cannon target: " + cur + "  (use /orbitalmob <name> to change)", ChatFormatting.GREEN);
                        return 1;
                    })
                    .then(Commands.argument("mob", StringArgumentType.greedyString()).executes(ctx -> {
                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                        String in = StringArgumentType.getString(ctx, "mob");
                        Identifier id = resolveMob(in);
                        if (id == null) {
                            msg(p, "No summonable mob matches '" + in + "'.", ChatFormatting.RED);
                            return 0;
                        }
                        MOB_CHOICE.put(p.getUUID(), id);
                        msg(p, "Orbital Mob Cannon now drops: " + id, ChatFormatting.GREEN);
                        return 1;
                    })));

            dispatcher.register(Commands.literal("stasislink")
                    .then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
                        ServerPlayer owner = ctx.getSource().getPlayerOrException();
                        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                        if (target.getUUID().equals(owner.getUUID())) {
                            msg(owner, "You cannot link yourself.", ChatFormatting.RED);
                            return 0;
                        }
                        linkAndFreeze(owner, target);
                        return 1;
                    })));
        });

        LOG.info("Orbital Arsenal loaded: {} items", ALL_ITEMS.size());
    }

    private static void reg(String id, Kind kind, String model, String name, ChatFormatting color, String... lore) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MOD_ID, id));
        List<Component> loreLines = new ArrayList<>();
        for (String l : lore) {
            loreLines.add(Component.literal(l).withStyle(ChatFormatting.GRAY));
        }
        Item.Properties props = new Item.Properties()
                .setId(key)
                .stacksTo(1)
                .rarity(Rarity.EPIC)
                .component(DataComponents.ITEM_MODEL, Identifier.parse(model))
                .component(DataComponents.ITEM_NAME, Component.literal(name).withStyle(color))
                .component(DataComponents.LORE, new ItemLore(loreLines))
                .component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        Item item = new ArsenalItem(props, kind);
        Registry.register(BuiltInRegistries.ITEM, key, item);
        ALL_ITEMS.add(item);
    }

    // =====================================================================================
    //  THE ITEM
    // =====================================================================================
    static final class ArsenalItem extends Item {
        private final Kind kind;

        ArsenalItem(Properties props, Kind kind) {
            super(props);
            this.kind = kind;
        }

        @Override
        public InteractionResult use(Level level, Player player, InteractionHand hand) {
            if (!(level instanceof ServerLevel sl) || !(player instanceof ServerPlayer sp)) {
                return InteractionResult.SUCCESS; // client: just swing
            }
            ItemStack stack = player.getItemInHand(hand);
            try {
                return switch (kind) {
                    case TNT_MINECART -> {
                        fireCarts(sl, sp, sp.isShiftKeyDown() ? CART_BARRAGE : CART_SALVO);
                        cooldown(sp, stack, sp.isShiftKeyDown() ? 60 : 15);
                        yield InteractionResult.SUCCESS;
                    }
                    case ORBITAL_TNT -> {
                        Aim a = aim(sl, sp, AIM_RANGE);
                        if (a == null) yield noTarget(sp);
                        tntStorm(sl, sp, a.hit());
                        cooldown(sp, stack, 200);
                        yield InteractionResult.SUCCESS;
                    }
                    case ORBITAL_STAB -> {
                        Aim a = aim(sl, sp, AIM_RANGE);
                        if (a == null) yield noTarget(sp);
                        stab(sl, sp, a.hit());
                        cooldown(sp, stack, 200);
                        yield InteractionResult.SUCCESS;
                    }
                    case ORBITAL_NUKE -> {
                        Aim a = aim(sl, sp, AIM_RANGE);
                        if (a == null) yield noTarget(sp);
                        nuke(sl, sp, a.hit());
                        cooldown(sp, stack, 600);
                        yield InteractionResult.SUCCESS;
                    }
                    case ORBITAL_WITHER -> {
                        Aim a = aim(sl, sp, AIM_RANGE);
                        if (a == null) yield noTarget(sp);
                        witherStorm(sl, sp, a.hit());
                        cooldown(sp, stack, 300);
                        yield InteractionResult.SUCCESS;
                    }
                    case ORBITAL_MOB -> {
                        if (sp.isShiftKeyDown()) {
                            cycleMob(sp);
                            yield InteractionResult.SUCCESS;
                        }
                        Aim a = aim(sl, sp, AIM_RANGE);
                        if (a == null) yield noTarget(sp);
                        mobDrop(sl, sp, a.hit());
                        cooldown(sp, stack, 200);
                        yield InteractionResult.SUCCESS;
                    }
                    case STASIS -> useStasisRod(sl, sp);
                    case STASIS_TP -> useStasisTeleporter(sl, sp);
                };
            } catch (Throwable t) {
                LOG.error("Orbital Arsenal item use failed", t);
                msg(sp, "Something went wrong: " + t.getClass().getSimpleName(), ChatFormatting.RED);
                return InteractionResult.FAIL;
            }
        }
    }

    private static InteractionResult noTarget(ServerPlayer p) {
        msg(p, "No target in range - aim at a block.", ChatFormatting.RED);
        return InteractionResult.FAIL;
    }

    private static void cooldown(ServerPlayer p, ItemStack stack, int ticks) {
        p.getCooldowns().addCooldown(stack, ticks);
    }

    // =====================================================================================
    //  JOB SCHEDULER
    // =====================================================================================
    interface Job {
        /** @return true when finished */
        boolean run();
    }

    static void addJob(Job j) {
        NEW_JOBS.add(j);
    }

    /** Runs every tick with age 0,1,2...; the predicate returns true when the timeline is finished. */
    static void timeline(IntPredicate fn) {
        addJob(new Job() {
            int age = 0;

            @Override
            public boolean run() {
                return fn.test(age++);
            }
        });
    }

    private static void serverTick(MinecraftServer server) {
        workBudget = WORK_PER_TICK;
        try {
            enforceStasis(server);
        } catch (Throwable t) {
            LOG.error("stasis tick failed", t);
        }
        if (!NEW_JOBS.isEmpty()) {
            JOBS.addAll(NEW_JOBS);
            NEW_JOBS.clear();
        }
        Iterator<Job> it = JOBS.iterator();
        while (it.hasNext()) {
            Job j = it.next();
            boolean done;
            try {
                done = j.run();
            } catch (Throwable t) {
                LOG.error("job failed", t);
                done = true;
            }
            if (done) it.remove();
        }
    }

    // =====================================================================================
    //  HELPERS: messaging, entities, effects, aiming
    // =====================================================================================
    static void msg(Player p, String text, ChatFormatting color) {
        p.sendSystemMessage(Component.literal(text).withStyle(color));
    }

    static void broadcast(ServerLevel level, String text, ChatFormatting color) {
        level.getServer().getPlayerList().broadcastSystemMessage(Component.literal(text).withStyle(color), false);
    }

    static Entity make(ServerLevel level, String id) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(id));
        return type.create(level, EntitySpawnReason.COMMAND);
    }

    static Holder<MobEffect> eff(String path) {
        return BuiltInRegistries.MOB_EFFECT.get(Identifier.parse("minecraft:" + path)).orElseThrow();
    }

    static void addEffect(LivingEntity e, String path, int ticks, int amp) {
        try {
            e.addEffect(new MobEffectInstance(eff(path), ticks, amp));
        } catch (Throwable t) {
            LOG.debug("effect {} failed", path);
        }
    }

    record Aim(Vec3 hit, Vec3 before) {}

    /** Block ray-march along the player's look vector. Returns null if nothing solid within range. */
    static Aim aim(ServerLevel level, Player p, double range) {
        Vec3 eye = p.getEyePosition();
        Vec3 dir = p.getLookAngle();
        Vec3 prev = eye;
        for (double d = 0.5; d <= range; d += 0.5) {
            Vec3 v = eye.add(dir.scale(d));
            BlockPos bp = BlockPos.containing(v);
            if (!level.hasChunkAt(bp)) return null;
            if (v.y < level.getMinY() - 16 || v.y > level.getMaxY() + 64) return null;
            BlockState s = level.getBlockState(bp);
            if (!s.getCollisionShape(level, bp).isEmpty() || !s.getFluidState().isEmpty()) {
                return new Aim(v, prev);
            }
            prev = v;
        }
        return null;
    }

    /** Nearest entity along the look ray (not hidden behind blocks). */
    static Entity aimEntity(ServerLevel level, Player p, double range, Predicate<Entity> filter) {
        Vec3 eye = p.getEyePosition();
        Vec3 dir = p.getLookAngle();
        Aim blockAim = aim(level, p, range);
        double maxDist = blockAim == null ? range : eye.distanceTo(blockAim.hit());
        Vec3 end = eye.add(dir.scale(maxDist));
        AABB box = p.getBoundingBox().expandTowards(dir.scale(maxDist)).inflate(1.0);
        Entity best = null;
        double bestD = maxDist;
        for (Entity e : level.getEntities(p, box, c -> c != p && c.isAlive() && !c.isSpectator() && filter.test(c))) {
            AABB bb = e.getBoundingBox().inflate(0.3);
            Optional<Vec3> hit = bb.clip(eye, end);
            if (hit.isPresent()) {
                double d = eye.distanceTo(hit.get());
                if (d < bestD) {
                    bestD = d;
                    best = e;
                }
            }
        }
        return best;
    }

    static void warnBeam(ServerLevel level, Vec3 t, int age, int total) {
        double top = Math.min(level.getMaxY(), t.y + 160);
        for (double y = t.y + (age % 4); y < top; y += 4) {
            level.sendParticles(ParticleTypes.END_ROD, t.x, y, t.z, 1, 0.08, 0.0, 0.08, 0.0);
        }
        double frac = 1.0 - (double) age / Math.max(1, total);
        double radius = 3 + 14 * frac;
        for (int i = 0; i < 24; i++) {
            double a = (Math.PI * 2 * i) / 24 + age * 0.1;
            level.sendParticles(ParticleTypes.FLAME, t.x + Math.cos(a) * radius, t.y + 1, t.z + Math.sin(a) * radius,
                    1, 0, 0, 0, 0);
        }
    }

    static void lightning(ServerLevel level, double x, double y, double z) {
        Entity bolt = make(level, "minecraft:lightning_bolt");
        if (bolt != null) {
            bolt.snapTo(x, y, z, 0f, 0f);
            level.addFreshEntity(bolt);
        }
    }

    static void blastFx(ServerLevel level, Vec3 c, float power) {
        level.explode(null, c.x, c.y, c.z, power, Level.ExplosionInteraction.NONE); // sound, particles, knockback, damage
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y + 1, c.z, 3, 3, 1, 3, 0);
    }

    static void damageArea(ServerLevel level, Vec3 c, double radius, float maxDamage, Entity owner, int witherTicks, int fireTicks) {
        AABB box = new AABB(c, c).inflate(radius);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box)) {
            double d = e.position().distanceTo(c);
            if (d > radius) continue;
            float dmg = (float) (maxDamage * Math.max(0.1, 1.0 - d / radius));
            e.hurtServer(level, level.damageSources().explosion(null, owner), dmg);
            if (witherTicks > 0) addEffect(e, "wither", witherTicks, 2);
            if (fireTicks > 0) e.setRemainingFireTicks(fireTicks);
        }
    }

    // =====================================================================================
    //  CRATER JOB  (budgeted block removal, sphere or cylinder)
    // =====================================================================================
    static boolean removeBlock(ServerLevel level, BlockPos.MutableBlockPos p, float maxHardness) {
        if (!level.hasChunkAt(p)) return false;
        BlockState s = level.getBlockState(p);
        if (s.isAir()) return false;
        float h = s.getDestroySpeed(level, p);
        if (h < 0 || h > maxHardness) return false; // bedrock, barriers, portal frames...
        level.setBlock(p, Blocks.AIR.defaultBlockState(), BLOCK_FLAGS);
        return true;
    }

    static final class Crater implements Job {
        final ServerLevel level;
        final int cx, cy, cz, r, bottom;
        final boolean sphere;
        final long r2;
        final float maxHardness;
        int x, y, z;

        private Crater(ServerLevel level, int cx, int cy, int cz, int r, int top, int bottom, boolean sphere, float maxHardness) {
            this.level = level;
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
            this.r = r;
            this.bottom = Math.max(bottom, level.getMinY());
            this.sphere = sphere;
            this.r2 = (long) r * r;
            this.maxHardness = maxHardness;
            this.x = cx - r;
            this.z = cz - r;
            this.y = Math.min(top, level.getMaxY());
        }

        static Crater sphere(ServerLevel level, BlockPos c, int r, float maxHardness) {
            return new Crater(level, c.getX(), c.getY(), c.getZ(), r, c.getY() + r, c.getY() - r, true, maxHardness);
        }

        static Crater sphere(ServerLevel level, BlockPos c, int r, int top, int bottom, float maxHardness) {
            return new Crater(level, c.getX(), c.getY(), c.getZ(), r, top, bottom, true, maxHardness);
        }

        static Crater cylinder(ServerLevel level, BlockPos c, int r, int top, int bottom) {
            return new Crater(level, c.getX(), c.getY(), c.getZ(), r, top, bottom, false, Float.MAX_VALUE);
        }

        @Override
        public boolean run() {
            BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
            while (y >= bottom) {
                while (x <= cx + r) {
                    while (z <= cz + r) {
                        if (workBudget <= 0) return false;
                        workBudget--;
                        long dx = x - cx, dz = z - cz, dy = y - cy;
                        long d2 = sphere ? dx * dx + dy * dy + dz * dz : dx * dx + dz * dz;
                        if (d2 <= r2) {
                            mp.set(x, y, z);
                            if (removeBlock(level, mp, maxHardness)) workBudget -= 12;
                        }
                        z++;
                    }
                    z = cz - r;
                    x++;
                }
                x = cx - r;
                y--;
            }
            return true;
        }
    }

    // =====================================================================================
    //  SHOT JOB  (server-driven projectile: TNT, minecart, wither skull)
    // =====================================================================================
    static final class Shot implements Job {
        final ServerLevel level;
        final Entity entity;
        final double gravity, drag;
        final int maxAge;
        final Consumer<Vec3> onImpact;
        Vec3 pos, vel;
        int age = 0;

        Shot(ServerLevel level, Entity entity, Vec3 pos, Vec3 vel, double gravity, double drag, int maxAge, Consumer<Vec3> onImpact) {
            this.level = level;
            this.entity = entity;
            this.pos = pos;
            this.vel = vel;
            this.gravity = gravity;
            this.drag = drag;
            this.maxAge = maxAge;
            this.onImpact = onImpact;
        }

        @Override
        public boolean run() {
            if (entity.isRemoved()) { // vanilla behaviour detonated/discarded it first
                onImpact.accept(pos);
                return true;
            }
            age++;
            vel = vel.add(0, -gravity, 0).scale(drag);
            int steps = Math.max(1, (int) Math.ceil(vel.length() / 0.5));
            Vec3 step = vel.scale(1.0 / steps);
            for (int i = 0; i < steps; i++) {
                Vec3 next = pos.add(step);
                BlockPos bp = BlockPos.containing(next);
                if (!level.hasChunkAt(bp) || next.y < level.getMinY() || hitsBlock(bp)) {
                    entity.discard();
                    onImpact.accept(pos);
                    return true;
                }
                pos = next;
            }
            entity.setPos(pos.x, pos.y, pos.z);
            entity.setDeltaMovement(vel);
            if (age > maxAge) {
                entity.discard();
                onImpact.accept(pos);
                return true;
            }
            return false;
        }

        private boolean hitsBlock(BlockPos bp) {
            BlockState s = level.getBlockState(bp);
            return !s.getCollisionShape(level, bp).isEmpty();
        }
    }

    // =====================================================================================
    //  WEAPON: TNT MINECART CANNON
    // =====================================================================================
    static void fireCarts(ServerLevel level, ServerPlayer owner, int count) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Vec3 eye = owner.getEyePosition();
        Vec3 look = owner.getLookAngle();
        for (int i = 0; i < count; i++) {
            Entity cart = make(level, "minecraft:tnt_minecart");
            if (cart == null) continue;
            Vec3 dir = look.add(rnd.nextGaussian() * 0.03, rnd.nextGaussian() * 0.03, rnd.nextGaussian() * 0.03).normalize();
            Vec3 start = eye.add(look.scale(2.5)).add(0, -0.5, 0);
            cart.setNoGravity(true);
            cart.snapTo(start.x, start.y, start.z, owner.getYRot(), 0f);
            level.addFreshEntity(cart);
            addJob(new Shot(level, cart, start, dir.scale(3.2), 0.03, 0.995, 400, p -> {
                level.explode(null, p.x, p.y, p.z, CART_POWER, Level.ExplosionInteraction.TNT);
                level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, p.x, p.y + 1, p.z, 1, 0, 0, 0, 0);
            }));
        }
        msg(owner, "TNT minecarts away x" + count, ChatFormatting.RED);
    }

    // =====================================================================================
    //  WEAPON: ORBITAL TNT CANNON
    // =====================================================================================
    static void tntStorm(ServerLevel level, ServerPlayer owner, Vec3 t) {
        broadcast(level, owner.getName().getString() + " called an ORBITAL TNT strike!", ChatFormatting.GOLD);
        int[] launched = {0};
        timeline(age -> {
            if (age < TNT_DELAY) {
                if (age % 2 == 0) warnBeam(level, t, age, TNT_DELAY);
                return false;
            }
            if (age % 4 == 0 && launched[0] < TNT_COUNT) {
                launchFaller(level, t, TNT_SPREAD, "minecraft:tnt", 80, p -> {
                    blastFx(level, p, 6f);
                    addJob(Crater.sphere(level, BlockPos.containing(p), TNT_RADIUS, Float.MAX_VALUE));
                    damageArea(level, p, TNT_RADIUS * 1.6, 120f, owner, 0, 60);
                });
                launched[0]++;
            }
            return launched[0] >= TNT_COUNT;
        });
    }

    /** Spawns a falling entity above a random point near t and drives it with a Shot. */
    static void launchFaller(ServerLevel level, Vec3 t, int spread, String entityId, int dropHeight, Consumer<Vec3> onImpact) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double ang = rnd.nextDouble() * Math.PI * 2;
        double dist = Math.sqrt(rnd.nextDouble()) * spread;
        double x = t.x + Math.cos(ang) * dist;
        double z = t.z + Math.sin(ang) * dist;
        int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
        double y = Math.min(level.getMaxY() - 2, Math.max(h, t.y) + dropHeight);
        Entity e = make(level, entityId);
        if (e == null) return;
        e.setNoGravity(true);
        e.snapTo(x, y, z, 0f, 0f);
        level.addFreshEntity(e);
        Vec3 vel = new Vec3(rnd.nextGaussian() * 0.1, -2.6, rnd.nextGaussian() * 0.1);
        addJob(new Shot(level, e, new Vec3(x, y, z), vel, 0.0, 1.0, 300, onImpact));
    }

    // =====================================================================================
    //  WEAPON: ORBITAL STAB
    // =====================================================================================
    static void stab(ServerLevel level, ServerPlayer owner, Vec3 t) {
        broadcast(level, "ORBITAL STAB locked on near " + (int) t.x + ", " + (int) t.z, ChatFormatting.AQUA);
        timeline(age -> {
            if (age < STAB_DELAY) {
                if (age % 2 == 0) warnBeam(level, t, age, STAB_DELAY);
                return false;
            }
            BlockPos c = BlockPos.containing(t);
            for (int i = 0; i < 3; i++) lightning(level, t.x, t.y, t.z);
            blastFx(level, t, 10f);
            addJob(Crater.cylinder(level, c, STAB_RADIUS, c.getY() + 30, level.getMinY()));
            damageArea(level, t, 45, 300f, owner, 0, 100);
            // visible column of light drilling down
            timeline(a2 -> {
                double y = t.y - a2 * 12.0;
                if (y < level.getMinY() || a2 > 40) return true;
                level.sendParticles(ParticleTypes.END_ROD, t.x, y, t.z, 40, STAB_RADIUS * 0.6, 3, STAB_RADIUS * 0.6, 0.05);
                level.sendParticles(ParticleTypes.FLAME, t.x, y, t.z, 20, STAB_RADIUS * 0.5, 2, STAB_RADIUS * 0.5, 0.05);
                return false;
            });
            return true;
        });
    }

    // =====================================================================================
    //  WEAPON: ORBITAL NUKE
    // =====================================================================================
    static void nuke(ServerLevel level, ServerPlayer owner, Vec3 t) {
        broadcast(level, "NUCLEAR LAUNCH DETECTED - orbital strike by " + owner.getName().getString()
                + " impacts at " + (int) t.x + ", " + (int) t.y + ", " + (int) t.z + " in 5 seconds!", ChatFormatting.DARK_RED);
        timeline(age -> {
            if (age < NUKE_DELAY) {
                if (age % 2 == 0) warnBeam(level, t, age, NUKE_DELAY);
                if (age % 25 == 0 && age > 0) lightning(level, t.x, t.y, t.z);
                return false;
            }
            detonateNuke(level, owner, t);
            return true;
        });
    }

    static void detonateNuke(ServerLevel level, ServerPlayer owner, Vec3 t) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < 10; i++) {
            double a = (Math.PI * 2 * i) / 10;
            lightning(level, t.x + Math.cos(a) * 18, t.y, t.z + Math.sin(a) * 18);
        }
        blastFx(level, t, 30f);
        BlockPos c = BlockPos.containing(t);
        // 1) the crater
        addJob(Crater.sphere(level, c.below(4), NUKE_RADIUS, Float.MAX_VALUE));
        // 2) scorched earth: strips soft blocks (hardness <= 1.0) in a wide dome
        addJob(Crater.sphere(level, c.above(5), NUKE_SCORCH_RADIUS, c.getY() + NUKE_SCORCH_RADIUS, c.getY() - 8, 1.0f));
        // 3) expanding shockwave
        Set<UUID> hit = new HashSet<>();
        timeline(age -> {
            double r = 4.0 * (age + 1);
            if (r > NUKE_DAMAGE_RADIUS + 4) return true;
            AABB box = new AABB(t, t).inflate(r);
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box)) {
                if (hit.contains(e.getUUID())) continue;
                double d = e.position().distanceTo(t);
                if (d > r) continue;
                hit.add(e.getUUID());
                float dmg = (float) (600.0 * Math.max(0.05, 1.0 - d / NUKE_DAMAGE_RADIUS));
                e.hurtServer(level, level.damageSources().explosion(null, owner), dmg);
                addEffect(e, "wither", 200, 1);
                e.setRemainingFireTicks(240);
            }
            for (int i = 0; i < 48; i++) {
                double a = (Math.PI * 2 * i) / 48;
                level.sendParticles(ParticleTypes.CLOUD, t.x + Math.cos(a) * r, t.y + 1.5, t.z + Math.sin(a) * r, 2, 0.5, 0.5, 0.5, 0.02);
            }
            return false;
        });
        // 4) mushroom cloud
        timeline(age -> {
            if (age > 100) return true;
            double h = age * 1.3;
            level.sendParticles(ParticleTypes.FLAME, t.x, t.y + h, t.z, 25, 2.5, 1.0, 2.5, 0.02);
            level.sendParticles(ParticleTypes.LARGE_SMOKE, t.x, t.y + h, t.z, 25, 3.0, 1.0, 3.0, 0.02);
            if (age > 35) {
                double capY = t.y + 35 * 1.3 + (age - 35) * 0.9;
                level.sendParticles(ParticleTypes.LARGE_SMOKE, t.x, capY, t.z, 70, 11, 3, 11, 0.02);
                level.sendParticles(ParticleTypes.FLAME, t.x, capY, t.z, 20, 9, 2, 9, 0.02);
            }
            if (age % 10 == 0) {
                level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, t.x + rnd.nextGaussian() * 10, t.y + 2, t.z + rnd.nextGaussian() * 10, 1, 0, 0, 0, 0);
            }
            return false;
        });
    }

    // =====================================================================================
    //  WEAPON: ORBITAL WITHER SKULL CANNON
    // =====================================================================================
    static void witherStorm(ServerLevel level, ServerPlayer owner, Vec3 t) {
        broadcast(level, "ORBITAL WITHER STORM called by " + owner.getName().getString() + "!", ChatFormatting.DARK_GRAY);
        int[] launched = {0};
        timeline(age -> {
            if (age < SKULL_DELAY) {
                if (age % 2 == 0) warnBeam(level, t, age, SKULL_DELAY);
                return false;
            }
            int perTick = 1 + (age % 2);
            for (int i = 0; i < perTick && launched[0] < SKULL_COUNT; i++) {
                launchFaller(level, t, SKULL_SPREAD, "minecraft:wither_skull", 80, p -> skullImpact(level, owner, p));
                launched[0]++;
            }
            return launched[0] >= SKULL_COUNT;
        });
    }

    static void skullImpact(ServerLevel level, ServerPlayer owner, Vec3 p) {
        blastFx(level, p, 3.5f);
        level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, p.x, p.y + 1, p.z, 90, 4, 2, 4, 0.12);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, p.x, p.y + 1, p.z, 40, 4, 2, 4, 0.05);
        addJob(Crater.sphere(level, BlockPos.containing(p), SKULL_RADIUS, Float.MAX_VALUE));
        damageArea(level, p, SKULL_RADIUS * 1.6, 80f, owner, 300, 0);
    }

    // =====================================================================================
    //  WEAPON: ORBITAL MOB CANNON
    // =====================================================================================
    static final List<Identifier> curatedValid() {
        List<Identifier> out = new ArrayList<>();
        for (String s : CURATED_MOBS) {
            Identifier id = Identifier.parse("minecraft:" + s);
            if (BuiltInRegistries.ENTITY_TYPE.containsKey(id)) out.add(id);
        }
        return out;
    }

    static void cycleMob(ServerPlayer p) {
        List<Identifier> list = curatedValid();
        if (list.isEmpty()) return;
        int next = (MOB_CYCLE.getOrDefault(p.getUUID(), -1) + 1) % list.size();
        MOB_CYCLE.put(p.getUUID(), next);
        MOB_CHOICE.put(p.getUUID(), list.get(next));
        msg(p, "Orbital Mob Cannon target: " + list.get(next) + "  (/orbitalmob <name> for any mob)", ChatFormatting.GREEN);
    }

    static Identifier resolveMob(String input) {
        String s = input.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (s.isEmpty()) return null;
        try {
            Identifier id = Identifier.parse(s.contains(":") ? s : "minecraft:" + s);
            if (BuiltInRegistries.ENTITY_TYPE.containsKey(id) && BuiltInRegistries.ENTITY_TYPE.getValue(id).canSummon()) return id;
        } catch (Exception ignored) {
            // fall through to fuzzy search
        }
        for (Identifier id : BuiltInRegistries.ENTITY_TYPE.keySet()) {
            if (id.getPath().contains(s) && BuiltInRegistries.ENTITY_TYPE.getValue(id).canSummon()) return id;
        }
        return null;
    }

    static void mobDrop(ServerLevel level, ServerPlayer owner, Vec3 t) {
        Identifier id = MOB_CHOICE.getOrDefault(owner.getUUID(), Identifier.parse("minecraft:zombie"));
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(id);
        int total = ThreadLocalRandom.current().nextInt(MOB_MIN, MOB_MAX + 1);
        broadcast(level, "ORBITAL MOB DROP: " + total + " x " + id + " inbound (called by " + owner.getName().getString() + ")", ChatFormatting.GREEN);
        int[] spawned = {0};
        timeline(age -> {
            if (age < MOB_DELAY) {
                if (age % 2 == 0) warnBeam(level, t, age, MOB_DELAY);
                return false;
            }
            for (int i = 0; i < MOB_PER_TICK && spawned[0] < total; i++) {
                spawnDroppedMob(level, type, t);
                spawned[0]++;
            }
            return spawned[0] >= total;
        });
    }

    static void spawnDroppedMob(ServerLevel level, EntityType<?> type, Vec3 t) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double ang = rnd.nextDouble() * Math.PI * 2;
        double dist = Math.sqrt(rnd.nextDouble()) * MOB_SPREAD;
        double x = t.x + Math.cos(ang) * dist;
        double z = t.z + Math.sin(ang) * dist;
        int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
        double y = Math.min(level.getMaxY() - 2, Math.max(h, t.y) + MOB_HEIGHT + rnd.nextDouble() * 20);
        BlockPos bp = BlockPos.containing(x, y, z);
        if (!level.hasChunkAt(bp)) return;
        Entity e = type.create(level, EntitySpawnReason.COMMAND);
        if (e == null) return;
        e.snapTo(x, y, z, rnd.nextFloat() * 360f, 0f);
        if (e instanceof Mob mob) {
            try {
                mob.finalizeSpawn(level, level.getCurrentDifficultyAt(bp), EntitySpawnReason.COMMAND, null);
            } catch (Throwable ignored) {
                // some mobs dislike being finalised this way; they still work without it
            }
            mob.setPersistenceRequired();
        }
        if (e instanceof LivingEntity le) {
            addEffect(le, "resistance", 400, 4); // survive the fall
        }
        level.addFreshEntity(e);
    }

    // =====================================================================================
    //  STASIS
    // =====================================================================================
    static final class Stasis {
        ResourceKey<Level> dim;
        Vec3 pos;
        float yRot, xRot;
        int expireTick;
        boolean hadNoGravity, wasInvulnerable, hadNoAi;
        UUID owner;
    }

    static InteractionResult useStasisRod(ServerLevel level, ServerPlayer sp) {
        MinecraftServer server = level.getServer();
        if (sp.isShiftKeyDown()) {
            int n = 0;
            for (UUID id : new ArrayList<>(STASIS.keySet())) {
                Stasis s = STASIS.get(id);
                if (s != null && sp.getUUID().equals(s.owner)) {
                    release(server, id);
                    n++;
                }
            }
            msg(sp, "Released " + n + " entities from stasis.", ChatFormatting.LIGHT_PURPLE);
            return InteractionResult.SUCCESS;
        }
        Entity e = aimEntity(level, sp, 128, x -> true);
        if (e == null) {
            msg(sp, "No entity in your crosshair.", ChatFormatting.RED);
            return InteractionResult.FAIL;
        }
        if (STASIS.containsKey(e.getUUID())) {
            release(server, e.getUUID());
            msg(sp, "Released " + e.getName().getString() + " from stasis.", ChatFormatting.LIGHT_PURPLE);
        } else {
            freeze(server, e, sp.getUUID());
            msg(sp, e.getName().getString() + " is now in STASIS.", ChatFormatting.LIGHT_PURPLE);
        }
        return InteractionResult.SUCCESS;
    }

    static void freeze(MinecraftServer server, Entity e, UUID owner) {
        if (STASIS.containsKey(e.getUUID())) return;
        Stasis s = new Stasis();
        s.dim = e.level().dimension();
        s.pos = e.position();
        s.yRot = e.getYRot();
        s.xRot = e.getXRot();
        s.expireTick = server.getTickCount() + STASIS_MAX_TICKS;
        s.hadNoGravity = e.isNoGravity();
        s.wasInvulnerable = e.isInvulnerable();
        s.hadNoAi = e instanceof Mob m && m.isNoAi();
        s.owner = owner;
        STASIS.put(e.getUUID(), s);
        if (e instanceof LivingEntity le) {
            try {
                le.addEffect(new MobEffectInstance(eff("glowing"), STASIS_MAX_TICKS, 0, false, false));
            } catch (Throwable ignored) {
                // cosmetic only
            }
        }
    }

    static Entity findEntity(MinecraftServer server, UUID id, Stasis s) {
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        if (p != null) return p;
        ServerLevel lv = server.getLevel(s.dim);
        return lv == null ? null : lv.getEntity(id);
    }

    static void release(MinecraftServer server, UUID id) {
        Stasis s = STASIS.remove(id);
        if (s == null) return;
        Entity e = findEntity(server, id, s);
        if (e == null) return;
        e.setNoGravity(s.hadNoGravity);
        e.setInvulnerable(s.wasInvulnerable);
        if (e instanceof Mob m) m.setNoAi(s.hadNoAi);
        if (e instanceof LivingEntity le) {
            try {
                le.removeEffect(eff("glowing"));
            } catch (Throwable ignored) {
                // cosmetic only
            }
        }
    }

    static void releaseAll(MinecraftServer server) {
        for (UUID id : new ArrayList<>(STASIS.keySet())) release(server, id);
    }

    static void enforceStasis(MinecraftServer server) {
        if (STASIS.isEmpty()) return;
        int now = server.getTickCount();
        for (UUID id : new ArrayList<>(STASIS.keySet())) {
            Stasis s = STASIS.get(id);
            if (s == null) continue;
            if (now >= s.expireTick) {
                release(server, id);
                continue;
            }
            Entity e = findEntity(server, id, s);
            if (e == null) continue;           // offline / unloaded: keep, will re-apply when back
            if (e.isRemoved() || !e.isAlive()) {
                STASIS.remove(id);
                continue;
            }
            // dimension change (portal etc.): re-anchor
            if (!e.level().dimension().equals(s.dim)) {
                s.dim = e.level().dimension();
                s.pos = e.position();
            }
            e.setNoGravity(true);
            e.setInvulnerable(true);
            if (e instanceof Mob m) m.setNoAi(true);
            e.setDeltaMovement(Vec3.ZERO);
            if (e.position().distanceToSqr(s.pos) > 0.0004) {
                if (e instanceof ServerPlayer sp) {
                    sp.connection.teleport(s.pos.x, s.pos.y, s.pos.z, s.yRot, s.xRot);
                } else {
                    e.snapTo(s.pos.x, s.pos.y, s.pos.z, s.yRot, s.xRot);
                }
            }
            if (now % 10 == 0 && e.level() instanceof ServerLevel lv) {
                lv.sendParticles(ParticleTypes.END_ROD, e.getX(), e.getY() + e.getBbHeight() / 2, e.getZ(), 4, 0.4, 0.5, 0.4, 0.01);
            }
        }
    }

    // ---- teleporter rod ----------------------------------------------------------------
    static void linkAndFreeze(ServerPlayer owner, ServerPlayer target) {
        MinecraftServer server = owner.level().getServer();
        UUID oldId = LINKS.get(owner.getUUID());
        if (oldId != null && !oldId.equals(target.getUUID())) release(server, oldId);
        LINKS.put(owner.getUUID(), target.getUUID());
        freeze(server, target, owner.getUUID());
        msg(owner, "Locked " + target.getName().getString() + " in stasis. Use the rod on a block to teleport them, "
                + "sneak + use to bring them to you.", ChatFormatting.BLUE);
        msg(target, "You have been placed in stasis by " + owner.getName().getString() + ".", ChatFormatting.BLUE);
    }

    static void moveStasisEntity(Entity e, Vec3 dest) {
        float yr = e.getYRot(), xr = e.getXRot();
        if (e instanceof ServerPlayer sp) {
            sp.connection.teleport(dest.x, dest.y, dest.z, yr, xr);
        } else {
            e.snapTo(dest.x, dest.y, dest.z, yr, xr);
        }
        Stasis s = STASIS.get(e.getUUID());
        if (s != null) {
            s.pos = dest;
            s.yRot = yr;
            s.xRot = xr;
        }
    }

    static InteractionResult useStasisTeleporter(ServerLevel level, ServerPlayer sp) {
        MinecraftServer server = level.getServer();
        UUID linkedId = LINKS.get(sp.getUUID());
        ServerPlayer linked = linkedId == null ? null : server.getPlayerList().getPlayer(linkedId);

        Entity aimed = aimEntity(level, sp, 200, x -> x instanceof ServerPlayer);
        if (aimed instanceof ServerPlayer other) {
            if (linked != null && linked.getUUID().equals(other.getUUID())) {
                release(server, other.getUUID());
                LINKS.remove(sp.getUUID());
                msg(sp, "Released " + other.getName().getString() + ".", ChatFormatting.BLUE);
                msg(other, "You have been released from stasis.", ChatFormatting.BLUE);
            } else {
                linkAndFreeze(sp, other);
            }
            return InteractionResult.SUCCESS;
        }

        if (linked == null) {
            msg(sp, "No linked player. Aim at a player and use the rod (or /stasislink <player>).", ChatFormatting.RED);
            return InteractionResult.FAIL;
        }
        if (!linked.level().dimension().equals(level.dimension())) {
            msg(sp, linked.getName().getString() + " is in another dimension - same-dimension teleports only.", ChatFormatting.RED);
            return InteractionResult.FAIL;
        }
        Vec3 dest;
        if (sp.isShiftKeyDown()) {
            Vec3 look = sp.getLookAngle();
            dest = sp.position().add(look.x * 2.0, 0.0, look.z * 2.0);
        } else {
            Aim a = aim(level, sp, AIM_RANGE);
            if (a == null) {
                msg(sp, "No target block in range.", ChatFormatting.RED);
                return InteractionResult.FAIL;
            }
            dest = a.before();
        }
        if (!STASIS.containsKey(linked.getUUID())) freeze(server, linked, sp.getUUID());
        moveStasisEntity(linked, dest);
        level.sendParticles(ParticleTypes.END_ROD, dest.x, dest.y + 1, dest.z, 40, 0.5, 1.0, 0.5, 0.1);
        msg(sp, "Teleported " + linked.getName().getString() + " (still in stasis).", ChatFormatting.BLUE);
        msg(linked, "You were teleported by " + sp.getName().getString() + ".", ChatFormatting.BLUE);
        return InteractionResult.SUCCESS;
    }
}
