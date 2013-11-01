/*
 * Copyright (C) 2010-12  Ciaran Gultnieks, ciaran@ciarang.com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.fdroid.fdroid;

import java.io.File;
import java.lang.Runtime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

import android.os.Build;
import android.app.Application;
import android.preference.PreferenceManager;
import android.util.Log;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;

import com.nostra13.universalimageloader.utils.StorageUtils;
import com.nostra13.universalimageloader.cache.disc.impl.UnlimitedDiscCache;
import com.nostra13.universalimageloader.cache.disc.naming.FileNameGenerator;
import com.nostra13.universalimageloader.core.assist.ImageScaleType;
import com.nostra13.universalimageloader.core.display.FadeInBitmapDisplayer;
import com.nostra13.universalimageloader.core.DisplayImageOptions;
import com.nostra13.universalimageloader.core.ImageLoader;
import com.nostra13.universalimageloader.core.ImageLoaderConfiguration;

public class FDroidApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        // Needs to be setup before anything else tries to access it.
        // Perhaps the constructor is a better place, but then again,
        // it is more deterministic as to when this gets called...
        Preferences.setup(this);

        // Clear cached apk files. We used to just remove them after they'd
        // been installed, but this causes problems for proprietary gapps
        // users since the introduction of verification (on pre-4.2 Android),
        // because the install intent says it's finished when it hasn't.
        SharedPreferences prefs = PreferenceManager
                .getDefaultSharedPreferences(getBaseContext());
        showIncompatible = prefs.getBoolean("showIncompatible", false);
        if (!prefs.getBoolean("cacheDownloaded", false)) {

            File local_path = DB.getDataPath(this);
            // Things can be null if the SD card is not ready - we'll just
            // ignore that and do it next time.
            if(local_path != null) {
                File[] files = local_path.listFiles();
                if(files != null) {
                    for(File f : files) {
                        if(f.getName().endsWith(".apk")) {
                            f.delete();
                        }
                    }
                }
            }
        }

        apps = null;
        invalidApps = new ArrayList<String>();
        ctx = getApplicationContext();
        DB.initDB(ctx);
        UpdateService.schedule(ctx);

        DisplayImageOptions options = new DisplayImageOptions.Builder()
            .cacheInMemory(true)
            .cacheOnDisc(true)
            .showImageOnLoading(R.drawable.ic_repo_app_default)
            .showImageForEmptyUri(R.drawable.ic_repo_app_default)
            .displayer(new FadeInBitmapDisplayer(200, true, true, false))
            .bitmapConfig(Bitmap.Config.RGB_565)
            .build();

        ImageLoaderConfiguration config = new ImageLoaderConfiguration.Builder(ctx)
            .discCache(new UnlimitedDiscCache(
                        new File(StorageUtils.getCacheDirectory(ctx), "icons"),
                        new FileNameGenerator() {
                            public String generate(String imageUri) {
                                return imageUri.substring(
                                    imageUri.lastIndexOf('/') + 1);
                            } } ))
            .defaultDisplayImageOptions(options)
            .threadPoolSize(Runtime.getRuntime().availableProcessors() * 2)
            .build();
        ImageLoader.getInstance().init(config);
    }

    Context ctx;

    // Global list of all known applications.
    private List<DB.App> apps;

    private boolean showIncompatible;

    // Set when something has changed (database or installed apps) so we know
    // we should invalidate the apps.
    private volatile boolean appsAllInvalid = false;
    private Semaphore appsInvalidLock = new Semaphore(1, false);
    private List<String> invalidApps;

    // Set apps invalid. Call this when the database has been updated with
    // new app information, or when the installed packages have changed.
    public void invalidateAllApps() {
        try {
            appsInvalidLock.acquire();
            appsAllInvalid = true;
        } catch (InterruptedException e) {
            // Don't care
        } finally {
            appsInvalidLock.release();
        }
    }

    // Invalidate a single app
    public void invalidateApp(String id) {
        Log.d("FDroid", "Invalidating "+id);
        invalidApps.add(id);
    }

    // Get a list of all known applications. Should not be called when the
    // database is locked (i.e. between DB.getDB() and db.releaseDB(). The
    // contents should never be modified, it's for reading only.
    public List<DB.App> getApps() {

        boolean invalid = false;
        try {
            appsInvalidLock.acquire();
            invalid = appsAllInvalid;
            if (invalid) {
                appsAllInvalid = false;
                Log.d("FDroid", "Dropping cached app data");
            }
        } catch (InterruptedException e) {
            // Don't care
        } finally {
            appsInvalidLock.release();
        }

        if (apps == null || invalid) {
            try {
                DB db = DB.getDB();
                apps = db.getApps(true);

                List<DB.Repo> repos = db.getRepos();
                for (DB.App app : apps) {
                    if (app.icon == null) continue;
                    for (DB.Repo repo : repos) {
                        int latestRepo = app.apks.get(0).repo;
                        if (repo.id == latestRepo) {
                            app.iconUrl = repo.address + "/icons/" + app.icon;
                            break;
                        }
                    }
                }

            } finally {
                DB.releaseDB();
            }
        } else if (!invalidApps.isEmpty()) {
            try {
                DB db = DB.getDB();
                apps = db.refreshApps(apps, invalidApps);

                List<DB.Repo> repos = db.getRepos();
                for (DB.App app : apps) {
                    if (app.icon == null) continue;
                    if (!invalidApps.contains(app.id)) continue;
                    for (DB.Repo repo : repos) {
                        int latestRepo = app.apks.get(0).repo;
                        if (repo.id == latestRepo) {
                            app.iconUrl = repo.address + "/icons/" + app.icon;
                            break;
                        }
                    }
                }

                invalidApps.clear();
            } finally {
                DB.releaseDB();
            }
        }
        if (apps == null)
            return new ArrayList<DB.App>();
        filterApps();
        return apps;
    }

    public void filterApps() {
        AppFilter appFilter = new AppFilter(ctx);
        for (DB.App app : apps) {
            app.filtered = appFilter.filter(app);

            app.toUpdate = (app.hasUpdates
                    && !app.ignoreAllUpdates
                    && app.curApk.vercode > app.ignoreThisUpdate
                    && !app.filtered
                    && (showIncompatible || app.compatible));
        }
    }

}
