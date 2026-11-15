package uz.shoxcraft;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.boss.*;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.*;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class ShoxCraft extends JavaPlugin implements Listener {

    private NamespacedKey keyId, keyUses;
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
    private final Map<UUID, Location> lastLoc = new HashMap<>();
    private final Map<UUID, UUID> tpaFrom = new HashMap<>();
    private final Map<UUID, Long> tpaTime = new HashMap<>();
    private File dataFile;
    private YamlConfiguration data;

    static class ShopHolder implements InventoryHolder {
        public Inventory getInventory() { return null; }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        keyId = new NamespacedKey(this, "artifact_id");
        keyUses = new NamespacedKey(this, "uses");
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        getServer().getPluginManager().registerEvents(this, this);
        startScheduler();
        getLogger().info("ShoxCraft yoqildi!");
    }

    @Override
    public void onDisable() { cleanupEvent(false); saveData(); }

    // ---------- yordamchi funksiyalar ----------
    private String color(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
    private void msg(CommandSender s, String m) { s.sendMessage(color(m)); }
    private void saveData() { try { data.save(dataFile); } catch (IOException e) { getLogger().warning("data.yml saqlanmadi: " + e.getMessage()); } }
    private void actionBar(Player p, String m) { p.sendActionBar(LegacyComponentSerializer.legacyAmpersand().deserialize(m)); }

    private double money(UUID u) { return data.getDouble("money." + u, getConfig().getDouble("boshlangich-pul", 1000)); }
    private void setMoney(UUID u, double v) { data.set("money." + u, v); saveData(); }
    private String rank(UUID u) { return data.getString("rank." + u, "oyinchi"); }

    private boolean isAdmin(CommandSender s) {
        if (!(s instanceof Player p)) return true;
        if (p.isOp()) return true;
        return getConfig().getBoolean("ranks." + rank(p.getUniqueId()) + ".admin", false);
    }
    private boolean canFly(Player p) {
        return isAdmin(p) || getConfig().getBoolean("ranks." + rank(p.getUniqueId()) + ".fly", false);
    }

    private double parseAmount(String s) {
        try { double d = Double.parseDouble(s); return d > 0 ? d : -1; } catch (NumberFormatException e) { return -1; }
    }

    private String locToStr(Location l) {
        return l.getWorld().getName() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ() + ";" + l.getYaw() + ";" + l.getPitch();
    }
    private Location strToLoc(String s) {
        if (s == null) return null;
        String[] x = s.split(";");
        World w = Bukkit.getWorld(x[0]);
        if (w == null) return null;
        return new Location(w, Double.parseDouble(x[1]), Double.parseDouble(x[2]), Double.parseDouble(x[3]),
                Float.parseFloat(x[4]), Float.parseFloat(x[5]));
    }

    // ---------- hodisalar: kirish, chat, orqaga, himoya ----------
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!data.contains("money." + p.getUniqueId())) setMoney(p.getUniqueId(), getConfig().getDouble("boshlangich-pul", 1000));
        msg(p, getConfig().getString("xush-kelibsiz", "").replace("%oyinchi%", p.getName()));
        if (bossBar != null) bossBar.addPlayer(p);
        if (!p.hasPlayedBefore()) {
            Location sp = strToLoc(data.getString("spawn"));
            if (sp != null) p.teleport(sp);
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        String prefix = getConfig().getString("ranks." + rank(e.getPlayer().getUniqueId()) + ".prefix", "");
        e.setFormat(color(prefix).replace("%", "%%") + "%1$s" + ChatColor.GRAY + ": " + ChatColor.WHITE + "%2$s");
    }

    @EventHandler
    public void onTp(PlayerTeleportEvent e) { lastLoc.put(e.getPlayer().getUniqueId(), e.getFrom().clone()); }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) { lastLoc.put(e.getEntity().getUniqueId(), e.getEntity().getLocation().clone()); }

    private boolean inSpawnZone(Location l) {
        int r = getConfig().getInt("spawn-himoya", 0);
        if (r <= 0) return false;
        Location sp = strToLoc(data.getString("spawn"));
        return sp != null && sp.getWorld().equals(l.getWorld()) && sp.distance(l) <= r;
    }
    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!isAdmin(e.getPlayer()) && inSpawnZone(e.getBlock().getLocation())) {
            e.setCancelled(true); actionBar(e.getPlayer(), "&cBoshlash hududi himoyalangan!");
        }
    }
    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!isAdmin(e.getPlayer()) && inSpawnZone(e.getBlock().getLocation())) {
            e.setCancelled(true); actionBar(e.getPlayer(), "&cBoshlash hududi himoyalangan!");
        }
    }

    // ---------- artefaktlar ----------
    private int level(int uses) {
        int per = getConfig().getInt("uses-per-level", 10);
        int max = getConfig().getInt("max-level", 5);
        return Math.min(max, 1 + uses / per);
    }

    private ItemStack createItem(String id) {
        ConfigurationSection s = getConfig().getConfigurationSection("artifacts." + id);
        if (s == null) return null;
        ItemStack item = new ItemStack(Material.valueOf(s.getString("material", "STICK")));
        ItemMeta m = item.getItemMeta();
        m.setDisplayName(color(s.getString("name", id)));
        List<String> lore = new ArrayList<>();
        for (String l : s.getStringList("lore")) lore.add(color(l));
        lore.add(color("&8Daraja: 1"));
        m.setLore(lore);
        int cmd = s.getInt("custom-model-data", 0);
        if (cmd > 0) m.setCustomModelData(cmd);
        m.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        m.getPersistentDataContainer().set(keyId, PersistentDataType.STRING, id);
        m.getPersistentDataContainer().set(keyUses, PersistentDataType.INTEGER, 0);
        item.setItemMeta(m);
        return item;
    }

    private List<String> ids() {
        ConfigurationSection s = getConfig().getConfigurationSection("artifacts");
        return s == null ? List.of() : new ArrayList<>(s.getKeys(false));
    }

    private void openShop(Player p) {
        Inventory inv = Bukkit.createInventory(new ShopHolder(), 27, color("&6&lShoxCraft Do'koni"));
        int slot = 10;
        for (String id : ids()) {
            ItemStack it = createItem(id);
            ItemMeta m = it.getItemMeta();
            List<String> lore = m.getLore();
            lore.add(color("&eNarxi: &f" + getConfig().getDouble("artifacts." + id + ".price")));
            lore.add(color("&aBosing: sotib olish"));
            m.setLore(lore);
            it.setItemMeta(m);
            inv.setItem(slot++, it);
            if (slot == 17) slot = 19;
        }
        p.openInventory(inv);
    }

    @EventHandler
    public void onShopClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof ShopHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        String id = clicked.getItemMeta().getPersistentDataContainer().get(keyId, PersistentDataType.STRING);
        if (id == null) return;
        double price = getConfig().getDouble("artifacts." + id + ".price");
        if (money(p.getUniqueId()) < price) { msg(p, "&cPulingiz yetarli emas!"); return; }
        setMoney(p.getUniqueId(), money(p.getUniqueId()) - price);
        p.getInventory().addItem(createItem(id));
        msg(p, "&aSotib olindi!");
    }

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND) return;
        ItemStack item = e.getItem();
        if (item == null || !item.hasItemMeta()) return;
        var pdc = item.getItemMeta().getPersistentDataContainer();
        String id = pdc.get(keyId, PersistentDataType.STRING);
        if (id == null) return;
        e.setCancelled(true);

        Player p = e.getPlayer();
        ConfigurationSection s = getConfig().getConfigurationSection("artifacts." + id);
        if (s == null) return;
        int uses = pdc.getOrDefault(keyUses, PersistentDataType.INTEGER, 0);
        int lvl = level(uses);

        double cd = s.getDouble("cooldown", 10) * (1 - 0.08 * (lvl - 1));
        long now = System.currentTimeMillis();
        Map<String, Long> map = cooldowns.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
        long ready = map.getOrDefault(id, 0L);
        if (now < ready) {
            actionBar(p, "&cKuting: " + String.format("%.1f", (ready - now) / 1000.0) + " s");
            return;
        }
        map.put(id, now + (long) (cd * 1000));

        castAbility(p, s.getString("ability", ""), lvl);

        uses++;
        ItemMeta m = item.getItemMeta();
        m.getPersistentDataContainer().set(keyUses, PersistentDataType.INTEGER, uses);
        int newLvl = level(uses);
        List<String> lore = m.getLore();
        if (lore != null && !lore.isEmpty()) {
            lore.set(lore.size() - 1, color("&8Daraja: " + newLvl));
            m.setLore(lore);
        }
        item.setItemMeta(m);
        if (newLvl > lvl) {
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
            actionBar(p, "&6&lArtefakt " + newLvl + "-darajaga ko'tarildi!");
        }
    }

    private void castAbility(Player p, String ability, int lvl) {
        World w = p.getWorld();
        Location loc = p.getLocation();
        switch (ability) {
            case "DASH" -> {
                p.setVelocity(loc.getDirection().multiply(1.6 + 0.2 * lvl).setY(0.35));
                w.spawnParticle(Particle.CLOUD, loc, 30, 0.3, 0.1, 0.3, 0.05);
                w.playSound(loc, Sound.ENTITY_BREEZE_WIND_BURST, 1f, 1f);
            }
            case "FIREBALL" -> {
                Fireball f = p.launchProjectile(Fireball.class);
                f.setYield(1.5f + 0.5f * lvl);
                f.setIsIncendiary(false);
                w.playSound(loc, Sound.ENTITY_BLAZE_SHOOT, 1f, 1f);
            }
            case "LEAP" -> {
                p.setVelocity(new org.bukkit.util.Vector(0, 0.9 + 0.15 * lvl, 0).add(loc.getDirection().multiply(0.5)));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 20 * (4 + lvl), 0));
                w.spawnParticle(Particle.CLOUD, loc, 25, 0.4, 0.1, 0.4, 0.02);
            }
            case "LIGHTNING" -> {
                Block b = p.getTargetBlockExact(30);
                if (b == null) { actionBar(p, "&cNishon topilmadi"); return; }
                for (int i = 0; i < Math.max(1, lvl / 2 + 1); i++) w.strikeLightning(b.getLocation());
            }
            case "HEAL" -> {
                double max = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
                p.setHealth(Math.min(max, p.getHealth() + 4 + 2 * lvl));
                w.spawnParticle(Particle.HEART, loc.clone().add(0, 1.5, 0), 10, 0.4, 0.3, 0.4);
                w.playSound(loc, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);
            }
            case "SOUL_DRAIN" -> {
                double dmg = 3 + lvl;
                double healed = 0;
                for (Entity en : p.getNearbyEntities(6, 4, 6)) {
                    if (en instanceof LivingEntity le) {
                        le.damage(dmg, p);
                        healed += dmg / 2;
                        w.spawnParticle(Particle.SOUL, le.getLocation().add(0, 1, 0), 10, 0.2, 0.4, 0.2, 0.02);
                    }
                }
                double max = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
                p.setHealth(Math.min(max, p.getHealth() + Math.min(healed, 10)));
                w.playSound(loc, Sound.PARTICLE_SOUL_ESCAPE, 1f, 1f);
            }
            case "VOID_STEP" -> {
                Location target = loc.clone().add(loc.getDirection().multiply(6 + 2 * lvl));
                target.setY(Math.max(loc.getY(), target.getY()));
                if (target.getBlock().isPassable() && target.clone().add(0, 1, 0).getBlock().isPassable()) {
                    w.spawnParticle(Particle.PORTAL, loc, 40, 0.3, 0.6, 0.3, 0.5);
                    p.teleport(target);
                    w.spawnParticle(Particle.PORTAL, target, 40, 0.3, 0.6, 0.3, 0.5);
                    w.playSound(target, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
                } else actionBar(p, "&cYo'l to'siq");
            }
        }
    }

    // ---------- tadbirlar (eventlar) ----------
    private String activeEvent = null;
    private Location treasureLoc;
    private LivingEntity boss;
    private BossBar bossBar;
    private BukkitTask endTask;
    private final Map<UUID, Double> scores = new HashMap<>();

    private void broadcast(String m) { Bukkit.broadcastMessage(color(m)); }

    private void startScheduler() {
        long ticks = getConfig().getLong("tadbirlar.oraliq-daqiqa", 30) * 60L * 20L;
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (activeEvent != null || Bukkit.getOnlinePlayers().isEmpty()) return;
            List<String> list = getConfig().getStringList("tadbirlar.royxat");
            if (list.isEmpty()) return;
            startEvent(list.get(new Random().nextInt(list.size())));
        }, ticks, ticks);
    }

    private boolean startEvent(String name) {
        if (activeEvent != null) return false;
        switch (name) {
            case "xazina" -> startTreasure();
            case "boss" -> startBoss();
            case "konchi" -> startMining();
            default -> { return false; }
        }
        return activeEvent != null;
    }

    private Location randomLocation() {
        World w = Bukkit.getWorlds().get(0);
        int r = getConfig().getInt("tadbirlar.radius", 2000);
        Random rnd = new Random();
        for (int i = 0; i < 30; i++) {
            int x = rnd.nextInt(2 * r + 1) - r;
            int z = rnd.nextInt(2 * r + 1) - r;
            int y = w.getHighestBlockYAt(x, z);
            Block ground = w.getBlockAt(x, y, z);
            if (ground.isLiquid()) continue;
            return new Location(w, x + 0.5, y + 1, z + 0.5);
        }
        return null;
    }

    private String coords(Location l) {
        return "X: " + l.getBlockX() + " Y: " + l.getBlockY() + " Z: " + l.getBlockZ();
    }

    private void startTreasure() {
        Location l = randomLocation();
        if (l == null) return;
        Block b = l.getBlock();
        b.setType(Material.CHEST);
        Chest ch = (Chest) b.getState();
        Inventory inv = ch.getInventory();
        inv.addItem(new ItemStack(Material.DIAMOND, 8), new ItemStack(Material.GOLDEN_APPLE, 4),
                new ItemStack(Material.NETHERITE_SCRAP, 3));
        List<String> all = ids();
        if (!all.isEmpty()) inv.addItem(createItem(all.get(new Random().nextInt(all.size()))));
        treasureLoc = l;
        activeEvent = "xazina";
        broadcast("&6&l[TADBIR] Xazina paydo bo'ldi! &e" + coords(l));
        broadcast("&7Birinchi bo'lib sandiqni ochgan pul va artefakt oladi. Anarxiya: hamma raqibingiz!");
        endTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!"xazina".equals(activeEvent)) return;
            broadcast("&c[TADBIR] Xazinani hech kim topmadi, u g'oyib bo'ldi.");
            cleanupEvent(true);
        }, 20L * 60 * getConfig().getLong("tadbirlar.xazina-daqiqa", 10));
    }

    private void startBoss() {
        Location l = randomLocation();
        if (l == null) return;
        WitherSkeleton ws = (WitherSkeleton) l.getWorld().spawnEntity(l, EntityType.WITHER_SKELETON);
        ws.setCustomName(color("&4&lQora Shox"));
        ws.setCustomNameVisible(true);
        double hp = getConfig().getDouble("tadbirlar.boss-jon", 400);
        ws.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).setBaseValue(hp);
        ws.setHealth(hp);
        ws.getAttribute(org.bukkit.attribute.Attribute.ATTACK_DAMAGE).setBaseValue(10);
        ws.getEquipment().setItemInMainHand(new ItemStack(Material.NETHERITE_SWORD));
        ws.getEquipment().setHelmet(new ItemStack(Material.DIAMOND_HELMET));
        ws.setRemoveWhenFarAway(false);
        boss = ws;
        bossBar = Bukkit.createBossBar(color("&4&lQora Shox"), BarColor.RED, BarStyle.SOLID);
        for (Player pl : Bukkit.getOnlinePlayers()) bossBar.addPlayer(pl);
        activeEvent = "boss";
        broadcast("&4&l[TADBIR] Qora Shox paydo bo'ldi! &e" + coords(l));
        broadcast("&7Eng ko'p zarar yetkazgan 3 kishi mukofot oladi.");
        endTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!"boss".equals(activeEvent)) return;
            broadcast("&c[TADBIR] Qora Shox g'oyib bo'ldi.");
            cleanupEvent(true);
        }, 20L * 60 * getConfig().getLong("tadbirlar.boss-daqiqa", 15));
    }

    private void startMining() {
        activeEvent = "konchi";
        long min = getConfig().getLong("tadbirlar.konchi-daqiqa", 10);
        broadcast("&6&l[TADBIR] Kon qazish musobaqasi boshlandi! &e" + min + " daqiqa ichida eng ko'p ruda qazigan g'olib.");
        endTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!"konchi".equals(activeEvent)) return;
            List<UUID> w = top3();
            if (w.isEmpty()) broadcast("&c[TADBIR] Kon musobaqasida hech kim qatnashmadi.");
            else { broadcast("&6&l[TADBIR] Kon musobaqasi tugadi!"); payout(w, "ruda"); }
            cleanupEvent(false);
        }, 20L * 60 * min);
    }

    private List<UUID> top3() {
        return scores.entrySet().stream()
                .sorted((x, y) -> Double.compare(y.getValue(), x.getValue()))
                .limit(3).map(Map.Entry::getKey).toList();
    }

    private void payout(List<UUID> winners, String unit) {
        List<Double> rw = getConfig().getDoubleList("tadbirlar.mukofot");
        for (int i = 0; i < winners.size(); i++) {
            UUID u = winners.get(i);
            double amt = i < rw.size() ? rw.get(i) : 0;
            setMoney(u, money(u) + amt);
            broadcast("&e" + (i + 1) + ". &f" + Bukkit.getOfflinePlayer(u).getName() + " &7- "
                    + String.format("%.0f", scores.get(u)) + " " + unit + " &a(+" + amt + ")");
            if (i == 0) {
                Player pl = Bukkit.getPlayer(u);
                List<String> all = ids();
                if (pl != null && !all.isEmpty()) pl.getInventory().addItem(createItem(all.get(new Random().nextInt(all.size()))));
            }
        }
    }

    private void cleanupEvent(boolean removeChest) {
        if (endTask != null) { endTask.cancel(); endTask = null; }
        if (bossBar != null) { bossBar.removeAll(); bossBar = null; }
        if (boss != null) { boss.remove(); boss = null; }
        if (removeChest && treasureLoc != null) treasureLoc.getBlock().setType(Material.AIR);
        treasureLoc = null;
        scores.clear();
        activeEvent = null;
    }

    @EventHandler
    public void onChestOpen(InventoryOpenEvent e) {
        if (!"xazina".equals(activeEvent) || treasureLoc == null) return;
        Location il = e.getInventory().getLocation();
        if (il == null || !il.getBlock().equals(treasureLoc.getBlock())) return;
        if (!(e.getPlayer() instanceof Player pl)) return;
        double amt = getConfig().getDouble("tadbirlar.xazina-pul", 1500);
        setMoney(pl.getUniqueId(), money(pl.getUniqueId()) + amt);
        broadcast("&6&l[TADBIR] " + pl.getName() + " xazinani topdi! &a(+" + amt + ")");
        cleanupEvent(false);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBossDamage(EntityDamageByEntityEvent e) {
        if (boss == null || bossBar == null || !e.getEntity().equals(boss)) return;
        Entity d = e.getDamager();
        if (d instanceof Projectile pr && pr.getShooter() instanceof Player sh) d = sh;
        if (!(d instanceof Player pl)) return;
        scores.merge(pl.getUniqueId(), e.getFinalDamage(), Double::sum);
        double max = boss.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
        bossBar.setProgress(Math.max(0, Math.min(1, (boss.getHealth() - e.getFinalDamage()) / max)));
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent e) {
        if (boss == null || !e.getEntity().equals(boss)) return;
        e.getDrops().clear();
        e.setDroppedExp(500);
        broadcast("&4&l[TADBIR] Qora Shox o'ldirildi!");
        payout(top3(), "zarar");
        boss = null;
        cleanupEvent(false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMine(BlockBreakEvent e) {
        if (!"konchi".equals(activeEvent)) return;
        Material m = e.getBlock().getType();
        if (m.name().endsWith("_ORE") || m == Material.ANCIENT_DEBRIS)
            scores.merge(e.getPlayer().getUniqueId(), 1.0, Double::sum);
    }

    // ---------- buyruqlar ----------
    private static final Set<String> ADMIN_CMDS = Set.of("artefakt", "shoxqayta", "pulber", "rol", "shifo",
            "manzilqoy", "manzilochir", "boshlashqoy", "tadbir");
    private static final Set<String> CONSOLE_OK = Set.of("artefakt", "shoxqayta", "pulber", "rol", "tadbir");

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        String c = cmd.getName().toLowerCase();
        Player p = sender instanceof Player pl ? pl : null;

        if (ADMIN_CMDS.contains(c) && !isAdmin(sender)) { msg(sender, "&cSizda ruxsat yo'q."); return true; }
        if (p == null && !CONSOLE_OK.contains(c)) { msg(sender, "&cBu buyruq faqat o'yinda ishlaydi."); return true; }

        switch (c) {
            case "yordam" -> {
                msg(p, "&6&lShoxCraft buyruqlari:");
                msg(p, "&e/balans /tolov /kunlik &7- pul");
                msg(p, "&e/uy /uyqoy /uyochir /uylar &7- uylar");
                msg(p, "&e/yuborish /qabul /rad &7- teleport so'rovi");
                msg(p, "&e/boshlash /manzil /manzillar /orqaga &7- teleport");
                msg(p, "&e/dokon &7- artefaktlar do'koni");
                msg(p, "&e/uchish &7- uchish (VIP va admin)");
                if (isAdmin(p)) msg(p, "&c/tadbir boshlash <xazina|boss|konchi> &7| &c/tadbir toxtat");
            }
            case "dokon" -> openShop(p);
            case "artefakt" -> {
                if (a.length < 2) { msg(sender, "&cIshlatilishi: /artefakt <o'yinchi> <id>"); return true; }
                Player t = Bukkit.getPlayer(a[0]);
                ItemStack it = createItem(a[1]);
                if (t == null || it == null) { msg(sender, "&cO'yinchi yoki artefakt topilmadi."); return true; }
                t.getInventory().addItem(it);
                msg(sender, "&aArtefakt berildi.");
            }
            case "shoxqayta" -> { reloadConfig(); msg(sender, "&aSozlamalar qayta yuklandi."); }

            // --- pul ---
            case "balans" -> {
                Player t = a.length > 0 ? Bukkit.getPlayer(a[0]) : p;
                if (t == null) { msg(p, "&cO'yinchi topilmadi."); return true; }
                msg(p, "&e" + t.getName() + " puli: &f" + String.format("%.2f", money(t.getUniqueId())));
            }
            case "tolov" -> {
                if (a.length < 2) { msg(p, "&cIshlatilishi: /tolov <o'yinchi> <miqdor>"); return true; }
                Player t = Bukkit.getPlayer(a[0]);
                double amt = parseAmount(a[1]);
                if (t == null || amt <= 0) { msg(p, "&cO'yinchi yoki miqdor noto'g'ri."); return true; }
                if (t.equals(p)) { msg(p, "&cO'zingizga yubora olmaysiz."); return true; }
                if (money(p.getUniqueId()) < amt) { msg(p, "&cPulingiz yetarli emas."); return true; }
                setMoney(p.getUniqueId(), money(p.getUniqueId()) - amt);
                setMoney(t.getUniqueId(), money(t.getUniqueId()) + amt);
                msg(p, "&a" + t.getName() + " ga " + amt + " yuborildi.");
                msg(t, "&a" + p.getName() + " sizga " + amt + " yubordi.");
            }
            case "pulber" -> {
                if (a.length < 2) { msg(sender, "&cIshlatilishi: /pulber <o'yinchi> <miqdor>"); return true; }
                Player t = Bukkit.getPlayer(a[0]);
                double amt = parseAmount(a[1]);
                if (t == null || amt <= 0) { msg(sender, "&cO'yinchi yoki miqdor noto'g'ri."); return true; }
                setMoney(t.getUniqueId(), money(t.getUniqueId()) + amt);
                msg(sender, "&aBerildi."); msg(t, "&a+" + amt + " pul oldingiz!");
            }
            case "kunlik" -> {
                long last = data.getLong("daily." + p.getUniqueId(), 0L);
                long now = System.currentTimeMillis();
                if (now - last < 24L * 3600 * 1000) {
                    long left = (24L * 3600 * 1000 - (now - last)) / 60000;
                    msg(p, "&cKeyingi mukofot: " + left / 60 + " soat " + left % 60 + " daqiqadan keyin.");
                    return true;
                }
                double r = getConfig().getDouble("kunlik-mukofot", 250);
                data.set("daily." + p.getUniqueId(), now);
                setMoney(p.getUniqueId(), money(p.getUniqueId()) + r);
                msg(p, "&aKunlik mukofot: +" + r);
            }

            // --- rollar ---
            case "rol" -> {
                if (a.length < 2) { msg(sender, "&cIshlatilishi: /rol <o'yinchi> <rol>"); return true; }
                Player t = Bukkit.getPlayer(a[0]);
                if (t == null) { msg(sender, "&cO'yinchi topilmadi."); return true; }
                if (!getConfig().contains("ranks." + a[1])) {
                    msg(sender, "&cBunday rol yo'q. Mavjud: " + getConfig().getConfigurationSection("ranks").getKeys(false));
                    return true;
                }
                data.set("rank." + t.getUniqueId(), a[1]); saveData();
                msg(sender, "&a" + t.getName() + " roli: " + a[1]);
                msg(t, "&aSizning yangi rolingiz: " + a[1]);
            }

            // --- uylar ---
            case "uyqoy" -> {
                String n = a.length > 0 ? a[0].toLowerCase() : "uy";
                String path = "homes." + p.getUniqueId();
                ConfigurationSection hs = data.getConfigurationSection(path);
                int count = hs == null ? 0 : hs.getKeys(false).size();
                if ((hs == null || !hs.contains(n)) && count >= getConfig().getInt("maks-uy", 3)) {
                    msg(p, "&cUylar soni chegarasiga yetdingiz."); return true;
                }
                data.set(path + "." + n, locToStr(p.getLocation())); saveData();
                msg(p, "&aUy '" + n + "' belgilandi.");
            }
            case "uy" -> {
                String n = a.length > 0 ? a[0].toLowerCase() : "uy";
                Location l = strToLoc(data.getString("homes." + p.getUniqueId() + "." + n));
                if (l == null) { msg(p, "&cBunday uy yo'q. /uylar"); return true; }
                p.teleport(l); msg(p, "&aUyga keldingiz.");
            }
            case "uyochir" -> {
                String n = a.length > 0 ? a[0].toLowerCase() : "uy";
                data.set("homes." + p.getUniqueId() + "." + n, null); saveData();
                msg(p, "&aUy o'chirildi.");
            }
            case "uylar" -> {
                ConfigurationSection hs = data.getConfigurationSection("homes." + p.getUniqueId());
                msg(p, "&eUylaringiz: &f" + (hs == null ? "yo'q" : hs.getKeys(false)));
            }

            // --- teleport so'rovlari ---
            case "yuborish" -> {
                if (a.length < 1) { msg(p, "&cIshlatilishi: /yuborish <o'yinchi>"); return true; }
                Player t = Bukkit.getPlayer(a[0]);
                if (t == null || t.equals(p)) { msg(p, "&cO'yinchi topilmadi."); return true; }
                tpaFrom.put(t.getUniqueId(), p.getUniqueId());
                tpaTime.put(t.getUniqueId(), System.currentTimeMillis());
                msg(p, "&aSo'rov yuborildi.");
                msg(t, "&e" + p.getName() + " sizning oldingizga kelmoqchi. &a/qabul &eyoki &c/rad");
            }
            case "qabul" -> {
                UUID from = tpaFrom.remove(p.getUniqueId());
                Long time = tpaTime.remove(p.getUniqueId());
                Player r = from == null ? null : Bukkit.getPlayer(from);
                long limit = getConfig().getLong("tpa-vaqt", 60) * 1000;
                if (r == null || time == null || System.currentTimeMillis() - time > limit) {
                    msg(p, "&cFaol so'rov yo'q."); return true;
                }
                r.teleport(p);
                msg(p, "&aQabul qilindi."); msg(r, "&aTeleport qilindingiz.");
            }
            case "rad" -> {
                UUID from = tpaFrom.remove(p.getUniqueId());
                tpaTime.remove(p.getUniqueId());
                Player r = from == null ? null : Bukkit.getPlayer(from);
                msg(p, "&cSo'rov rad etildi.");
                if (r != null) msg(r, "&c" + p.getName() + " so'rovni rad etdi.");
            }

            // --- boshlash, manzillar, orqaga ---
            case "boshlashqoy" -> { data.set("spawn", locToStr(p.getLocation())); saveData(); msg(p, "&aBoshlash nuqtasi belgilandi."); }
            case "boshlash" -> {
                Location l = strToLoc(data.getString("spawn"));
                if (l == null) { msg(p, "&cBoshlash nuqtasi belgilanmagan."); return true; }
                p.teleport(l);
            }
            case "manzilqoy" -> {
                if (a.length < 1) { msg(p, "&cIshlatilishi: /manzilqoy <nom>"); return true; }
                data.set("warps." + a[0].toLowerCase(), locToStr(p.getLocation())); saveData();
                msg(p, "&aManzil belgilandi.");
            }
            case "manzil" -> {
                if (a.length < 1) { msg(p, "&cIshlatilishi: /manzil <nom>"); return true; }
                Location l = strToLoc(data.getString("warps." + a[0].toLowerCase()));
                if (l == null) { msg(p, "&cBunday manzil yo'q. /manzillar"); return true; }
                p.teleport(l);
            }
            case "manzilochir" -> {
                if (a.length < 1) { msg(p, "&cIshlatilishi: /manzilochir <nom>"); return true; }
                data.set("warps." + a[0].toLowerCase(), null); saveData(); msg(p, "&aO'chirildi.");
            }
            case "manzillar" -> {
                ConfigurationSection ws = data.getConfigurationSection("warps");
                msg(p, "&eManzillar: &f" + (ws == null ? "yo'q" : ws.getKeys(false)));
            }
            case "orqaga" -> {
                Location l = lastLoc.get(p.getUniqueId());
                if (l == null) { msg(p, "&cQaytadigan joy yo'q."); return true; }
                p.teleport(l);
            }

            case "tadbir" -> {
                if (a.length == 0) { msg(sender, "&cIshlatilishi: /tadbir boshlash <xazina|boss|konchi> | /tadbir toxtat"); return true; }
                if (a[0].equalsIgnoreCase("toxtat")) {
                    if (activeEvent == null) { msg(sender, "&cFaol tadbir yo'q."); return true; }
                    broadcast("&cTadbir admin tomonidan to'xtatildi.");
                    cleanupEvent(true);
                } else if (a[0].equalsIgnoreCase("boshlash") && a.length > 1) {
                    if (!startEvent(a[1].toLowerCase())) msg(sender, "&cBoshlab bo'lmadi (boshqa tadbir ketyapti yoki nom noto'g'ri).");
                } else msg(sender, "&cIshlatilishi: /tadbir boshlash <xazina|boss|konchi> | /tadbir toxtat");
            }

            // --- qo'shimcha ---
            case "shifo" -> {
                p.setHealth(p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());
                p.setFoodLevel(20);
                msg(p, "&aDavolandingiz.");
            }
            case "uchish" -> {
                if (!canFly(p)) { msg(p, "&cUchish faqat VIP va adminlar uchun."); return true; }
                boolean on = !p.getAllowFlight();
                p.setAllowFlight(on);
                if (!on) p.setFlying(false);
                msg(p, on ? "&aUchish yoqildi." : "&cUchish o'chirildi.");
            }
        }
        return true;
    }
}
