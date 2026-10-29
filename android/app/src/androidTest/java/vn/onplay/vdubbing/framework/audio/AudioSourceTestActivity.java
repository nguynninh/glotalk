package vn.onplay.vdubbing.framework.audio;

import android.app.Activity;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.EditText;
import android.view.inputmethod.InputMethodManager;
import java.io.IOException;

public class AudioSourceTestActivity extends Activity {
    private MediaPlayer player;
    private AudioManager audioManager;
    private AudioFocusRequest focus;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if ("STOP".equals(getIntent().getAction())) {
            finish();
            return;
        }
        TextView label = new TextView(this);
        label.setText("VDubbing playback capture test: English speech playing");
        label.setTextSize(22);
        if (getIntent().getBooleanExtra("keyboard", false)) {
            EditText editor = new EditText(this);
            editor.setHint(label.getText());
            setContentView(editor);
            editor.requestFocus();
            editor.post(() -> getSystemService(InputMethodManager.class)
                .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT));
        } else {
            setContentView(label);
        }
        audioManager = getSystemService(AudioManager.class);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener(change -> {}).build();
        audioManager.requestAudioFocus(focus);
        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL).build());
        try (AssetFileDescriptor file = getAssets().openFd("whisper-jfk.wav")) {
            player.setDataSource(file.getFileDescriptor(), file.getStartOffset(), file.getLength());
            player.setLooping(true);
            player.prepare();
            player.start();
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if ("STOP".equals(intent.getAction())) finish();
    }

    @Override public void onDestroy() {
        if (player != null) player.release();
        if (audioManager != null && focus != null) audioManager.abandonAudioFocusRequest(focus);
        super.onDestroy();
    }
}
