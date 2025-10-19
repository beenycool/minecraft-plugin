package com.crimsonwarpedcraft.exampleplugin;

import com.crimsonwarpedcraft.exampleplugin.service.WorldResetScheduler;
import io.papermc.lib.PaperLib;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Created by Levi Muniz on 7/29/20.
 *
 * @author Copyright (c) Levi Muniz. All Rights Reserved.
 */
public class ExamplePlugin extends JavaPlugin {

  private WorldResetScheduler worldResetScheduler;

  @Override
  public void onEnable() {
    PaperLib.suggestPaper(this);

    saveDefaultConfig();

    worldResetScheduler = new WorldResetScheduler(this);
    worldResetScheduler.start();
  }

  @Override
  public void onDisable() {
    if (worldResetScheduler != null) {
      worldResetScheduler.cancel();
    }
  }
}
