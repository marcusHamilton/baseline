# R8 rules for the release build (spec §12). Most libraries here ship their own consumer rules; this file
# only adds what they don't cover, and records why the rest needs nothing.

# Readable crash stack traces: keep line numbers, retrace with app/build/outputs/mapping/release/mapping.txt.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# WorkManager stores the worker's class name in its database and creates it by reflection with
# (Context, WorkerParameters). work-runtime's consumer rules already keep the name and constructor of every
# ListenableWorker; this repeats it for SyncWorker so a periodic sync queued by an older build still resolves.
-keepnames class dev.baseline.sync.SyncWorker
-keepclassmembers class dev.baseline.sync.SyncWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Nothing else is needed:
# - Room 3 (KSP): AppDatabase_Impl is passed as a factory, not looked up by name; room3-runtime keeps RoomDatabase subclasses.
# - Garmin FIT SDK: plain Java with no reflection on the paths FitReader uses (MesgBroadcaster dispatches with instanceof).
# - kotlinx.serialization: only the JsonElement tree is used (data/parse); there are no @Serializable classes.
# - MapLibre: JNI-reached classes carry @Keep or are kept by the AAR's own rules (NativeMapOptions, RenderingStats,
#   TileOperation, org.maplibre.geojson.**).
# - OkHttp, DataStore, Compose, Navigation: covered by their consumer rules.
# - Persisted enum names (GarminEndpoint, TaskStatus, SyncInterval, BanisterSet): R8 keeps Enum.name() and valueOf()
#   returning the source names, so stored rows and prefs stay readable.
# - Home-screen widgets (ReadinessWidgetProvider, VitalsWidgetProvider, BodyBatteryWidgetProvider, SleepWidgetProvider,
#   WeeklyActivityWidgetProvider) and WidgetActionReceiver: AAPT2 automatically generates -keep rules for every
#   <receiver> declared in AndroidManifest.xml; RemoteViews method names ("setBackgroundResource", "setColorFilter")
#   target framework android.view.View / android.widget.ImageView methods that R8 never renames.
