package fr.kawaiiapp.k7audiob;

import android.app.PendingIntent;
import android.content.Intent;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

/**
 * Service de lecture K7 : le vrai lecteur audio vit ici, hors de la page web.
 * Android le garde en vie pendant la lecture (service de premier plan « lecture multimédia »),
 * affiche la notification avec la pochette, gère l'écran verrouillé et les boutons du casque.
 */
public class K7PlaybackService extends MediaSessionService {
    private MediaSession session;

    @Override
    public void onCreate() {
        super.onCreate();
        ExoPlayer player = new ExoPlayer.Builder(this)
            .setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(), true)               // true = pause auto si appel / autre appli audio
            .setHandleAudioBecomingNoisy(true) // pause si on débranche le casque
            .setWakeMode(C.WAKE_MODE_LOCAL)    // le processeur reste éveillé écran éteint
            .setSeekBackIncrementMs(30000)
            .setSeekForwardIncrementMs(30000)
            .build();
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pi = PendingIntent.getActivity(this, 0, launch,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        session = new MediaSession.Builder(this, player).setSessionActivity(pi).build();
    }

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    @Override
    public void onTaskRemoved(@Nullable Intent rootIntent) {
        // appli balayée des récentes : on continue si ça joue, sinon on s'arrête proprement
        if (session != null) {
            Player p = session.getPlayer();
            if (!p.getPlayWhenReady() || p.getMediaItemCount() == 0) {
                stopSelf();
            }
        }
    }

    @Override
    public void onDestroy() {
        if (session != null) {
            session.getPlayer().release();
            session.release();
            session = null;
        }
        super.onDestroy();
    }
}
