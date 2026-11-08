package dev.codesnack.linuxterm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * Servicio en primer plano: mientras haya trabajos de Jobs activos, Android
 * no debería cerrar el proceso de la app. Se detiene solo cuando no queda
 * ningún trabajo.
 */
public class KeepAliveService extends Service {
    private static final String CHANNEL = "trabajos";
    private static final int NOTIFICATION_ID = 1;
    /** Tope de seguridad: si algo se cuelga, el bloqueo no queda para siempre. */
    private static final long LOCK_TIMEOUT_MS = 12 * 60 * 60 * 1000L;

    private android.os.PowerManager.WakeLock wake;
    private android.net.wifi.WifiManager.WifiLock wifi;

    /** Actualiza el servicio según haya o no trabajos activos. */
    static void sync(Context ctx) {
        Intent i = new Intent(ctx, KeepAliveService.class);
        try {
            if (Jobs.runningCount() > 0) {
                if (Build.VERSION.SDK_INT >= 26) {
                    ctx.startForegroundService(i);
                } else {
                    ctx.startService(i);
                }
            } else {
                ctx.startService(i); // onStartCommand lo detiene si ya no hay trabajos
            }
        } catch (RuntimeException ignored) {
            // Android puede rechazar el arranque desde segundo plano; el trabajo sigue vivo mientras el proceso exista.
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int running = Jobs.runningCount();
        if (running == 0) {
            releaseLocks();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        // Red y CPU despiertas mientras dure el trabajo. Sin esto, el Wi-Fi
        // entra en ahorro al apagar la pantalla y los sockets abiertos se
        // abortan con Errno 103.
        if (wake == null) {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            wake = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "linuxterm:trabajos");
            wake.setReferenceCounted(false);
            wake.acquire(LOCK_TIMEOUT_MS);
        } else if (!wake.isHeld()) {
            wake.acquire(LOCK_TIMEOUT_MS);
        }
        if (wifi == null) {
            android.net.wifi.WifiManager wm =
                    (android.net.wifi.WifiManager) getApplicationContext()
                            .getSystemService(Context.WIFI_SERVICE);
            wifi = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "linuxterm:trabajos");
            wifi.setReferenceCounted(false);
            wifi.acquire();
        } else if (!wifi.isHeld()) {
            wifi.acquire();
        }

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Trabajos en segundo plano",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = b
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Terminal Linux")
                .setContentText(running + (running == 1 ? " trabajo" : " trabajos") + " en segundo plano")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        promover(n);
        return START_STICKY;
    }

    /**
     * Pasa a primer plano declarando el tipo del manifest. En Android 14 el
     * proceso muere al salir de la app si el servicio no tiene tipo, y es
     * justo lo que hacia que se cayera al cambiar de aplicacion.
     */
    private void promover(Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, n,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (RuntimeException e) {
            try {
                startForeground(NOTIFICATION_ID, n);
            } catch (RuntimeException ignored) {
                // sin notificacion no hay primer plano; el trabajo sigue
                // mientras el sistema no recicle el proceso
            }
        }
    }

    @Override
    public void onDestroy() {
        releaseLocks();
        super.onDestroy();
    }

    private void releaseLocks() {
        try {
            if (wake != null && wake.isHeld()) wake.release();
        } catch (RuntimeException ignored) {
        }
        try {
            if (wifi != null && wifi.isHeld()) wifi.release();
        } catch (RuntimeException ignored) {
        }
        wake = null;
        wifi = null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
