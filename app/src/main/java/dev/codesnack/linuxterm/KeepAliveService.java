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
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
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
        startForeground(NOTIFICATION_ID, n);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
