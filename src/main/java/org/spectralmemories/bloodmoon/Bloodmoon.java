package org.spectralmemories.bloodmoon;

import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.spectralmemories.sqlaccess.FieldType;
import org.spectralmemories.sqlaccess.SQLAccess;
import org.spectralmemories.sqlaccess.SQLField;
import org.spectralmemories.sqlaccess.SQLTable;

import java.io.File;
import java.io.IOException;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Entry class for the BloodMoon plugin. Singleton, you should never create an instance manually
 */
public final class Bloodmoon extends JavaPlugin
{
    public static final String CACHE_DB = "cache.db";
    /**
     * The config file
     */
    public static final String CONFIG_FILE = "config.yml";
    public static final String SLASH = "/";
    /**
     * The locale file
     */
    public static final String LOCALES_YML = "locales.yml";
    public final static long NIGHT_CHECK_DELAY = 40;

    private static SQLAccess sqlAccess;
    private static LocaleReader localeReader;

    private static Bloodmoon instance;

    private static List<PeriodicNightCheck> nightChecks;
    private static List<BloodmoonActuator> actuators;
    private static List<World> bloodmoonWorlds;
    private static Map<World, ConfigReader> configReaders;
    private static List<ConfigReader> allConfigReaders;

    private static WorldManager worldManager;

    /**
     * Returns the Bloodmoon instance
     * This method has a very low latency
     * @return The Bloodmoon singleton instance
     */
    public static Bloodmoon GetInstance ()
    {
        return instance;
    }

    private List<World> BlackListedWorlds;

    /**
     * Returns blacklisted worlds
     * @return Blacklisted worlds
     */
    public List<World> getBlacklistedWorlds()
    {
        return BlackListedWorlds;
    }

    /**
     * Returns worlds where bloodmoons apply, either periodically or permanently
     * @return Bloodmoon enabled worlds
     */
    public static List<World> GetBloodMoonWorlds()
    {
        return bloodmoonWorlds;
    }

    /**
     * Returns the server scheduler. Mostly a shortcut
     * @return scheduler
     */
    public BukkitScheduler GetScheduler ()
    {
        return getServer().getScheduler();
    }

    private void InitializeSQLAccess ()
    {
        boolean mustCreateDb = ! (new File(getDataFolder().getAbsolutePath() + SLASH + CACHE_DB).exists());
        try
        {
            DriverManager.getConnection(SQLAccess.JDBC_SQLITE + getDataFolder().getAbsolutePath() + SLASH + CACHE_DB); //create db if it does not exist
            File db = new File(getDataFolder().getAbsoluteFile() + SLASH + CACHE_DB);

            sqlAccess = new SQLAccess(db);

            if (mustCreateDb)
            {
                List<SQLField> fields = new ArrayList<>();
                fields.add(new SQLField("world", FieldType.TEXT, true, false));
                fields.add(new SQLField("days", FieldType.INTEGER, false, false));
                fields.add(new SQLField("checkAt", FieldType.INTEGER, false, false));

                SQLTable bloodMoonTable = new SQLTable("lastBloodMoon",  fields);

                sqlAccess.CreateTable(bloodMoonTable);
            }
        }
        catch (SQLException e)
        {
            e.printStackTrace();
        }
    }

    /**
     * Returns the SQLAccess object initialized at startup
     * @return the SQLAccess instance
     */
    public SQLAccess getSqlAccess ()
    {
        if (sqlAccess == null) InitializeSQLAccess();
        return sqlAccess;
    }

    /**
     * Returns the initialized ConfigReader for a world. May return null if none was found
     * @param world The world in question
     * @return The ConfigReader for that world
     */
    public ConfigReader getConfigReader (World world)
    {
        try
        {
            return configReaders.get(world);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Returns all valid ConfigReaders found
     * @return all ConfigReaders
     */
    public ConfigReader[] getAllConfigReaders ()
    {
        return allConfigReaders.toArray(new ConfigReader[allConfigReaders.size()]);
    }

    /**
     * Returns the LocaleReader
     * @return LocaleReader
     */
    public LocaleReader getLocaleReader ()
    {
        if (localeReader == null)
        {
            File localeFile = new File (getDataFolder () + SLASH + LOCALES_YML);

            try
            {
                if (! localeFile.exists())
                {
                    localeFile.createNewFile();

                    localeReader = new LocaleReader (localeFile);
                    localeReader.GenerateDefaultFile();
                }
            }
            catch (IOException e)
            {
                getLogger().log(Level.SEVERE, "Error: Could not create locale file");
            }

            localeReader = new LocaleReader (localeFile);
            localeReader.ReadAllEntries();
        }

        return localeReader;
    }

    /**
     * Creates the BloodMoon folder if it does not exist
     */
    public void CreateFolder ()
    {
        File folder = getDataFolder();
        folder.mkdir();
    }

    private void LoadCache (World world)
    {
        try
        {
            SQLAccess access = getSqlAccess();
            boolean exists = access.EntryExist("lastBloodMoon", new SQLField("world", FieldType.TEXT, true, false), world.getUID().toString());

            if (exists)
            {
                ResultSet set = access.ExecuteSQLQuery("SELECT days, checkAt FROM lastBloodMoon WHERE world = '" + world.getUID().toString() + "';");
                set.next();

                PeriodicNightCheck nightCheck = PeriodicNightCheck.GetPeriodicNightCheck(world);

                nightCheck.SetDaysRemaining(set.getInt("days"));
                nightCheck.SetCheckAfter(set.getInt("checkAt"));
                set.close();
            }
        }
        catch (SQLException e)
        {
            e.printStackTrace();
        }
    }

    //Create a config reader, setting it up if it does not exist
    private ConfigReader CreateSingleConfigReader (World world)
    {
        File worldFolder = new File (getDataFolder() + SLASH + world.getName());
        if (! worldFolder.exists()) worldFolder.mkdir();

        File configFile = new File (worldFolder.getAbsolutePath() + SLASH + CONFIG_FILE);
        if (! configFile.exists())
        {
            try
            {
                configFile.createNewFile();
                ConfigReader reader = new ConfigReader(configFile, world);
                reader.GenerateDefaultFile();
            }
            catch (IOException e)
            {
                e.printStackTrace();
                return null;
            }
        }
        ConfigReader reader = new ConfigReader(configFile, world);
        reader.ReadAllSettings();
        configReaders.put(world, reader);
        allConfigReaders.add(reader);
        return reader;
    }

    /**
     * Enables the plugin
     */
    @Override
    public void onEnable()
    {
        instance = this;

        CreateFolder();

        getSqlAccess();
        getLocaleReader();

        worldManager = new WorldManager();

        nightChecks = new ArrayList<>();
        actuators = new ArrayList<>();

        BlackListedWorlds = new ArrayList<>();
        configReaders = new HashMap<>();
        allConfigReaders = new ArrayList<>();
        bloodmoonWorlds = new ArrayList<>();

        for (World world : getServer().getWorlds())
        {
            LoadWorld(world);
        }

        getServer().getPluginManager().registerEvents (worldManager, this);

        getCommand("bloodmoon").setExecutor(new BloodmoonCommandExecutor());


        getCommand("testsuite").setExecutor(new TestCommandExecutor());

        CheckOlderConfigs();
    }

    /**
     * Loads a world and reads its config
     * @param world The world to load
     */
    public void LoadWorld (World world)
    {
        if (world.getEnvironment() != World.Environment.NORMAL)
        {
            return;
        }

        ConfigReader configReader = CreateSingleConfigReader(world);
        if (configReader.GetIsBlacklistedConfig())
        {
            BlackListedWorlds.add(world);
            return;
        }

        BloodmoonActuator actuator = new BloodmoonActuator(world);
        getServer().getPluginManager().registerEvents(actuator, this);
        actuators.add(actuator);

        PurgeBosses(world);

        if (! configReader.GetPermanentBloodMoonConfig())
        {
            PeriodicNightCheck nightCheck = new PeriodicNightCheck(world, actuator);
            GetScheduler().runTaskLater(this, nightCheck, 0);
            getServer().getPluginManager().registerEvents(nightCheck, this);
            nightChecks.add(nightCheck);
            LoadCache(world);
        }

        bloodmoonWorlds.add(world);
    }


    /**
     * Removes all boss remaining from a world
     * @param world Chosen world
     */
    private void PurgeBosses (World world){
        for(LivingEntity entity : world.getLivingEntities()){
            if(
                    entity.getCustomName() != null
                    && !entity.getCustomName().isEmpty()
                    && entity.getCustomName().equals(getLocaleReader().GetLocaleString("ZombieBossName"))
            ){
                entity.remove();
            }
        }
    }

    /**
     * Disables the plugin
     */
    @Override
    public void onDisable()
    {
        for (PeriodicNightCheck nightCheck : nightChecks)
        {
            nightCheck.UpdateCacheDatabase();
        }

        for (BloodmoonActuator actuator : actuators)
        {
            if (actuator.isInProgress()) actuator.StopBloodMoon();
            actuator.close();
        }

        if (sqlAccess != null) sqlAccess.close();
        for (ConfigReader configReader : allConfigReaders)
        {
            if (configReader != null)
            {
                try
                {
                    configReader.close();
                }
                catch (IOException e)
                {
                    getLogger().log(Level.SEVERE,"[Error]");
                    e.printStackTrace();
                }
            }
        }
        try
        {
            localeReader.close();
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    private void CheckOlderConfigs ()
    {
        getLogger().log(Level.INFO,"This plugin is still in its infancy. If you encounter a bug, please report it to https://www.spigotmc.org/threads/bloodmoon.412741/");

        File oldConfig = new File (getDataFolder() + SLASH + CONFIG_FILE);
        if (oldConfig.exists()) getLogger().log(Level.WARNING,"[Deprecated] BloodMoon/config.yml is no longer used. Use per-world configuration instead");

        String localesVersion = getLocaleReader().GetFileVersion();
        if (localesVersion.equals("NaN"))
        {
            getLogger().log(Level.WARNING,"[Error] locales.yml has no valid version tag. Consider regenerating it");
            return;
        }
        if (! GetMajorVersions(localesVersion).equals(GetMajorVersions(getDescription().getVersion())))
            getLogger().log(Level.WARNING,"[Warning] Locales file was not updated since the last major update. Regenerating it is *highly* recommended");
        for (World world : bloodmoonWorlds)
        {
            if (BlackListedWorlds.contains(world)) continue;

            String configVersion = getConfigReader(world).GetFileVersion();
            if (configVersion.equals("NaN"))
            {
                getLogger().log(Level.SEVERE,"[Error] " + world.getName() + "/config.yml has no valid version tag. Consider regenerating it");
                return;
            }
            if (! GetMajorVersions(configVersion).equals(GetMajorVersions(getDescription().getVersion())))
                getLogger().log(Level.WARNING, "[Warning] Config file for world " + world.getName() + " was not updated since the last major update. Regenerating it is *highly* recommended");
            if (getConfigReader(world).GetIntervalConfig() < 1)
                getLogger().log(Level.WARNING,"[Warning] BloodMoonInterval config is set to 0 or less.\nThis may cause problem, please use the PermanentBloodMoon option instead");

        }
    }

    private static String GetMajorVersions (String version)
    {
        String[] segments = version.split("\\.");
        return segments[0] + "." + segments[1];
    }
}
