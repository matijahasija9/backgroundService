package id.flutter.flutter_background_service;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.AlarmManagerCompat;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

import io.flutter.FlutterInjector;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.embedding.engine.loader.FlutterLoader;
import io.flutter.plugin.common.JSONMethodCodec;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.view.FlutterCallbackInformation;
import io.flutter.view.FlutterMain;

public class BackgroundService extends Service implements MethodChannel.MethodCallHandler {
    private static final String TAG = "BackgroundService";
    private FlutterEngine backgroundEngine;
    private MethodChannel methodChannel;
    private DartExecutor.DartCallback dartCallback;

    String notificationTitle = "Background Service";
    String notificationContent = "Running";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void enqueue(Context context){
        Intent intent = new Intent(context, WatchdogReceiver.class);
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

        PendingIntent pIntent = PendingIntent.getBroadcast(context, 111, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        AlarmManagerCompat.setAndAllowWhileIdle(manager, AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 5000, pIntent);
    }

    public static void setCallbackDispatcher(Context context, long callbackHandleId, boolean isForeground){
        SharedPreferences pref = context.getSharedPreferences("id.flutter.background_service", MODE_PRIVATE);
        pref.edit().putLong("callback_handle", callbackHandleId).putBoolean("is_foreground", isForeground).apply();
    }

    public void setForegroundServiceMode(boolean value){
        SharedPreferences pref = getSharedPreferences("id.flutter.background_service", MODE_PRIVATE);
        pref.edit().putBoolean("is_foreground", value).apply();
    }

    public static boolean isForegroundService(Context context){
        SharedPreferences pref = context.getSharedPreferences("id.flutter.background_service", MODE_PRIVATE);
        return pref.getBoolean("is_foreground", true);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        notificationContent = "Preparing";
        updateNotificationInfo();
    }

    @Override
    public void onDestroy() {
        enqueue(this);

        stopForeground(true);
        isRunning.set(false);

        backgroundEngine.getServiceControlSurface().detachFromService();
        backgroundEngine = null;
        methodChannel = null;
        dartCallback = null;
        super.onDestroy();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "Background Service";
            String description = "Executing process in background";

            int importance = NotificationManager.IMPORTANCE_LOW;
            NotificationChannel channel = new NotificationChannel("FOREGROUND_DEFAULT", name, importance);
            channel.setDescription(description);

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    protected void updateNotificationInfo() {
        if (isForegroundService(this)){
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(this, "FOREGROUND_DEFAULT")
                        .setSmallIcon(R.drawable.ic_bg_service_small)
                        .setAutoCancel(true)
                        .setOngoing(true)
                        .setContentTitle(notificationTitle)
                        .setContentText(notificationContent);

                startForeground(99778, mBuilder.build());
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        enqueue(this);
        runService();

        return START_STICKY;
    }

    AtomicBoolean isRunning = new AtomicBoolean(false);
    private void runService(){
        if (isRunning.get() || (backgroundEngine != null && !backgroundEngine.getDartExecutor().isExecutingDart())) return;
        updateNotificationInfo();

        SharedPreferences pref = getSharedPreferences("id.flutter.background_service", MODE_PRIVATE);
        long callbackHandle = pref.getLong("callback_handle", 0);

        FlutterInjector.instance().flutterLoader().ensureInitializationComplete(getApplicationContext(), null);
        FlutterCallbackInformation callback =  FlutterCallbackInformation.lookupCallbackInformation(callbackHandle);
        if (callback == null){
            Log.e(TAG, "callback handle not found");
            return;
        }

        isRunning.set(true);
        backgroundEngine = new FlutterEngine(this);
        backgroundEngine.getServiceControlSurface().attachToService(BackgroundService.this, null, true);

        methodChannel = new MethodChannel(backgroundEngine.getDartExecutor().getBinaryMessenger(), "id.flutter/background_service_bg", JSONMethodCodec.INSTANCE);
        methodChannel.setMethodCallHandler(this);

        dartCallback = new DartExecutor.DartCallback(getAssets(), FlutterMain.findAppBundlePath(), callback);
        backgroundEngine.getDartExecutor().executeDartCallback(dartCallback);
    }

    public void receiveData(JSONObject data){
        if (methodChannel != null){
            try {
                methodChannel.invokeMethod("onReceiveData", data);
            }catch (Exception e){
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        String method = call.method;

        try {
            if (method.equalsIgnoreCase("setNotificationInfo")) {
                JSONObject arg = (JSONObject) call.arguments;
                if (arg.has("title")) {
                    notificationTitle = arg.getString("title");
                    notificationContent = arg.getString("content");
                    updateNotificationInfo();
                    result.success(true);
                    return;
                }
            }

            if (method.equalsIgnoreCase("setForegroundMode")){
                JSONObject arg = (JSONObject) call.arguments;
                boolean value = arg.getBoolean("value");
                setForegroundServiceMode(value);
                updateNotificationInfo();
                result.success(true);
                return;
            }

            if (method.equalsIgnoreCase("sendData")){
                LocalBroadcastManager manager = LocalBroadcastManager.getInstance(this);
                Intent intent = new Intent("id.flutter/background_service");
                intent.putExtra("data", ((JSONObject) call.arguments).toString());
                manager.sendBroadcast(intent);
                result.success(true);
                return;
            }
        } catch (JSONException e){
            Log.e(TAG, e.getMessage());
            e.printStackTrace();
        }

        result.notImplemented();
    }
}
